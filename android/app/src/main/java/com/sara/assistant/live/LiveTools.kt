package com.sara.assistant.live

import org.json.JSONObject

/**
 * The function declarations Sara's Live session is given.
 *
 * Schema type names must be the **uppercase proto enum** values ("OBJECT", "STRING").
 * Verified by dumping `google.genai.types.Tool` to JSON: lowercase strings are rejected
 * by the BidiGenerateContent endpoint. (The newer Interactions API differs — it uses
 * lowercase, and it is a different endpoint. Don't copy from there.)
 *
 * `behavior: NON_BLOCKING` lets the user keep talking while a long task runs. It is only
 * supported on some models, so [declarationsFor] gates it.
 */
object LiveTools {

    const val DELEGATE_TASK = "delegate_task"
    const val STOP_EVERYTHING = "stop_everything"

    /**
     * @param model bare model id; determines whether async function calling is allowed.
     *   Docs: NON_BLOCKING is supported on Gemini 3.8 Live and Gemini 2.5 Flash Live, but
     *   NOT on Gemini 3.1 Flash Live Preview (sync only there).
     */
    fun declarationsFor(model: String): List<JSONObject> {
        val asyncCapable = supportsAsyncFunctionCalling(model)
        return listOf(
            delegateTask(asyncCapable),
            stopEverything(asyncCapable),
        )
    }

    fun supportsAsyncFunctionCalling(model: String): Boolean {
        val normalized = model.lowercase()
        if (normalized.contains("3.1-flash-live")) return false
        return true
    }

    private fun delegateTask(async: Boolean): JSONObject = JSONObject().apply {
        put("name", DELEGATE_TASK)
        put(
            "description",
            "Hand a multi-step goal to the agent team running on the backend. Use this " +
                "whenever the request needs the user's phone or the web — opening apps, " +
                "tapping, typing, scrolling, reading the screen, searching the internet. " +
                "Pass one clear, self-contained goal. Do not use it for small talk.",
        )
        put("parameters", JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("goal", JSONObject().apply {
                    put("type", "STRING")
                    put(
                        "description",
                        "The single goal to accomplish, in plain language. Include names " +
                            "and exact wording the user gave (e.g. the message text).",
                    )
                })
                put("kind", JSONObject().apply {
                    put("type", "STRING")
                    put("enum", listOf("auto", "device", "research"))
                    put(
                        "description",
                        "Which specialist is likely needed. 'device' for phone control, " +
                            "'research' for web/information, 'auto' to let the planner decide.",
                    )
                })
            })
            put("required", listOf("goal"))
        })
        if (async) put("behavior", "NON_BLOCKING")
    }

    private fun stopEverything(async: Boolean): JSONObject = JSONObject().apply {
        put("name", STOP_EVERYTHING)
        put(
            "description",
            "Immediately abort every running task, stop any phone automation, and stop " +
                "talking. Call this the moment the user says stop, cancel, ruko, or bas.",
        )
        put("parameters", JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject())
        })
        if (async) put("behavior", "NON_BLOCKING")
    }
}
