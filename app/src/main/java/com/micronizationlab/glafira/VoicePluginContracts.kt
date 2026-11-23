package com.micronizationlab.glafira

import java.time.Instant

/**
 * Provider-neutral contract for the optional voice plugin.
 *
 * This file deliberately contains no Android microphone or audio implementation.
 * Platform adapters must request permission only after an explicit user action.
 */
enum class VoiceCapability {
    SPEECH_INPUT,
    SPEECH_OUTPUT
}

enum class VoicePluginState {
    DISABLED,
    ENABLING,
    READY,
    UNAVAILABLE,
    ERROR
}

enum class VoiceProfileKind {
    ORIGINAL_SYNTHETIC,
    IDENTIFIABLE_PERSON
}

data class VoicePluginDescriptor(
    val id: String,
    val displayName: String,
    val capabilities: Set<VoiceCapability>,
    val languageTags: Set<String> = emptySet()
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "Invalid plugin id" }
        require(displayName.isNotBlank()) { "Display name is required" }
        require(capabilities.isNotEmpty()) { "At least one capability is required" }
    }
}

data class VoicePluginConfiguration(
    val enabled: Boolean = false
)

data class VoiceDataRetentionPolicy(
    val retainRawAudio: Boolean = false,
    val retainTranscripts: Boolean = false,
    val retentionDays: Int = 0
) {
    init {
        require(retentionDays >= 0)
        require(retainRawAudio || retainTranscripts || retentionDays == 0) {
            "A retention period is meaningless when no voice data is retained"
        }
    }
}

data class VoiceInputRequest(
    val projectId: String,
    val conversationId: String,
    val languageTag: String,
    val startedAt: Instant
)

data class VoiceTranscript(
    val text: String,
    val languageTag: String,
    val providerId: String,
    val modelId: String?,
    val createdAt: Instant
)

data class VoiceOutputRequest(
    val text: String,
    val languageTag: String,
    val profileId: String? = null,
    val deliveryProfileId: String? = null,
    val aiSpeechDisclosureRequired: Boolean = true
)

data class VoiceOutputReceipt(
    val providerId: String,
    val modelId: String?,
    val profileKind: VoiceProfileKind,
    val aiGeneratedDisclosure: String,
    val createdAt: Instant
)

data class VoiceConsentEvidence(
    val profileId: String,
    val subjectReference: String,
    val authorizedUses: Set<String>,
    val evidenceReference: String,
    val expiresAt: Instant,
    val revokedAt: Instant? = null
)

interface VoiceConsentStore {
    suspend fun findConsent(profileId: String, intendedUse: String): VoiceConsentEvidence?
}

interface MicrophonePermissionGate {
    /** Must be called only after the owner explicitly starts speech input. */
    suspend fun requestForUserStartedSession(): Boolean
}

interface SpeechInputProvider {
    val descriptor: VoicePluginDescriptor
    suspend fun transcribe(request: VoiceInputRequest): VoiceTranscript
}

interface SpeechOutputProvider {
    val descriptor: VoicePluginDescriptor
    suspend fun synthesize(request: VoiceOutputRequest): VoiceOutputReceipt
}

interface VoicePlugin {
    val descriptor: VoicePluginDescriptor
    val state: VoicePluginState
    suspend fun enable(): Boolean
    suspend fun disable()
}
