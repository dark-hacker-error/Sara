package com.sara.assistant.ui.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.sara.assistant.backend.HealthChecker
import com.sara.assistant.backend.HealthResult
import com.sara.assistant.data.SettingsRepository
import com.sara.assistant.data.remote.GeminiModelCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class CheckStatus { OK, WARN, FAIL, SKIPPED }

data class DiagnosticCheck(
    val name: String,
    val status: CheckStatus,
    val detail: String,
    val hint: String? = null,
)

/**
 * Runs the first-run health sweep. Every check is independent, so one failure still shows the
 * rest of the picture — which is the whole point of a diagnostics screen.
 *
 * The WebSocket check opens a real socket to `/ws/session` and waits for the backend's `ready`
 * frame, so it proves the whole path (DNS/TCP/TLS/upgrade/first frame) rather than just HTTP.
 */
class DiagnosticsRunner(
    private val context: Context,
    private val settings: SettingsRepository,
    private val healthChecker: HealthChecker,
) {

    private val geminiCatalog = GeminiModelCatalog()

    suspend fun runAll(): List<DiagnosticCheck> = buildList {
        add(checkInternet())
        add(checkMicrophone())
        add(checkAccessibility())
        add(checkOverlay())

        val health = healthChecker.check(settings.current.backendUrl)
        add(healthCheck(health))

        // Only probe the socket if HTTP reached a Sara backend; otherwise the result is noise.
        add(
            if (health is HealthResult.Healthy) checkWebSocket() else DiagnosticCheck(
                name = "Backend WebSocket",
                status = CheckStatus.SKIPPED,
                detail = "Skipped — HTTP health check failed.",
                hint = "Fix the health check first.",
            ),
        )

        add(checkGeminiKey())
        add(
            if (settings.current.live.geminiApiKey.isBlank()) {
                DiagnosticCheck(
                    name = "Live model reachable",
                    status = CheckStatus.SKIPPED,
                    detail = "Skipped — no Gemini API key.",
                )
            } else {
                checkLiveModels()
            },
        )
    }

    // ---------------------------------------------------------------- local checks

    fun checkInternet(): DiagnosticCheck {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        // There is no `activeNetworkCapabilities` property; capabilities belong to a Network.
        val capabilities = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        return when {
            capabilities == null -> DiagnosticCheck(
                name = "Internet",
                status = CheckStatus.FAIL,
                detail = "No active network.",
                hint = "Turn on Wi-Fi or mobile data.",
            )

            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> DiagnosticCheck(
                name = "Internet",
                status = CheckStatus.OK,
                detail = "Connected and validated.",
            )

            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> DiagnosticCheck(
                name = "Internet",
                status = CheckStatus.WARN,
                detail = "Network present but not validated (captive portal?).",
                hint = "Open a browser and complete the login page.",
            )

            else -> DiagnosticCheck(
                name = "Internet",
                status = CheckStatus.WARN,
                detail = "Connected, but this network reports no internet.",
                hint = "USB + `adb reverse` works without internet, so you can still test the backend.",
            )
        }
    }

    fun checkMicrophone(): DiagnosticCheck {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (granted) {
            DiagnosticCheck("Microphone", CheckStatus.OK, "Permission granted.")
        } else {
            DiagnosticCheck(
                "Microphone",
                CheckStatus.FAIL,
                "Permission missing.",
                "Settings → Sara setup → Allow microphone.",
            )
        }
    }

    fun checkAccessibility(): DiagnosticCheck {
        val enabled = com.sara.assistant.ui.Permissions.isAccessibilityEnabled(context)
        return if (enabled) {
            DiagnosticCheck("Accessibility service", CheckStatus.OK, "Service is enabled.")
        } else {
            DiagnosticCheck(
                "Accessibility service",
                CheckStatus.FAIL,
                "Not enabled — Sara cannot see or tap the screen.",
                "Android Settings → Accessibility → Sara phone control → ON.",
            )
        }
    }

    fun checkOverlay(): DiagnosticCheck =
        if (android.provider.Settings.canDrawOverlays(context)) {
            DiagnosticCheck("Display over other apps", CheckStatus.OK, "Granted.")
        } else {
            DiagnosticCheck(
                "Display over other apps",
                CheckStatus.WARN,
                "Optional — only the floating bubble needs it.",
                "Android Settings → Apps → Sara → Display over other apps.",
            )
        }

    // ---------------------------------------------------------------- network checks

    private fun checkGeminiKey(): DiagnosticCheck {
        val key = settings.current.live.geminiApiKey
        return if (key.isBlank()) {
            DiagnosticCheck(
                "Gemini API key",
                CheckStatus.FAIL,
                "Not set.",
                "Settings → Gemini Live → paste your key from aistudio.google.com.",
            )
        } else {
            DiagnosticCheck(
                "Gemini API key",
                CheckStatus.OK,
                "Present (${key.length} chars, ends …${key.takeLast(4)}).",
            )
        }
    }

    private suspend fun checkLiveModels(): DiagnosticCheck {
        val key = settings.current.live.geminiApiKey
        return geminiCatalog.listLiveModels(key).fold(
            onSuccess = { models ->
                val configured = settings.current.live.model
                if (models.isEmpty()) {
                    DiagnosticCheck(
                        "Live model reachable",
                        CheckStatus.WARN,
                        "Google reachable, but it listed no Live models for this key.",
                        "Pick a model in Settings; the curated list still works.",
                    )
                } else if (models.none { it == configured }) {
                    DiagnosticCheck(
                        "Live model reachable",
                        CheckStatus.WARN,
                        "Configured model '$configured' is not in this key's Live list.",
                        "Available: ${models.take(6).joinToString()} — change it in Settings.",
                    )
                } else {
                    DiagnosticCheck(
                        "Live model reachable",
                        CheckStatus.OK,
                        "$configured is available (${models.size} Live models listed).",
                    )
                }
            },
            onFailure = { error ->
                DiagnosticCheck(
                    "Live model reachable",
                    CheckStatus.FAIL,
                    error.message ?: "Could not reach Gemini.",
                    "Check internet, then re-copy the API key.",
                )
            },
        )
    }

    private fun healthCheck(result: HealthResult): DiagnosticCheck = when (result) {
        is HealthResult.Healthy -> DiagnosticCheck(
            name = "Backend /health",
            status = if (result.adkReady) CheckStatus.OK else CheckStatus.WARN,
            detail = "OK (v${result.version}) at ${result.endpoint}. " +
                "ADK ${if (result.adkReady) "ready" else "NOT ready"}, " +
                "Gemini key ${if (result.geminiKeyPresent) "present" else "missing"}. " +
                "${result.subagents} subagent(s).",
            hint = if (result.adkReady) null else {
                "The backend is up but has no orchestrator credentials. Set GEMINI_API_KEY in backend/.env."
            },
        )

        is HealthResult.Failure -> DiagnosticCheck(
            name = "Backend /health",
            status = CheckStatus.FAIL,
            detail = "${result.kind}: ${result.message}",
            hint = result.hint,
        )
    }

    private suspend fun checkWebSocket(): DiagnosticCheck = withContext(Dispatchers.IO) {
        val wsUrl = settings.current.endpoint.wsUrl
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val ready = CompletableDeferred<String>()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"ready\"") && !ready.isCompleted) {
                    ready.complete("ready frame received")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!ready.isCompleted) {
                    val code = response?.code
                    ready.complete(
                        "failed: ${t.javaClass.simpleName}: ${t.message}${if (code != null) " (HTTP $code)" else ""}",
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!ready.isCompleted) ready.complete("closed $code $reason")
            }
        }

        val socket = client.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
        val outcome = withTimeoutOrNull(8_000) { ready.await() } ?: "timed out after 8s"
        socket.cancel()

        if (outcome.startsWith("ready")) {
            DiagnosticCheck("Backend WebSocket", CheckStatus.OK, "$wsUrl — $outcome.")
        } else {
            DiagnosticCheck(
                name = "Backend WebSocket",
                status = CheckStatus.FAIL,
                detail = "$wsUrl — $outcome",
                hint = "Health OK but the socket failed: check for a proxy that rewrites WebSocket upgrades.",
            )
        }
    }
}