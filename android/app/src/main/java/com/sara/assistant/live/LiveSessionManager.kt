package com.sara.assistant.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.core.content.ContextCompat
import com.sara.assistant.core.SaraLog
import com.sara.assistant.data.SaraSettings
import com.sara.assistant.data.SettingsRepository
import com.sara.assistant.safety.SafetyGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * Owns the Gemini Live session: connect, stream mic audio, play model audio, handle
 * barge-in, execute tool calls, and survive the 15-minute session cap.
 *
 * Deliberately dumb about *what* to do with a tool call — that's [LiveToolExecutor].
 *
 * Notable behaviours:
 * - **Model fallback**: tries `settings.live.model`, then `fallbackModel`, on connect failure.
 * - **Resumption**: stores `sessionResumptionUpdate.newHandle` and reconnects with it, so a
 *   long conversation continues instead of restarting.
 * - **Barge-in**: server `interrupted` flushes the audio queue; [AudioEngine] also flushes
 *   locally when the mic hears speech, to cut the round-trip latency.
 */
class LiveSessionManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val safety: SafetyGuard,
    private val tools: LiveToolExecutor,
) {

    private val scope = CoroutineScope(Job())
    private val audio = AudioEngine(scope)
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow(SaraUiState())
    val state: StateFlow<SaraUiState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var toolJob: Job? = null

    /** Handle from the server for resuming after a disconnect. */
    @Volatile
    private var resumeHandle: String? = null

    @Volatile
    private var activeModelIndex = 0

    @Volatile
    private var reconnectAttempts = 0

    @Volatile
    private var running = false

    private val nextTranscriptId = AtomicLong(1)
    private val nextStepId = AtomicLong(1)

    /** Index into the model chain; guarded by the session coroutine. */
    private val modelChain: List<String>
        get() = settings.current.modelChain

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (running) return
        if (!hasMicPermission()) {
            update { it.copy(liveState = LiveState.ERROR, lastError = "Microphone permission is required") }
            return
        }
        val current = settings.current
        if (current.live.geminiApiKey.isBlank()) {
            update { it.copy(liveState = LiveState.ERROR, lastError = "Add your Gemini API key in Settings") }
            return
        }

        running = true
        reconnectAttempts = 0
        activeModelIndex = 0
        safety.clearStop()
        audio.onInputFrame = ::onMicFrame
        audio.startCapture()
        audio.startPlayback()
        connect(current)
    }

    fun stop() {
        running = false
        toolJob?.cancel()
        toolJob = null
        socket?.close(NORMAL_CLOSURE, "client stopped")
        socket = null
        audio.onInputFrame = null
        audio.release()
        update { it.copy(liveState = LiveState.IDLE, statusText = "", inputLevel = 0f, outputLevel = 0f) }
    }

    /** Injects a text turn — used by the overlay bubble and the "Stop" flow. */
    fun sendText(text: String) {
        val active = socket ?: return
        active.send(LiveProtocol.textInput(text))
        appendTranscript(fromUser = true, text = text, final = true)
    }

    /** Stops Sara mid-sentence without ending the session. */
    fun bargeIn() {
        audio.flushOutput()
        update { it.copy(liveState = LiveState.LISTENING, statusText = "Listening") }
    }

    // ---------------------------------------------------------------- connect

    private fun connect(current: SaraSettings) {
        val chain = current.modelChain
        if (chain.isEmpty()) {
            fail("No Live model configured")
            return
        }

        val model = chain[activeModelIndex.coerceIn(0, chain.lastIndex)]
        val apiKey = current.live.geminiApiKey
        update {
            it.copy(
                liveState = LiveState.CONNECTING,
                statusText = "Connecting to $model…",
                lastError = null,
            )
        }

        val request = Request.Builder().url(LiveProtocol.endpointFor(apiKey)).build()
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
        val active = socket ?: return
        if (safety.isStopped) return
        val encoded = Base64.encodeToString(pcm, Base64.NO_WRAP)
        if (!active.send(LiveProtocol.audioChunk(encoded))) {
            SaraLog.w("Live audio send failed")
            return
        }
        update { it.copy(inputLevel = level) }
    }

    // ---------------------------------------------------------------- inbound events

    private fun handle(event: LiveServerEvent) {
        when (event) {
            is LiveServerEvent.SetupComplete -> {
                reconnectAttempts = 0
                update { it.copy(liveState = LiveState.LISTENING, statusText = "Listening") }
            }

            is LiveServerEvent.Audio -> {
                audio.enqueueOutput(event.pcm)
                update { it.copy(liveState = LiveState.SPEAKING, statusText = "Speaking") }
            }

            is LiveServerEvent.InputTranscript -> {
                // Feed the safety gate: an affirmative reply here is what unlocks a
                // previously refused sensitive action.
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
                SaraLog.i("Server going away (${event.timeLeft}); will reconnect")
                reconnectAfterGoAway()
            }

            is LiveServerEvent.Usage -> SaraLog.d("Live tokens used: ${event.totalTokens}")
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

    // ---------------------------------------------------------------- reconnect

    private fun reconnectAfterGoAway() {
        if (!running) return
        scheduleReconnect("server asked us to reconnect")
    }

    private fun scheduleReconnect(reason: String) {
        if (!running) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            // Exhausted the current model; fall to the next one in the chain.
            val chain = modelChain
            if (activeModelIndex < chain.lastIndex) {
                activeModelIndex++
                reconnectAttempts = 0
                SaraLog.i("Switching to fallback model ${chain[activeModelIndex]}")
                update {
                    it.copy(
                        statusText = "Switching to ${chain[activeModelIndex]}…",
                        lastError = null,
                    )
                }
            } else {
                fail("Reconnect attempts exhausted ($reason)")
                return
            }
        }

        reconnectAttempts++
        val backoff = RECONNECT_BASE_MS * reconnectAttempts
        update { it.copy(liveState = LiveState.CONNECTING, statusText = "Reconnecting…") }
        scope.launch {
            delay(backoff)
            if (!running) return@launch
            socket?.cancel()
            socket = null
            connect(settings.current)
        }
    }

    private fun fail(message: String) {
        SaraLog.e("Live session failed: $message")
        update { it.copy(liveState = LiveState.ERROR, lastError = message, statusText = "") }
        running = false
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
            // Interim transcriptions replace the previous unsettled line.
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

    /** Called by the ViewModel when a delegated task emits progress. */
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

        private var setupSent = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            SaraLog.i("Live socket open ($model)")
            setupSent = true
            sendSetup(model)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            LiveProtocol.decodeAll(text).forEach(::handle)
        }

        /**
         * The Live endpoint frequently delivers JSON in binary frames, so both callbacks are
         * wired to the same decoder. Missing this is the classic "audio only, no transcripts"
         * bug.
         */
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            LiveProtocol.decodeAll(bytes.utf8()).forEach(::handle)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            SaraLog.i("Live socket closed ($code $reason)")
            socket = null
            if (running) scheduleReconnect("closed $code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            SaraLog.e("Live socket failure (model=$model, setupSent=$setupSent)", t)
            socket = null
            if (!running) return
            val detail = response?.code?.let { "HTTP $it: ${t.message}" } ?: t.message
            update { it.copy(statusText = "Connection problem…") }
            scheduleReconnect(detail ?: "socket failure")
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val MAX_RECONNECT_ATTEMPTS = 3
        const val RECONNECT_BASE_MS = 700L
        const val MAX_TRANSCRIPT_LINES = 200
        const val MAX_TASK_STEPS = 40
    }
}
