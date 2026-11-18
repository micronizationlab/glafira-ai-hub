package com.micronizationlab.glafira

/**
 * Trusted in-process bridge between the one-time proof verifier and the owner queue.
 * A transport may call this only after applying its request-size/rate limits. The
 * coordinator never grants scopes and never issues credentials.
 */
sealed class GlafiraBridgeEnrollmentSubmission {
    data class Pending(val approval: GlafiraBridgePendingApproval) : GlafiraBridgeEnrollmentSubmission()
    data class Rejected(val error: GlafiraBridgePairingError) : GlafiraBridgeEnrollmentSubmission()
    data object InvalidMetadata : GlafiraBridgeEnrollmentSubmission()
    data object QueueStorageError : GlafiraBridgeEnrollmentSubmission()
}

class GlafiraBridgeEnrollmentCoordinator(
    private val pairingProtocol: GlafiraBridgePairingProtocol,
    private val ownerApprovalService: GlafiraBridgeOwnerApprovalService
) {
    fun receiveVerifiedEnrollmentProof(
        ticketId: String,
        clientNonce: String,
        proofHex: String,
        deviceLabel: String,
        platform: GlafiraBridgeClientPlatform,
        requestedScopes: Set<String>,
        nowEpochMs: Long = System.currentTimeMillis()
    ): GlafiraBridgeEnrollmentSubmission {
        if (nowEpochMs <= 0L ||
            deviceLabel.trim().length !in 1..64 ||
            deviceLabel.any(Char::isISOControl) ||
            requestedScopes.size > GlafiraBridgeOwnerApprovalService.MAX_REQUESTED_SCOPES ||
            requestedScopes.any { scope ->
                scope.length !in 1..96 || scope.any { !(it.isLetterOrDigit() || it in "._-") }
            }
        ) return GlafiraBridgeEnrollmentSubmission.InvalidMetadata

        return when (
            val verified = pairingProtocol.verifyAndConsume(
                ticketId = ticketId,
                clientNonce = clientNonce,
                proofHex = proofHex,
                nowEpochMs = nowEpochMs
            )
        ) {
            is GlafiraBridgePairingResult.Rejected ->
                GlafiraBridgeEnrollmentSubmission.Rejected(verified.error)

            is GlafiraBridgePairingResult.Verified -> {
                val pending = ownerApprovalService.submitVerifiedEnrollment(
                    GlafiraBridgePairingCandidate(
                        enrollment = verified.enrollment,
                        deviceLabel = deviceLabel,
                        platform = platform,
                        requestedScopes = requestedScopes,
                        receivedAtEpochMs = nowEpochMs
                    )
                )
                if (pending == null) GlafiraBridgeEnrollmentSubmission.QueueStorageError
                else GlafiraBridgeEnrollmentSubmission.Pending(pending)
            }
        }
    }
}
