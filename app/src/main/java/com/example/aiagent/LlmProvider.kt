package com.example.aiagent

/**
 * Common polymorphic interface for Large Language Model backends (Online & Offline).
 */
interface LlmProvider {

    data class Message(
        val role: String, // "system", "user", "assistant", "tool"
        val content: String,
        val toolCallId: String? = null
    )

    data class ToolCall(
        val id: String,
        val functionName: String,
        val argumentsJson: String
    )

    data class Response(
        val text: String?,
        val toolCalls: List<ToolCall> = emptyList(),
        val isSuccessful: Boolean = true,
        val errorMessage: String? = null
    )

    /**
     * Generates a response from the model, optionally utilizing OpenAI-compatible tool definitions.
     */
    suspend fun generateResponse(
        messages: List<Message>,
        toolsJsonArray: String? = null
    ): Response
}
