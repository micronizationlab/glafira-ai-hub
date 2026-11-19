package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.SecureRandom
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraBridgePairingApprovalInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: AndroidKeystoreBridgePairingApprovalStore
    private lateinit var ticketStore: AndroidKeystoreBridgePairingTicketStore

    @Before
    fun setUp() {
        context.deleteDatabase("glafira_bridge_approvals.db")
        context.deleteDatabase("glafira_bridge_pairing.db")
        store = AndroidKeystoreBridgePairingApprovalStore(context)
        ticketStore = AndroidKeystoreBridgePairingTicketStore(context)
    }

    @After
    fun tearDown() {
        store.close()
        ticketStore.close()
        context.deleteDatabase("glafira_bridge_approvals.db")
        context.deleteDatabase("glafira_bridge_pairing.db")
    }

    @Test
    fun fullPairingProofApprovalCredentialDeliveryAndAcknowledgementRoundTrip() {
        val now = System.currentTimeMillis()
        val protocol = GlafiraBridgePairingProtocol(ticketStore)
        val invitation = protocol.issueInvitation(now)
        val nonceBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val clientNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        nonceBytes.fill(0)
        val proof = GlafiraBridgePairingProtocol.createClientProof(
            invitation.ticketId,
            invitation.pairingCapability,
            clientNonce
        )

        val invalidProof = protocol.verifyAndConsume(
            invitation.ticketId, clientNonce, "00".repeat(32), now + 1L
        )
        assertTrue(invalidProof is GlafiraBridgePairingResult.Rejected)

        val verification = protocol.verifyAndConsume(
            invitation.ticketId, clientNonce, proof, now + 2L
        )
        val enrollment = (verification as? GlafiraBridgePairingResult.Verified)?.enrollment
            ?: error("Valid one-time pairing proof was not accepted.")

        val service = GlafiraBridgeOwnerApprovalService(store)
        val pending = service.submitVerifiedEnrollment(
            GlafiraBridgePairingCandidate(
                enrollment = enrollment,
                deviceLabel = "GLAFIRA Desktop",
                platform = GlafiraBridgeClientPlatform.DESKTOP,
                requestedScopes = setOf("project.read"),
                receivedAtEpochMs = now + 3L
            )
        )
        val pendingApproval = pending ?: error("Verified enrollment was not queued for owner approval.")

        val ownerSession = GlafiraBridgeOwnerSession(
            sessionId = "owner-session-acceptance-001",
            expiresAtEpochMs = now + 60_000L
        )
        val approved = service.approve(
            pendingApproval.approvalId,
            setOf("project.read"),
            ownerSession,
            now + 4L
        )
        val deviceId = (approved as? GlafiraBridgeApprovalMutation.Approved)?.deviceId
            ?: error("Owner approval did not issue a paired device credential.")

        val claimed = service.claimCredentialDelivery(
            pendingApproval.approvalId,
            ownerSession,
            now + 5L
        )
        val delivery = (claimed as? GlafiraBridgeDeliveryResult.Available)?.delivery
            ?: error("Owner credential delivery could not be claimed.")

        val storedCredential = store.load(deviceId)
            ?: error("Issued device credential could not be decrypted from Android Keystore storage.")
        assertArrayEquals(delivery.hmacSecret, storedCredential.hmacSecret)
        assertEquals(setOf("project.read"), delivery.grantedScopes)
        assertEquals(delivery.grantedScopes, storedCredential.grantedScopes)

        assertTrue(service.acknowledgeCredentialDelivery(
            pendingApproval.approvalId, ownerSession, now + 6L
        ))
        assertTrue(
            service.claimCredentialDelivery(pendingApproval.approvalId, ownerSession, now + 7L)
                is GlafiraBridgeDeliveryResult.AlreadyAcknowledged
        )

        delivery.hmacSecret.fill(0)
        storedCredential.hmacSecret.fill(0)
    }

    @Test
    fun stalePendingPairingApprovalExpiresAndCannotBeListed() {
        val now = System.currentTimeMillis()
        val approval = GlafiraBridgePendingApproval(
            approvalId = "approval_identifier_001",
            ticketId = "ticket_identifier_001",
            clientNonce = "n".repeat(43),
            deviceLabel = "GLAFIRA Desktop",
            platform = GlafiraBridgeClientPlatform.DESKTOP,
            requestedScopes = setOf("project.read"),
            receivedAtEpochMs = now - 24L * 60L * 60L * 1000L - 1L,
            status = GlafiraBridgeApprovalStatus.PENDING
        )

        assertTrue(store.enqueue(approval))
        assertTrue(store.listPending().isEmpty())
        assertEquals(
            GlafiraBridgeApprovalStatus.EXPIRED,
            store.find(approval.approvalId)?.status
        )
    }
}
