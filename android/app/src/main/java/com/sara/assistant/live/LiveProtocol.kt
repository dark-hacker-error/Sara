package com.sara.assistant.live

import org.json.JSONObject

/** One function call requested by the Live model. */
data class FunctionCall(
    val id: String,
    val name: String,
    val args: JSONObject,
) {
    fun stringArg(key: String): String = args.optString(key, "").trim()
}

/** Parsed Live API server message. The wire protocol is documented at /api-reference/live. */
sealed interface LiveServerEvent {

    data object SetupComplete : LiveServerEvent

    /** Raw 24kHz 16-bit little-endian mono PCM, already base64-decoded. */
    class Audio(val pcm: ByteArray) : LiveServerEvent

    /**
     * @param final true for `inputTranscription` (a finished phrase) and false for the
     *   low-latency `interimInputTranscription` that keeps getting revised.
     */
    class InputTranscript(val text: String, val final: Boolean) : LiveServerEvent

    class OutputTranscript(val text: String) : LiveServerEvent

    /** Barge-in fired: drop any queued playback immediately. */
    data object Interrupted : LiveServerEvent

    data object TurnComplete : LiveServerEvent

    class ToolCall(val calls: List<FunctionCall>) : LiveServerEvent

    class ToolCallCancellation(val ids: List<String>) : LiveServerEvent

    /** Server is about to disconnect (15-minute audio session cap). Resume with the handle. */
    class GoAway(val timeLeft: String?) : LiveServerEvent

    class SessionResumption(val handle: String?, val resumable: Boolean) : LiveServerEvent

    class Usage(val totalTokens: Int) : LiveServerEvent
}

/**
 * Encodes/decodes the Gemini Live WebSocket protocol. Every field name below was taken from
 * https://ai.google.dev/api/live — nothing here is invented.
 */
object LiveProtocol {

    const val ENDPOINT =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    const val AUDIO_MIME_IN = "audio/pcm;rate=16000"

    fun endpointFor(apiKey: String): String = "$ENDPOINT?key=$apiKey"

    /**
     * Builds the first message of the session. Mirrors BidiGenerateContentSetup.
     *
     * @param model bare model id, e.g. `gemini-3.8-live` (the `models/` prefix is added here).
     * @param resumeHandle previous `sessionResumptionUpdate.newHandle`, when reconnecting.
     */
    fun setup(
        model: String,
        systemInstruction: String,
        voice: String,
        inputTranscription: Boolean,
        outputTranscription: Boolean,
        silenceDurationMs: Int,
        prefixPaddingMs: Int,
        bargeIn: Boolean,
        resumeHandle: String?,
        toolDeclarations: List<JSONObject>,
    ): String {
        val setup = JSONObject().apply {
            put("model", "models/$model")
            put("responseModalities", listOf("AUDIO"))

            put("systemInstruction", JSONObject().apply {
                put("parts", listOf(JSONObject().put("text", systemInstruction)))
            })

            put("speechConfig", JSONObject().apply {
                put("voiceConfig", JSONObject().apply {
                    put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
                })
            })

            put("realtimeInputConfig", JSONObject().apply {
                put("automaticActivityDetection", JSONObject().apply {
                    put("disabled", false)
                    put("prefixPaddingMs", prefixPaddingMs)
                    put("silenceDurationMs", silenceDurationMs)
                })
                // START_OF_ACTIVITY_INTERRUPTS is what gives us barge-in.
                put(
                    "activityHandling",
                    if (bargeIn) "START_OF_ACTIVITY_INTERRUPTS" else "NO_INTERRUPTION",
                )
                // Gemini 3.x defaults to including all video; we only ever send audio.
                put("turnCoverage", "TURN_INCLUDES_ONLY_ACTIVITY")
            })

            if (inputTranscription) {
                put("inputAudioTranscription", JSONObject())
            }
            if (outputTranscription) {
                put("outputAudioTranscription", JSONObject())
            }

            // Keeps a long conversation inside the 128k context limit.
            put("contextWindowCompression", JSONObject().apply {
                put("slidingWindow", JSONObject())
            })

            // Enables sessionResumptionUpdate so we can reconnect past the 15-minute cap.
            put("sessionResumption", JSONObject().apply {
                if (!resumeHandle.isNullOrBlank()) put("handle", resumeHandle)
            })

            if (toolDeclarations.isNotEmpty()) {
                put("tools", listOf(JSONObject().put("functionDeclarations", toolDeclarations)))
            }
        }
        return JSONObject().put("setup", setup).toString()
    }

