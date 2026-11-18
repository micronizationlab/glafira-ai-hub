package com.micronizationlab.glafira.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class VoiceRuntimeContractTest {
    private val request = SpeechSynthesisRequest("request-1", "Здравствуйте", "ru-RU", "original-ru", "formal", "work")

    @Test
    fun providersAreDisabledUntilExplicitlyEnabledByOwner() {
        val provider = FakeTtsProvider()
        assertNull(VoicePluginCatalog(listOf(provider)).provider(provider.descriptor.id, VoicePluginCapability.TEXT_TO_SPEECH))
        assertEquals(provider, VoicePluginCatalog(listOf(provider), setOf(provider.descriptor.id))
            .provider(provider.descriptor.id, VoicePluginCapability.TEXT_TO_SPEECH))
    }

    @Test
    fun synthesisConsentGateRunsBeforeProvider() = runBlocking {
        val provider = FakeTtsProvider()
        val runtime = GlafiraVoiceRuntime(
            VoicePluginCatalog(listOf(provider), setOf(provider.descriptor.id)),
            VoiceSynthesisAuthorizationGate { VoiceAuthorizationDecision.CONSENT_REQUIRED }
        )
        try { runtime.synthesize(provider.descriptor.id, request); fail("Must be denied.") }
        catch (_: IllegalStateException) { assertEquals(0, provider.calls) }
    }

    @Test
    fun allowedSynthesisUsesEnabledProvider() = runBlocking {
        val provider = FakeTtsProvider()
        val runtime = GlafiraVoiceRuntime(
            VoicePluginCatalog(listOf(provider), setOf(provider.descriptor.id)),
            VoiceSynthesisAuthorizationGate { VoiceAuthorizationDecision.ALLOWED }
        )
        assertEquals("fake.tts", runtime.synthesize(provider.descriptor.id, request).providerId)
        assertEquals(1, provider.calls)
    }

    @Test
    fun audioPayloadIsDefensivelyCopied() {
        val source = byteArrayOf(1, 2, 3)
        val audio = VoiceAudioPayload(source, "audio/wav", 1_000)
        source[0] = 99
        val returned = audio.copyBytes()
        returned[1] = 99
        assertEquals(1, audio.copyBytes()[0].toInt())
        assertEquals(2, audio.copyBytes()[1].toInt())
    }

    @Test
    fun catalogRejectsDuplicateIdsAndUnknownOwnerEnablement() {
        val provider = FakeTtsProvider()
        try { VoicePluginCatalog(listOf(provider, provider)); fail("Duplicate IDs must be rejected.") }
        catch (_: IllegalArgumentException) { }
        try { VoicePluginCatalog(listOf(provider), setOf("unknown")); fail("Unknown ID must be rejected.") }
        catch (_: IllegalArgumentException) { }
    }

    private class FakeTtsProvider : SpeechSynthesisProvider {
        override val descriptor = VoiceProviderDescriptor(
            "fake.tts", "Fake TTS", setOf(VoicePluginCapability.TEXT_TO_SPEECH),
            setOf("ru-RU"), VoiceProviderRetention.EPHEMERAL_REQUEST_ONLY, false
        )
        var calls = 0
        override suspend fun close() = Unit
        override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechSynthesisResult {
            calls++
            return SpeechSynthesisResult(
                descriptor.id, VoiceAudioPayload(byteArrayOf(1, 2, 3), "audio/wav", 1_000),
                "Голос сгенерирован ИИ."
            )
        }
    }
}
