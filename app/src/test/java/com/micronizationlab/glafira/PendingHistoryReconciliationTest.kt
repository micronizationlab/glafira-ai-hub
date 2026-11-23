package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingHistoryReconciliationTest {
    private val pending = PendingMessage(
        projectId = "project-a",
        agentName = "agent-a",
        text = "owner question",
        time = "12:00",
        id = "operation-001",
        date = "2026-11-23",
        state = PendingDeliveryState.RESULT_READY,
        result = "durable result"
    )

    @Test
    fun exactUserAndResultReplayIsIdempotent() {
        val initial = reconcilePendingUserHistory(emptyList(), pending)
        val withResult = reconcilePendingResultHistory(
            initial,
            pending,
            requireNotNull(pending.result),
            "12:01",
            "2026-11-23"
        )

        assertEquals(initial, reconcilePendingUserHistory(initial, pending))
        assertEquals(
            withResult,
            reconcilePendingResultHistory(
                withResult,
                pending,
                requireNotNull(pending.result),
                "13:59",
                "2026-11-24"
            )
        )
        assertEquals(2, withResult.size)
    }

    @Test
    fun conflictingUserTextForSameOperationFailsClosed() {
        val forged = listOf(Msg("different question", true, "12:00", operationId = pending.id))

        val error = assertThrows(IllegalStateException::class.java) {
            reconcilePendingUserHistory(forged, pending)
        }

        assertEquals("PENDING_HISTORY_USER_CONFLICT", error.message)
    }

    @Test
    fun conflictingResultForSameOperationFailsClosed() {
        val forged = listOf(Msg("different result", false, "12:01", operationId = pending.id))

        val error = assertThrows(IllegalStateException::class.java) {
            reconcilePendingResultHistory(
                forged,
                pending,
                requireNotNull(pending.result),
                "12:02",
                "2026-11-23"
            )
        }

        assertEquals("PENDING_HISTORY_RESULT_CONFLICT", error.message)
    }
}
