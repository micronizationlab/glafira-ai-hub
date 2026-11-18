package com.micronizationlab.glafira

import java.util.UUID

enum class VoiceCapability {
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH
}

enum class VoiceDeliveryProfile {
    PERSONAL_INFORMAL,
    WORK_FORMAL,
    NEUTRAL
}

data class VoicePluginDescriptor(
    val pluginId: String,
    val displayName: String,
    val capabilities: Set<VoiceCapability>
) {
    init {
        require(pluginId.matches(Regex("[A-Za-z0-9._-]{3,64}")))
        require(displayName.isNotBlank() && displayName.length <= 96)
        require(capabilities.isNotEmpty())
    }
}

data class VoiceTranscriptionRequest(
    val audioBytes: ByteArray,
    val mimeType: String,
    val localeTag: String
)

data class VoiceTranscription(
    val text: String,
    val providerId: String,
    val modelId: String,
    val detectedLocaleTag: String
)

data class VoiceSynthesisRequest(
    val text: String,
    val profile: VoiceProfileAuthorization,
    val use: VoiceSynthesisUse,
    val deliveryProfile: VoiceDeliveryProfile
)

data class VoiceProviderAudio(
    val audioBytes: ByteArray,
    val mimeType: String,
    val providerId: String,
    val modelId: String
)

data class VoiceSynthesisOutput(
    val audioBytes: ByteArray,
    val mimeType: String,
    val providerId: String,
    val modelId: String,
    /** Always true: callers must disclose that the returned speech is AI-generated. */
    val aiGeneratedDisclosureRequired: Boolean = true
)

/**
 * Provider adapters receive only the audio/text needed for the current operation.
 * They must not persist raw audio/transcripts or log content/secrets unless a separate,
 * explicit owner policy and provider disclosure authorize retention.
 */
interface SpeechToTextProvider {
    suspend fun transcribe(request: VoiceTranscriptionRequest): VoiceTranscription
}

interface TextToSpeechProvider {
    suspend fun synthesize(request: VoiceSynthesisRequest): VoiceProviderAudio
}

interface VoicePlugin {
    val descriptor: VoicePluginDescriptor
    val speechToTextProvider: SpeechToTextProvider?
    val textToSpeechProvider: TextToSpeechProvider?
}

class VoicePluginRegistry {
    private val plugins = LinkedHashMap<String, VoicePlugin>()

    @Synchronized
    fun register(plugin: VoicePlugin) {
        val descriptor = plugin.descriptor
        val actual = buildSet {
            if (plugin.speechToTextProvider != null) add(VoiceCapability.SPEECH_TO_TEXT)
            if (plugin.textToSpeechProvider != null) add(VoiceCapability.TEXT_TO_SPEECH)
        }
        require(actual == descriptor.capabilities) {
            "Voice plugin capability declaration does not match its provider adapters."
        }
        require(plugins.putIfAbsent(descriptor.pluginId, plugin) == null) {
            "Voice plugin is already registered."
        }
    }

    @Synchronized
    fun find(pluginId: String): VoicePlugin? = plugins[pluginId]

    @Synchronized
    fun discover(): List<VoicePluginDescriptor> = plugins.values.map { it.descriptor }
}

data class VoiceSession internal constructor(
    val sessionId: String,
    val pluginId: String,
    val capabilities: Set<VoiceCapability>,
    val startedAtEpochMs: Long
)

enum class VoiceRuntimeError {
    DISABLED,
    PLUGIN_NOT_FOUND,
    CAPABILITY_UNAVAILABLE,
    MICROPHONE_PERMISSION_REQUIRED,
    SESSION_NOT_ACTIVE,
    INVALID_AUDIO,
    INVALID_TEXT,
    CONSENT_POLICY_BLOCKED,
    PROVIDER_FAILURE,
    SESSION_CANCELLED
}

sealed class VoiceRuntimeResult<out T> {
    data class Success<T>(val value: T) : VoiceRuntimeResult<T>()
    data class Failure(val error: VoiceRuntimeError) : VoiceRuntimeResult<Nothing>()
}

/**
 * Opt-in runtime coordinator. It does not capture microphone audio or play audio itself;
 * the Android/Desktop UI must request microphone permission only when starting an STT
 * session and pass the already-captured bytes to this provider-neutral contract.
 */
