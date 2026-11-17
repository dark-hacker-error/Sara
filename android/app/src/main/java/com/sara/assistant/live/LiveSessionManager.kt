package com.sara.assistant.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.core.content.ContextCompat
import com.sara.assistant.core.SaraLog
import com.sara.assistant.data.SettingsRepository
import com.sara.assistant.safety.SafetyGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the Gemini Live session.
 *
 * ## The frame-ordering rule that fixes close code 1007
 *
 * Per the Live API docs: *"Clients should wait for a `BidiGenerateContentSetupComplete`
 * message before sending any additional messages."*
 *
 * Streaming mic audio as soon as the socket object exists violates that: OkHttp queues the
 * audio frames ahead of the setup message, the server receives audio before it has a session,
 * and it closes with **1007 (invalid frame payload data)**. So every outbound realtime frame
 * goes through [setupComplete]. Text typed before the session is ready is queued, not dropped.
 *
 * ## Failure policy
 *
 * Close codes are classified. Protocol/config codes (1002/1003/1007/1008/1009, 4xxx) stop and
 * surface the reason with a fix hint — retrying those just repeats the same rejection.
 * Transient drops retry with exponential backoff (1,2,4,8,16s, max 5 tries), then fall through
 * to the configured fallback model, and only then give up.
 */
class LiveSessionManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val safety: SafetyGuard,
    private val tools: LiveToolExecutor,
) {

    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val audio = AudioEngine(scope)
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow(SaraUiState())
    val state: StateFlow<SaraUiState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var toolJob: Job? = null

    /** Handle for session resumption. Cleared whenever the model changes: a handle is only
     *  meaningful to the model that issued it. */
    @Volatile
    private var resumeHandle: String? = null

    /**
     * True only after the server's `setupComplete`. Nothing realtime leaves the device before
     * this flips — see the class comment.
     */
    @Volatile
    private var setupComplete = false

    @Volatile
    private var activeModelIndex = 0

    @Volatile
    private var reconnectAttempt = 0

    @Volatile
    private var running = false

    /** Last close code/reason from the server, kept so the UI and logs can show them. */
    @Volatile
    private var lastCloseCode: Int? = null

    @Volatile
    private var lastCloseReason: String = ""

    /** Text typed while the session was still connecting. */
    private val pendingText = mutableListOf<String>()

    private val nextTranscriptId = AtomicLong(1)
    private val nextStepId = AtomicLong(1)

    private val modelChain: List<String>
        get() = settings.current.modelChain

    private val currentModel: String
        get() = modelChain.getOrElse(activeModelIndex) { modelChain.firstOrNull().orEmpty() }

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (running) return
        if (!hasMicPermission()) {
            publishError("Microphone permission is required", "Grant it in Sara's setup screen.")
            return
        }
        if (settings.current.live.geminiApiKey.isBlank()) {
            publishError(
                "Gemini API key missing",
                "Add your key in Settings → Gemini Live.",
            )
            return
        }

        running = true
        reconnectAttempt = 0
        activeModelIndex = 0
        resumeHandle = null
        pendingText.clear()
        safety.clearStop()
        audio.onInputFrame = ::onMicFrame
        audio.startCapture()
        audio.startPlayback()
        connect()
    }

    fun stop() {
        running = false
        setupComplete = false
        toolJob?.cancel()
        toolJob = null
        socket?.close(NORMAL_CLOSURE, "client stopped")
        socket = null
        pendingText.clear()
        audio.onInputFrame = null
        audio.release()
        update {
            it.copy(
                liveState = LiveState.IDLE,
                gemini = GeminiConnection.IDLE,
                statusText = "",
                inputLevel = 0f,
                outputLevel = 0f,
            )
        }
    }

    /** "Fix & retry": clears the failure and starts a fresh attempt immediately. */
    fun retryNow() {
        if (!running) {
            start()
            return
        }
        reconnectAttempt = 0
        activeModelIndex = 0
        resumeHandle = null
        setupComplete = false
        update { it.copy(failure = null, lastError = null, gemini = GeminiConnection.CONNECTING) }
        socket?.cancel()
        socket = null
        connect()
    }

    /** Injects a text turn. Queued until the session is ready. */
    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (!setupComplete) {
            pendingText += trimmed
            update { it.copy(statusText = "Waiting for Gemini…") }
            return
        }
        socket?.send(LiveProtocol.textInput(trimmed))
        appendTranscript(fromUser = true, text = trimmed, final = true)
    }

    fun bargeIn() {
        audio.flushOutput()
        update { it.copy(liveState = LiveState.LISTENING, statusText = "Listening") }
    }

    // ---------------------------------------------------------------- connect

    private fun connect() {
        val chain = modelChain
        if (chain.isEmpty()) {
            publishError("No Live model configured", "Pick one in Settings → Gemini Live.")
            return
        }

        val model = currentModel
        update {
            it.copy(
                liveState = LiveState.CONNECTING,
                gemini = GeminiConnection.CONNECTING,
                statusText = "Connecting to $model…",
                failure = null,
            )
        }
        SaraLog.i("Opening Live socket for model=$model")

        val request = Request.Builder()
            .url(LiveProtocol.endpointFor(settings.current.live.geminiApiKey))
            .build()
        socket = client.newWebSocket(request, Listener(model))
    }

    private fun sendSetup(model: String) {
        val live = settings.current.live
        val payload = LiveProtocol.setup(
            model = model,
            systemInstruction = live.systemPrompt,
            voice = live.voice,
            inputTranscription = live.inputTranscription,
            outputTranscription = live.outputTranscription,
            silenceDurationMs = live.silenceDurationMs,
            prefixPaddingMs = live.prefixPaddingMs,
            bargeIn = live.bargeIn,
            resumeHandle = resumeHandle,
            toolDeclarations = LiveTools.declarationsFor(model),
        )
        socket?.send(payload)
    }

    // ---------------------------------------------------------------- audio

    private fun onMicFrame(pcm: ByteArray, level: Float) {
        // THE FIX for 1007: never send audio before setupComplete.
        if (!setupComplete) return
        if (safety.isStopped) return
        val active = socket ?: return

        val encoded = Base64.encodeToString(pcm, Base64.NO_WRAP)
        if (!active.send(LiveProtocol.audioChunk(encoded))) {
            SaraLog.w("Live audio send failed (socket busy or closed)")
            return
        }
        update { it.copy(inputLevel = level) }
    }

    // ---------------------------------------------------------------- inbound

    private fun handle(event: LiveServerEvent) {
        when (event) {
            is LiveServerEvent.SetupComplete -> {
                setupComplete = true
                reconnectAttempt = 0
                SaraLog.i("Live setupComplete (model=$currentModel)")
                update {
                    it.copy(
                        liveState = LiveState.LISTENING,
                        gemini = GeminiConnection.CONNECTED,
                        statusText = "Listening",
                        failure = null,
                        lastError = null,
                    )
                }
                flushPendingText()
            }

            is LiveServerEvent.Audio -> {
                audio.enqueueOutput(event.pcm)
                update { it.copy(liveState = LiveState.SPEAKING, statusText = "Speaking") }
            }

            is LiveServerEvent.InputTranscript -> {
                safety.noteUserUtterance(event.text)
                appendTranscript(fromUser = true, text = event.text, final = event.final)
            }

            is LiveServerEvent.OutputTranscript ->
                appendTranscript(fromUser = false, text = event.text, final = true)

            is LiveServerEvent.Interrupted -> {
                audio.flushOutput()
                update { it.copy(liveState = LiveState.LISTENING, statusText = "Listening") }
            }

            is LiveServerEvent.TurnComplete ->
                update {
                    if (it.liveState == LiveState.SPEAKING) {
                        it.copy(liveState = LiveState.LISTENING, statusText = "Listening")
                    } else {
                        it
                    }
                }

            is LiveServerEvent.ToolCall -> onToolCall(event.calls)

            is LiveServerEvent.ToolCallCancellation ->
                SaraLog.i("Tool calls cancelled: ${event.ids.joinToString()}")

            is LiveServerEvent.SessionResumption -> {
                if (event.handle != null) resumeHandle = event.handle
            }

            is LiveServerEvent.GoAway -> {
                SaraLog.i("Server goAway (${event.timeLeft}); resuming")
                // Not a failure: reconnect straight away with the resumption handle.
                setupComplete = false
                reconnectAttempt = 0
                socket?.cancel()
                socket = null
                if (running) connect()
            }

            is LiveServerEvent.Usage -> SaraLog.d("Live tokens used: ${event.totalTokens}")
        }
    }

    private fun flushPendingText() {
        if (pendingText.isEmpty()) return
        val queued = pendingText.toList()
        pendingText.clear()
        queued.forEach { text ->
            socket?.send(LiveProtocol.textInput(text))
            appendTranscript(fromUser = true, text = text, final = true)
        }
    }

    private fun onToolCall(calls: List<FunctionCall>) {
        if (calls.isEmpty()) return
        update { it.copy(liveState = LiveState.ACTING, statusText = "Working on it…") }

        toolJob = scope.launch {
            val responses = calls.map { call ->
                val payload = runCatching { tools.execute(call) }.getOrElse { error ->
                    SaraLog.e("Tool '${call.name}' failed", error)
                    JSONObject().put("ok", false).put("error", error.message ?: "Tool failed")
                }
                LiveProtocol.functionResponse(call.id, call.name, payload)
            }
            socket?.send(LiveProtocol.toolResponse(responses))
            update { it.copy(liveState = LiveState.THINKING, statusText = "Thinking") }
        }
    }

    // ---------------------------------------------------------------- failure policy

    /**
     * Codes that will fail again identically no matter how often we retry: the setup is
     * wrong, the key is wrong, or the model does not exist. Retrying these wastes the user's
     * time and hides the real error.
     */
    private fun isPermanentClose(code: Int): Boolean = when (code) {
        1002, 1003, 1007, 1008, 1009 -> true
        in 4400..4499 -> true
        else -> false
    }

    private fun hintForClose(code: Int?): String {
        if (code == null) {
            return "Connection lost. Check your network and tap Retry."
        }
        return when (code) {
            1007 -> "The server rejected a frame. This is almost always a wrong Live model id, or " +
                "a setup field that model does not support. Pick a model in Settings → Gemini Live " +
                "(current stable: gemini-3.8-live)."

            1008 -> "The Gemini API rejected the request. Check the API key, and that this model is " +
                "available to your key."

            1003 -> "Unsupported data type. Native-audio Live models accept AUDIO responses only."

            1002 -> "Protocol error: the setup JSON was rejected. Reset the Live model and voice to defaults."

            1009 -> "Frame too large. Please report this with the Logcat tag SaraWS."

            in 4400..4499 -> "The server refused this session. Usually a bad key or a model that is " +
                "not enabled for it."

            else -> "Connection lost. Check your network and tap Retry."
        }
    }

    /** Transient drop: exponential backoff 1,2,4,8,16s, max 5 tries, then the fallback model. */
    private fun scheduleReconnect(reason: String) {
        if (!running) return
        setupComplete = false
        audio.flushOutput()

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            val chain = modelChain
            if (activeModelIndex < chain.lastIndex) {
                activeModelIndex++
                reconnectAttempt = 0
                // A resumption handle belongs to the model that issued it.
                resumeHandle = null
                SaraLog.i("Falling back to ${currentModel}")
                update {
                    it.copy(
                        statusText = "Trying fallback model $currentModel…",
                        failure = null,
                    )
                }
            } else {
                publishFailure(
                    LiveFailure(
                        code = lastCloseCode,
                        reason = lastCloseReason.ifBlank { reason },
                        permanent = false,
                        hint = hintForClose(lastCloseCode) + " All retries and the fallback model failed.",
                    ),
                )
                return
            }
        }

        reconnectAttempt++
        val backoffSeconds = 1L shl (reconnectAttempt - 1) // 1, 2, 4, 8, 16
        SaraLog.w(
            "Live reconnect #$reconnectAttempt in ${backoffSeconds}s (reason=$reason, " +
                "code=$lastCloseCode)",
        )
        update {
            it.copy(
                liveState = LiveState.CONNECTING,
                gemini = GeminiConnection.CONNECTING,
                statusText = "Reconnecting in ${backoffSeconds}s (attempt $reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)…",
            )
        }
        scope.launch {
            delay(backoffSeconds * 1_000)
            if (!running || setupComplete) return@launch
            socket?.cancel()
            socket = null
            connect()
        }
    }

    private fun publishFailure(failure: LiveFailure) {
        running = false
        setupComplete = false
        audio.flushOutput()
        SaraLog.e(
            "Live session failed: code=${failure.code} reason='${failure.reason}' " +
                "permanent=${failure.permanent}",
        )
        update {
            it.copy(
                liveState = LiveState.ERROR,
                gemini = GeminiConnection.ERROR,
                statusText = "",
                failure = failure,
                lastError = failure.hint,
            )
        }
    }

    private fun publishError(message: String, hint: String) {
        update {
            it.copy(
                liveState = LiveState.ERROR,
                gemini = GeminiConnection.ERROR,
                lastError = hint,
                failure = LiveFailure(null, message, permanent = true, hint = hint),
            )
        }
    }

    // ---------------------------------------------------------------- state helpers

    private fun update(transform: (SaraUiState) -> SaraUiState) {
        _state.value = transform(_state.value)
    }

    private fun appendTranscript(fromUser: Boolean, text: String, final: Boolean) {
        if (text.isBlank()) return
        val current = _state.value.transcript
        val last = current.lastOrNull()
        val next = if (last != null && last.fromUser == fromUser && !last.final) {
            current.dropLast(1) + last.copy(text = text, final = final)
        } else {
            current + TranscriptLine(
                id = nextTranscriptId.getAndIncrement(),
                fromUser = fromUser,
                text = text,
                final = final,
            )
        }
        _state.value = _state.value.copy(transcript = next.takeLast(MAX_TRANSCRIPT_LINES))
    }

    fun pushStep(agent: String, detail: String, status: StepStatus) {
        val steps = _state.value.taskSteps + TaskStep(
            id = nextStepId.getAndIncrement(),
            agent = agent,
            detail = detail,
            status = status,
        )
        _state.value = _state.value.copy(
            taskSteps = steps.takeLast(MAX_TASK_STEPS),
            liveState = if (status == StepStatus.RUNNING) LiveState.ACTING else _state.value.liveState,
        )
    }

    fun beginTask(goal: String) {
        _state.value = _state.value.copy(
            activeTaskGoal = goal,
            taskSteps = emptyList(),
            liveState = LiveState.ACTING,
            statusText = "On it",
        )
    }

    fun endTask() {
        _state.value = _state.value.copy(
            activeTaskGoal = null,
            liveState = LiveState.LISTENING,
            statusText = "Listening",
        )
    }

    fun reportError(message: String) {
        _state.value = _state.value.copy(lastError = message)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- socket

    private inner class Listener(private val model: String) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            SaraLog.i("Live socket open (model=$model)")
            setupComplete = false
            sendSetup(model)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            LiveProtocol.decodeAll(text).forEach(::handle)
        }

        /** The Live endpoint often sends JSON in binary frames; both paths share the decoder. */
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            LiveProtocol.decodeAll(bytes.utf8()).forEach(::handle)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            SaraLog.w("Live socket closing: code=$code reason='$reason'")
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            lastCloseCode = code
            lastCloseReason = reason
            setupComplete = false
            socket = null

            // Log exactly what the user needs to see, with a stable tag.
            SaraLog.e("SaraWS close code=$code reason='$reason' model=$model")

            if (!running) return

            if (isPermanentClose(code)) {
                publishFailure(
                    LiveFailure(
                        code = code,
                        reason = reason.ifBlank { "no reason given by server" },
                        permanent = true,
                        hint = hintForClose(code),
                    ),
                )
            } else if (code != NORMAL_CLOSURE) {
                scheduleReconnect("closed $code")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            setupComplete = false
            socket = null
            val httpCode = response?.code
            SaraLog.e(
                "SaraWS failure model=$model http=${httpCode ?: "-"} " +
                    "error=${t.javaClass.simpleName}: ${t.message}",
                t,
            )
            if (!running) return

            if (httpCode != null && httpCode in 400..499) {
                publishFailure(
                    LiveFailure(
                        code = httpCode,
                        reason = t.message ?: "HTTP $httpCode",
                        permanent = true,
                        hint = "Gemini rejected the connection with HTTP $httpCode. Check the API key " +
                            "and the Live model id in Settings.",
                    ),
                )
                return
            }

            scheduleReconnect(t.javaClass.simpleName + ": " + (t.message ?: "socket failure"))
        }
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val MAX_TRANSCRIPT_LINES = 200
        private const val MAX_TASK_STEPS = 40
    }
}