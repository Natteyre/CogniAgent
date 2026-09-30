package com.example.aiagent

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 100% Offline Local LLM Fallback using Google MediaPipe Tasks GenAI.
 * Loads the quantized model binary directly from internal app storage (context.filesDir).
 * Ensures cognitive operations remain active even without Internet connectivity.
 */
class LocalLlmClient(
    private val context: Context,
    var modelFileName: String = "gemma-2b-it-cpu-int4.bin"
) : LlmProvider {

    private val tag = "LocalLlmClient"
    private var llmInference: LlmInference? = null
    private var isInitialized = false

    init {
        tryInitialize()
    }

    fun isModelAvailable(): Boolean {
        val modelFile = File(context.filesDir, modelFileName)
        return modelFile.exists() && modelFile.length() > 0
    }

    private fun tryInitialize() {
        val modelFile = File(context.filesDir, modelFileName)
        if (!modelFile.exists()) {
            Log.w(tag, "Local model binary not found at: ${modelFile.absolutePath}")
            isInitialized = false
            return
        }

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            Log.i(tag, "MediaPipe LlmInference initialized from ${modelFile.absolutePath}")
        } catch (e: Throwable) {
            Log.e(tag, "Failed to load MediaPipe local model: ${e.message}", e)
            isInitialized = false
            llmInference = null
        }
    }

    override suspend fun generateResponse(
        messages: List<LlmProvider.Message>,
        toolsJsonArray: String?
    ): LlmProvider.Response = withContext(Dispatchers.IO) {
        val modelFile = File(context.filesDir, modelFileName)

        if (!isInitialized || llmInference == null) {
            tryInitialize()
        }

        if (llmInference == null) {
            return@withContext LlmProvider.Response(
                text = null,
                isSuccessful = false,
                errorMessage = "Brak lokalnego modelu LLM w pamięci (${modelFile.name}). Umieść plik .bin w katalogu aplikacji lub włącz tryb online."
            )
        }

        try {
            // Build chat prompt with standard turn markers
            val prompt = buildChatPrompt(messages)
            val generated = llmInference?.generateResponse(prompt)

            return@withContext LlmProvider.Response(
                text = generated?.trim(),
                isSuccessful = true
            )
        } catch (e: Exception) {
            Log.e(tag, "Local inference generation failed: ${e.message}", e)
            return@withContext LlmProvider.Response(
                text = null,
                isSuccessful = false,
                errorMessage = "Błąd lokalnego wnioskowania: ${e.message}"
            )
        }
    }

    private fun buildChatPrompt(messages: List<LlmProvider.Message>): String {
        val sb = StringBuilder()
        for (m in messages) {
            when (m.role) {
                "system" -> sb.append("Instrukcja systemowa: ").append(m.content).append("\n\n")
                "user" -> sb.append("<start_of_turn>user\n").append(m.content).append("<end_of_turn>\n")
                "assistant" -> sb.append("<start_of_turn>model\n").append(m.content).append("<end_of_turn>\n")
                else -> sb.append(m.content).append("\n")
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    fun close() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing LlmInference: ${e.message}")
        }
        llmInference = null
        isInitialized = false
    }
}