class VoiceAssistantRuntime(
    private val registry: VoicePluginRegistry,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    @Volatile private var enabled = false
    @Volatile private var activeSession: VoiceSession? = null

    @Synchronized
    fun enableByOwner() {
        enabled = true
    }

    @Synchronized
    fun disableByOwner() {
        enabled = false
        activeSession = null
    }

    @Synchronized
    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun startSession(
        pluginId: String,
        requestedCapabilities: Set<VoiceCapability>,
        microphonePermissionGranted: Boolean
    ): VoiceRuntimeResult<VoiceSession> {
        if (!enabled) return VoiceRuntimeResult.Failure(VoiceRuntimeError.DISABLED)
        val plugin = registry.find(pluginId)
            ?: return VoiceRuntimeResult.Failure(VoiceRuntimeError.PLUGIN_NOT_FOUND)
        if (requestedCapabilities.isEmpty() ||
            !plugin.descriptor.capabilities.containsAll(requestedCapabilities)
        ) return VoiceRuntimeResult.Failure(VoiceRuntimeError.CAPABILITY_UNAVAILABLE)
        if (VoiceCapability.SPEECH_TO_TEXT in requestedCapabilities && !microphonePermissionGranted) {
            return VoiceRuntimeResult.Failure(VoiceRuntimeError.MICROPHONE_PERMISSION_REQUIRED)
        }
        val now = nowEpochMs()
        if (now <= 0L) return VoiceRuntimeResult.Failure(VoiceRuntimeError.SESSION_CANCELLED)
        val session = VoiceSession(
            UUID.randomUUID().toString(),
            pluginId,
            requestedCapabilities.toSet(),
            now
        )
        activeSession = session
        return VoiceRuntimeResult.Success(session)
    }

    suspend fun transcribe(
        session: VoiceSession,
        request: VoiceTranscriptionRequest
    ): VoiceRuntimeResult<VoiceTranscription> {
        if (!isActive(session)) return VoiceRuntimeResult.Failure(VoiceRuntimeError.SESSION_NOT_ACTIVE)
        if (VoiceCapability.SPEECH_TO_TEXT !in session.capabilities) {
            return VoiceRuntimeResult.Failure(VoiceRuntimeError.CAPABILITY_UNAVAILABLE)
        }
        if (request.audioBytes.isEmpty() || request.audioBytes.size > MAX_AUDIO_BYTES ||
            request.mimeType !in SUPPORTED_AUDIO_MIME_TYPES ||
            !request.localeTag.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}"))
        ) return VoiceRuntimeResult.Failure(VoiceRuntimeError.INVALID_AUDIO)

        val provider = registry.find(session.pluginId)?.speechToTextProvider
            ?: return VoiceRuntimeResult.Failure(VoiceRuntimeError.CAPABILITY_UNAVAILABLE)
        return try {
            val result = provider.transcribe(request)
            if (!isActive(session)) VoiceRuntimeResult.Failure(VoiceRuntimeError.SESSION_CANCELLED)
            else if (result.text.isBlank() || result.text.length > MAX_TRANSCRIPT_CHARS ||
                result.providerId.isBlank() || result.modelId.isBlank()
            ) VoiceRuntimeResult.Failure(VoiceRuntimeError.PROVIDER_FAILURE)
            else VoiceRuntimeResult.Success(result)
        } catch (_: Exception) {
            VoiceRuntimeResult.Failure(VoiceRuntimeError.PROVIDER_FAILURE)
        }
    }

    suspend fun synthesize(
        session: VoiceSession,
        request: VoiceSynthesisRequest
    ): VoiceRuntimeResult<VoiceSynthesisOutput> {
        if (!isActive(session)) return VoiceRuntimeResult.Failure(VoiceRuntimeError.SESSION_NOT_ACTIVE)
        if (VoiceCapability.TEXT_TO_SPEECH !in session.capabilities) {
            return VoiceRuntimeResult.Failure(VoiceRuntimeError.CAPABILITY_UNAVAILABLE)
        }
        if (request.text.isBlank() || request.text.length > MAX_SYNTHESIS_TEXT_CHARS) {
            return VoiceRuntimeResult.Failure(VoiceRuntimeError.INVALID_TEXT)
        }
        if (VoiceProfilePolicy.evaluate(request.profile, request.use, nowEpochMs()) != VoicePolicyDecision.ALLOWED) {
            return VoiceRuntimeResult.Failure(VoiceRuntimeError.CONSENT_POLICY_BLOCKED)
        }
        val provider = registry.find(session.pluginId)?.textToSpeechProvider
            ?: return VoiceRuntimeResult.Failure(VoiceRuntimeError.CAPABILITY_UNAVAILABLE)
        return try {
            val audio = provider.synthesize(request)
            if (!isActive(session)) {
                audio.audioBytes.fill(0)
                VoiceRuntimeResult.Failure(VoiceRuntimeError.SESSION_CANCELLED)
            } else if (audio.audioBytes.isEmpty() || audio.audioBytes.size > MAX_AUDIO_BYTES ||
                audio.mimeType !in SUPPORTED_AUDIO_MIME_TYPES ||
                audio.providerId.isBlank() || audio.modelId.isBlank()
            ) {
                audio.audioBytes.fill(0)
                VoiceRuntimeResult.Failure(VoiceRuntimeError.PROVIDER_FAILURE)
            } else {
                VoiceRuntimeResult.Success(
                    VoiceSynthesisOutput(
                        audio.audioBytes,
                        audio.mimeType,
                        audio.providerId,
                        audio.modelId,
                        aiGeneratedDisclosureRequired = true
                    )
                )
            }
        } catch (_: Exception) {
            VoiceRuntimeResult.Failure(VoiceRuntimeError.PROVIDER_FAILURE)
        }
    }

    @Synchronized
    fun endSession(session: VoiceSession) {
        if (activeSession?.sessionId == session.sessionId) activeSession = null
    }

    @Synchronized
    private fun isActive(session: VoiceSession): Boolean =
        enabled && activeSession?.sessionId == session.sessionId &&
            activeSession?.pluginId == session.pluginId

    companion object {
        const val MAX_AUDIO_BYTES = 10 * 1024 * 1024
        const val MAX_TRANSCRIPT_CHARS = 32_000
        const val MAX_SYNTHESIS_TEXT_CHARS = 20_000
        val SUPPORTED_AUDIO_MIME_TYPES = setOf(
            "audio/wav", "audio/mpeg", "audio/ogg", "audio/mp4", "audio/webm"
        )
    }
}
