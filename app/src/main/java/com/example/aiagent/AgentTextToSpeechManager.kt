package com.example.aiagent

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Text-to-Speech Manager hardcoded to Polish language.
 * Manages an asynchronous queue of utterances and triggers dynamic callbacks
 * upon completion of speech playback using UtteranceProgressListener.
 */
class AgentTextToSpeechManager(
    private val context: Context,
    private val onInitialized: ((Boolean) -> Unit)? = null
) : TextToSpeech.OnInitListener {

    private val tag = "AgentTTSManager"
    private var tts: TextToSpeech? = null
    private var isReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Dynamic thread-safe callback mapping
    private val utteranceCallbacks = ConcurrentHashMap<String, () -> Unit>()

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val polishLocale = Locale("pl", "PL")
            val result = tts?.setLanguage(polishLocale)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(tag, "Polish language is not supported or missing data in TTS engine! Falling back to default locale.")
                tts?.language = Locale.getDefault()
            } else {
                Log.i(tag, "TTS initialized with Polish language (pl-PL).")
            }

            setupProgressListener()
            isReady = true
            onInitialized?.invoke(true)
        } else {
            Log.e(tag, "TTS initialization failed with code: $status")
            isReady = false
            onInitialized?.invoke(false)
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(tag, "TTS playback started for utterance: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(tag, "TTS playback finished for utterance: $utteranceId")
                if (utteranceId != null) {
                    val callback = utteranceCallbacks.remove(utteranceId)
                    if (callback != null) {
                        mainHandler.post { callback.invoke() }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(tag, "TTS playback error for utterance: $utteranceId")
                if (utteranceId != null) {
                    utteranceCallbacks.remove(utteranceId)
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(tag, "TTS error ($errorCode) for utterance: $utteranceId")
                if (utteranceId != null) {
                    utteranceCallbacks.remove(utteranceId)
                }
            }
        })
    }

    /**
     * Speaks the given Polish text aloud.
     * @param text The text message to speak
     * @param queueMode TextToSpeech.QUEUE_FLUSH or TextToSpeech.QUEUE_ADD
     * @param onDone Callback to execute immediately when the utterance finishes playing
     */
    fun speak(
        text: String,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        onDone: (() -> Unit)? = null
    ) {
        if (!isReady || tts == null) {
            Log.w(tag, "TTS not ready yet. Dropping text: $text")
            onDone?.invoke()
            return
        }

        val utteranceId = UUID.randomUUID().toString()
        if (onDone != null) {
            utteranceCallbacks[utteranceId] = onDone
        }

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        tts?.speak(text, queueMode, params, utteranceId)
    }

    fun stop() {
        tts?.stop()
        utteranceCallbacks.clear()
    }

    fun shutdown() {
        try {
            stop()
            tts?.shutdown()
            tts = null
            isReady = false
        } catch (e: Exception) {
            Log.e(tag, "Error shutting down TTS: ${e.message}", e)
        }
    }
}
