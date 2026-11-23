package com.micronizationlab.glafira

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

object TtsHelper {
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingText: String? = null
    private var onDoneCallback: (() -> Unit)? = null
    private var enabled = true

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences("glafira_audio", Context.MODE_PRIVATE)
            .edit().putBoolean("voice_output_enabled", value).apply()
        if (!value) stop()
    }

    fun init(context: Context) {
        enabled = context.getSharedPreferences("glafira_audio", Context.MODE_PRIVATE)
            .getBoolean("voice_output_enabled", true)
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("ru", "RU"))
                isReady = result != TextToSpeech.LANG_MISSING_DATA &&
                          result != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        onDoneCallback?.invoke()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onDoneCallback?.invoke()
                    }
                })
                pendingText?.let {
                    speak(it)
                    pendingText = null
                }
            }
        }
    }

    fun isLanguageAvailable(): Boolean = isReady

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!enabled || text.isBlank()) return
        onDoneCallback = onDone
        if (!isReady) {
            pendingText = text
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "glafira_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
        onDoneCallback?.invoke()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
