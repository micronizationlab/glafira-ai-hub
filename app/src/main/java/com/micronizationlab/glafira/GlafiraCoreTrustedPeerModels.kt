package com.micronizationlab.glafira

/** Candidate public identity received from the peer; it is untrusted until owner-approved. */
data class GlafiraPeerEnrollmentCandidate(
    val coreId: String,
    val keyEpoch: Long,
    val publicKeySpkiBase64: String,
    val claimedFingerprintSha256: String
)

/** Public, non-secret metadata for a peer key explicitly approved by this installation's owner. */
data class GlafiraTrustedPeer(
    val coreId: String,
    val keyEpoch: Long,
    val publicKeySpkiBase64: String,
    val fingerprintSha256: String,
    val approvedAtMillis: Long,
    val revokedAtMillis: Long?
)

enum class GlafiraPeerTrustMutation {
    ENROLLED,
    ALREADY_TRUSTED,
    REVOKED,
    ALREADY_REVOKED,
    NOT_FOUND,
    OWNER_AUTHENTICATION_REQUIRED,
    INVALID_PEER_IDENTITY,
    FINGERPRINT_MISMATCH,
    ACTIVE_KEY_EXISTS,
    KEY_EPOCH_NOT_MONOTONIC,
    STORAGE_ERROR
}
