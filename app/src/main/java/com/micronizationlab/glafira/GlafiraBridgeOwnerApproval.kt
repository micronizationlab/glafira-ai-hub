package com.micronizationlab.glafira

import java.security.SecureRandom
import java.util.Base64

/**
 * Owner-controlled Bridge enrollment workflow.
 *
 * A verified one-time pairing proof is only evidence that the joining client possessed
 * the short-lived invitation. It is not an approval and grants no device scopes.
 *
 * The persistence adapter MUST implement approveAndIssue atomically: transition the
 * pending request, persist the long-term credential encrypted, and persist an encrypted
 * owner-delivery outbox record in one transaction. It must never return the generated
 * secret from this method. The owner retrieves it through a session-bound delivery claim.
 */
data class GlafiraBridgePairingCandidate(
    val enrollment: GlafiraBridgeVerifiedEnrollment,
    val deviceLabel: String,
    val platform: GlafiraBridgeClientPlatform,
    /** Untrusted request only; these values are display hints and are never grants. */
    val requestedScopes: Set<String>,
    val receivedAtEpochMs: Long
)

enum class GlafiraBridgeClientPlatform { ANDROID, IOS, DESKTOP, UNKNOWN }

enum class GlafiraBridgeApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED }

data class GlafiraBridgePendingApproval(
    val approvalId: String,
    val ticketId: String,
    val clientNonce: String,
    val deviceLabel: String,
    val platform: GlafiraBridgeClientPlatform,
    val requestedScopes: Set<String>,
    val receivedAtEpochMs: Long,
    val status: GlafiraBridgeApprovalStatus
)

data class GlafiraBridgeCredentialDelivery(
    val approvalId: String,
    val ticketId: String,
    val deviceId: String,
    /** Sensitive: expose only to the authenticated owner delivery surface. */
    val hmacSecret: ByteArray,
    val grantedScopes: Set<String>
)

data class GlafiraBridgePendingCredentialDelivery(
    val approvalId: String,
    val ticketId: String,
    val deviceId: String
)

/**
 * Short-lived proof that the platform owner-authentication gate was passed.
 * Only the trusted platform authentication adapter should construct this token.
 */
class GlafiraBridgeOwnerSession internal constructor(
    val sessionId: String,
    val expiresAtEpochMs: Long
) {
    internal fun isValid(nowEpochMs: Long): Boolean =
        sessionId.length in 16..128 &&
            sessionId.all { it.isLetterOrDigit() || it in "-_." } &&
            expiresAtEpochMs > nowEpochMs
}

sealed class GlafiraBridgeApprovalMutation {
    data class Approved(val deviceId: String) : GlafiraBridgeApprovalMutation()
    data object NotFound : GlafiraBridgeApprovalMutation()
    data object AlreadyDecided : GlafiraBridgeApprovalMutation()
    data object InvalidScopes : GlafiraBridgeApprovalMutation()
    data object StorageError : GlafiraBridgeApprovalMutation()
}

sealed class GlafiraBridgeDeliveryResult {
    data class Available(val delivery: GlafiraBridgeCredentialDelivery) :
        GlafiraBridgeDeliveryResult()
    data object NotFound : GlafiraBridgeDeliveryResult()
    data object NotApproved : GlafiraBridgeDeliveryResult()
    data object ClaimedByAnotherOwnerSession : GlafiraBridgeDeliveryResult()
    data object AlreadyAcknowledged : GlafiraBridgeDeliveryResult()
    data object StorageError : GlafiraBridgeDeliveryResult()
}

/**
 * Implementations must use durable, transactional storage. Approval and credential
 * issuance are a single atomic state transition; scopes come exclusively from the
 * owner's selection. A delivery claim is bound to one authenticated owner session.
 */
interface GlafiraBridgePairingApprovalStore {
    fun enqueue(candidate: GlafiraBridgePendingApproval): Boolean
    fun find(approvalId: String): GlafiraBridgePendingApproval?
    fun listPending(limit: Int = GlafiraBridgeOwnerApprovalService.MAX_PENDING_APPROVALS): List<GlafiraBridgePendingApproval>

    fun approveAndIssue(
        approvalId: String,
        deviceId: String,
        hmacSecret: ByteArray,
        ownerSelectedScopes: Set<String>,
        approvedAtEpochMs: Long
    ): GlafiraBridgeApprovalMutation

    fun reject(approvalId: String, rejectedAtEpochMs: Long): Boolean
    fun listUndeliveredDeliveries(limit: Int): List<GlafiraBridgePendingCredentialDelivery>

    /** Repeated reads are allowed only for the same ownerSessionId until acknowledged. */
    fun claimDelivery(
        approvalId: String,
        ownerSessionId: String,
        ownerSessionExpiresAtEpochMs: Long,
        nowEpochMs: Long
    ): GlafiraBridgeDeliveryResult

    /** Called only after the owner UI confirms that the secret was safely received. */
    fun acknowledgeDelivery(approvalId: String, ownerSessionId: String): Boolean
}

