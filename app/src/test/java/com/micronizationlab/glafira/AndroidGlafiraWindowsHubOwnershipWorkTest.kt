package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidGlafiraWindowsHubOwnershipWorkTest {
    private val now = 1_800_000_000_000L

    @Test
    fun pairedStateCreatesOneScopedWorkItem() {
        val enqueued = mutableListOf<Pair<String, String>>()
        val admission = GlafiraWindowsHubOwnershipWorkAdmission(
            pairingState = state(GlafiraWindowsHubPairingStatus.PAIRED),
            enqueue = { project, transfer -> enqueued += project to transfer }
        )

        assertEquals(
            GlafiraWindowsHubOwnershipScheduleResult.ENQUEUED,
            admission.schedule("project-0001", "transfer-0001", now)
        )
        assertEquals(listOf("project-0001" to "transfer-0001"), enqueued)
    }

    @Test
    fun nonPairedStatesNeverCreateDurableWork() {
        val expected = mapOf(
            GlafiraWindowsHubPairingStatus.UNPAIRED to
                GlafiraWindowsHubOwnershipScheduleResult.BLOCKED_UNPAIRED,
            GlafiraWindowsHubPairingStatus.EXPIRED to
                GlafiraWindowsHubOwnershipScheduleResult.BLOCKED_EXPIRED,
            GlafiraWindowsHubPairingStatus.REVOKED to
                GlafiraWindowsHubOwnershipScheduleResult.BLOCKED_REVOKED,
            GlafiraWindowsHubPairingStatus.INVALID to
                GlafiraWindowsHubOwnershipScheduleResult.BLOCKED_INVALID
        )
        expected.forEach { (status, result) ->
            var enqueued = false
            val admission = GlafiraWindowsHubOwnershipWorkAdmission(
                pairingState = state(status),
                enqueue = { _, _ -> enqueued = true }
            )
            assertEquals(result, admission.schedule("project-0001", "transfer-0001", now))
            assertFalse(enqueued)
        }

        val failing = GlafiraWindowsHubOwnershipWorkAdmission(
            pairingState = GlafiraWindowsHubPairingStateProvider {
                throw IllegalStateException("corrupt state")
            },
            enqueue = { _, _ -> throw AssertionError("must not enqueue") }
        )
        assertEquals(
            GlafiraWindowsHubOwnershipScheduleResult.BLOCKED_INVALID,
            failing.schedule("project-0001", "transfer-0001", now)
        )
    }

    @Test
    fun invalidIdentifiersFailBeforeStateOrQueueAndWorkNameLeaksNoIdentifiers() {
        var stateReads = 0
        val admission = GlafiraWindowsHubOwnershipWorkAdmission(
            pairingState = GlafiraWindowsHubPairingStateProvider {
                stateReads += 1
                GlafiraWindowsHubPairingState(GlafiraWindowsHubPairingStatus.PAIRED)
            },
            enqueue = { _, _ -> throw AssertionError("must not enqueue") }
        )
        assertThrows(IllegalArgumentException::class.java) {
            admission.schedule("short", "transfer-0001", now)
        }
        assertEquals(0, stateReads)

        val name = AndroidGlafiraWindowsHubOwnershipScheduler.uniqueWorkName(
            "private-project-0001",
            "private-transfer-0001"
        )
        assertFalse(name.contains("private-project"))
        assertFalse(name.contains("private-transfer"))
        assertEquals(94, name.length)
        assertEquals(
            "glafira-windows-hub-ownership",
            AndroidGlafiraWindowsHubOwnershipScheduler.OWNERSHIP_WORK_TAG
        )
    }

    private fun state(status: GlafiraWindowsHubPairingStatus) =
        GlafiraWindowsHubPairingStateProvider {
            GlafiraWindowsHubPairingState(status)
        }
}
