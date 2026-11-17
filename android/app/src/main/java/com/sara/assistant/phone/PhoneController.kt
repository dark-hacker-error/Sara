package com.sara.assistant.phone

import android.content.Context
import com.sara.assistant.core.SaraLog
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns abstract device actions from the backend into real accessibility calls.
 *
 * Every method returns a JSON result instead of throwing, because the result goes straight
 * back over the wire to the ADK agent, which needs to reason about failures ("element not
 * found" vs "service not enabled") rather than get a stack trace.
 */
class PhoneController(private val context: Context) {

    private val appRegistry = AppRegistry(context)

    /** True when the accessibility service is connected and the tree is readable. */
    fun isReady(): Boolean = SaraAccessibilityService.isRunning

    /** Human-readable reason the device side is unavailable, or null when it's fine. */
    fun unavailableReason(): String? =
        if (isReady()) null else "Accessibility service is not enabled. Ask the user to enable it in Settings."

    /**
     * Dispatches one action. Unknown actions return `ok: false` instead of crashing so the
     * agent can recover or pick a different approach.
     */
    suspend fun execute(action: String, params: JSONObject): JSONObject {
        val service = SaraAccessibilityService.instance
            ?: return failure(unavailableReason() ?: "Accessibility service unavailable")

        return try {
            when (action) {
                "tap" -> boolResult(
                    service.tap(params.optDouble("x").toFloat(), params.optDouble("y").toFloat()),
                )
                "long_press" -> boolResult(
                    service.longPress(params.optDouble("x").toFloat(), params.optDouble("y").toFloat()),
                )
                "swipe" -> boolResult(
                    service.swipe(
                        fromX = params.optDouble("from_x").toFloat(),
                        fromY = params.optDouble("from_y").toFloat(),
                        toX = params.optDouble("to_x").toFloat(),
                        toY = params.optDouble("to_y").toFloat(),
                        durationMs = params.optLong("duration_ms", 300L),
                    ),
                )
                "scroll" -> scroll(service, params.optString("direction", "down"))
                "tap_text" -> service.tapText(
                    query = params.optString("query"),
                    exact = params.optBoolean("exact", false),
                )
                "tap_node" -> boolResult(service.tapNode(params.optInt("index", -1)))
                "type" -> typeText(service, params)
                "read_screen" -> service.readScreen()
                "open_app" -> openApp(params)
                "list_apps" -> listApps(params)
                "back" -> boolResult(service.globalBack())
                "home" -> boolResult(service.globalHome())
                "recents" -> boolResult(service.globalRecents())
                "notifications" -> boolResult(service.globalNotifications())
                else -> failure("Unknown action '$action'")
            }
        } catch (t: Throwable) {
            SaraLog.e("Device action '$action' threw", t)
            failure(t.message ?: "Action failed")
        }
    }

    private suspend fun typeText(
        service: SaraAccessibilityService,
        params: JSONObject,
    ): JSONObject {
        val text = params.optString("text")
        val submit = params.optBoolean("submit", false)
        // Give the IME a beat to bind to the field before setting text.
        delay(FOCUS_SETTLE_MS)
        val typed = service.typeText(text, submit)
        return if (typed) {
            JSONObject().put("ok", true).put("typed", text.length)
        } else {
            failure("No editable field found on screen")
        }
    }

    private suspend fun scroll(
        service: SaraAccessibilityService,
        direction: String,
    ): JSONObject {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val horizontalInset = width * 0.5f
        val topInset = height * 0.35f
        val bottomInset = height * 0.65f

        val (fromX, fromY, toX, toY) = when (direction.lowercase()) {
            "up" -> listOf(horizontalInset, topInset, horizontalInset, bottomInset)
            "down" -> listOf(horizontalInset, bottomInset, horizontalInset, topInset)
            "left" -> listOf(width * 0.8f, height * 0.5f, width * 0.2f, height * 0.5f)
            "right" -> listOf(width * 0.2f, height * 0.5f, width * 0.8f, height * 0.5f)
            else -> return failure("Invalid scroll direction '$direction'")
        }
        val ok = service.swipe(fromX, fromY, toX, toY, SCROLL_DURATION_MS)
        return JSONObject().put("ok", ok).put("direction", direction)
    }

    private fun openApp(params: JSONObject): JSONObject {
        val packageName = params.optString("package")
        if (packageName.isNotBlank()) {
            val launched = appRegistry.launch(packageName)
            return JSONObject()
                .put("ok", launched)
                .put("package", packageName)
                .also { if (!launched) it.put("error", "No launch intent for $packageName") }
        }

        val query = params.optString("app")
        if (query.isBlank()) return failure("open_app needs either 'app' or 'package'")

        val entry = appRegistry.launchByName(query)
            ?: return failure("No installed app matches '$query'. Try list_apps to see options.")

        return JSONObject()
            .put("ok", true)
            .put("package", entry.packageName)
            .put("label", entry.label)
    }

    private fun listApps(params: JSONObject): JSONObject {
        val query = params.optString("query").trim().lowercase()
        val all = appRegistry.installedApps()
        val filtered = if (query.isEmpty()) {
            all
        } else {
            all.filter {
                it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }
        return JSONObject().apply {
            put("ok", true)
            put("total", all.size)
            put("apps", JSONArray().apply {
                filtered.take(MAX_APP_RESULTS).forEach { entry ->
                    put(JSONObject().put("label", entry.label).put("package", entry.packageName))
                }
            })
        }
    }

    private fun boolResult(ok: Boolean): JSONObject =
        if (ok) JSONObject().put("ok", true) else failure("The action did not register")

    private fun failure(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    companion object {
        private const val FOCUS_SETTLE_MS = 120L
        private const val SCROLL_DURATION_MS = 320L
        private const val MAX_APP_RESULTS = 60
    }
}
