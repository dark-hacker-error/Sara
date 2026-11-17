package com.sara.assistant.backend

import com.sara.assistant.core.SaraLog
import com.sara.assistant.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Events the app must act on, pushed by the backend. */
sealed interface BackendEvent {

    class Ready(val agents: List<String>) : BackendEvent

    class Step(
        val taskId: String,
        val agent: String,
        val detail: String,
        val status: String,
    ) : BackendEvent

    /** The backend wants the phone to do something. Reply with [deviceResult]. */
    class DeviceAction(
        val taskId: String,
        val callId: String,
        val action: String,
        val params: JSONObject,
    ) : BackendEvent

    class Error(val taskId: String?, val message: String) : BackendEvent

    class Disconnected(val reason: String) : BackendEvent
}

/**
 * WebSocket client for the ADK backend.
 *
 * The app is the *device side*: the backend drives phone actions through this channel while
 * an ADK subagent works, and streams progress back so the UI can show a task timeline.
 *
 * Delegation is request/response correlated by `task_id`: [delegate] suspends until the
 * backend sends `delegate_result` for that id.
 */
class BackendClient(private val settings: SettingsRepository) {

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null

    private val _connection = MutableStateFlow(ConnectionState.OFFLINE)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    /** Human-readable reason for the last failure, shown in the UI instead of a bare "offline". */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<BackendEvent> = _events.asSharedFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result<String>>>()

    /**
     * Opens the socket and greets the backend with the current agent config, so the server
     * can build its agents without a restart.
     */
    fun connect() {
        if (socket != null) return
        val endpoint = settings.current.endpoint
        _connection.value = ConnectionState.CONNECTING
        _lastError.value = null

        val request = Request.Builder().url(endpoint.wsUrl).build()
        socket = client.newWebSocket(request, Listener())
        send(helloMessage())
    }

    fun disconnect() {
        socket?.close(1000, "client closing")
        socket = null
        failAllPending("Disconnected")
        _connection.value = ConnectionState.OFFLINE
    }

    /** Pushes edited agent config to a live backend. No restart needed. */
    fun pushConfig() {
        send(helloMessage(type = "config_update"))
    }

    /**
     * Hands a goal to the agent team and suspends until it finishes.
     * The caller (the Live tool handler) sends the returned text back as a tool response.
     */
    suspend fun delegate(taskId: String, goal: String, kind: String): Result<String> {
        if (_connection.value != ConnectionState.ONLINE) {
            connect()
            // Give the handshake a moment; delegated tasks are user-initiated so a short
            // wait is far better than failing outright.
            var waited = 0
            while (_connection.value != ConnectionState.ONLINE && waited < HANDSHAKE_WAIT_MS) {
                kotlinx.coroutines.delay(100)
                waited += 100
            }
        }
        if (_connection.value != ConnectionState.ONLINE) {
            return Result.failure(
                IllegalStateException(
                    _lastError.value
                        ?: "Backend offline at ${settings.current.endpoint.httpBase}. " +
                        "Check the URL in Settings, or use USB with `adb reverse tcp:8000 tcp:8000`.",
                ),
            )
        }

        val deferred = CompletableDeferred<Result<String>>()
        pending[taskId] = deferred
        send(JSONObject().apply {
            put("type", "delegate")
            put("task_id", taskId)
            put("goal", goal)
            put("kind", kind.ifBlank { "auto" })
        }.toString())
        return deferred.await()
    }

    fun cancel(taskId: String) {
        send(JSONObject().put("type", "cancel").put("task_id", taskId).toString())
        pending.remove(taskId)?.complete(Result.failure(CancellationException("Cancelled")))
    }

    fun deviceResult(taskId: String, callId: String, ok: Boolean, data: JSONObject, error: String?) {
        send(JSONObject().apply {
            put("type", "device_result")
            put("task_id", taskId)
            put("call_id", callId)
            put("ok", ok)
            put("data", data)
            if (error != null) put("error", error)
        }.toString())
    }

    // ---------------------------------------------------------------- internals

    private fun send(payload: String) {
        val active = socket
        if (active == null) {
            SaraLog.w("Backend send dropped, no socket")
            return
        }
        if (!active.send(payload)) SaraLog.w("Backend send failed")
    }

    private fun helloMessage(type: String = "hello"): String {
        val current = settings.current
        return JSONObject().apply {
            put("type", type)
            put("protocol", PROTOCOL_VERSION)
            put("live_model", current.live.model)
            put("system_prompt", current.live.systemPrompt)
            put("agents", JSONArray().apply {
                current.agents.forEach { put(it.toWireJson()) }
            })
        }.toString()
    }

