package com.micronizationlab.glafira

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePluginContractTest {
    private class FakePlugin : VoicePlugin {
        var synthesisCalls = 0
        override val descriptor = VoicePluginDescriptor(
            "mock-voice",
            "Mock voice",
            setOf(VoiceCapability.SPEECH_TO_TEXT, VoiceCapability.TEXT_TO_SPEECH)
        )
        override val speechToTextProvider = object : SpeechToTextProvider {
            override suspend fun transcribe(request: VoiceTranscriptionRequest) =
                VoiceTranscription("test words", "mock-stt", "mock-model", request.localeTag)
        }
        override val textToSpeechProvider = object : TextToSpeechProvider {
            override suspend fun synthesize(request: VoiceSynthesisRequest): VoiceProviderAudio {
                synthesisCalls++
                return VoiceProviderAudio(byteArrayOf(1, 2, 3), "audio/wav", "mock-tts", "mock-model")
            }
        }
    }

    @Test
    fun runtimeIsDisabledUntilOwnerExplicitlyEnablesIt() {
        val registry = VoicePluginRegistry()
        registry.register(FakePlugin())
        val runtime = VoiceAssistantRuntime(registry) { 10_000L }

        val result = runtime.startSession(
            "mock-voice",
            setOf(VoiceCapability.TEXT_TO_SPEECH),
            microphonePermissionGranted = false
        )
        assertEquals(VoiceRuntimeError.DISABLED, (result as VoiceRuntimeResult.Failure).error)
    }

    @Test
    fun microphonePermissionIsRequiredOnlyForSpeechInput() {
        val registry = VoicePluginRegistry()
        registry.register(FakePlugin())
        val runtime = VoiceAssistantRuntime(registry) { 10_000L }
        runtime.enableByOwner()

        val input = runtime.startSession(
            "mock-voice",
            setOf(VoiceCapability.SPEECH_TO_TEXT),
            microphonePermissionGranted = false
        )
        assertEquals(
            VoiceRuntimeError.MICROPHONE_PERMISSION_REQUIRED,
            (input as VoiceRuntimeResult.Failure).error
        )

        val output = runtime.startSession(
            "mock-voice",
            setOf(VoiceCapability.TEXT_TO_SPEECH),
            microphonePermissionGranted = false
        )
        assertTrue(output is VoiceRuntimeResult.Success)
    }

    @Test
    fun synthesisRequiresConsentAndAlwaysRequiresAiDisclosure() = runBlocking {
        val plugin = FakePlugin()
        val registry = VoicePluginRegistry().apply { register(plugin) }
        val runtime = VoiceAssistantRuntime(registry) { 10_000L }
        runtime.enableByOwner()
        val session = (runtime.startSession(
            "mock-voice",
            setOf(VoiceCapability.TEXT_TO_SPEECH),
            microphonePermissionGranted = false
        ) as VoiceRuntimeResult.Success).value

        val unauthorized = VoiceProfileAuthorization(
            "real-person-profile",
            VoiceProfileKind.IDENTIFIABLE_REAL_PERSON,
            setOf(VoiceSynthesisUse.PERSONAL_CONVERSATION)
        )
        val blocked = runtime.synthesize(
            session,
            VoiceSynthesisRequest(
                "hello",
                unauthorized,
                VoiceSynthesisUse.PERSONAL_CONVERSATION,
                VoiceDeliveryProfile.PERSONAL_INFORMAL
            )
        )
        assertEquals(VoiceRuntimeError.CONSENT_POLICY_BLOCKED, (blocked as VoiceRuntimeResult.Failure).error)
        assertEquals(0, plugin.synthesisCalls)

        val original = VoiceProfileAuthorization(
            "original-synthetic-voice",
            VoiceProfileKind.ORIGINAL_SYNTHETIC,
            setOf(VoiceSynthesisUse.PERSONAL_CONVERSATION)
        )
        val allowed = runtime.synthesize(
            session,
            VoiceSynthesisRequest(
                "hello",
                original,
                VoiceSynthesisUse.PERSONAL_CONVERSATION,
                VoiceDeliveryProfile.PERSONAL_INFORMAL
            )
        ) as VoiceRuntimeResult.Success
        assertTrue(allowed.value.aiGeneratedDisclosureRequired)
        assertEquals(1, plugin.synthesisCalls)
    }

    @Test
    fun disablingRuntimeInvalidatesActiveSession() = runBlocking {
        val registry = VoicePluginRegistry().apply { register(FakePlugin()) }
        val runtime = VoiceAssistantRuntime(registry) { 10_000L }
        runtime.enableByOwner()
        val session = (runtime.startSession(
            "mock-voice",
            setOf(VoiceCapability.SPEECH_TO_TEXT),
            microphonePermissionGranted = true
        ) as VoiceRuntimeResult.Success).value
        runtime.disableByOwner()

        val result = runtime.transcribe(
            session,
            VoiceTranscriptionRequest(byteArrayOf(1), "audio/wav", "ru-RU")
        )
        assertEquals(VoiceRuntimeError.SESSION_NOT_ACTIVE, (result as VoiceRuntimeResult.Failure).error)
    }
}
