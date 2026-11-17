package com.sara.assistant.backend

import android.util.Log
import com.sara.assistant.data.BackendEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * `GET {base}/health` with every failure classified.
 *
 * "Backend offline" is useless when you are debugging. This produces a specific reason plus a
 * concrete next step, which is what the Settings "Test connection" button and the Diagnostics
 * screen show.
 */
class HealthChecker {

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TOTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun check(rawUrl: String): HealthResult = withContext(Dispatchers.IO) {
        val endpoint = BackendEndpoint.parse(rawUrl).getOrElse { error ->
            return@withContext HealthResult.Failure(
                kind = HealthFailure.BAD_URL,
                message = error.message ?: "Backend URL is not valid.",
                hint = "Use the shape http://<host>:8000 — e.g. http://127.0.0.1:8000 over USB.",
            )
        }

        val request = okhttp3.Request.Builder()
            .url(endpoint.healthUrl)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext HealthResult.Failure(
                        kind = HealthFailure.HTTP,
                        message = "${endpoint.healthUrl} returned HTTP ${response.code}.",
                        hint = if (response.code == 404) {
                            "URL reached something, but not Sara's backend. Check the port and path."
                        } else {
                            "The server answered with an error. Read its console log."
                        },
                    )
                }

                val json = runCatching { JSONObject(body) }.getOrNull()
                    ?: return@withContext HealthResult.Failure(
                        kind = HealthFailure.MALFORMED,
                        message = "${endpoint.healthUrl} did not return Sara's JSON.",
                        hint = "Is another service running on that port?",
                    )

                HealthResult.Healthy(
                    endpoint = endpoint.httpBase,
                    version = json.optString("version", "?"),
                    adkReady = json.optBoolean("adk_ready", json.optBoolean("orchestrator_ready", false)),
                    geminiKeyPresent = json.optBoolean("gemini_key_present", false),
                    subagents = json.optJSONArray("subagents")?.length() ?: 0,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "health check failed for ${endpoint.healthUrl}: ${e.javaClass.simpleName}: ${e.message}")
            classify(endpoint, e)
        }
    }

    private fun classify(endpoint: BackendEndpoint, error: Exception): HealthResult.Failure = when (error) {
        is UnknownHostException -> HealthResult.Failure(
            kind = HealthFailure.DNS,
            message = "Host not found: ${error.message ?: endpoint.httpBase}",
            hint = "Typo in the IP? On Windows run `ipconfig`, on macOS/Linux `ip a`, and use the IPv4 of the PC.",
        )

        is ConnectException -> HealthResult.Failure(
            kind = HealthFailure.REFUSED,
            message = "Connection refused at ${endpoint.httpBase}.",
            hint = "Backend is probably not running, or bound to 127.0.0.1 only. Start it with " +
                "`uvicorn app.main:app --host 0.0.0.0 --port 8000`.",
        )

        is SocketTimeoutException -> HealthResult.Failure(
            kind = HealthFailure.TIMEOUT,
            message = "Timed out after ${TOTAL_TIMEOUT_SECONDS}s contacting ${endpoint.httpBase}.",
            hint = "A firewall is dropping packets. Allow port 8000, or use USB + `adb reverse`.",
        )

        is UnknownServiceException -> {
            val text = error.message.orEmpty()
            if (text.contains("CLEARTEXT", ignoreCase = true)) {
                HealthResult.Failure(
                    kind = HealthFailure.CLEARTEXT_BLOCKED,
                    message = "Android blocked plain http:// to ${endpoint.httpBase}.",
                    hint = "This build allows cleartext for local/LAN hosts. If you still see this, " +
                        "use https:// (tunnel) instead of http://.",
                )
            } else {
                HealthResult.Failure(
                    kind = HealthFailure.NETWORK,
                    message = error.message ?: "Network error.",
                    hint = "Check the Wi-Fi/mobile-data connection.",
                )
            }
        }

        is SSLException -> HealthResult.Failure(
            kind = HealthFailure.TLS,
            message = "TLS handshake failed: ${error.message ?: "certificate problem"}",
            hint = "For https tunnels the certificate must be public (Cloudflare/ngrok are). " +
                "Self-signed certs are rejected by Android.",
        )

        is IOException -> {
            val text = error.message.orEmpty()
            if (text.contains("CLEARTEXT", ignoreCase = true)) {
                HealthResult.Failure(
                    kind = HealthFailure.CLEARTEXT_BLOCKED,
                    message = "Android blocked plain http:// to ${endpoint.httpBase}.",
                    hint = "Use https:// (tunnel), or a build whose network config allows this host.",
                )
            } else {
                HealthResult.Failure(
                    kind = HealthFailure.NETWORK,
                    message = error.message ?: "Network error.",
                    hint = "Check the connection and try again.",
                )
            }
        }

        else -> HealthResult.Failure(
            kind = HealthFailure.NETWORK,
            message = "${error.javaClass.simpleName}: ${error.message ?: "unknown error"}",
            hint = "Unexpected failure. Check the backend console log.",
        )
    }

    private companion object {
        const val TAG = "SaraWS"
        const val CONNECT_TIMEOUT_SECONDS = 8L
        const val READ_TIMEOUT_SECONDS = 8L
        const val TOTAL_TIMEOUT_SECONDS = 10L
    }
}

enum class HealthFailure {
    BAD_URL,
    DNS,
    REFUSED,
    TIMEOUT,
    CLEARTEXT_BLOCKED,
    TLS,
    HTTP,
    MALFORMED,
    NETWORK,
}

sealed interface HealthResult {

    data class Healthy(
        val endpoint: String,
        val version: String,
        val adkReady: Boolean,
        val geminiKeyPresent: Boolean,
        val subagents: Int,
    ) : HealthResult

    data class Failure(
        val kind: HealthFailure,
        val message: String,
        val hint: String,
    ) : HealthResult
}