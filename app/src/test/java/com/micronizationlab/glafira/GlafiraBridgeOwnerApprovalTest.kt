package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraBridgeOwnerApprovalTest {
    @Test
    fun verifiedEnrollmentIsQueuedButRequestedScopesAreNotGranted() {
        val store = FakeApprovalStore()
        val service = GlafiraBridgeOwnerApprovalService(store)

        val queued = service.submitVerifiedEnrollment(candidate(setOf("project.read", "finance.payment")))

        assertNotNull(queued)
        assertEquals(GlafiraBridgeApprovalStatus.PENDING, queued!!.status)
        assertEquals(setOf("project.read", "finance.payment"), queued.requestedScopes)
        assertTrue(store.issuedScopes.isEmpty())
        assertTrue(service.pendingApprovals().any { it.approvalId == queued.approvalId })
    }

    @Test
    fun onlyAuthenticatedOwnerCanApproveAndOnlySelectedScopesAreIssued() {
        val store = FakeApprovalStore()
        val service = GlafiraBridgeOwnerApprovalService(store)
        val queued = service.submitVerifiedEnrollment(candidate(setOf("project.read", "finance.payment")))!!

        val denied = service.approve(queued.approvalId, setOf("project.read"), expiredOwnerSession(), 2_000L)
        assertEquals(GlafiraBridgeApprovalMutation.InvalidScopes, denied)
        assertTrue(store.issuedScopes.isEmpty())

        val approved = service.approve(queued.approvalId, setOf("project.read"), ownerSession(), 2_001L)
        assertTrue(approved is GlafiraBridgeApprovalMutation.Approved)
        assertEquals(setOf("project.read"), store.issuedScopes)
        assertEquals(32, store.persistedSecret?.size)
        assertTrue(store.persistedSecret!!.any { it != 0.toByte() })
    }

    @Test
    fun requestedScopesCannotBeUsedToExpandOwnerSelection() {
        val store = FakeApprovalStore()
        val service = GlafiraBridgeOwnerApprovalService(store)
        val queued = service.submitVerifiedEnrollment(candidate(setOf("finance.payment")))!!

        val result = service.approve(queued.approvalId, setOf("project.read"), ownerSession(), 2_000L)

        assertTrue(result is GlafiraBridgeApprovalMutation.Approved)
        assertEquals(setOf("project.read"), store.issuedScopes)
        assertFalse("finance.payment" in store.issuedScopes)
    }

    @Test
    fun invalidCandidateNeverEntersQueue() {
        val store = FakeApprovalStore()
        val service = GlafiraBridgeOwnerApprovalService(store)
        val invalid = candidate(emptySet()).copy(deviceLabel = "   ")

        assertNull(service.submitVerifiedEnrollment(invalid))
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun rejectedRequestCannotBeApproved() {
        val store = FakeApprovalStore()
        val service = GlafiraBridgeOwnerApprovalService(store)
        val queued = service.submitVerifiedEnrollment(candidate(emptySet()))!!

        assertTrue(service.reject(queued.approvalId, ownerSession(), 2_000L))
        val result = service.approve(queued.approvalId, setOf("project.read"), ownerSession(), 2_001L)

        assertEquals(GlafiraBridgeApprovalMutation.AlreadyDecided, result)
    }

    @Test
    fun deliveryClaimIsBoundToOwnerSessionAndRequiresAcknowledgement() {
        val store = FakeApprovalStore()
        val service = GlafiraBridgeOwnerApprovalService(store)
        val queued = service.submitVerifiedEnrollment(candidate(emptySet()))!!
        service.approve(queued.approvalId, setOf("project.read"), ownerSession(), 2_000L)

        val unauthenticated = service.claimCredentialDelivery(queued.approvalId, expiredOwnerSession(), 2_001L)
        assertEquals(GlafiraBridgeDeliveryResult.NotApproved, unauthenticated)

        val first = service.claimCredentialDelivery(queued.approvalId, ownerSession("owner-session-0001"), 2_001L)
        assertTrue(first is GlafiraBridgeDeliveryResult.Available)
        val otherSession = service.claimCredentialDelivery(queued.approvalId, ownerSession("owner-session-0002"), 2_001L)
        assertEquals(GlafiraBridgeDeliveryResult.ClaimedByAnotherOwnerSession, otherSession)

        assertTrue(service.acknowledgeCredentialDelivery(queued.approvalId, ownerSession("owner-session-0001"), 2_001L))
        assertEquals(
            GlafiraBridgeDeliveryResult.AlreadyAcknowledged,
            service.claimCredentialDelivery(queued.approvalId, ownerSession("owner-session-0001"), 2_001L)
        )
    }

    @Test
    fun undeliveredCredentialCanBeRecoveredByOwnerAfterRestart() {
        val store = FakeApprovalStore()
        val service = GlafiraBridgeOwnerApprovalService(store)
        val queued = service.submitVerifiedEnrollment(candidate(emptySet()))!!
        service.approve(queued.approvalId, setOf("project.read"), ownerSession(), 2_000L)
        val firstClaim = service.claimCredentialDelivery(
            queued.approvalId, ownerSession("owner-session-0001"), 2_001L
        )
        assertTrue(firstClaim is GlafiraBridgeDeliveryResult.Available)

        // A later owner session can recover the unacknowledged delivery after the lease expires.
        val recoveredSession = ownerSession("owner-session-0002", 20_000L)
        val pending = service.pendingCredentialDeliveries(recoveredSession, 10_001L)

        assertEquals(1, pending.size)
        assertEquals(queued.approvalId, pending.single().approvalId)
        assertEquals(queued.ticketId, pending.single().ticketId)
        val delivery = service.claimCredentialDelivery(
            pending.single().approvalId,
            recoveredSession,
            10_001L
        )
        assertTrue(delivery is GlafiraBridgeDeliveryResult.Available)
    }

    private fun ownerSession(
        id: String = "owner-session-0001",
        expiresAtEpochMs: Long = 10_000L
    ) = GlafiraBridgeOwnerSession(id, expiresAtEpochMs)

    private fun expiredOwnerSession() =
        GlafiraBridgeOwnerSession("owner-session-expired", 1_999L)

    private fun candidate(scopes: Set<String>) = GlafiraBridgePairingCandidate(
        enrollment = GlafiraBridgeVerifiedEnrollment(
            ticketId = "0123456789abcdef",
            clientNonce = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            verifiedAtEpochMs = 1_000L
        ),
        deviceLabel = "Test Android",
        platform = GlafiraBridgeClientPlatform.ANDROID,
        requestedScopes = scopes,
        receivedAtEpochMs = 1_001L
    )

    private class FakeApprovalStore : GlafiraBridgePairingApprovalStore {
        val pending = linkedMapOf<String, GlafiraBridgePendingApproval>()
        var issuedScopes: Set<String> = emptySet()
        var persistedSecret: ByteArray? = null
        var issuedDeviceId: String? = null
        private var claimedBy: String? = null
        private var claimedUntilEpochMs: Long = 0L
        private var acknowledged = false

        override fun enqueue(candidate: GlafiraBridgePendingApproval): Boolean {
            if (candidate.approvalId in pending) return false
            pending[candidate.approvalId] = candidate
            return true
        }

        override fun find(approvalId: String) = pending[approvalId]

        override fun listPending(limit: Int) =
            pending.values.filter { it.status == GlafiraBridgeApprovalStatus.PENDING }.take(limit)

        override fun approveAndIssue(
            approvalId: String,
            deviceId: String,
            hmacSecret: ByteArray,
            ownerSelectedScopes: Set<String>,
            approvedAtEpochMs: Long
        ): GlafiraBridgeApprovalMutation {
            val current = pending[approvalId] ?: return GlafiraBridgeApprovalMutation.NotFound
            if (current.status != GlafiraBridgeApprovalStatus.PENDING) {
                return GlafiraBridgeApprovalMutation.AlreadyDecided
            }
            issuedScopes = ownerSelectedScopes.toSet()
            issuedDeviceId = deviceId
            persistedSecret = hmacSecret.clone()
            pending[approvalId] = current.copy(status = GlafiraBridgeApprovalStatus.APPROVED)
            return GlafiraBridgeApprovalMutation.Approved(deviceId)
        }

        override fun reject(approvalId: String, rejectedAtEpochMs: Long): Boolean {
            val current = pending[approvalId] ?: return false
            if (current.status != GlafiraBridgeApprovalStatus.PENDING) return false
            pending[approvalId] = current.copy(status = GlafiraBridgeApprovalStatus.REJECTED)
            return true
        }

        override fun listUndeliveredDeliveries(limit: Int): List<GlafiraBridgePendingCredentialDelivery> =
            pending.values.filter {
                it.status == GlafiraBridgeApprovalStatus.APPROVED && !acknowledged
            }.take(limit).map {
                GlafiraBridgePendingCredentialDelivery(it.approvalId, it.ticketId, issuedDeviceId!!)
            }

        override fun claimDelivery(
            approvalId: String,
            ownerSessionId: String,
            ownerSessionExpiresAtEpochMs: Long,
            nowEpochMs: Long
        ): GlafiraBridgeDeliveryResult {
            val record = pending[approvalId] ?: return GlafiraBridgeDeliveryResult.NotFound
            if (record.status != GlafiraBridgeApprovalStatus.APPROVED) {
                return GlafiraBridgeDeliveryResult.NotApproved
            }
            if (acknowledged) return GlafiraBridgeDeliveryResult.AlreadyAcknowledged
            if (claimedBy != null && claimedBy != ownerSessionId &&
                claimedUntilEpochMs > nowEpochMs
            ) return GlafiraBridgeDeliveryResult.ClaimedByAnotherOwnerSession
            claimedBy = ownerSessionId
            claimedUntilEpochMs = ownerSessionExpiresAtEpochMs
            return GlafiraBridgeDeliveryResult.Available(
                GlafiraBridgeCredentialDelivery(
                    approvalId,
                    record.ticketId,
                    "device-credential-1234",
                    persistedSecret!!.clone(),
                    issuedScopes
                )
            )
        }

        override fun acknowledgeDelivery(approvalId: String, ownerSessionId: String): Boolean {
            if (approvalId !in pending || claimedBy != ownerSessionId || acknowledged) return false
            acknowledged = true
            persistedSecret?.fill(0)
            persistedSecret = null
            return true
        }
    }
}
