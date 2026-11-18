package com.micronizationlab.glafira

import android.media.AudioManager
import android.media.ToneGenerator

object SoundHelper {
    private var toneGenerator: ToneGenerator? = null

    fun playIncoming() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            }
            // Два коротких бипа — «uh-oh» по мотивам ICQ
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            Thread {
                Thread.sleep(150)
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
            }.start()
        } catch (e: Exception) {
            // Тихо игнорируем — звук не критичен
        }
    }
}
