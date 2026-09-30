package com.example.aiagent

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * GLiNER 2.5 Inference Engine running on LiteRT (TensorFlow Lite).
 * Specially optimized for HiSilicon Kirin 980 (4 CPU threads + XNNPACK delegate).
 * Enforces INT32 inputs to guarantee runtime stability and prevent type mismatches.
 */
class GlinerAgent(private val context: Context) {

    private val tag = "GlinerAgent"
    private var interpreter: Interpreter? = null
    private val tokenizer = GlinerTokenizer(context)
    private val outputParser = GlinerOutputParser()

    val maxTokens = 128
    val defaultLabels = listOf(
        "action",
        "target_app",
        "contact",
        "message",
        "setting_name",
        "setting_value",
        "search_query",
        "fact_content"
    )

    data class AgentIntent(
        val intentType: String,
        val targetApp: String? = null,
        val contact: String? = null,
        val messageText: String? = null,
        val settingName: String? = null,
        val settingValue: Int? = null,
        val searchQuery: String? = null,
        val memoryFact: String? = null,
        val rawEntities: List<GlinerOutputParser.RecognizedEntity> = emptyList()
    )

    init {
        initializeLiteRT()
    }

    private fun initializeLiteRT() {
        try {
            val assetManager = context.assets
            val modelName = "gliner_2.5_fp16.tflite"
            val assetList = assetManager.list("") ?: emptyArray()

            if (assetList.contains(modelName)) {
                val fileDescriptor: AssetFileDescriptor = assetManager.openFd(modelName)
                val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
                val fileChannel: FileChannel = inputStream.channel
                val startOffset = fileDescriptor.startOffset
                val declaredLength = fileDescriptor.declaredLength
                val modelBuffer: ByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

                val options = Interpreter.Options().apply {
                    setNumThreads(4) // Kirin 980 big.LITTLE 4-thread execution
                    setUseXNNPACK(true) // Accelerated CPU inference
                }

                interpreter = Interpreter(modelBuffer, options)
                Log.i(tag, "Successfully initialized LiteRT Interpreter with 4 threads and XNNPACK.")
            } else {
                Log.w(tag, "Model '$modelName' not found in assets. Agent will operate with Polish linguistic fallback.")
            }
        } catch (e: Throwable) {
            Log.e(tag, "LiteRT initialization failed: ${e.message}. Using linguistic fallback.", e)
            interpreter = null
        }
    }

    /**
     * Asynchronous intent extraction to ensure the microphone/main thread is never blocked.
     */
    suspend fun predictIntentAsync(text: String): AgentIntent = withContext(Dispatchers.Default) {
        extractIntent(text)
    }

    /**
     * Extracts structured intent and parameters from user speech input in Polish.
     */
    fun extractIntent(text: String): AgentIntent {
        val tflite = interpreter
        if (tflite != null) {
            try {
                val tokenized = tokenizer.encode(text, defaultLabels)
                val numLabels = defaultLabels.size

                // Allocate direct native ByteBuffers with INT32 (4 bytes per element)
                val inputIdsBuffer = ByteBuffer.allocateDirect(maxTokens * 4).order(ByteOrder.nativeOrder())
                val attentionMaskBuffer = ByteBuffer.allocateDirect(maxTokens * 4).order(ByteOrder.nativeOrder())
                val wordsMaskBuffer = ByteBuffer.allocateDirect(maxTokens * 4).order(ByteOrder.nativeOrder())

                for (id in tokenized.inputIds) {
                    inputIdsBuffer.putInt(id)
                }
                for (mask in tokenized.attentionMask) {
                    attentionMaskBuffer.putInt(mask)
                }
                for (i in 0 until maxTokens) {
                    wordsMaskBuffer.putInt(0)
                }

                inputIdsBuffer.rewind()
                attentionMaskBuffer.rewind()
                wordsMaskBuffer.rewind()

                // Output logits buffer: [1, maxTokens, numLabels]
                val outputBuffer = ByteBuffer.allocateDirect(maxTokens * numLabels * 4).order(ByteOrder.nativeOrder())

                val inputs = arrayOf<Any>(inputIdsBuffer, attentionMaskBuffer, wordsMaskBuffer)
                val outputs = mapOf(0 to outputBuffer)

                tflite.runForMultipleInputsOutputs(inputs, outputs)

                outputBuffer.rewind()
                val floatLogits = FloatArray(maxTokens * numLabels)
                outputBuffer.asFloatBuffer().get(floatLogits)

                val recognizedEntities = outputParser.parseLogits(
                    logits = floatLogits,
                    seqLen = maxTokens,
                    numLabels = numLabels,
                    tokens = tokenized.tokens,
                    labels = defaultLabels,
                    threshold = 0.45f
                )

                if (recognizedEntities.isNotEmpty()) {
                    return mapEntitiesToIntent(recognizedEntities, text)
                }
            } catch (e: Throwable) {
                Log.e(tag, "LiteRT execution error: ${e.message}, falling back to linguistic parser", e)
            }
        }

        // Robust Polish rule-based / linguistic parser fallback
        return ruleBasedPolishParser(text)
    }

