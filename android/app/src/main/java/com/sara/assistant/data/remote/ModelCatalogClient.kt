package com.sara.assistant.data

/** A model returned by an OpenAI-compatible `/models` endpoint. */
data class ModelInfo(
    val id: String,
    /** Best-effort guess that this model costs nothing. Heuristic — see [ModelCatalogClient]. */
    val looksFree: Boolean,
)

enum class ModelFetchFailure {
    BAD_URL,
    UNAUTHORIZED,
    TIMEOUT,
    NETWORK,
    SERVER,
    EMPTY,
    MALFORMED,
}

sealed interface ModelListResult {
    class Success(val models: List<ModelInfo>) : ModelListResult
    class Failure(val reason: ModelFetchFailure, val message: String) : ModelListResult
}

/**
 * Fetches the model list from an OpenAI-compatible provider.
 *
 * Contract: `GET {base_url}/models` with `Authorization: Bearer <key>`, returning
 * `{"data": [{"id": "..."}, ...]}`. That is the OpenAI REST shape and what OpenRouter,
 * Together, Groq, Fireworks, LM Studio, vLLM and friends all serve.
 *
 * The "free" tag is a **heuristic** on the model id: OpenRouter marks free variants with a
 * `:free` suffix, and several providers append `-free`. It is a convenience label for the
 * dropdown, never a guarantee.
 */
class ModelCatalogClient {

    private val client = okhttp3.OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun fetch(baseUrl: String, apiKey: String): ModelListResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val endpoint = buildEndpoint(baseUrl)
                ?: return@withContext ModelListResult.Failure(
                    ModelFetchFailure.BAD_URL,
                    "Base URL is not a valid http(s) URL: '$baseUrl'",
                )

            val request = okhttp3.Request.Builder()
                .url(endpoint)
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .header("Accept", "application/json")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()

                    if (response.code == 401 || response.code == 403) {
                        return@withContext ModelListResult.Failure(
                            ModelFetchFailure.UNAUTHORIZED,
                            "The provider rejected this API key (HTTP ${response.code}).",
                        )
                    }
                    if (!response.isSuccessful) {
                        return@withContext ModelListResult.Failure(
                            ModelFetchFailure.SERVER,
                            "Provider returned HTTP ${response.code}. ${body.take(160)}",
                        )
                    }

                    val parsed = parse(body)
                    when {
                        parsed == null -> ModelListResult.Failure(
                            ModelFetchFailure.MALFORMED,
                            "Could not read a model list from the response. Expected {\"data\":[…]}. " +
                                "Got: ${body.take(160)}",
                        )

                        parsed.isEmpty() -> ModelListResult.Failure(
                            ModelFetchFailure.EMPTY,
                            "The endpoint responded, but listed no models. Check the base URL points " +
                                "at the API root that has /models.",
                        )

                        else -> ModelListResult.Success(parsed)
                    }
                }
            } catch (timeout: java.net.SocketTimeoutException) {
                ModelListResult.Failure(
                    ModelFetchFailure.TIMEOUT,
                    "Timed out after ${CALL_TIMEOUT_SECONDS}s talking to $endpoint.",
                )
            } catch (io: java.io.IOException) {
                ModelListResult.Failure(
                    ModelFetchFailure.NETWORK,
                    "Network error reaching $endpoint: ${io.message ?: "unknown"}",
                )
            } catch (t: Throwable) {
                ModelListResult.Failure(ModelFetchFailure.NETWORK, t.message ?: "Request failed")
            }
        }

    /**
     * Normalises the base URL. Accepts the common shapes people paste in:
     * `https://x/v1`, `https://x/v1/`, `https://x/v1/models`, `https://x`.
     */
    fun buildEndpoint(baseUrl: String): String? {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        return if (trimmed.endsWith("/models")) trimmed else "$trimmed/models"
    }

    private fun parse(body: String): List<ModelInfo>? {
        val root = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return null
        val data = root.optJSONArray("data")
            ?: root.optJSONArray("models")
            ?: return null

        val models = mutableListOf<ModelInfo>()
        for (index in 0 until data.length()) {
            val entry = data.optJSONObject(index)
            val id = when {
                entry == null -> data.optString(index)
                entry.has("id") -> entry.optString("id")
                else -> entry.optString("name")
            }
            if (id.isBlank()) continue
            models.add(ModelInfo(id = id, looksFree = looksFree(id, entry)))
        }
        return models.distinctBy { it.id }.sortedBy { it.id }
    }

    private fun looksFree(id: String, entry: org.json.JSONObject?): Boolean {
        val lower = id.lowercase()
        if (lower.endsWith(":free") || lower.contains(":free")) return true
        if (lower.endsWith("-free") || lower.startsWith("free")) return true
        // OpenRouter publishes a zero price for free variants; honour it when present.
        val pricing = entry?.optJSONObject("pricing")
        val prompt = pricing?.optString("prompt")
        val completion = pricing?.optString("completion")
        if (prompt != null && completion != null) {
            val promptZero = prompt.toDoubleOrNull() == 0.0
            val completionZero = completion.toDoubleOrNull() == 0.0
            if (promptZero && completionZero) return true
        }
        return false
    }

    private companion object {
        const val CALL_TIMEOUT_SECONDS = 15L
        const val CONNECT_TIMEOUT_SECONDS = 8L
    }
}
