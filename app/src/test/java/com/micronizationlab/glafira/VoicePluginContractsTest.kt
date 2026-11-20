package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePluginContractsTest {
    @Test
    fun pluginIsDisabledByDefault() {
        assertFalse(VoicePluginConfiguration().enabled)
    }

    @Test
    fun voiceDataRetentionIsOffByDefault() {
        val policy = VoiceDataRetentionPolicy()
        assertFalse(policy.retainRawAudio)
        assertFalse(policy.retainTranscripts)
        assertEquals(0, policy.retentionDays)
    }

    @Test
    fun speechOutputRequiresAiDisclosureByDefault() {
        val request = VoiceOutputRequest(text = "Привет", languageTag = "ru-RU")
        assertTrue(request.aiSpeechDisclosureRequired)
        assertNull(request.profileId)
    }

    @Test
    fun descriptorRejectsEmptyCapabilities() {
        val failure = runCatching {
            VoicePluginDescriptor("test-provider", "Test", emptySet())
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun retentionPeriodCannotBeSetWhenRetentionIsDisabled() {
        val failure = runCatching {
            VoiceDataRetentionPolicy(retentionDays = 30)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
