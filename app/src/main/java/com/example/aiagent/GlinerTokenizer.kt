package com.example.aiagent

import android.content.Context
import android.util.Log
import ai.djl.huggingface.tokenizers.Encoding
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import java.io.InputStream

/**
 * Tokenizer for GLiNER 2.5 mDeBERTa-v3 model.
 * Loads "tokenizer.json" from Android assets using DJL Hugging Face Tokenizers.
 * Encodes text into INT32 arrays padded/truncated to fixed sequence length (128).
 */
class GlinerTokenizer(private val context: Context) {

    private val tag = "GlinerTokenizer"
    private var djlTokenizer: HuggingFaceTokenizer? = null
    val maxTokens: Int = 128

    data class TokenizedInput(
        val inputIds: IntArray,
        val attentionMask: IntArray,
        val tokens: List<String>
    )

    init {
        loadTokenizer()
    }

    private fun loadTokenizer() {
        try {
            val assetManager = context.assets
            val files = assetManager.list("") ?: emptyArray()
            if (files.contains("tokenizer.json")) {
                val cacheFile = java.io.File(context.cacheDir, "tokenizer.json")
                assetManager.open("tokenizer.json").use { inputStream ->
                    cacheFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                djlTokenizer = HuggingFaceTokenizer.newInstance(cacheFile.toPath())
                Log.i(tag, "Loaded DJL HuggingFaceTokenizer from assets/tokenizer.json")
            } else {
                Log.w(tag, "tokenizer.json not found in assets. Falling back to heuristic tokenizer.")
            }
        } catch (e: Throwable) {
            Log.e(tag, "Failed to initialize HuggingFaceTokenizer: ${e.message}. Using heuristic fallback.")
            djlTokenizer = null
        }
    }

    /**
     * Encodes input text and target entity labels into INT32 model inputs.
     */
    fun encode(text: String, labels: List<String> = emptyList()): TokenizedInput {
        val inputIds = IntArray(maxTokens) { 0 }
        val attentionMask = IntArray(maxTokens) { 0 }
        val tokenStrings = mutableListOf<String>()

        val tokenizer = djlTokenizer
        if (tokenizer != null) {
            try {
                // If labels are provided, format prompt: "[ENT] label1 [ENT] label2 [TEXT] text"
                val promptText = if (labels.isNotEmpty()) {
                    val labelPrefix = labels.joinToString(" ") { "<<$it>>" }
                    "$labelPrefix [SEP] $text"
                } else {
                    text
                }

                val encoding: Encoding = tokenizer.encode(promptText)
                val rawIds: LongArray = encoding.ids
                val rawTokens: Array<String> = encoding.tokens

                val length = minOf(rawIds.size, maxTokens)
                for (i in 0 until length) {
                    inputIds[i] = rawIds[i].toInt()
                    attentionMask[i] = 1
                }
                for (i in 0 until minOf(rawTokens.size, maxTokens)) {
                    tokenStrings.add(rawTokens[i])
                }

                return TokenizedInput(inputIds, attentionMask, tokenStrings)
            } catch (e: Throwable) {
                Log.e(tag, "DJL encode failed: ${e.message}, switching to heuristic fallback")
            }
        }

        // Resilient fallback tokenizer if asset tokenizer is missing
        return heuristicTokenize(text, labels)
    }

    private fun heuristicTokenize(text: String, labels: List<String>): TokenizedInput {
        val inputIds = IntArray(maxTokens) { 0 }
        val attentionMask = IntArray(maxTokens) { 0 }
        val tokenStrings = mutableListOf<String>()

        // 1: [CLS]
        tokenStrings.add("[CLS]")
        inputIds[0] = 1
        attentionMask[0] = 1

        var idx = 1
        // Add labels if any
        for (label in labels) {
            if (idx >= maxTokens - 2) break
            tokenStrings.add("<<$label>>")
            inputIds[idx] = (label.hashCode() and 0x7FFF) + 1000
            attentionMask[idx] = 1
            idx++
        }

        if (labels.isNotEmpty() && idx < maxTokens - 2) {
            tokenStrings.add("[SEP]")
            inputIds[idx] = 2
            attentionMask[idx] = 1
            idx++
        }

        // Add Polish words with ' ' (U+2581) or 'Ġ' prefix simulation
        val words = text.trim().split(Regex("\\s+"))
        for (w in words) {
            if (idx >= maxTokens - 1) break
            val prefix = if (idx == 1 || tokenStrings.last() == "[SEP]") "" else " "
            val formattedToken = "$prefix$w"
            tokenStrings.add(formattedToken)
            inputIds[idx] = (w.hashCode() and 0x7FFF) + 2000
            attentionMask[idx] = 1
            idx++
        }

        // [SEP] token at the end
        if (idx < maxTokens) {
            tokenStrings.add("[SEP]")
            inputIds[idx] = 2
            attentionMask[idx] = 1
        }

        return TokenizedInput(inputIds, attentionMask, tokenStrings)
    }
}