    private fun handle(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrElse {
            SaraLog.w("Backend sent non-JSON payload")
            return
        }
        when (json.optString("type")) {
            "ready" -> {
                val agents = json.optJSONArray("agents")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }
                }.orEmpty()
                _events.tryEmit(BackendEvent.Ready(agents))
            }

            "step" -> _events.tryEmit(
                BackendEvent.Step(
                    taskId = json.optString("task_id"),
                    agent = json.optString("agent"),
                    detail = json.optString("detail"),
                    status = json.optString("status", "running"),
                ),
            )

            "device_action" -> _events.tryEmit(
                BackendEvent.DeviceAction(
                    taskId = json.optString("task_id"),
                    callId = json.optString("call_id"),
                    action = json.optString("action"),
                    params = json.optJSONObject("params") ?: JSONObject(),
                ),
            )

            "delegate_result" -> {
                val taskId = json.optString("task_id")
                val ok = json.optBoolean("ok", false)
                val text = json.optString("text")
                val result = if (ok) {
                    Result.success(text)
                } else {
                    Result.failure(IllegalStateException(text.ifBlank { "Task failed" }))
                }
                pending.remove(taskId)?.complete(result)
                _events.tryEmit(BackendEvent.Step(taskId, "root", text, if (ok) "done" else "failed"))
            }

            "error" -> {
                val taskId = json.optString("task_id").ifBlank { null }
                val message = json.optString("message", "Backend error")
                if (taskId != null) {
                    pending.remove(taskId)?.complete(Result.failure(IllegalStateException(message)))
                }
                _events.tryEmit(BackendEvent.Error(taskId, message))
            }

            else -> SaraLog.d("Backend event ignored: ${json.optString("type")}")
        }
    }

    private fun failAllPending(reason: String) {
        pending.keys.toList().forEach { key ->
            pending.remove(key)?.complete(Result.failure(IllegalStateException(reason)))
        }
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            SaraLog.i("Backend connected: ${settings.current.endpoint.wsUrl}")
            _connection.value = ConnectionState.ONLINE
            _lastError.value = null
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            _connection.value = ConnectionState.OFFLINE
            val text = "Backend closed the socket (code $code${if (reason.isBlank()) "" else ", $reason"})."
            _lastError.value = text
            failAllPending(text)
            _events.tryEmit(BackendEvent.Disconnected(text))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            SaraLog.e("Backend socket failure at ${settings.current.endpoint.wsUrl}", t)
            socket = null
            _connection.value = ConnectionState.OFFLINE
            val text = describeFailure(t, response)
            _lastError.value = text
            failAllPending(text)
            _events.tryEmit(BackendEvent.Disconnected(text))
        }
    }

    /**
     * Turns an OkHttp exception into something actionable. The user's screenshot said
     * "failed to connect ... after 10000ms", which hides whether it was DNS, a closed port or
     * a firewall.
     */
    private fun describeFailure(t: Throwable, response: Response?): String {
        val base = settings.current.endpoint.httpBase
        if (response != null) {
            return when (response.code) {
                401, 403 -> "Backend rejected the handshake (HTTP ${response.code}). Check the server log."
                404 -> "No /ws/session at $base — wrong port or wrong path."
                else -> "Backend handshake failed: HTTP ${response.code}."
            }
        }
        val message = t.message.orEmpty()
        return when {
            t is java.net.UnknownHostException ->
                "Host not found ($base). Use the PC's LAN IPv4, or USB + adb reverse."

            t is java.net.ConnectException ->
                "Connection refused at $base. Is the backend running on 0.0.0.0:8000?"

            t is java.net.SocketTimeoutException ->
                "$base did not answer in ${t.message ?: "timeout"}. Firewall or wrong IP."

            message.contains("CLEARTEXT", ignoreCase = true) ->
                "Android blocked plain ws:// to $base. Use wss:// (tunnel) instead."

            t is javax.net.ssl.SSLException ->
                "TLS failed for $base. The tunnel certificate must be publicly trusted."

            else -> "Backend unreachable: ${t.javaClass.simpleName}: ${message.ifBlank { "unknown" }}"
        }
    }

    enum class ConnectionState { OFFLINE, CONNECTING, ONLINE }

    companion object {
        const val PROTOCOL_VERSION = 1
        private const val HANDSHAKE_WAIT_MS = 3_000L
    }
}

private fun com.sara.assistant.data.AgentConfig.toWireJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("role", role.name)
    put("enabled", enabled)
    put("provider", provider.name)
    put("base_url", baseUrl)
    put("api_key", apiKey)
    put("model", model)
    put("system_prompt", systemPrompt)
}

/** Small helper so callers get a stable id without importing java.util.UUID everywhere. */
fun newTaskId(): String = UUID.randomUUID().toString()
