package com.micronizationlab.glafira

enum class VoiceProfileKind {
    ORIGINAL_SYNTHETIC,
    IDENTIFIABLE_REAL_PERSON
}

enum class VoiceSynthesisUse {
    PRIVATE_EVALUATION,
    PERSONAL_CONVERSATION,
    WORK_CONVERSATION,
    PUBLIC_DISTRIBUTION
}

data class VoiceProfileAuthorization(
    val profileId: String,
    val kind: VoiceProfileKind,
    val permittedUses: Set<VoiceSynthesisUse>,
    /** Reference to separately stored consent evidence; never store the evidence itself here. */
    val consentEvidenceReference: String? = null,
    val consentValidUntilEpochMs: Long? = null,
    val revoked: Boolean = false
)

enum class VoicePolicyDecision {
    ALLOWED,
    INVALID_PROFILE,
    CONSENT_REQUIRED,
    CONSENT_REVOKED,
    CONSENT_EXPIRED,
    USE_NOT_AUTHORIZED
}

/**
 * Consent gate for synthesis. This is policy only: it does not train, clone or generate audio.
 * Identifiable real-person profiles require documented, unrevoked, time-valid consent for
 * the requested use. An original non-identifying synthetic voice follows its declared uses.
 */
object VoiceProfilePolicy {
    fun evaluate(
        profile: VoiceProfileAuthorization,
        use: VoiceSynthesisUse,
        nowEpochMs: Long = System.currentTimeMillis()
    ): VoicePolicyDecision {
        if (profile.profileId.isBlank() || profile.permittedUses.isEmpty() || nowEpochMs <= 0L) {
            return VoicePolicyDecision.INVALID_PROFILE
        }

        if (profile.kind == VoiceProfileKind.IDENTIFIABLE_REAL_PERSON) {
            if (profile.revoked) return VoicePolicyDecision.CONSENT_REVOKED
            if (profile.consentEvidenceReference.isNullOrBlank() ||
                profile.consentValidUntilEpochMs == null
            ) return VoicePolicyDecision.CONSENT_REQUIRED
            if (profile.consentValidUntilEpochMs <= nowEpochMs) {
                return VoicePolicyDecision.CONSENT_EXPIRED
            }
        }

        return if (use in profile.permittedUses) {
            VoicePolicyDecision.ALLOWED
        } else {
            VoicePolicyDecision.USE_NOT_AUTHORIZED
        }
    }
}
