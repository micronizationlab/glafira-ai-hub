package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraDeviceRevocationInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: AndroidKeystoreBridgePairingApprovalStore
    private val device = "device-revocation-test-001"
    private val approval = "approval-revocation-test-001"
    @Before fun setUp() {
        context.deleteDatabase("glafira_bridge_approvals.db")
        store = AndroidKeystoreBridgePairingApprovalStore(context)
    }
    @After fun tearDown() {
        store.close()
        context.deleteDatabase("glafira_bridge_approvals.db")
    }
    private fun issue(now: Long) {
        assertTrue(store.enqueue(GlafiraBridgePendingApproval(
            approvalId = approval, ticketId = "ticket-revocation-test-001",
            clientNonce = "nonce-revocation-instrumented-test-001", deviceLabel = "Desktop test",
            platform = GlafiraBridgeClientPlatform.DESKTOP,
            requestedScopes = setOf("project.read"), receivedAtEpochMs = now, status = GlafiraBridgeApprovalStatus.PENDING)))
        assertTrue(store.approveAndIssue(approval, device, ByteArray(32) { 7 },
            setOf("project.read"), now + 1) is GlafiraBridgeApprovalMutation.Approved)
    }
    @Test fun revocationSurvivesRestartAndRemovesUndeliveredSecrets() {
        val now = System.currentTimeMillis()
        issue(now)
        assertEquals(device, store.listPairedDevices().single().deviceId)
        val session = GlafiraBridgeOwnerSession("owner-revocation-test-001", now + 60_000)
        assertTrue(store.revokeDevice(device, session, now + 2))
        store.close()
        store = AndroidKeystoreBridgePairingApprovalStore(context)
        assertNull(store.load(device))
        assertTrue(store.listPairedDevices().isEmpty())
        assertEquals(0, store.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM credential_deliveries WHERE device_id=?", arrayOf(device)
        ).use { it.moveToFirst(); it.getInt(0) })
        assertTrue(store.revokeDevice(device, session, now + 3)) // ACK loss is harmless.
    }
    @Test fun expiredOwnerSessionCannotRevoke() {
        val now = System.currentTimeMillis()
        issue(now)
        assertFalse(store.revokeDevice(device, GlafiraBridgeOwnerSession("expired-session-test-001", now), now + 2))
        store.load(device)?.let { it.hmacSecret.fill(0) } ?: fail("Credential was unexpectedly revoked")
    }
    @Test fun schemaOneMigrationPreservesExistingCredentials() {
        val now = System.currentTimeMillis()
        issue(now)
        store.writableDatabase.execSQL("DROP TABLE device_revocations")
        store.writableDatabase.version = 1
        store.close()
        store = AndroidKeystoreBridgePairingApprovalStore(context)
        assertEquals(2, store.readableDatabase.version)
        store.load(device)?.let { it.hmacSecret.fill(0) } ?: fail("Migration lost credential")
        assertTrue(store.revokeDevice(device, GlafiraBridgeOwnerSession("migration-session-test-001", now + 60_000), now + 2))
    }
    @Test fun separateReaderRejectsReconnectedDeviceAndTombstoneCannotBeReissued() {
        val now = System.currentTimeMillis()
        issue(now)
        AndroidKeystoreBridgePairingApprovalStore(context).use { reader ->
            reader.load(device)?.let { it.hmacSecret.fill(0) } ?: fail("Missing issued credential")
            val session = GlafiraBridgeOwnerSession("owner-second-reader-test-001", now + 60_000)
            assertTrue(store.revokeDevice(device, session, now + 2))
            assertNull(reader.load(device))
            val admission = GlafiraBridgeRequestAdmission(reader, object : GlafiraBridgeReplayStore {
                override fun claim(authenticatedDeviceId: String, idempotencyKey: String,
                    expiresAtEpochMs: Long, nowEpochMs: Long): BridgeReplayClaim =
                    error("A revoked device must be rejected before replay admission")
            })
            val response = admission.admit(GlafiraBridgeRequest(
                1, "request-revocation-test-001", "operation-revocation-test-001", device,
                "default", GlafiraBridgeOperation.PROJECT_READ, now, now + 60_000, emptyMap()), now + 3)
            assertEquals("DEVICE_NOT_PAIRED", response.errorCode)
            val newApproval = "approval-revocation-test-002"
            assertTrue(store.enqueue(GlafiraBridgePendingApproval(newApproval,
                "ticket-revocation-test-002", "nonce-revocation-instrumented-test-002", "New desktop",
                GlafiraBridgeClientPlatform.DESKTOP, setOf("project.read"), now + 4,
                GlafiraBridgeApprovalStatus.PENDING)))
            assertFalse(store.approveAndIssue(newApproval, device, ByteArray(32) { 8 },
                setOf("project.read"), now + 5) is GlafiraBridgeApprovalMutation.Approved)
            assertTrue(store.approveAndIssue(newApproval, "device-revocation-test-002", ByteArray(32) { 9 },
                setOf("project.read"), now + 6) is GlafiraBridgeApprovalMutation.Approved)
            assertNull(reader.load(device))
            assertEquals("device-revocation-test-002", reader.listPairedDevices().single().deviceId)
        }
    }

}
