package com.micronizationlab.glafira

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GlafiraCoreDrainBatchTest {
    @Test fun waitingPreviewFailureAndRetryItemsDoNotStallIndependentWork() = runBlocking {
        val statuses = listOf(GlafiraTaskExecutionStatus.WAITING_FOR_OWNER,
            GlafiraTaskExecutionStatus.WAITING_FOR_NETWORK, GlafiraTaskExecutionStatus.RETRY_SCHEDULED,
            GlafiraTaskExecutionStatus.DRY_RUN_PREVIEWED, GlafiraTaskExecutionStatus.FAILED,
            GlafiraTaskExecutionStatus.SUCCEEDED, GlafiraTaskExecutionStatus.IDLE)
        var calls = 0
        assertFalse(drainGlafiraCoreBatch(8, { false }) {
            GlafiraTaskExecutionResult(null, statuses[calls++])
        })
        assertEquals(statuses.size, calls)
    }
    @Test fun boundedBatchRequestsContinuationWithoutUnboundedLoop() = runBlocking {
        var calls = 0
        assertTrue(drainGlafiraCoreBatch(8, { false }) {
            calls++
            GlafiraTaskExecutionResult(null, GlafiraTaskExecutionStatus.SUCCEEDED)
        })
        assertEquals(8, calls)
    }
    @Test fun stoppedWorkerDoesNotClaimAnotherTask() = runBlocking {
        assertFalse(drainGlafiraCoreBatch(8, { true }) {
            throw AssertionError("Cancelled worker must not claim a task")
        })
    }
}
