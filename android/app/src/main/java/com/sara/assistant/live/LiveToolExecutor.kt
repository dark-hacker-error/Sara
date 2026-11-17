package com.sara.assistant.live

import org.json.JSONObject

/**
 * Executes the function calls Sara's Live session asks for.
 *
 * Kept as an interface so the transport (direct-to-Gemini today, proxied through the backend
 * later) can change without touching the session logic.
 */
interface LiveToolExecutor {
    suspend fun execute(call: FunctionCall): JSONObject
}
