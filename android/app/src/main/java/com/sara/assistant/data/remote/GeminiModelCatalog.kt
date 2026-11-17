package com.sara.assistant.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Lists the Live-capable models available to a given Gemini API key.
 *
 * Uses the documented REST endpoint `GET /v1beta/models`, whose `Model` resource carries
 * `supportedGenerationMethods` (Pascal-case method names). Live models advertise
 * `bidiGenerateContent` — that is the flag this filters on, with the model-name pattern as a
 * fallback for older responses that omit the field.
 *
 * This exists so the user can pick a model that actually exists instead of typing one from
 * memory: several plausible-looking Live ids do not exist, and the server answers those with a
 * close code rather than a helpful error.
 */
class GeminiModelCatalog {

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun listLiveModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Add your Gemini API key first."))
        }

        val request = okhttp3.Request.Builder()
            .url("$ENDPOINT?key=$apiKey&pageSize=200")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 400 || response.code == 401 || response.code == 403) {
                    return@withContext Result.failure(
                        IllegalArgumentException(
                            "Gemini rejected the API key (HTTP ${response.code}). " +
                                "Re-copy it from aistudio.google.com.",
                        ),
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Model list failed: HTTP ${response.code}"),
                    )
                }

                val root = runCatching { JSONObject(body) }.getOrNull()
                    ?: return@withContext Result.failure(
                        IllegalStateException("Model list was not JSON."),
                    )
                val models = root.optJSONArray("models")
                    ?: return@withContext Result.success(emptyList())

                val out = linkedSetOf<String>()
                for (index in 0 until models.length()) {
                    val entry = models.optJSONObject(index) ?: continue
                    val name = entry.optString("name").removePrefix("models/").trim()
                    if (name.isEmpty()) continue

                    val methods = entry.optJSONArray("supportedGenerationMethods")
                    val supportsBidi = methods != null && (0 until methods.length()).any { i ->
                        methods.optString(i).equals("bidiGenerateContent", ignoreCase = true)
                    }
                    val looksLive = name.contains("-live", ignoreCase = true) ||
                        name.contains("native-audio", ignoreCase = true)

                    if (supportsBidi || looksLive) out += name
                }

                Log.i(TAG, "Gemini listed ${out.size} Live-capable models")
                Result.success(out.sorted())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini model list failed: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "SaraWS"
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
    }
}