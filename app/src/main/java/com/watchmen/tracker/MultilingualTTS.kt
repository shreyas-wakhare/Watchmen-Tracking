package com.watchmen.tracker

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

class MultilingualTTS(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    companion object {
        const val TAG = "MultilingualTTS"
    }

    fun initialize(onSuccess: () -> Unit = {}, onError: () -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                Log.i(TAG, "✅ TTS initialized successfully")
                onSuccess()
            } else {
                Log.e(TAG, "❌ TTS initialization failed")
                onError()
            }
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "🔊 TTS started: $utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "✅ TTS completed: $utteranceId")
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "❌ TTS error: $utteranceId")
            }
        })
    }

    fun speak(
        text: String,
        languageCode: String = "en",
        urgent: Boolean = false,
        utteranceId: String = "TTS_ID_${System.currentTimeMillis()}"
    ) {
        if (!isInitialized) {
            Log.w(TAG, "⚠️ TTS not initialized, initializing now...")
            initialize(onSuccess = { speak(text, languageCode, urgent, utteranceId) })
            return
        }

        val locale = when (languageCode.lowercase()) {
            "ur" -> Locale("ur", "PK")
            "hi" -> Locale("hi", "IN")
            "ar" -> Locale("ar", "SA")
            "en" -> Locale.US
            else -> Locale.US
        }

        val result = tts?.setLanguage(locale)

        when (result) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                Log.e(TAG, "❌ Language $languageCode not supported, falling back to English")
                tts?.setLanguage(Locale.US)
                tts?.speak(
                    "Language not available. $text",
                    if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null,
                    utteranceId
                )
            }
            else -> {
                if (urgent) {
                    tts?.setSpeechRate(1.2f)
                    tts?.setPitch(1.1f)
                } else {
                    tts?.setSpeechRate(0.9f)
                    tts?.setPitch(1.0f)
                }

                tts?.speak(
                    text,
                    if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null,
                    utteranceId
                )
            }
        }
    }


    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        isInitialized = false
    }

    fun isLanguageAvailable(languageCode: String): Boolean {
        val locale = when (languageCode.lowercase()) {
            "ur" -> Locale("ur", "PK")
            "hi" -> Locale("hi", "IN")
            "ar" -> Locale("ar", "SA")
            "en" -> Locale.US
            else -> Locale.US
        }

        val result = tts?.isLanguageAvailable(locale)
        return result == TextToSpeech.LANG_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_AVAILABLE
    }
}