    fun audioChunk(base64Pcm: String): String =
        JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "audio",
                JSONObject().put("data", base64Pcm).put("mimeType", AUDIO_MIME_IN),
            ),
        ).toString()

    fun textInput(text: String): String =
        JSONObject().put("realtimeInput", JSONObject().put("text", text)).toString()

    /** Tells the server the mic went quiet so any cached audio is flushed. */
    fun audioStreamEnd(): String =
        JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()

    /** Flip to true for text turns and false again before streaming audio. */
    fun activityStart(): String =
        JSONObject().put("realtimeInput", JSONObject().put("activityStart", JSONObject())).toString()

    fun activityEnd(): String =
        JSONObject().put("realtimeInput", JSONObject().put("activityEnd", JSONObject())).toString()

    fun toolResponse(responses: List<JSONObject>): String =
        JSONObject().put(
            "toolResponse",
            JSONObject().put("functionResponses", responses),
        ).toString()

    /** One FunctionResponse entry, matched back to the call by `id`. */
    fun functionResponse(id: String, name: String, payload: JSONObject): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("name", name)
            put("response", payload)
        }

    // ---------------------------------------------------------------- decoding

    /**
     * Decodes one server frame into **every** event it carries.
     *
     * The Live API deliberately batches: a single frame can hold audio *and* a transcript
     * *and* usage metadata, and the docs warn that missing parts of an event means missing
     * content. So this returns a list, never a single event.
     *
     * `messageType` is a proto union — at most one of setupComplete / serverContent /
     * toolCall / toolCallCancellation / goAway / sessionResumptionUpdate is set — while
     * `usageMetadata` and `voiceActivity` can accompany any of them. Metadata is therefore
     * only reported when no union member is present.
     */
    fun decodeAll(raw: String): List<LiveServerEvent> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()

        root.optJSONObject("toolCallCancellation")?.let { cancellation ->
            val ids = cancellation.optJSONArray("ids")?.let { array ->
                (0 until array.length()).map { array.optString(it) }
            }.orEmpty()
            return listOf(LiveServerEvent.ToolCallCancellation(ids))
        }

        root.optJSONObject("toolCall")?.let { toolCall ->
            val array = toolCall.optJSONArray("functionCalls") ?: return emptyList()
            val calls = (0 until array.length()).mapNotNull { index ->
                val call = array.optJSONObject(index) ?: return@mapNotNull null
                FunctionCall(
                    id = call.optString("id"),
                    name = call.optString("name"),
                    args = call.optJSONObject("args") ?: JSONObject(),
                )
            }
            return listOf(LiveServerEvent.ToolCall(calls))
        }

        root.optJSONObject("goAway")?.let { goAway ->
            return listOf(
                LiveServerEvent.GoAway(goAway.optString("timeLeft").ifBlank { null }),
            )
        }

        root.optJSONObject("sessionResumptionUpdate")?.let { update ->
            return listOf(
                LiveServerEvent.SessionResumption(
                    handle = update.optString("newHandle").ifBlank { null },
                    resumable = update.optBoolean("resumable", false),
                ),
            )
        }

        if (root.has("setupComplete")) return listOf(LiveServerEvent.SetupComplete)

        root.optJSONObject("serverContent")?.let { return decodeServerContent(it) }

        root.optJSONObject("usageMetadata")?.let { usage ->
            return listOf(LiveServerEvent.Usage(usage.optInt("totalTokenCount", 0)))
        }

        return emptyList()
    }

    private fun decodeServerContent(content: JSONObject): List<LiveServerEvent> {
        val events = mutableListOf<LiveServerEvent>()

        content.optJSONObject("interimInputTranscription")?.let { interim ->
            val text = interim.optString("text")
            if (text.isNotEmpty()) events += LiveServerEvent.InputTranscript(text, final = false)
        }

        content.optJSONObject("inputTranscription")?.let { input ->
            val text = input.optString("text")
            if (text.isNotEmpty()) events += LiveServerEvent.InputTranscript(text, final = true)
        }

        content.optJSONObject("outputTranscription")?.let { output ->
            val text = output.optString("text")
            if (text.isNotEmpty()) events += LiveServerEvent.OutputTranscript(text)
        }

        content.optJSONObject("modelTurn")?.let { turn ->
            val parts = turn.optJSONArray("parts")
            if (parts != null) {
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    if (part.optBoolean("thought", false)) continue // thinking summary, not audio
                    val inline = part.optJSONObject("inlineData") ?: continue
                    val data = inline.optString("data")
                    if (data.isEmpty()) continue
                    val pcm = runCatching {
                        android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                    }.getOrNull() ?: continue
                    if (pcm.isNotEmpty()) events += LiveServerEvent.Audio(pcm)
                }
            }
        }

        // Interruption is authoritative and must land after any audio in the same frame so
        // the flush wins.
        if (content.optBoolean("interrupted", false)) events += LiveServerEvent.Interrupted

        if (content.optBoolean("turnComplete", false)) events += LiveServerEvent.TurnComplete

        return events
    }
}
