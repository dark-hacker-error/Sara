package com.sara.assistant.data

import android.content.Context
import com.sara.assistant.core.SaraLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for settings. Persists a JSON blob into [SecureStore] and
 * exposes it as a [StateFlow] so Compose and the Live session react to edits immediately.
 *
 * JSON is hand-rolled with org.json on purpose — no serializer plugin, no codegen, and
 * the wire format is small and stable.
 */
class SettingsRepository(context: Context) {

    private val store = SecureStore(context)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<SaraSettings> = _settings.asStateFlow()

    val current: SaraSettings get() = _settings.value

    fun update(transform: (SaraSettings) -> SaraSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        save(next)
    }

    fun updateLive(transform: (LiveSettings) -> LiveSettings) =
        update { it.copy(live = transform(it.live)) }

    fun upsertAgent(agent: AgentConfig) = update { current ->
        val existing = current.agents.indexOfFirst { it.id == agent.id }
        val agents = if (existing >= 0) {
            current.agents.toMutableList().also { it[existing] = agent }
        } else {
            current.agents + agent
        }
        current.copy(agents = agents)
    }

    fun removeAgent(id: String) = update { current ->
        current.copy(agents = current.agents.filterNot { it.id == id })
    }

    fun resetAgents() = update { it.copy(agents = AgentConfig.defaults()) }

    // ---------------------------------------------------------------- persistence

    private fun load(): SaraSettings {
        val raw = store.read(KEY_SETTINGS) ?: return SaraSettings()
        return runCatching { parse(raw) }.getOrElse { error ->
            SaraLog.e("Settings blob unreadable, falling back to defaults", error)
            SaraSettings()
        }
    }

    private fun save(settings: SaraSettings) {
        runCatching { store.write(KEY_SETTINGS, serialize(settings)) }
            .onFailure { SaraLog.e("Failed to persist settings", it) }
    }

    private fun serialize(settings: SaraSettings): String {
        val live = settings.live
        return JSONObject().apply {
            put("backend_url", settings.backendUrl)
            put("onboarding_complete", settings.onboardingComplete)
            put("live", JSONObject().apply {
                put("gemini_api_key", live.geminiApiKey)
                put("model", live.model)
                put("fallback_model", live.fallbackModel)
                put("voice", live.voice)
                put("system_prompt", live.systemPrompt)
                put("input_transcription", live.inputTranscription)
                put("output_transcription", live.outputTranscription)
                put("silence_duration_ms", live.silenceDurationMs)
                put("prefix_padding_ms", live.prefixPaddingMs)
                put("barge_in", live.bargeIn)
            })
            put("agents", JSONArray().apply {
                settings.agents.forEach { agent ->
                    put(JSONObject().apply {
                        put("id", agent.id)
                        put("name", agent.name)
                        put("role", agent.role.name)
                        put("enabled", agent.enabled)
                        put("provider", agent.provider.name)
                        put("base_url", agent.baseUrl)
                        put("api_key", agent.apiKey)
                        put("model", agent.model)
                        put("system_prompt", agent.systemPrompt)
                    })
                }
            })
        }.toString()
    }

    private fun parse(raw: String): SaraSettings {
        val root = JSONObject(raw)
        val liveObj = root.optJSONObject("live") ?: JSONObject()
        val defaults = LiveSettings()

        val live = LiveSettings(
            geminiApiKey = liveObj.optString("gemini_api_key", defaults.geminiApiKey),
            model = liveObj.optString("model", defaults.model).ifBlank { defaults.model },
            fallbackModel = liveObj.optString("fallback_model", defaults.fallbackModel),
            voice = liveObj.optString("voice", defaults.voice).ifBlank { defaults.voice },
            systemPrompt = liveObj.optString("system_prompt", defaults.systemPrompt)
                .ifBlank { defaults.systemPrompt },
            inputTranscription = liveObj.optBoolean("input_transcription", true),
            outputTranscription = liveObj.optBoolean("output_transcription", true),
            silenceDurationMs = liveObj.optInt("silence_duration_ms", defaults.silenceDurationMs),
            prefixPaddingMs = liveObj.optInt("prefix_padding_ms", defaults.prefixPaddingMs),
            bargeIn = liveObj.optBoolean("barge_in", true),
        )

        val agentsArray = root.optJSONArray("agents")
        val agents = if (agentsArray == null || agentsArray.length() == 0) {
            AgentConfig.defaults()
        } else {
            (0 until agentsArray.length()).map { index ->
                val obj = agentsArray.getJSONObject(index)
                AgentConfig(
                    id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    name = obj.optString("name"),
                    role = AgentRole.fromKey(obj.optString("role")),
                    enabled = obj.optBoolean("enabled", true),
                    provider = ProviderKind.fromKey(obj.optString("provider")),
                    baseUrl = obj.optString("base_url"),
                    apiKey = obj.optString("api_key"),
                    model = obj.optString("model"),
                    systemPrompt = obj.optString("system_prompt"),
                )
            }
        }

        val storedBackend = root.optString("backend_url", BackendEndpoint.DEFAULT)

        // Migration: earlier builds stored a WebSocket URL and defaulted to the emulator alias,
        // which does not resolve on a real phone. Only the exact old default is rewritten;
        // anything the user actually typed is normalised and kept.
        val migratedBackend = when (storedBackend.trim()) {
            LEGACY_EMULATOR_WS_DEFAULT, "", "ws://10.0.2.2:8000/ws/session" -> BackendEndpoint.DEFAULT
            else -> BackendEndpoint.parseOrDefault(storedBackend).httpBase
        }

        return SaraSettings(
            live = live,
            agents = agents,
            backendUrl = migratedBackend,
            onboardingComplete = root.optBoolean("onboarding_complete", false),
        )
    }

    private companion object {
        const val KEY_SETTINGS = "sara_settings_v1"
        const val LEGACY_EMULATOR_WS_DEFAULT = "ws://10.0.2.2:8000/ws/session"
    }
}
