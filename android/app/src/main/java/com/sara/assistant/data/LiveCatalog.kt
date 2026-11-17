package com.sara.assistant.data

/**
 * Live API model identifiers. Kept as data, not constants scattered through the code,
 * because the model string is a user-facing setting (and gets deprecated over time).
 *
 * Verified against https://ai.google.dev/gemini-api/docs/models on the date below:
 *  - `gemini-3.8-live` is the current default Live model.
 *  - `gemini-3.8-live-extended-thinking` is the high-reasoning variant.
 *  - `gemini-3.1-flash-live-preview` and `gemini-2.5-flash-native-audio-preview-12-2025`
 *    are older but still served, so they are offered as fallbacks.
 *
 * NOTE: `gemini-2.5-flash-native-audio-latest` does NOT exist — the real id carries a
 * date suffix. Check the docs before adding new strings here.
 */
object LiveCatalog {

    const val DEFAULT_MODEL = "gemini-3.8-live"

    /** Ordered attempt list: first entry is used, later ones are fallbacks. */
    val knownModels: List<String> = listOf(
        "gemini-3.8-live",
        "gemini-3.8-live-extended-thinking",
        "gemini-3.1-flash-live-preview",
        "gemini-2.5-flash-native-audio-preview-12-2025",
    )

    /**
     * Prebuilt studio voices from the Gemini TTS voice table. Native-audio Live models
     * accept these names in `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName`.
     */
    val voices: List<String> = listOf(
        "Kore", "Puck", "Charon", "Zephyr", "Fenrir", "Leda", "Orus", "Aoede",
        "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
        "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
        "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
        "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat",
    )

    const val DEFAULT_VOICE = "Kore"
}

/** Default persona. Editable from Settings; shipped to both Live and the backend. */
val DEFAULT_SYSTEM_PROMPT = """
You are Sara, a warm, quick-witted voice assistant living on the user's Android phone.
You speak Hinglish naturally (Hindi + English) and mirror the user's language.

What you handle yourself:
- Small talk, quick answers, timers of thought, opinions, explanations.

What you delegate:
- Anything that needs the phone (opening apps, tapping, typing, scrolling, reading the
  screen) or the web. Call the `delegate_task` tool with a clear, single goal.

Rules:
- Speak in short sentences. No markdown, no bullet lists out loud.
- Before you delegate, say one short line so the user knows you're on it.
- Never claim a device action succeeded until the tool result says so.
- If a tool result reports failure, say so plainly and offer one alternative.
- For payments, deleting data, sending money, or entering passwords: ask the user to
  confirm out loud, and wait for an explicit yes before delegating.
""".trim()
