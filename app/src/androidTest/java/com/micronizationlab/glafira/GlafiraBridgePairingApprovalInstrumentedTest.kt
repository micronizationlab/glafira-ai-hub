package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraBridgePairingApprovalInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: AndroidKeystoreBridgePairingApprovalStore

    @Before
    fun setUp() {
        context.deleteDatabase("glafira_bridge_approvals.db")
        store = AndroidKeystoreBridgePairingApprovalStore(context)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("glafira_bridge_approvals.db")
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
