package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exhaustive contract test for the canonical Android Core task transition policy.
 * Keep this matrix aligned with docs/contracts/CORE_TASK_STATE_TRANSITIONS.md.
 */
class GlafiraTaskStateTransitionMatrixTest {
    private val allowed = mapOf(
        GlafiraTaskState.QUEUED to setOf(
            GlafiraTaskState.RUNNING,
            GlafiraTaskState.WAITING_FOR_NETWORK,
            GlafiraTaskState.WAITING_FOR_OWNER,
            GlafiraTaskState.CANCELLED,
            GlafiraTaskState.FAILED
        ),
        GlafiraTaskState.RUNNING to setOf(
            GlafiraTaskState.WAITING_FOR_NETWORK,
            GlafiraTaskState.WAITING_FOR_OWNER,
            GlafiraTaskState.RETRY_SCHEDULED,
            GlafiraTaskState.SUCCEEDED,
            GlafiraTaskState.FAILED,
            GlafiraTaskState.CANCELLED
        ),
        GlafiraTaskState.WAITING_FOR_NETWORK to setOf(
            GlafiraTaskState.QUEUED,
            GlafiraTaskState.RUNNING,
            GlafiraTaskState.FAILED,
            GlafiraTaskState.CANCELLED
        ),
        GlafiraTaskState.WAITING_FOR_OWNER to setOf(
            GlafiraTaskState.QUEUED,
            GlafiraTaskState.FAILED,
            GlafiraTaskState.CANCELLED
        ),
        GlafiraTaskState.RETRY_SCHEDULED to setOf(
            GlafiraTaskState.RUNNING,
            GlafiraTaskState.FAILED,
            GlafiraTaskState.CANCELLED
        ),
        GlafiraTaskState.SUCCEEDED to emptySet(),
        GlafiraTaskState.FAILED to emptySet(),
        GlafiraTaskState.CANCELLED to emptySet()
    )

    @Test
    fun everyStatePairMatchesTheCanonicalTransitionMatrix() {
        val states = GlafiraTaskState.values().toSet()
        assertEquals("The matrix must describe every persisted Core state.", states, allowed.keys)

        states.forEach { from ->
            states.forEach { to ->
                assertEquals(
                    "Unexpected transition $from -> $to",
                    to in allowed.getValue(from),
                    GlafiraTaskPolicy.canTransition(from, to)
                )
            }
        }
    }

    @Test
    fun persistedStateValuesAreUniqueAndRoundTrip() {
        val states = GlafiraTaskState.values()
        assertEquals(states.size, states.map { it.databaseValue }.toSet().size)
        states.forEach { state ->
            assertEquals(state, GlafiraTaskState.fromDatabaseValue(state.databaseValue))
        }
    }
}
