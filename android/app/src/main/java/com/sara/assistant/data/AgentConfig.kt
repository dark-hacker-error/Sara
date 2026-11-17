package com.sara.assistant.data

import java.util.UUID

/** Which job a subagent is wired up for. New roles get added here in later prompts. */
enum class AgentRole {
    PHONE_CONTROL,
    RESEARCH,
    CUSTOM;

    companion object {
        fun fromKey(key: String): AgentRole =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: CUSTOM
    }
}

/**
 * How the backend should call this subagent's model.
 * - OPENAI_COMPATIBLE: any `/chat/completions` endpoint (routes through ADK's LiteLlm wrapper).
 * - GEMINI_NATIVE: Google's GenAI API through ADK's native Gemini model.
 */
enum class ProviderKind {
    OPENAI_COMPATIBLE,
    GEMINI_NATIVE;

    companion object {
        fun fromKey(key: String): ProviderKind =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: OPENAI_COMPATIBLE
    }
}

/**
 * A user-configurable subagent: its own base URL, key and model, exactly as requested.
 * This object is the single source of truth that the Android app ships to the backend.
 */
data class AgentConfig(
    val id: String = UUID.randomUUID().toString(),
    /** Name the ADK agent is registered under. Must stay stable across edits. */
    val name: String,
    val role: AgentRole,
    val enabled: Boolean = true,
    val provider: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String = "",
) {
    /** True when the backend has everything it needs to construct this agent's model. */
    val isRunnable: Boolean
        get() = enabled && model.isNotBlank() &&
            (provider == ProviderKind.GEMINI_NATIVE || (baseUrl.isNotBlank() && apiKey.isNotBlank()))

    companion object {
        /** Defaults matching the two subagents the backend ships with. */
        fun defaults(): List<AgentConfig> = listOf(
            AgentConfig(
                name = "phone_control_agent",
                role = AgentRole.PHONE_CONTROL,
                model = "",
                systemPrompt = "",
            ),
            AgentConfig(
                name = "research_agent",
                role = AgentRole.RESEARCH,
                model = "",
                systemPrompt = "",
            ),
        )
    }
}