class GlafiraBridgeOwnerApprovalService(
    private val store: GlafiraBridgePairingApprovalStore,
    private val secureRandom: SecureRandom = SecureRandom()
) {
    fun submitVerifiedEnrollment(
        candidate: GlafiraBridgePairingCandidate
    ): GlafiraBridgePendingApproval? {
        if (!isValidCandidate(candidate)) return null
        val approval = GlafiraBridgePendingApproval(
            approvalId = randomToken(APPROVAL_ID_BYTES),
            ticketId = candidate.enrollment.ticketId,
            clientNonce = candidate.enrollment.clientNonce,
            deviceLabel = candidate.deviceLabel.trim(),
            platform = candidate.platform,
            requestedScopes = candidate.requestedScopes.toSet(),
            receivedAtEpochMs = candidate.receivedAtEpochMs,
            status = GlafiraBridgeApprovalStatus.PENDING
        )
        return if (runCatching { store.enqueue(approval) }.getOrDefault(false)) approval else null
    }

    fun pendingApprovals(limit: Int = MAX_PENDING_APPROVALS): List<GlafiraBridgePendingApproval> {
        require(limit in 1..MAX_PENDING_APPROVALS)
        return runCatching { store.listPending(limit) }.getOrDefault(emptyList())
            .filter { it.status == GlafiraBridgeApprovalStatus.PENDING }
    }

    /**
     * The caller must pass a short-lived owner session minted by the trusted platform
     * authentication gate and obtain selected scopes from the owner UI. The LLM, candidate
     * request and device cannot mint this session or call this as owner.
     */
    fun approve(
        approvalId: String,
        ownerSelectedScopes: Set<String>,
        ownerSession: GlafiraBridgeOwnerSession,
        nowEpochMs: Long = System.currentTimeMillis()
    ): GlafiraBridgeApprovalMutation {
        if (!ownerSession.isValid(nowEpochMs) || !isValidApprovalId(approvalId) ||
            nowEpochMs <= 0L || !isValidScopes(ownerSelectedScopes)
        ) return GlafiraBridgeApprovalMutation.InvalidScopes

        val deviceId = randomToken(DEVICE_ID_BYTES)
        val secret = ByteArray(DEVICE_SECRET_BYTES).also(secureRandom::nextBytes)
        return try {
            runCatching {
                store.approveAndIssue(
                    approvalId = approvalId,
                    deviceId = deviceId,
                    hmacSecret = secret,
                    ownerSelectedScopes = ownerSelectedScopes.toSet(),
                    approvedAtEpochMs = nowEpochMs
                )
            }.getOrDefault(GlafiraBridgeApprovalMutation.StorageError)
        } finally {
            secret.fill(0)
        }
    }

    fun reject(
        approvalId: String,
        ownerSession: GlafiraBridgeOwnerSession,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!ownerSession.isValid(nowEpochMs) || !isValidApprovalId(approvalId) || nowEpochMs <= 0L) return false
        return runCatching { store.reject(approvalId, nowEpochMs) }.getOrDefault(false)
    }

    fun pendingCredentialDeliveries(
        ownerSession: GlafiraBridgeOwnerSession,
        nowEpochMs: Long = System.currentTimeMillis(),
        limit: Int = MAX_PENDING_APPROVALS
    ): List<GlafiraBridgePendingCredentialDelivery> {
        if (nowEpochMs <= 0L || !ownerSession.isValid(nowEpochMs) || limit !in 1..MAX_PENDING_APPROVALS) {
            return emptyList()
        }
        return runCatching { store.listUndeliveredDeliveries(limit) }.getOrDefault(emptyList())
    }

    fun claimCredentialDelivery(
        approvalId: String,
        ownerSession: GlafiraBridgeOwnerSession,
        nowEpochMs: Long = System.currentTimeMillis()
    ): GlafiraBridgeDeliveryResult {
        if (nowEpochMs <= 0L || !ownerSession.isValid(nowEpochMs) ||
            !isValidApprovalId(approvalId)
        ) return GlafiraBridgeDeliveryResult.NotApproved
        return runCatching {
            store.claimDelivery(
                approvalId,
                ownerSession.sessionId,
                ownerSession.expiresAtEpochMs,
                nowEpochMs
            )
        }
            .getOrDefault(GlafiraBridgeDeliveryResult.StorageError)
    }

    fun acknowledgeCredentialDelivery(
        approvalId: String,
        ownerSession: GlafiraBridgeOwnerSession,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (nowEpochMs <= 0L || !ownerSession.isValid(nowEpochMs) ||
            !isValidApprovalId(approvalId)
        ) return false
        return runCatching { store.acknowledgeDelivery(approvalId, ownerSession.sessionId) }
            .getOrDefault(false)
    }

    private fun isValidCandidate(candidate: GlafiraBridgePairingCandidate): Boolean =
        candidate.receivedAtEpochMs > 0L &&
            candidate.enrollment.verifiedAtEpochMs > 0L &&
            candidate.enrollment.verifiedAtEpochMs <= candidate.receivedAtEpochMs &&
            candidate.deviceLabel.trim().length in 1..64 &&
            candidate.deviceLabel.none(Char::isISOControl) &&
            isValidToken(candidate.enrollment.ticketId, 16, 128) &&
            isValidToken(candidate.enrollment.clientNonce, 32, 128) &&
            candidate.requestedScopes.size <= MAX_REQUESTED_SCOPES &&
            candidate.requestedScopes.all(::isValidScope)

    private fun randomToken(byteCount: Int): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(byteCount).also(secureRandom::nextBytes))

    companion object {
        const val MAX_PENDING_APPROVALS = 100
        const val MAX_REQUESTED_SCOPES = 32
        const val DEVICE_SECRET_BYTES = 32
        private const val APPROVAL_ID_BYTES = 16
        private const val DEVICE_ID_BYTES = 16

        private fun isValidApprovalId(value: String) = isValidToken(value, 16, 128)
        private fun isValidToken(value: String, min: Int, max: Int) =
            value.length in min..max && value.all { it.isLetterOrDigit() || it in "-_" }
        private fun isValidScope(value: String) =
            value.length in 1..96 && value.all { it.isLetterOrDigit() || it in "._-" }
        private fun isValidScopes(scopes: Set<String>) =
            scopes.isNotEmpty() && scopes.size <= MAX_REQUESTED_SCOPES && scopes.all(::isValidScope)
    }
}
