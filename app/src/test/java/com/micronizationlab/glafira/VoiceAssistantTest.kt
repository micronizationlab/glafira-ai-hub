package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAssistantTest {
    private class FakeVoicePlugin(
        override val descriptor: VoicePluginDescriptor,
        private val state: VoicePluginAvailability = VoicePluginAvailability.AVAILABLE
    ) : VoicePlugin {
        var calls = 0
        override fun availability() = state
        override fun transcribe(audio: ByteArray, mimeType: String): String {
            calls++
            return "transcript"
        }
        override fun synthesize(text: String, profile: VoiceStyleProfile): VoiceAudio {
            calls++
            return VoiceAudio(byteArrayOf(1), "audio/mpeg", "test-provider")
        }
    }

    @Test
    fun voiceIsOffByDefaultAndNoPluginIsActivated() {
        val plugin = FakeVoicePlugin(
            VoicePluginDescriptor("stock-voice", "Original voice", setOf(VoiceCapability.SPEECH_OUTPUT))
        )
        val runtime = VoiceAssistantRuntime(VoicePluginRegistry(listOf(plugin)))

        assertFalse(runtime.settings.enabled)
        assertNull(runtime.activePlugin())
        assertEquals(0, plugin.calls)
    }

    @Test
    fun ownerCanEnableSelectAndDisableInstalledPlugin() {
        val plugin = FakeVoicePlugin(
            VoicePluginDescriptor("stock-voice", "Original voice", setOf(VoiceCapability.SPEECH_OUTPUT))
        )
        val runtime = VoiceAssistantRuntime(VoicePluginRegistry(listOf(plugin)))

        runtime.enable("stock-voice")
        assertTrue(runtime.settings.enabled)
        assertSame(plugin, runtime.activePlugin())

        runtime.disable()
        assertFalse(runtime.settings.enabled)
        assertNull(runtime.activePlugin())
    }

    @Test
    fun cannotEnableMissingOrUnavailablePlugin() {
        val unavailable = FakeVoicePlugin(
            VoicePluginDescriptor("cloud-voice", "Cloud voice", setOf(VoiceCapability.SPEECH_INPUT)),
            VoicePluginAvailability.NEEDS_CONFIGURATION
        )
        val runtime = VoiceAssistantRuntime(VoicePluginRegistry(listOf(unavailable)))

        assertThrows(IllegalArgumentException::class.java) { runtime.enable("missing") }
        assertThrows(IllegalArgumentException::class.java) { runtime.enable("cloud-voice") }
        assertFalse(runtime.settings.enabled)
    }

    @Test
    fun duplicatePluginIdsAreRejected() {
        val first = FakeVoicePlugin(VoicePluginDescriptor("same", "First", setOf(VoiceCapability.SPEECH_INPUT)))
        val second = FakeVoicePlugin(VoicePluginDescriptor("same", "Second", setOf(VoiceCapability.SPEECH_OUTPUT)))
        assertThrows(IllegalArgumentException::class.java) {
            VoicePluginRegistry(listOf(first, second))
        }
    }

    @Test
    fun uncertainConversationDefaultsToFormalWorkSafeProfile() {
        val registry = VoicePluginRegistry(emptyList())
        val runtime = VoiceAssistantRuntime(registry)

        assertEquals(VoiceStyleProfile.WORK_FORMAL_ORIGINAL, runtime.styleFor(null))
        assertEquals(VoiceStyleProfile.WORK_FORMAL_ORIGINAL, runtime.styleFor(true))
        assertEquals(VoiceStyleProfile.PERSONAL_INFORMAL_ORIGINAL, runtime.styleFor(false))
    }
    @Test
    fun voiceAudioCopiesInputAndReturnedBytes() {
        val source = byteArrayOf(1, 2, 3)
        val audio = VoiceAudio(source, "audio/mpeg", "test-provider")
        source[0] = 9

        val firstRead = audio.bytes
        assertEquals(1, firstRead[0])
        firstRead[1] = 9
        assertEquals(2, audio.bytes[1])
    }

    @Test
    fun voiceAudioRejectsUnsupportedMimeTypeAndEmptyProvenance() {
        assertThrows(IllegalArgumentException::class.java) {
            VoiceAudio(byteArrayOf(1), "application/octet-stream", "test-provider")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceAudio(byteArrayOf(1), "audio/mpeg", " ")
        }
    }

}
