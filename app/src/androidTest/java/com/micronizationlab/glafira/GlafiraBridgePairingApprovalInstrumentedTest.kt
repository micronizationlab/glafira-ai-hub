package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.SecureRandom
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val unknownScope = service.approve(
            pendingApproval.approvalId,
            setOf("admin.root"),
            ownerSession,
            now + 4L
        )
        assertEquals(GlafiraBridgeApprovalMutation.InvalidScopes, unknownScope)

        val approved = service.approve(
            pendingApproval.approvalId,
            setOf("project.read"),
            ownerSession,
            now + 5L
        )
        val deviceId = (approved as? GlafiraBridgeApprovalMutation.Approved)?.deviceId
            ?: error("Owner approval did not issue a paired device credential.")

        val claimed = service.claimCredentialDelivery(
            pendingApproval.approvalId,
            ownerSession,
            now + 6L
        )
        val delivery = (claimed as? GlafiraBridgeDeliveryResult.Available)?.delivery
            ?: error("Owner credential delivery could not be claimed.")

        val storedCredential = store.load(deviceId)
            ?: error("Issued device credential could not be decrypted from Android Keystore storage.")
        assertArrayEquals(delivery.hmacSecret, storedCredential.hmacSecret)
        assertEquals(setOf("project.read"), delivery.grantedScopes)
        assertEquals(delivery.grantedScopes, storedCredential.scopes)

        // Core uses a separate SQLite helper instance, but it must read the same
        // owner-approved credential record that HTTPS admission just issued.
        val coreCredentialReader = AndroidKeystoreBridgePairingApprovalStore(context)
        val coreTaskStore = GlafiraTaskStore(context)
        try {
            val grantSource = GlafiraCompositeTaskGrantSource(
                GlafiraCoreOwnerApprovalStore(coreTaskStore),
                coreCredentialReader
            )
            val bridgeTask = GlafiraTask(
                taskId = "bridge-task-authorization-001",
                projectId = "default",
                taskType = "project.read",
                payload = """{"actionSummary":"Read project default"}""",
                origin = "android-desktop-bridge:$deviceId",
                createdAtMillis = now + 5L,
                updatedAtMillis = now + 5L,
                idempotencyKey = "bridge-idempotency-001",
                requiredScopes = setOf("project.read")
            )
            assertTrue(grantSource.grantedScopes(bridgeTask).contains("project.read"))
        } finally {
            coreTaskStore.close()
            coreCredentialReader.close()
        }

        assertTrue(service.acknowledgeCredentialDelivery(
            pendingApproval.approvalId, ownerSession, now + 7L
        ))
        assertTrue(
            service.claimCredentialDelivery(pendingApproval.approvalId, ownerSession, now + 8L)
                is GlafiraBridgeDeliveryResult.AlreadyAcknowledged
        )

        assertFalse(invitation.toString().contains(invitation.pairingCapability))
        assertFalse(delivery.toString().contains(delivery.hmacSecret.joinToString("") { "%02x".format(it) }))
        delivery.wipeSecret()
        storedCredential.hmacSecret.fill(0)
    }

    @Test
    fun expirySweepCommitsWhenPendingApprovalQueueIsFull() {
        val now = System.currentTimeMillis()
        repeat(GlafiraBridgeOwnerApprovalService.MAX_PENDING_APPROVALS) { index ->
            val suffix = index.toString().padStart(4, '0')
            assertTrue(
                store.enqueue(
                    GlafiraBridgePendingApproval(
                        approvalId = "active_approval_$suffix",
                        ticketId = "active_ticket_$suffix",
                        clientNonce = "n".repeat(43),
                        deviceLabel = "GLAFIRA Desktop",
                        platform = GlafiraBridgeClientPlatform.DESKTOP,
                        requestedScopes = setOf("project.read"),
                        receivedAtEpochMs = now,
                        status = GlafiraBridgeApprovalStatus.PENDING
                    )
                )
            )
        }

        val staleId = "stale_approval_identifier"
        val staleTicket = "stale_ticket_identifier"
        val staleValues = android.content.ContentValues().apply {
            put("approval_id", staleId)
            put("ticket_id", staleTicket)
            put("client_nonce", "s".repeat(43))
            put("device_label", "Old Desktop")
            put("platform", GlafiraBridgeClientPlatform.DESKTOP.name)
            put("requested_scopes", "project.read")
            put("received_at", now - 24L * 60L * 60L * 1000L - 1L)
            put("status", GlafiraBridgeApprovalStatus.PENDING.name)
        }
        assertTrue(store.writableDatabase.insertOrThrow("pairing_approvals", null, staleValues) >= 0L)

        val rejected = store.enqueue(
            GlafiraBridgePendingApproval(
                approvalId = "new_approval_identifier",
                ticketId = "new_ticket_identifier",
                clientNonce = "x".repeat(43),
                deviceLabel = "New Desktop",
                platform = GlafiraBridgeClientPlatform.DESKTOP,
                requestedScopes = setOf("project.read"),
                receivedAtEpochMs = now,
                status = GlafiraBridgeApprovalStatus.PENDING
            )
        )
        assertFalse(rejected)
        assertEquals(
            GlafiraBridgeApprovalStatus.EXPIRED,
            store.find(staleId)?.status
        )
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
