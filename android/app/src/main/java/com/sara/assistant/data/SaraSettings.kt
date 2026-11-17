package com.sara.assistant.data

import com.sara.assistant.BuildConfig

/** Live API session settings. Everything here is user-editable at runtime. */
data class LiveSettings(
    val geminiApiKey: String = "",
    val model: String = BuildConfig.DEFAULT_LIVE_MODEL,
    val fallbackModel: String = BuildConfig.DEFAULT_LIVE_MODEL_FALLBACK,
    val voice: String = LiveCatalog.DEFAULT_VOICE,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val inputTranscription: Boolean = true,
    val outputTranscription: Boolean = true,
    /** VAD tuning. Docs recommend 500–800ms; below that, utterances get chopped up. */
    val silenceDurationMs: Int = 600,
    val prefixPaddingMs: Int = 120,
    /** Barge-in: when true, the user speaking interrupts Sara mid-sentence. */
    val bargeIn: Boolean = true,
)

/** Everything the app persists. Serialized to JSON in one secure blob. */
data class SaraSettings(
    val live: LiveSettings = LiveSettings(),
    val agents: List<AgentConfig> = AgentConfig.defaults(),
    val backendUrl: String = BuildConfig.DEFAULT_BACKEND_WS,
    /** Set once the user finishes the permission walkthrough. */
    val onboardingComplete: Boolean = false,
) {
    /** Models to try, in order, when opening a Live session. */
    val modelChain: List<String>
        get() = buildList {
            add(live.model)
            if (live.fallbackModel.isNotBlank() && live.fallbackModel != live.model) {
                add(live.fallbackModel)
            }
        }
}
