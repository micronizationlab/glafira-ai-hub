package com.micronizationlab.glafira

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
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
    @Test fun parallelDrainRunsConfiguredNumberOfLanes() = runBlocking {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val drain = async {
            drainGlafiraCoreParallelBatch(3, 1, { false }) {
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                if (current == 3) allStarted.complete(Unit)
                try {
                    release.await()
                    GlafiraTaskExecutionResult(null, GlafiraTaskExecutionStatus.SUCCEEDED)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        withTimeout(5_000L) { allStarted.await() }
        assertEquals(3, maximumActive.get())
        release.complete(Unit)
        assertTrue(withTimeout(5_000L) { drain.await() })
        assertEquals(0, active.get())
    }

}
