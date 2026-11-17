package com.sara.assistant.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sara.assistant.core.SaraLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * The accessibility service that actually touches the phone.
 *
 * It exposes a deliberate, narrow API surface ([tap], [swipe], [typeText], [globalAction],
 * [readScreen], [tapText]) and keeps the last screen snapshot so the backend can address
 * elements by index instead of guessing pixel coordinates.
 *
 * Only one instance exists at a time; it registers itself in [companion].
 */
class SaraAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        SaraLog.i("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately quiet: we read the tree on demand instead of reacting to every event.
        // Later prompts (visual grounding, step verification) can hook in here.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- gestures

    /** Taps the given screen coordinates. */
    suspend fun tap(x: Float, y: Float): Boolean = gesture(x, y, x, y, TAP_DURATION_MS)

    suspend fun longPress(x: Float, y: Float): Boolean =
        gesture(x, y, x, y, LONG_PRESS_DURATION_MS)

    suspend fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = SWIPE_DURATION_MS,
    ): Boolean = gesture(fromX, fromY, toX, toY, durationMs)

    private suspend fun gesture(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

        val accepted = runCatching {
            dispatchGesture(
                description,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null,
            )
        }.getOrElse {
            SaraLog.e("dispatchGesture threw", it)
            false
        }

        if (!accepted && continuation.isActive) continuation.resume(false)
    }

    // ---------------------------------------------------------------- global actions

    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    fun globalBack(): Boolean = globalAction(GLOBAL_ACTION_BACK)
    fun globalHome(): Boolean = globalAction(GLOBAL_ACTION_HOME)
    fun globalRecents(): Boolean = globalAction(GLOBAL_ACTION_RECENTS)
    fun globalNotifications(): Boolean = globalAction(GLOBAL_ACTION_NOTIFICATIONS)

    // ---------------------------------------------------------------- screen reading

    /**
     * Snapshot of the active window as a compact JSON tree.
     *
     * The node list is cached so [tapNode] can address elements by the `i` index it returns.
     */
    suspend fun readScreen(): JSONObject = withContext(Dispatchers.Default) {
        val root = rootInActiveWindow
        if (root == null) {
            lastNodes = emptyList()
            return@withContext JSONObject().apply {
                put("ok", false)
                put("error", "No active window — is the screen unlocked and the service enabled?")
            }
        }

        val collected = mutableListOf<AccessibilityNodeInfo>()
        val elements = JSONArray()
        walk(root, collected, elements)

        lastNodes = collected
        lastPackage = root.packageName?.toString().orEmpty()

        JSONObject().apply {
            put("ok", true)
            put("package", lastPackage)
            put("count", elements.length())
            put("elements", elements)
        }
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        collected: MutableList<AccessibilityNodeInfo>,
        out: JSONArray,
    ) {
        if (collected.size >= MAX_NODES) return

        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        val clickable = node.isClickable
        val editable = node.isEditable
        val focusable = node.isFocusable

        // Only nodes the agent can act on or read from are worth reporting.
        if (text.isNotEmpty() || description.isNotEmpty() || clickable) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val index = collected.size
            collected.add(node)
            out.put(
                JSONObject().apply {
                    put("i", index)
                    put("text", text)
                    put("desc", description)
                    put("class", node.className?.toString().orEmpty())
                    put("clickable", clickable)
                    put("editable", editable)
                    put("focusable", focusable)
                    put("scrollable", node.isScrollable)
                    put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                },
            )
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            walk(child, collected, out)
        }
    }

    /** Taps a node from the most recent [readScreen] snapshot. */
    suspend fun tapNode(index: Int): Boolean {
        val node = lastNodes.getOrNull(index) ?: return false
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        // Many rows expose the click on an ancestor; walk up before falling back to a gesture.
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < MAX_PARENT_HOPS) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
            hops++
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    /** Finds the first element whose text or content description matches, then taps it. */
    suspend fun tapText(query: String, exact: Boolean = false): JSONObject {
        val snapshot = readScreen()
        val elements = snapshot.optJSONArray("elements") ?: JSONArray()
        val needle = query.trim().lowercase()

        var matchIndex = -1
        var matchText = ""
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val haystackText = element.optString("text")
            val haystackDesc = element.optString("desc")
            val hit = matches(haystackText, needle, exact) || matches(haystackDesc, needle, exact)
            if (hit) {
                matchIndex = element.optInt("i", index)
                matchText = haystackText.ifBlank { haystackDesc }
                break
            }
        }

        if (matchIndex < 0) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "No element matched '$query'")
            }
        }

        val clicked = tapNode(matchIndex)
        return JSONObject().apply {
            put("ok", clicked)
            put("matched", matchText)
            put("index", matchIndex)
            if (!clicked) put("error", "Matched but the tap did not register")
        }
    }

    private fun matches(haystack: String, needle: String, exact: Boolean): Boolean {
        if (haystack.isBlank()) return false
        val value = haystack.trim().lowercase()
        return if (exact) value == needle else value.contains(needle)
    }

    /** Types into the currently focused editor, or focuses the first one it can find. */
    suspend fun typeText(text: String, submit: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findFocusedEditable(root) ?: findFirstEditable(root) ?: return false

        if (!target.isFocused) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        val typed = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (typed && submit) {
            // There is no ACTION_IME_ENTER int constant; the action id lives on
            // AccessibilityAction and only exists from API 30.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
        return typed
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findFocusedEditable(child)?.let { return it }
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findFirstEditable(child)?.let { return it }
        }
        return null
    }

    /** Package name of the app that was frontmost at the last screen read. */
    fun currentPackage(): String = lastPackage

    companion object {
        @Volatile
        var instance: SaraAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null

        private const val MAX_NODES = 400
        private const val MAX_PARENT_HOPS = 4
        private const val TAP_DURATION_MS = 60L
        private const val LONG_PRESS_DURATION_MS = 700L
        private const val SWIPE_DURATION_MS = 300L
    }

    private var lastNodes: List<AccessibilityNodeInfo> = emptyList()
    private var lastPackage: String = ""
}
