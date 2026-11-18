package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceProfilePolicyTest {
    private val now = 8_000_000L

    private fun authorizedRealPerson(
        uses: Set<VoiceSynthesisUse> = setOf(VoiceSynthesisUse.PRIVATE_EVALUATION),
        evidence: String? = "consent-record-001",
        expires: Long? = now + 60_000L,
        revoked: Boolean = false
    ) = VoiceProfileAuthorization(
        profileId = "voice-profile-001",
        kind = VoiceProfileKind.IDENTIFIABLE_REAL_PERSON,
        permittedUses = uses,
        consentEvidenceReference = evidence,
        consentValidUntilEpochMs = expires,
        revoked = revoked
    )

    @Test
    fun originalSyntheticVoiceCanBeUsedWithinDeclaredScope() {
        val profile = VoiceProfileAuthorization(
            "original-voice-001",
            VoiceProfileKind.ORIGINAL_SYNTHETIC,
            setOf(VoiceSynthesisUse.PERSONAL_CONVERSATION)
        )
        assertEquals(
            VoicePolicyDecision.ALLOWED,
            VoiceProfilePolicy.evaluate(profile, VoiceSynthesisUse.PERSONAL_CONVERSATION, now)
        )
    }

    @Test
    fun identifiableVoiceRequiresConsentEvidenceAndExpiry() {
        assertEquals(
            VoicePolicyDecision.CONSENT_REQUIRED,
            VoiceProfilePolicy.evaluate(
                authorizedRealPerson(evidence = null),
                VoiceSynthesisUse.PRIVATE_EVALUATION,
                now
            )
        )
        assertEquals(
            VoicePolicyDecision.CONSENT_REQUIRED,
            VoiceProfilePolicy.evaluate(
                authorizedRealPerson(expires = null),
                VoiceSynthesisUse.PRIVATE_EVALUATION,
                now
            )
        )
    }

    @Test
    fun revokedOrExpiredConsentBlocksSynthesis() {
        assertEquals(
            VoicePolicyDecision.CONSENT_REVOKED,
            VoiceProfilePolicy.evaluate(
                authorizedRealPerson(revoked = true),
                VoiceSynthesisUse.PRIVATE_EVALUATION,
                now
            )
        )
        assertEquals(
            VoicePolicyDecision.CONSENT_EXPIRED,
            VoiceProfilePolicy.evaluate(
                authorizedRealPerson(expires = now),
                VoiceSynthesisUse.PRIVATE_EVALUATION,
                now
            )
        )
    }

    @Test
    fun consentForPrivateEvaluationDoesNotAuthorizePublicDistribution() {
        assertEquals(
            VoicePolicyDecision.USE_NOT_AUTHORIZED,
            VoiceProfilePolicy.evaluate(
                authorizedRealPerson(),
                VoiceSynthesisUse.PUBLIC_DISTRIBUTION,
                now
            )
        )
    }

    @Test
    fun emptyOrInvalidProfileIsRejected() {
        val profile = VoiceProfileAuthorization(
            "",
            VoiceProfileKind.ORIGINAL_SYNTHETIC,
            emptySet()
        )
        assertEquals(
            VoicePolicyDecision.INVALID_PROFILE,
            VoiceProfilePolicy.evaluate(profile, VoiceSynthesisUse.WORK_CONVERSATION, now)
        )
    }
}
