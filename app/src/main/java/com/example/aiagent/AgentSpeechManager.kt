package com.example.aiagent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Speech Recognition Manager hardcoded to Polish ("pl-PL").
 * Wraps Android's native SpeechRecognizer with callback mechanisms and automatic restart logic.
 */
class AgentSpeechManager(
    private val context: Context,
    private val onResultCallback: (String) -> Unit,
    private val onPartialCallback: ((String) -> Unit)? = null,
    private val onErrorCallback: ((Int, String) -> Unit)? = null
) {

    private val tag = "AgentSpeechManager"
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var continuousMode = false

    private val recognitionIntent: Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pl-PL")
        putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("pl-PL"))
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(tag, "Ready for Polish speech input...")
        }

        override fun onBeginningOfSpeech() {
            Log.d(tag, "Speech detected.")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(tag, "End of speech utterance.")
        }

        override fun onError(error: Int) {
            val errorMsg = getErrorMessage(error)
            Log.w(tag, "Speech recognition error ($error): $errorMsg")
            isListening = false
            onErrorCallback?.invoke(error, errorMsg)

            if (continuousMode && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                restartListening()
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val recognizedText = matches[0].trim()
                Log.i(tag, "Recognized Polish Speech: $recognizedText")
                onResultCallback(recognizedText)
            }

            if (continuousMode) {
                restartListening()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partialMatches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!partialMatches.isNullOrEmpty()) {
                val text = partialMatches[0].trim()
                onPartialCallback?.invoke(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    init {
        initRecognizer()
    }

    private fun initRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(recognitionListener)
            }
            Log.i(tag, "SpeechRecognizer initialized.")
        } else {
            Log.e(tag, "Speech recognition is NOT available on this device!")
        }
    }

    fun startListening(continuous: Boolean = false) {
        if (isListening) return
        continuousMode = continuous

        if (speechRecognizer == null) {
            initRecognizer()
        }

        try {
            isListening = true
            speechRecognizer?.startListening(recognitionIntent)
            Log.i(tag, "Started listening in Polish (continuous=$continuous)...")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start listening: ${e.message}", e)
            isListening = false
        }
    }

    fun stopListening() {
        continuousMode = false
        if (!isListening) return
        try {
            speechRecognizer?.stopListening()
            isListening = false
            Log.i(tag, "Stopped listening.")
        } catch (e: Exception) {
            Log.e(tag, "Failed to stop listening: ${e.message}", e)
        }
    }

    private fun restartListening() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.startListening(recognitionIntent)
            isListening = true
        } catch (e: Exception) {
            Log.e(tag, "Failed to restart speech recognizer: ${e.message}", e)
            isListening = false
        }
    }

    fun destroy() {
        continuousMode = false
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            isListening = false
        } catch (e: Exception) {
            Log.e(tag, "Error destroying SpeechRecognizer: ${e.message}", e)
        }
    }

    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Błąd nagrywania dźwięku"
            SpeechRecognizer.ERROR_CLIENT -> "Błąd klienta rozpoznawania"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Brak uprawnień do mikrofonu"
            SpeechRecognizer.ERROR_NETWORK -> "Błąd sieciowy STT"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Przekroczono limit czasu sieci"
            SpeechRecognizer.ERROR_NO_MATCH -> "Nie rozpoznano mowy"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Moduł rozpoznawania zajęty"
            SpeechRecognizer.ERROR_SERVER -> "Błąd serwera STT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Brak mowy (timeout)"
            else -> "Nieznany błąd rozpoznawania ($errorCode)"
        }
    }
}
