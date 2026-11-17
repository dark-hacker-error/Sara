package com.sara.assistant.data

import java.net.URI

/**
 * One place that understands the user-typed backend address.
 *
 * Accepts whatever people actually paste:
 *   `http://192.168.1.5:8000`, `https://sara.example.com`, `ws://127.0.0.1:8000`,
 *   `wss://sara.example.com/ws/session`, `192.168.1.5:8000`
 *
 * and derives the HTTP base, the health URL and the WebSocket URL from it. The app stores a
 * single base URL, so switching between USB (`127.0.0.1`), LAN (`192.168.x.x`), a tunnel
 * (`https://...`) and the emulator (`10.0.2.2`) is just an edit of that one field.
 */
data class BackendEndpoint(val httpBase: String) {

    /** e.g. ws://127.0.0.1:8000/ws/session */
    val wsUrl: String
        get() {
            val scheme = if (httpBase.startsWith("https://")) "wss://" else "ws://"
            return scheme + httpBase.substringAfter("://").trimEnd('/') + WS_PATH
        }

    /** e.g. http://127.0.0.1:8000/health */
    val healthUrl: String get() = httpBase.trimEnd('/') + "/health"

    override fun toString(): String = httpBase

    companion object {
        const val DEFAULT: String = "http://127.0.0.1:8000"

        private const val WS_PATH = "/ws/session"
        private val ALLOWED_SCHEMES = setOf("http", "https", "ws", "wss")

        fun parse(raw: String): Result<BackendEndpoint> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("Backend URL is empty."))
            }

            // A bare host or host:port is the most common paste; assume http.
            val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"

            val uri = try {
                URI(withScheme)
            } catch (e: Exception) {
                return Result.failure(IllegalArgumentException("Not a valid URL: $trimmed"))
            }

            val scheme = uri.scheme?.lowercase()
                ?: return Result.failure(IllegalArgumentException("URL has no scheme."))
            if (scheme !in ALLOWED_SCHEMES) {
                return Result.failure(
                    IllegalArgumentException("Only http, https, ws or wss are supported (got '$scheme')."),
                )
            }

            val rawHost = uri.host
                ?: return Result.failure(IllegalArgumentException("URL has no host."))
            if (rawHost.isBlank()) {
                return Result.failure(IllegalArgumentException("URL has no host."))
            }
            // IPv6 literals need brackets when rebuilt.
            val host = if (rawHost.contains(':') && !rawHost.startsWith("[")) "[$rawHost]" else rawHost

            val port = if (uri.port > 0) ":${uri.port}" else ""

            // Drop the paths users copy from a WebSocket example or a health URL.
            var path = uri.path.orEmpty().trimEnd('/')
            if (path.endsWith(WS_PATH)) path = path.removeSuffix(WS_PATH)
            if (path.endsWith("/health")) path = path.removeSuffix("/health")
            path = path.trimEnd('/')

            val httpScheme = when (scheme) {
                "https", "wss" -> "https"
                else -> "http"
            }

            return Result.success(BackendEndpoint("$httpScheme://$host$port$path"))
        }

        /** Best-effort parse that falls back to the default instead of failing. */
        fun parseOrDefault(raw: String): BackendEndpoint =
            parse(raw).getOrElse { BackendEndpoint(DEFAULT) }
    }
}