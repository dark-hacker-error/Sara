package com.sara.assistant.live

/** High-level state of the voice session, drives the orb animation and UI. */
enum class LiveState {
    IDLE,
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    ACTING,
    ERROR,
}

/** Gemini Live socket state, tracked separately from the backend connection. */
enum class GeminiConnection {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/**
 * Why the Live session stopped. [permanent] means retrying the same config will fail again
 * (bad key, bad model, rejected setup), so the UI offers "Fix & retry" instead of looping.
 */
data class LiveFailure(
    val code: Int?,
    val reason: String,
    val permanent: Boolean,
    val hint: String,
)

/** A line in the live transcript list. */
data class TranscriptLine(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val final: Boolean,
)

/** One step of a delegated multi-step task, shown on the home timeline. */
data class TaskStep(
    val id: Long,
    val agent: String,
    val detail: String,
    val status: StepStatus,
)

enum class StepStatus { RUNNING, DONE, FAILED }

/** Everything the home screen needs. */
data class SaraUiState(
    val liveState: LiveState = LiveState.IDLE,
    val gemini: GeminiConnection = GeminiConnection.IDLE,
    val statusText: String = "",
    val transcript: List<TranscriptLine> = emptyList(),
    val taskSteps: List<TaskStep> = emptyList(),
    val activeTaskGoal: String? = null,
    val lastError: String? = null,
    val failure: LiveFailure? = null,
    val inputLevel: Float = 0f,
    val outputLevel: Float = 0f,
)