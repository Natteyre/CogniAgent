package com.example.aiagent

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.util.concurrent.TimeUnit

/**
 * Online LLM Provider communicating via Ktor HTTP Client.
 * Fully supports OpenAI, OpenRouter, Google AI Studio OpenAI-compatible endpoints,
 * and custom self-hosted endpoints with JSON Function Calling (Tools).
 */
class OnlineLlmClient(
    var endpointUrl: String = "https://api.openai.com/v1/chat/completions",
    var apiKey: String = "",
    var modelName: String = "gpt-4o-mini"
) : LlmProvider {

    private val tag = "OnlineLlmClient"
    private val gson = Gson()

    private val httpClient = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(45, TimeUnit.SECONDS)
                writeTimeout(30, TimeUnit.SECONDS)
            }
        }
    }

    override suspend fun generateResponse(
        messages: List<LlmProvider.Message>,
        toolsJsonArray: String?
    ): LlmProvider.Response {
        if (apiKey.isBlank()) {
            return LlmProvider.Response(
                text = null,
                isSuccessful = false,
                errorMessage = "Brak klucza API do serwisu LLM. Skonfiguruj API Key w ustawieniach."
            )
        }

        try {
            val root = JsonObject().apply {
                addProperty("model", modelName)
                addProperty("temperature", 0.3)

                val msgsArray = JsonArray()
                for (m in messages) {
                    val obj = JsonObject().apply {
                        addProperty("role", m.role)
                        addProperty("content", m.content)
                        if (m.toolCallId != null) {
                            addProperty("tool_call_id", m.toolCallId)
                        }
                    }
                    msgsArray.add(obj)
                }
                add("messages", msgsArray)

                // Attach OpenAI Tools specification if provided
                if (!toolsJsonArray.isNullOrBlank()) {
                    try {
                        val parsedTools = JsonParser.parseString(toolsJsonArray)
                        if (parsedTools.isJsonArray) {
                            add("tools", parsedTools.asJsonArray)
                            addProperty("tool_choice", "auto")
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to parse tools JSON: ${e.message}")
                    }
                }
            }

            val requestBodyJson = gson.toJson(root)

            val httpResponse: HttpResponse = httpClient.post(endpointUrl) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                // OpenRouter specific headers (safe for all endpoints)
                header("HTTP-Referer", "https://aiagent.android.local")
                header("X-Title", "CogniAgent Android")
                setBody(requestBodyJson)
            }

            val rawResponse = httpResponse.bodyAsText()
            val statusCode = httpResponse.status.value

            if (statusCode !in 200..299) {
                Log.e(tag, "LLM API Error ($statusCode): $rawResponse")
                return LlmProvider.Response(
                    text = null,
                    isSuccessful = false,
                    errorMessage = "Błąd API ($statusCode): $rawResponse"
                )
            }

            // Parse response body
            val parsedResponse = JsonParser.parseString(rawResponse).asJsonObject
            val choices = parsedResponse.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                return LlmProvider.Response(
                    text = null,
                    isSuccessful = false,
                    errorMessage = "Pusta odpowiedź z modelu LLM."
                )
            }

            val firstChoice = choices[0].asJsonObject
            val messageObj = firstChoice.getAsJsonObject("message")
            val content = if (messageObj.has("content") && !messageObj.get("content").isJsonNull) {
                messageObj.get("content").asString
            } else {
                null
            }

            val toolCallsList = mutableListOf<LlmProvider.ToolCall>()
            if (messageObj.has("tool_calls") && !messageObj.get("tool_calls").isJsonNull) {
                val toolCallsArray = messageObj.getAsJsonArray("tool_calls")
                for (elem in toolCallsArray) {
                    val tcObj = elem.asJsonObject
                    val id = tcObj.get("id").asString
                    val fnObj = tcObj.getAsJsonObject("function")
                    val fnName = fnObj.get("name").asString
                    val fnArgs = fnObj.get("arguments").asString
                    toolCallsList.add(LlmProvider.ToolCall(id, fnName, fnArgs))
                }
            }

            return LlmProvider.Response(
                text = content,
                toolCalls = toolCallsList,
                isSuccessful = true
            )
        } catch (e: Exception) {
            Log.e(tag, "Network call failed: ${e.message}", e)
            return LlmProvider.Response(
                text = null,
                isSuccessful = false,
                errorMessage = "Błąd połączenia: ${e.localizedMessage ?: e.message}"
            )
        }
    }
}
