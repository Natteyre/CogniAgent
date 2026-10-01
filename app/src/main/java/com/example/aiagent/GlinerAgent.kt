package com.example.aiagent

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.IntBuffer

class GlinerAgent(private val context: Context) {
    private val tag = "GlinerAgent"
    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    private val tokenizer = GlinerTokenizer(context)
    private val outputParser = GlinerOutputParser()
    val maxTokens = 128

    val defaultLabels = listOf("action", "target_app", "contact", "message", "setting_name", "setting_value", "search_query", "fact_content")

    data class AgentIntent(
        val intentType: String,
        val targetApp: String? = null,
        val contact: String? = null,
        val messageText: String? = null,
        val settingName: String? = null,
        val settingValue: Int? = null,
        val searchQuery: String? = null,
        val memoryFact: String? = null
    )

    init {
        initializeOnnx()
    }

    private fun initializeOnnx() {
        try {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val modelFile = File(baseDir, "gliner_static.onnx")

            if (modelFile.exists() && modelFile.length() > 0) {
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                }
                ortSession = ortEnv.createSession(modelFile.absolutePath, options)
                Log.i(tag, "Sukces! Silnik ONNX Runtime poprawnie załadował model GLiNER.")
            } else {
                Log.w(tag, "Plik gliner_static.onnx nie został znaleziony. Aktywny fallback lingwistyczny.")
            }
        } catch (e: Exception) {
            Log.e(tag, "Błąd inicjalizacji ONNX Runtime: ${e.message}", e)
        }
    }

    suspend fun predictIntentAsync(text: String): AgentIntent = withContext(Dispatchers.Default) {
        val session = ortSession ?: return@withContext ruleBasedPolishParser(text)

        try {
            val tokenized = tokenizer.encode(text, defaultLabels)
            val numLabels = defaultLabels.size

            val inputIdsBuffer = IntBuffer.wrap(tokenized.inputIds)
            val attentionMaskBuffer = IntBuffer.wrap(tokenized.attentionMask)
            val inputShape = longArrayOf(1, maxTokens.toLong())

            val inputIdsTensor = OnnxTensor.createTensor(ortEnv, inputIdsBuffer, inputShape)
            val attentionMaskTensor = OnnxTensor.createTensor(ortEnv, attentionMaskBuffer, inputShape)

            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor
            )

            session.execute(inputs).use { results ->
                if (results.count() > 0) {
                    val outputTensor = results.get(0) as OnnxTensor
                    val rawValue = outputTensor.value
                    if (rawValue is Array<*>) {
                        val tokenOutputs = rawValue as Array<Array<FloatArray>>
                        val flatLogits = FloatArray(maxTokens * numLabels)
                        
                        var idx = 0
                        for (i in 0 until maxTokens) {
                            for (l in 0 until numLabels) {
                                if (idx < flatLogits.size && l < tokenOutputs[i].size) {
                                    flatLogits[idx] = tokenOutputs[i][l]
                                    idx++
                                }
                            }
                        }

                        val recognizedEntities = outputParser.parseLogits(
                            logits = flatLogits, seqLen = maxTokens, numLabels = numLabels,
                            tokens = tokenized.tokens, labels = defaultLabels, threshold = 0.45f
                        )

                        if (recognizedEntities.isNotEmpty()) {
                            return@withContext mapEntitiesToIntent(recognizedEntities, text)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Błąd ONNX Runtime podczas wnioskowania: ${e.message}", e)
        }

        return@withContext ruleBasedPolishParser(text)
    }

    private fun mapEntitiesToIntent(
        entities: List<GlinerOutputParser.RecognizedEntity>,
        originalText: String
    ): AgentIntent {
        val action = entities.firstOrNull { it.label == "action" }?.text?.lowercase()
        val targetApp = entities.firstOrNull { it.label == "target_app" }?.text
        val contact = entities.firstOrNull { it.label == "contact" }?.text
        val message = entities.firstOrNull { it.label == "message" }?.text
        val settingName = entities.firstOrNull { it.label == "setting_name" }?.text
        val settingValueStr = entities.firstOrNull { it.label == "setting_value" }?.text
        val searchQuery = entities.firstOrNull { it.label == "search_query" }?.text
        
        // POPRAWKA: Rygorystyczne rzutowanie typu wyjściowego na czystą zmienną Int? dla kompilatora Kotlina
        val parsedInt: Int? = settingValueStr?.filter { it.isDigit() }?.toIntOrNull()
        val settingVal: Int? = parsedInt ?: if (!settingValueStr.isNullOrBlank()) PolishWordToNumberParser.extractNumberFromText(settingValueStr) else null

        if (targetApp != null || action?.contains("otwórz") == true || action?.contains("uruchom") == true) {
            return AgentIntent(intentType = "OPEN_APP", targetApp = targetApp ?: extractAppNameFallback(originalText))
        }
        if (contact != null || action?.contains("napisz") == true || action?.contains("wyślij") == true) {
            return AgentIntent(intentType = "SEND_SMS", contact = contact, messageText = message)
        }
        if (settingName != null || action?.contains("głośność") == true || action?.contains("jasność") == true) {
            return AgentIntent(intentType = "ADJUST_SETTING", settingName = settingName ?: if (originalText.contains("jasność", ignoreCase = true)) "BRIGHTNESS" else "VOLUME", settingValue = settingVal)
        }
        if (searchQuery != null || action?.contains("szukaj") == true || action?.contains("wyszukaj") == true) {
            return AgentIntent(intentType = "WEB_SEARCH", searchQuery = searchQuery ?: originalText)
        }
        return ruleBasedPolishParser(originalText)
    }

    fun ruleBasedPolishParser(text: String): AgentIntent {
        val lower = text.lowercase().trim()
        if (lower.startsWith("otwórz") || lower.startsWith("uruchom") || lower.startsWith("włącz aplikację")) {
            return AgentIntent(intentType = "OPEN_APP", targetApp = extractAppNameFallback(text))
        }
        if (lower.contains("głośność") || lower.contains("podgłośnij") || lower.contains("przycisz")) {
            return AgentIntent(intentType = "ADJUST_SETTING", settingName = "VOLUME", settingValue = PolishWordToNumberParser.extractNumberFromText(lower))
        }
        if (lower.contains("jasność") || lower.contains("ekran")) {
            return AgentIntent(intentType = "ADJUST_SETTING", settingName = "BRIGHTNESS", settingValue = PolishWordToNumberParser.extractNumberFromText(lower))
        }
        if (lower.contains("wyślij sms") || lower.contains("napisz sms")) {
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
        if (lower.startsWith("zapamiętaj że") || lower.startsWith("zapisz informację")) {
            return AgentIntent(intentType = "SAVE_FACT", memoryFact = text.replace(Regex("^(zapamiętaj[\\s,]+że|zapisz informację[\\s:]*)", RegexOption.IGNORE_CASE), "").trim())
        }
        if (lower.startsWith("co wiesz o") || lower.startsWith("przypomnij mi o")) {
            return AgentIntent(intentType = "QUERY_MEMORY", searchQuery = text.replace(Regex("^(co wiesz o|przypomnij mi o)\\s*", RegexOption.IGNORE_CASE), "").trim())
        }
        return AgentIntent(intentType = "GENERAL_CONVERSATION", messageText = text)
    }

    private fun extractAppNameFallback(text: String): String {
        return text.replace(Regex("^(otwórz|uruchom|włącz aplikację)\\s+", RegexOption.IGNORE_CASE), "").replace(Regex("[.,!?;]"), "").trim()
    }

    fun close() {
        ortSession?.close()
        ortSession = null
    }
}
