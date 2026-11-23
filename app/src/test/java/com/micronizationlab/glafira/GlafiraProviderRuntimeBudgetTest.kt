package com.micronizationlab.glafira

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraProviderRuntimeBudgetTest {
    @Test
    fun configuredCostCeilingRejectsUnmeteredCallBeforeDispatch() = runBlocking {
        var dispatched = false

        val result = executeWithProviderRuntimeBudget(
            GlafiraRuntimeBudget(maxCostMicros = 1L),
            GlafiraCoreExecutionLimiter()
        ) {
            dispatched = true
            "must not run"
        }

        assertEquals(
            GlafiraProviderBudgetOutcome.Rejected("RUNTIME_COST_UNMETERED"),
            result
        )
        assertFalse(dispatched)
        val failure = providerBudgetFailure("RUNTIME_COST_UNMETERED")
        assertEquals(AiProviderErrorCategory.BUDGET, failure.error.category)
        assertEquals(AiRequestOutcome.NOT_SENT, failure.error.outcome)
        assertTrue(failure.error.message.contains("стоимость"))
    }

    @Test
    fun ownerTimeLimitStopsSlowProviderWithUnknownOutcome() = runBlocking {
        val never = CompletableDeferred<String>()

        val result = executeWithProviderRuntimeBudget(
            GlafiraRuntimeBudget(),
            GlafiraCoreExecutionLimiter(),
            timeoutMillis = 10L
        ) { never.await() }

        assertEquals(
            GlafiraProviderBudgetOutcome.Rejected("RUNTIME_TIME_LIMIT_EXCEEDED"),
            result
        )
        val failure = providerBudgetFailure("RUNTIME_TIME_LIMIT_EXCEEDED")
        assertEquals(AiProviderErrorCategory.TIMEOUT, failure.error.category)
        assertEquals(AiRequestOutcome.UNKNOWN, failure.error.outcome)
        assertFalse(failure.error.canRetryAutomatically)
    }

    @Test
    fun providerCallsShareConfiguredConcurrencySlot() = runBlocking {
        val limiter = GlafiraCoreExecutionLimiter()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        val first = async {
            executeWithProviderRuntimeBudget(GlafiraRuntimeBudget(maxConcurrentTasks = 1), limiter) {
                val current = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, current) }
                firstStarted.complete(Unit)
                releaseFirst.await()
                active.decrementAndGet()
            }
        }
        firstStarted.await()
        val second = async {
            executeWithProviderRuntimeBudget(GlafiraRuntimeBudget(maxConcurrentTasks = 1), limiter) {
                val current = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, current) }
                secondStarted.complete(Unit)
                active.decrementAndGet()
            }
        }
        yield()
        assertFalse(secondStarted.isCompleted)
        releaseFirst.complete(Unit)
        withTimeout(2_000L) { secondStarted.await() }
        first.await()
        second.await()

        assertEquals(1, maximum.get())
    }

    @Test
    fun providerCallInsideCorePermitDoesNotDeadlockOrConsumeSecondSlot() = runBlocking {
        val limiter = GlafiraCoreExecutionLimiter()

        val value = withTimeout(2_000L) {
            limiter.withPermit(1) {
                executeWithProviderRuntimeBudget(GlafiraRuntimeBudget(maxConcurrentTasks = 1), limiter) {
                    "nested provider result"
                }
            }
        }

        assertEquals(
            GlafiraProviderBudgetOutcome.Completed("nested provider result"),
            value
        )
    }
}
