package com.example.aiagent

import kotlin.math.exp

/**
 * Parser for GLiNER 2.5 probability matrices and token sequences.
 * Evaluates logits with the Sigmoid function and reconstructs subword tokens into
 * coherent Polish phrases, properly handling DeBERTa subword boundary characters (' ' and 'Ġ').
 */
class GlinerOutputParser {

    data class RecognizedEntity(
        val label: String,
        val text: String,
        val confidence: Float,
        val startTokenIndex: Int,
        val endTokenIndex: Int
    )

    /**
     * Numerically stable Sigmoid activation function.
     */
    fun sigmoid(x: Float): Float {
        return if (x >= 0) {
            1.0f / (1.0f + exp(-x))
        } else {
            val z = exp(x)
            z / (1.0f + z)
        }
    }

    /**
     * Parses a 2D or 3D logits matrix (flattened or nested) into recognized entities.
     * @param logits FloatArray of dimensions [seqLen, numLabels]
     * @param seqLen Sequence length (128)
     * @param numLabels Number of candidate labels
     * @param tokens Sequence of token strings from the tokenizer
     * @param labels Candidate label names in corresponding order
     * @param threshold Confidence threshold (default 0.5)
     */
    fun parseLogits(
        logits: FloatArray,
        seqLen: Int,
        numLabels: Int,
        tokens: List<String>,
        labels: List<String>,
        threshold: Float = 0.5f
    ): List<RecognizedEntity> {
        val entities = mutableListOf<RecognizedEntity>()
        if (numLabels <= 0 || seqLen <= 0 || tokens.isEmpty()) return entities

        var currentLabel: String? = null
        var currentTokenBuffer = mutableListOf<String>()
        var startIdx = -1
        var accumulatedProb = 0f
        var tokenCount = 0

        val maxIter = minOf(seqLen, tokens.size)

        for (i in 0 until maxIter) {
            val token = tokens[i]
            if (token == "[CLS]" || token == "[SEP]" || token == "[PAD]" || token.startsWith("<<")) {
                // If ending an active span
                if (currentLabel != null) {
                    val reconstructed = reconstructText(currentTokenBuffer)
                    if (reconstructed.isNotBlank()) {
                        entities.add(
                            RecognizedEntity(
                                label = currentLabel,
                                text = reconstructed,
                                confidence = accumulatedProb / maxOf(1, tokenCount),
                                startTokenIndex = startIdx,
                                endTokenIndex = i - 1
                            )
                        )
                    }
                    currentLabel = null
                    currentTokenBuffer.clear()
                    startIdx = -1
                    accumulatedProb = 0f
                    tokenCount = 0
                }
                continue
            }

            // Find best label for this token position
            var bestProb = 0f
            var bestLabelIdx = -1

            for (l in 0 until numLabels) {
                val flatIndex = i * numLabels + l
                if (flatIndex < logits.size) {
                    val prob = sigmoid(logits[flatIndex])
                    if (prob > bestProb) {
                        bestProb = prob
                        bestLabelIdx = l
                    }
                }
            }

            if (bestProb >= threshold && bestLabelIdx >= 0 && bestLabelIdx < labels.size) {
                val label = labels[bestLabelIdx]
                if (currentLabel == label) {
                    // Continuation of span
                    currentTokenBuffer.add(token)
                    accumulatedProb += bestProb
                    tokenCount++
                } else {
                    // Flush previous entity if any
                    if (currentLabel != null) {
                        val reconstructed = reconstructText(currentTokenBuffer)
                        if (reconstructed.isNotBlank()) {
                            entities.add(
                                RecognizedEntity(
                                    label = currentLabel,
                                    text = reconstructed,
                                    confidence = accumulatedProb / maxOf(1, tokenCount),
                                    startTokenIndex = startIdx,
                                    endTokenIndex = i - 1
                                )
                            )
                        }
                    }
                    // Start new entity
                    currentLabel = label
                    currentTokenBuffer = mutableListOf(token)
                    startIdx = i
                    accumulatedProb = bestProb
                    tokenCount = 1
                }
            } else {
                // No entity above threshold
                if (currentLabel != null) {
                    val reconstructed = reconstructText(currentTokenBuffer)
                    if (reconstructed.isNotBlank()) {
                        entities.add(
                            RecognizedEntity(
                                label = currentLabel,
                                text = reconstructed,
                                confidence = accumulatedProb / maxOf(1, tokenCount),
                                startTokenIndex = startIdx,
                                endTokenIndex = i - 1
                            )
                        )
                    }
                    currentLabel = null
                    currentTokenBuffer.clear()
                    startIdx = -1
                    accumulatedProb = 0f
                    tokenCount = 0
                }
            }
        }

        // Flush remaining at the end
        if (currentLabel != null && currentTokenBuffer.isNotEmpty()) {
            val reconstructed = reconstructText(currentTokenBuffer)
            if (reconstructed.isNotBlank()) {
                entities.add(
                    RecognizedEntity(
                        label = currentLabel,
                        text = reconstructed,
                        confidence = accumulatedProb / maxOf(1, tokenCount),
                        startTokenIndex = startIdx,
                        endTokenIndex = maxIter - 1
                    )
                )
            }
        }

        return entities
    }

    /**
     * Reconstructs Polish words from mDeBERTa/SentencePiece tokens.
     * Handles ' ' (U+2581) and 'Ġ' (U+0120) space indicators and WordPiece '##' prefixes.
     */
    fun reconstructText(tokens: List<String>): String {
        val sb = StringBuilder()
        for (token in tokens) {
            var cleanToken = token
            var isNewWord = false

            // SentencePiece space marker
            if (cleanToken.startsWith(" ")) {
                isNewWord = true
                cleanToken = cleanToken.removePrefix(" ")
            }
            // Byte-Pair / RoBERTa space marker
            if (cleanToken.startsWith("Ġ")) {
                isNewWord = true
                cleanToken = cleanToken.removePrefix("Ġ")
            }
            // WordPiece subword marker
            if (cleanToken.startsWith("##")) {
                cleanToken = cleanToken.removePrefix("##")
                isNewWord = false
            }

            if (isNewWord && sb.isNotEmpty()) {
                sb.append(" ")
            }
            sb.append(cleanToken)
        }
        return sb.toString().trim()
    }
}
