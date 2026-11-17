package com.sara.assistant.safety

import com.sara.assistant.core.SaraLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Two jobs:
 *
 * 1. **Gate dangerous actions** behind an out-loud confirmation. Anything touching money,
 *    deletion, credentials or irreversible state is classified [Risk.CONFIRM]. The action is
 *    *not* executed until the user says something affirmative, which the Live transcript
 *    feeds back through [noteUserUtterance].
 *
 * 2. **Hard stop.** [requestStop] makes every in-flight action and delegation bail out.
 *    Wired to the Stop button and to the spoken "stop"/"ruko" path.
 *
 * Honest limitation: the confirmation match is keyword-based, not semantic. It is a guard
 * rail, not a security boundary. A model that rephrases around it will still be caught,
 * because the check runs on the *action payload* (element text, typed text, app), not only
 * on the user's words.
 */
class SafetyGuard {

    enum class Risk { SAFE, CONFIRM }

    private val _stopRequested = MutableStateFlow(false)
    val stopRequested: StateFlow<Boolean> = _stopRequested.asStateFlow()

    /** Signature of the action that is currently waiting on a spoken confirmation. */
    private var pendingSignature: String? = null

    /** Signature that the user has already confirmed. Consumed on use. */
    private var approvedSignature: String? = null

    fun updatePending(signature: String) {
        pendingSignature = signature
    }

    fun currentPending(): String? = pendingSignature

    fun isApproved(signature: String): Boolean = approvedSignature == signature

    fun consumeApproval(signature: String) {
        if (approvedSignature == signature) approvedSignature = null
        if (pendingSignature == signature) pendingSignature = null
    }

    /**
     * Feeds a user transcript line through the approval detector.
     * @return true when the line read as a confirmation and a pending action got approved.
     */
    fun noteUserUtterance(text: String): Boolean {
        val pending = pendingSignature ?: return false
        if (!isAffirmative(text)) return false
        approvedSignature = pending
        SaraLog.i("User confirmed action $pending by voice")
        return true
    }

    private fun isAffirmative(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.isEmpty()) return false
        // Negation must win: "haan mat karo" contains "haan".
        if (NEGATIONS.any { normalized.contains(it) }) return false
        return AFFIRMATIONS.any { normalized.contains(it) }
    }

    fun requestStop() {
        _stopRequested.value = true
        SaraLog.i("Hard stop requested")
    }

    fun clearStop() {
        _stopRequested.value = false
    }

    val isStopped: Boolean get() = _stopRequested.value

    /**
     * Classifies an action. The haystack combines the action name with the user-visible text
     * and typed content, so "tap the Pay button" is caught even though the action is `tap`.
     */
    fun classify(action: String, params: JSONObject): Risk {
        val haystack = buildString {
            append(action.lowercase())
            append(' ')
            append(params.optString("text").lowercase())
            append(' ')
            append(params.optString("query").lowercase())
            append(' ')
            append(params.optString("app").lowercase())
            append(' ')
            append(params.optString("label").lowercase())
        }
        val risky = SENSITIVE_MARKERS.any { haystack.contains(it) }
        return if (risky) Risk.CONFIRM else Risk.SAFE
    }

    /** Stable identity for a gated action, used as the approval key. */
    fun signature(action: String, params: JSONObject): String =
        "$action|${params.optString("text")}|${params.optString("query")}|${params.optString("app")}"

    private companion object {
        val SENSITIVE_MARKERS = listOf(
            "pay", "payment", "upi", "transfer", "send money", "remit",
            "delete", "uninstall", "remove account", "erase", "format",
            "password", "passcode", "otp", "pin", "cvv", "credit card",
            "purchase", "buy now", "checkout", "place order", "confirm order",
            "book now", "subscribe", "loan", "withdraw", "recharge",
        )

        val AFFIRMATIONS = listOf(
            "yes", "yeah", "yep", "yup", "confirm", "confirmed", "sure",
            "go ahead", "do it", "proceed", "haan", "han", "ha ", "karo", "kardo",
            "kar do", "theek hai", "thik hai", "ok", "okay", "chalega",
        )

        val NEGATIONS = listOf(
            "no ", "nope", "don't", "dont", "do not", "cancel", "stop", "wait",
            "nahi", "nahin", "na ", "mat ", "ruko", "ruk ", "bas",
        )
    }
}
