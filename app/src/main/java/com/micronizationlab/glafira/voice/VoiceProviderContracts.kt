package com.micronizationlab.glafira.voice

/**
 * Provider-neutral runtime contract. Providers are never started automatically:
 * the application must open an explicit voice session after owner action and
 * runtime microphone permission grant. Implementations must not persist raw audio
 * or transcripts, and must not log request content or credentials.
 */
enum class VoicePluginCapability { SPEECH_TO_TEXT, TEXT_TO_SPEECH }
enum class VoiceProviderRetention { EPHEMERAL_REQUEST_ONLY, PROVIDER_RETENTION_DISCLOSED, LOCAL_PERSISTENCE_REQUIRED }

data class VoiceProviderDescriptor(
    val id: String,
    val displayName: String,
    val capabilities: Set<VoicePluginCapability>,
    val supportedLocaleTags: Set<String>,
    val retention: VoiceProviderRetention,
    val requiresNetwork: Boolean
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{1,63}")))
        require(displayName.isNotBlank() && displayName.length <= 80)
        require(capabilities.isNotEmpty())
        require(supportedLocaleTags.all { it.matches(Regex("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*")) })
    }
}

interface VoicePluginProvider {
    val descriptor: VoiceProviderDescriptor
    suspend fun close()
}

class VoiceAudioPayload(bytes: ByteArray, val mimeType: String, val durationMillis: Long) {
    private val content = bytes.copyOf()
    init {
        require(content.isNotEmpty() && content.size <= MAX_AUDIO_BYTES)
        require(mimeType in SUPPORTED_AUDIO_MIME_TYPES)
        require(durationMillis in 1..MAX_AUDIO_DURATION_MILLIS)
    }
    fun copyBytes(): ByteArray = content.copyOf()
    companion object {
        const val MAX_AUDIO_BYTES = 25 * 1024 * 1024
        const val MAX_AUDIO_DURATION_MILLIS = 10 * 60 * 1000L
        val SUPPORTED_AUDIO_MIME_TYPES = setOf(
            "audio/aac", "audio/mp4", "audio/mpeg", "audio/ogg",
            "audio/wav", "audio/webm", "audio/3gpp"
        )
    }
}

data class SpeechRecognitionRequest(val requestId: String, val audio: VoiceAudioPayload, val localeTag: String) {
    init {
        require(requestId.isNotBlank() && requestId.length <= 128)
        require(localeTag.isNotBlank() && localeTag.length <= 32)
    }
}

data class SpeechRecognitionResult(
    val providerId: String,
    val transcript: String,
    val detectedLocaleTag: String?,
    val confidence: Double?
) {
    init {
        require(providerId.isNotBlank())
        require(transcript.length <= 100_000)
        require(confidence == null || confidence in 0.0..1.0)
    }
}

interface SpeechRecognitionProvider : VoicePluginProvider {
    suspend fun recognize(request: SpeechRecognitionRequest): SpeechRecognitionResult
}

data class SpeechSynthesisRequest(
    val requestId: String,
    val text: String,
    val localeTag: String,
    val voiceProfileId: String,
    val deliveryProfileId: String,
    val useCaseId: String
) {
    init {
        require(requestId.isNotBlank() && requestId.length <= 128)
        require(text.isNotBlank() && text.length <= 20_000)
        require(localeTag.isNotBlank() && localeTag.length <= 32)
        require(voiceProfileId.isNotBlank() && voiceProfileId.length <= 128)
        require(deliveryProfileId.isNotBlank() && deliveryProfileId.length <= 128)
        require(useCaseId.isNotBlank() && useCaseId.length <= 64)
    }
}

data class SpeechSynthesisResult(
    val providerId: String,
    val audio: VoiceAudioPayload,
    val disclosureText: String
) {
    init {
        require(providerId.isNotBlank())
        require(disclosureText.isNotBlank() && disclosureText.length <= 512)
    }
}

interface SpeechSynthesisProvider : VoicePluginProvider {
    suspend fun synthesize(request: SpeechSynthesisRequest): SpeechSynthesisResult
}

enum class VoiceAuthorizationDecision {
    ALLOWED, PROFILE_INVALID, CONSENT_REQUIRED, CONSENT_REVOKED, CONSENT_EXPIRED, USE_NOT_AUTHORIZED
}

fun interface VoiceSynthesisAuthorizationGate {
    fun evaluate(request: SpeechSynthesisRequest): VoiceAuthorizationDecision
}

class VoicePluginCatalog(
    providers: Collection<VoicePluginProvider>,
    ownerEnabledProviderIds: Set<String> = emptySet()
) {
    private val allProviders: Map<String, VoicePluginProvider>
    private val enabledIds = ownerEnabledProviderIds.toSet()

    init {
        val indexed = providers.associateBy { it.descriptor.id }
        require(indexed.size == providers.size) { "Duplicate voice provider ID." }
        require(enabledIds.all(indexed::containsKey)) { "Owner enabled an unknown voice provider." }
        allProviders = indexed
    }

    fun enabledProviders(capability: VoicePluginCapability): List<VoicePluginProvider> =
        allProviders.values
            .filter { it.descriptor.id in enabledIds && capability in it.descriptor.capabilities }
            .sortedBy { it.descriptor.id }

    fun provider(id: String, capability: VoicePluginCapability): VoicePluginProvider? =
        allProviders[id]?.takeIf { id in enabledIds && capability in it.descriptor.capabilities }
}

class GlafiraVoiceRuntime(
    private val catalog: VoicePluginCatalog,
    private val authorizationGate: VoiceSynthesisAuthorizationGate
) {
    suspend fun recognize(providerId: String, request: SpeechRecognitionRequest): SpeechRecognitionResult {
        val provider = catalog.provider(providerId, VoicePluginCapability.SPEECH_TO_TEXT)
            as? SpeechRecognitionProvider
            ?: throw IllegalStateException("Voice recognition provider is disabled or unavailable.")
        return provider.recognize(request)
    }

    suspend fun synthesize(providerId: String, request: SpeechSynthesisRequest): SpeechSynthesisResult {
        val decision = authorizationGate.evaluate(request)
        check(decision == VoiceAuthorizationDecision.ALLOWED) {
            "Voice synthesis denied by Core policy: $decision"
        }
        val provider = catalog.provider(providerId, VoicePluginCapability.TEXT_TO_SPEECH)
            as? SpeechSynthesisProvider
            ?: throw IllegalStateException("Voice synthesis provider is disabled or unavailable.")
        return provider.synthesize(request)
    }
}