    private fun mapEntitiesToIntent(
        entities: List<GlinerOutputParser.RecognizedEntity>,
        originalText: String
    ): AgentIntent {
        var action = entities.firstOrNull { it.label == "action" }?.text?.lowercase()
        val targetApp = entities.firstOrNull { it.label == "target_app" }?.text
        val contact = entities.firstOrNull { it.label == "contact" }?.text
        val message = entities.firstOrNull { it.label == "message" }?.text
        val settingName = entities.firstOrNull { it.label == "setting_name" }?.text
        val settingValueStr = entities.firstOrNull { it.label == "setting_value" }?.text
        val searchQuery = entities.firstOrNull { it.label == "search_query" }?.text
        val factContent = entities.firstOrNull { it.label == "fact_content" }?.text

        // Extract integer value from string digits or Polish words
        val settingVal = settingValueStr?.filter { it.isDigit() }?.toIntOrNull()
            ?: (if (!settingValueStr.isNullOrBlank()) PolishWordToNumberParser.extractNumberFromText(settingValueStr) else null)
            ?: PolishWordToNumberParser.extractNumberFromText(originalText)

        if (targetApp != null || action?.contains("otwórz") == true || action?.contains("uruchom") == true) {
            return AgentIntent(
                intentType = "OPEN_APP",
                targetApp = targetApp ?: extractAppNameFallback(originalText),
                rawEntities = entities
            )
        }

        if (contact != null || action?.contains("napisz") == true || action?.contains("wyślij") == true) {
            return AgentIntent(
                intentType = "SEND_SMS",
                contact = contact,
                messageText = message,
                rawEntities = entities
            )
        }

        if (settingName != null || action?.contains("głośność") == true || action?.contains("jasność") == true) {
            return AgentIntent(
                intentType = "ADJUST_SETTING",
                settingName = settingName ?: if (originalText.contains("jasnoś", ignoreCase = true)) "BRIGHTNESS" else "VOLUME",
                settingValue = settingVal,
                rawEntities = entities
            )
        }

        if (searchQuery != null || action?.contains("szukaj") == true || action?.contains("wyszukaj") == true) {
            return AgentIntent(
                intentType = "WEB_SEARCH",
                searchQuery = searchQuery ?: originalText,
                rawEntities = entities
            )
        }

        return ruleBasedPolishParser(originalText)
    }

    /**
     * Polish linguistic parser recognizing system actions, apps, settings, and memory queries.
     */
    fun ruleBasedPolishParser(text: String): AgentIntent {
        val lower = text.lowercase().trim()

        // 1. OPEN_APP
        if (lower.startsWith("otwórz") || lower.startsWith("uruchom") || lower.startsWith("włącz aplikację")) {
            val appName = extractAppNameFallback(text)
            return AgentIntent(intentType = "OPEN_APP", targetApp = appName)
        }

        // 2. ADJUST_SETTING
        if (lower.contains("głośność") || lower.contains("podgłośnij") || lower.contains("przycisz")) {
            val num = PolishWordToNumberParser.extractNumberFromText(lower)
            return AgentIntent(intentType = "ADJUST_SETTING", settingName = "VOLUME", settingValue = num)
        }
        if (lower.contains("jasność") || lower.contains("ekran")) {
            val num = PolishWordToNumberParser.extractNumberFromText(lower)
            return AgentIntent(intentType = "ADJUST_SETTING", settingName = "BRIGHTNESS", settingValue = num)
        }

        // 3. SEND_SMS
        if (lower.contains("wyślij sms") || lower.contains("napisz sms") || lower.contains("wyślij wiadomość")) {
            val toIndex = lower.indexOf(" do ")
            val oIndex = lower.indexOf(" o treści ")
            var contact: String? = null
            var msg: String? = null

            if (toIndex != -1) {
                if (oIndex != -1 && oIndex > toIndex) {
                    contact = text.substring(toIndex + 4, oIndex).trim()
                    msg = text.substring(oIndex + 10).trim()
                } else {
                    contact = text.substring(toIndex + 4).trim()
                }
            }
            return AgentIntent(intentType = "SEND_SMS", contact = contact, messageText = msg)
        }

        // 4. MEMORY: Zapamiętaj / Zapisz fakt
        if (lower.startsWith("zapamiętaj że") || lower.startsWith("zapamiętaj, że") || lower.startsWith("zapisz informację")) {
            val fact = text.replace(Regex("^(zapamiętaj[\\s,]+że|zapisz informację[\\s:]*)", RegexOption.IGNORE_CASE), "").trim()
            return AgentIntent(intentType = "SAVE_FACT", memoryFact = fact)
        }

        // 5. MEMORY: Co wiesz o / Przypomnij
        if (lower.startsWith("co wiesz o") || lower.startsWith("przypomnij mi o") || lower.startsWith("czy pamiętasz")) {
            val query = text.replace(Regex("^(co wiesz o|przypomnij mi o|czy pamiętasz)\\s*", RegexOption.IGNORE_CASE), "").trim()
            return AgentIntent(intentType = "QUERY_MEMORY", searchQuery = query)
        }

        // 6. WEB_SEARCH
        if (lower.startsWith("wyszukaj") || lower.startsWith("szukaj w internecie") || lower.startsWith("sprawdź w google")) {
            val query = text.replace(Regex("^(wyszukaj|szukaj w internecie|sprawdź w google)\\s*", RegexOption.IGNORE_CASE), "").trim()
            return AgentIntent(intentType = "WEB_SEARCH", searchQuery = query)
        }

        // 7. General Assistant / LLM Conversation
        return AgentIntent(intentType = "GENERAL_CONVERSATION", messageText = text)
    }

    private fun extractAppNameFallback(text: String): String {
        return text.replace(Regex("^(otwórz|uruchom|włącz aplikację|włącz)\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[.,!?;]"), "")
            .trim()
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
