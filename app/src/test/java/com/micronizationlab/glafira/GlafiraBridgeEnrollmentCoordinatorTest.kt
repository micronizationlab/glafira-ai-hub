package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraBridgeEnrollmentCoordinatorTest {
    @Test
    fun validPairingProofCreatesPendingApprovalOnly() {
        val ticketStore = FakeTicketStore()
        val protocol = GlafiraBridgePairingProtocol(ticketStore)
        val invitation = protocol.issueInvitation(5_000L)
        val nonce = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val proof = GlafiraBridgePairingProtocol.createClientProof(
            invitation.ticketId, invitation.pairingCapability, nonce
        )
        val approvals = FakeApprovalStore()
        val coordinator = GlafiraBridgeEnrollmentCoordinator(
            protocol, GlafiraBridgeOwnerApprovalService(approvals)
        )

        val result = coordinator.receiveVerifiedEnrollmentProof(
            invitation.ticketId, nonce, proof, "iPhone", GlafiraBridgeClientPlatform.IOS,
            setOf("project.read", "finance.payment"), 5_001L
        )

        assertTrue(result is GlafiraBridgeEnrollmentSubmission.Pending)
        val pending = (result as GlafiraBridgeEnrollmentSubmission.Pending).approval
        assertEquals(GlafiraBridgeApprovalStatus.PENDING, pending.status)
        assertEquals(setOf("project.read", "finance.payment"), pending.requestedScopes)
        assertTrue(approvals.issuedScopes.isEmpty())
    }

    @Test
    fun invalidProofNeverEntersOwnerQueue() {
        val ticketStore = FakeTicketStore()
        val protocol = GlafiraBridgePairingProtocol(ticketStore)
        val invitation = protocol.issueInvitation(5_000L)
        val nonce = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val approvals = FakeApprovalStore()
        val coordinator = GlafiraBridgeEnrollmentCoordinator(
            protocol, GlafiraBridgeOwnerApprovalService(approvals)
        )

        val result = coordinator.receiveVerifiedEnrollmentProof(
            invitation.ticketId, nonce, "00".repeat(32), "Android",
            GlafiraBridgeClientPlatform.ANDROID, emptySet(), 5_001L
        )

        assertEquals(
            GlafiraBridgePairingError.INVALID_PROOF,
            (result as GlafiraBridgeEnrollmentSubmission.Rejected).error
        )
        assertTrue(approvals.pending.isEmpty())
    }

    @Test
    fun invalidMetadataDoesNotConsumeOneTimeTicket() {
        val ticketStore = FakeTicketStore()
        val protocol = GlafiraBridgePairingProtocol(ticketStore)
        val invitation = protocol.issueInvitation(5_000L)
        val nonce = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val proof = GlafiraBridgePairingProtocol.createClientProof(
            invitation.ticketId, invitation.pairingCapability, nonce
        )
        val approvals = FakeApprovalStore()
        val coordinator = GlafiraBridgeEnrollmentCoordinator(
            protocol, GlafiraBridgeOwnerApprovalService(approvals)
        )

        val invalid = coordinator.receiveVerifiedEnrollmentProof(
            invitation.ticketId, nonce, proof, "   ", GlafiraBridgeClientPlatform.ANDROID,
            emptySet(), 5_001L
        )
        assertEquals(GlafiraBridgeEnrollmentSubmission.InvalidMetadata, invalid)

        val valid = coordinator.receiveVerifiedEnrollmentProof(
            invitation.ticketId, nonce, proof, "Android", GlafiraBridgeClientPlatform.ANDROID,
            emptySet(), 5_002L
        )
        assertTrue(valid is GlafiraBridgeEnrollmentSubmission.Pending)
    }

    private class FakeTicketStore : GlafiraBridgePairingTicketStore {
        private val records = mutableMapOf<String, GlafiraBridgePairingTicket>()
        override fun create(ticket: GlafiraBridgePairingTicket): Boolean {
            if (ticket.ticketId in records) return false
            records[ticket.ticketId] = ticket.copy(verifierKey = ticket.verifierKey.clone())
            return true
        }
        override fun find(ticketId: String) =
            records[ticketId]?.let { it.copy(verifierKey = it.verifierKey.clone()) }
        override fun consume(ticketId: String, expectedVerifierKey: ByteArray, nowEpochMs: Long): Boolean {
            val current = records[ticketId] ?: return false
            if (current.expiresAtEpochMs <= nowEpochMs ||
                !current.verifierKey.contentEquals(expectedVerifierKey)
            ) return false
            records.remove(ticketId)
            return true
        }
    }

    private class FakeApprovalStore : GlafiraBridgePairingApprovalStore {
        val pending = mutableMapOf<String, GlafiraBridgePendingApproval>()
        var issuedScopes: Set<String> = emptySet()

        override fun enqueue(candidate: GlafiraBridgePendingApproval): Boolean {
            if (candidate.approvalId in pending) return false
            pending[candidate.approvalId] = candidate
            return true
        }
        override fun find(approvalId: String) = pending[approvalId]
        override fun listPending(limit: Int) =
            pending.values.filter { it.status == GlafiraBridgeApprovalStatus.PENDING }.take(limit)
        override fun approveAndIssue(
            approvalId: String, deviceId: String, hmacSecret: ByteArray,
            ownerSelectedScopes: Set<String>, approvedAtEpochMs: Long
        ): GlafiraBridgeApprovalMutation {
            val current = pending[approvalId] ?: return GlafiraBridgeApprovalMutation.NotFound
            if (current.status != GlafiraBridgeApprovalStatus.PENDING) {
                return GlafiraBridgeApprovalMutation.AlreadyDecided
            }
            issuedScopes = ownerSelectedScopes
            pending[approvalId] = current.copy(status = GlafiraBridgeApprovalStatus.APPROVED)
            return GlafiraBridgeApprovalMutation.Approved(deviceId)
        }
        override fun reject(approvalId: String, rejectedAtEpochMs: Long): Boolean {
            val current = pending[approvalId] ?: return false
            if (current.status != GlafiraBridgeApprovalStatus.PENDING) return false
            pending[approvalId] = current.copy(status = GlafiraBridgeApprovalStatus.REJECTED)
            return true
        }
        override fun listUndeliveredDeliveries(limit: Int) = emptyList<GlafiraBridgePendingCredentialDelivery>()
        override fun claimDelivery(
            approvalId: String, ownerSessionId: String,
            ownerSessionExpiresAtEpochMs: Long, nowEpochMs: Long
        ) = GlafiraBridgeDeliveryResult.NotFound
        override fun acknowledgeDelivery(approvalId: String, ownerSessionId: String) = false
    }
}
