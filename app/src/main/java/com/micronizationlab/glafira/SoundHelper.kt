package com.micronizationlab.glafira

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * ICQ-style event sounds bundled in res/raw.
 * The sound API includes hooks for events that will be enabled when
 * chat requests and file transfers are implemented in the app.
 */
object SoundHelper {
    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<String, Int>()
    private val loadedIds = mutableSetOf<Int>()
    private var enabled = true
    private var volume = 1f

    @Synchronized
    fun init(context: Context) {
        val preferences = context.getSharedPreferences("glafira_audio", Context.MODE_PRIVATE)
        enabled = preferences.getBoolean("sound_effects_enabled", true)
        volume = preferences.getFloat("sound_effects_volume", 1f).coerceIn(0f, 1f)
        if (soundPool != null) return

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val pool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attributes)
            .build()

        pool.setOnLoadCompleteListener { _, sampleId, status ->
            synchronized(this) {
                if (status == 0) loadedIds.add(sampleId)
            }
        }

        soundPool = pool
        soundIds["incoming"] = pool.load(context, R.raw.icq_message, 1)
        soundIds["online"] = pool.load(context, R.raw.icq_online, 1)
        soundIds["sent"] = pool.load(context, R.raw.icq_message_sent, 1)
        soundIds["chat"] = pool.load(context, R.raw.icq_chat_request, 1)
        soundIds["file"] = pool.load(context, R.raw.icq_incoming_file, 1)
    }

    @Synchronized
    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences("glafira_audio", Context.MODE_PRIVATE)
            .edit().putBoolean("sound_effects_enabled", value).apply()
    }

    @Synchronized
    fun setVolume(context: Context, value: Float) {
        volume = value.coerceIn(0f, 1f)
        context.getSharedPreferences("glafira_audio", Context.MODE_PRIVATE)
            .edit().putFloat("sound_effects_volume", volume).apply()
    }

    fun playIncoming() = play("incoming")
    fun playOnline() = play("online")
    fun playSent() = play("sent")
    fun playChatRequest() = play("chat")
    fun playIncomingFile() = play("file")

    @Synchronized
    private fun play(event: String) {
        if (!enabled) return
        val pool = soundPool ?: return
        val sampleId = soundIds[event] ?: return
        if (sampleId !in loadedIds) return
        pool.play(sampleId, volume, volume, 1, 0, 1f)
    }

    @Synchronized
    fun release() {
        soundPool?.release()
        soundPool = null
        soundIds.clear()
        loadedIds.clear()
    }
}
