package com.micronizationlab.glafira

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * ICQ-style event sounds bundled in res/raw.
 *
 * SoundPool loading is asynchronous. Events requested before their sample is
 * ready are queued briefly instead of being silently discarded.
 */
object SoundHelper {
    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<String, Int>()
    private val loadedIds = mutableSetOf<Int>()
    private val failedIds = mutableSetOf<Int>()
    private val pendingEvents = mutableListOf<String>()
    private var enabled = true
    private var volume = 1f

    @Synchronized
    fun init(context: Context) {
        val preferences = context.getSharedPreferences("glafira_audio", Context.MODE_PRIVATE)
        enabled = preferences.getBoolean("sound_effects_enabled", true)
        volume = preferences.getFloat("sound_effects_volume", 1f).coerceIn(0f, 1f)
        if (soundPool != null) return

        val attributes = AudioAttributes.Builder()
            // Use the media stream so the user's normal playback volume controls
            // these in-app effects consistently across OEM audio policies.
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val pool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attributes)
            .build()

        pool.setOnLoadCompleteListener { _, sampleId, status ->
            synchronized(this) {
                if (status == 0) {
                    loadedIds.add(sampleId)
                    val event = soundIds.entries.firstOrNull { it.value == sampleId }?.key
                    if (event != null) {
                        val queued = pendingEvents.count { it == event }
                        repeat(queued) {
                            pendingEvents.remove(event)
                            playLoaded(event)
                        }
                    }
                } else {
                    failedIds.add(sampleId)
                    val event = soundIds.entries.firstOrNull { it.value == sampleId }?.key
                    if (event != null) pendingEvents.removeAll { it == event }
                }
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
        if (!value) pendingEvents.clear()
        context.getSharedPreferences("glafira_audio", Context.MODE_PRIVATE)
            .edit().putBoolean("sound_effects_enabled", value).apply()
    }

    @Synchronized
    fun setVolume(context: Context, value: Float) {
        volume = value.coerceIn(0f, 1f)
        context.getSharedPreferences("glafira_audio", Context.MODE_PRIVATE)
            .edit().putFloat("sound_effects_volume", volume).apply()
    }

    fun playIncoming() { play("incoming") }
    fun playOnline() { play("online") }
    fun playSent() { play("sent") }
    fun playChatRequest() { play("chat") }
    fun playIncomingFile() { play("file") }

    /** Plays the bundled online chime from the Settings sound-test action. */
    fun playTest(): Boolean = play("online")

    @Synchronized
    private fun play(event: String): Boolean {
        if (!enabled || soundPool == null) return false
        val sampleId = soundIds[event] ?: return false
        if (sampleId in failedIds) return false
        if (sampleId !in loadedIds) {
            if (pendingEvents.size < 8) pendingEvents.add(event)
            return true
        }
        return playLoaded(event)
    }

    private fun playLoaded(event: String): Boolean {
        val pool = soundPool ?: return false
        val sampleId = soundIds[event] ?: return false
        if (sampleId !in loadedIds || !enabled) return false
        return pool.play(sampleId, volume, volume, 1, 0, 1f) != 0
    }

    @Synchronized
    fun release() {
        soundPool?.release()
        soundPool = null
        soundIds.clear()
        loadedIds.clear()
        failedIds.clear()
        pendingEvents.clear()
    }
}
