package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraRuntimeBudgetTest {
    private fun task(
        attempts: Int = 1,
        deadline: Long? = null,
        created: Long = 1_000L
    ) = GlafiraTask(
        taskId = "task-1",
        projectId = "project-1",
        taskType = "project.read",
        payload = "{}",
        origin = "test",
        state = GlafiraTaskState.RUNNING,
        createdAtMillis = created,
        updatedAtMillis = created,
        attemptCount = attempts,
        deadlineAtMillis = deadline,
        idempotencyKey = "idem-1"
    )

    @Test
    fun allowsTaskWithinWallClockAndDeadline() {
        val result = GlafiraRuntimeBudgetPolicy.evaluate(
            task = task(deadline = 20_000L),
            nowMillis = 3_000L,
            budgetStartedAtMillis = 1_000L,
            budget = GlafiraRuntimeBudget(maxWallClockMillis = 10_000L),
            measuredCostMicros = null
        )
        assertTrue(result.allowed)
        assertEquals(8_000L, result.timeoutMillis)
    }

    @Test
    fun rejectsExpiredWallClockBudget() {
        val result = GlafiraRuntimeBudgetPolicy.evaluate(
            task = task(),
            nowMillis = 12_000L,
            budgetStartedAtMillis = 1_000L,
            budget = GlafiraRuntimeBudget(maxWallClockMillis = 10_000L),
            measuredCostMicros = null
        )
        assertFalse(result.allowed)
        assertEquals("RUNTIME_TIME_LIMIT_EXCEEDED", result.reason)
    }

    @Test
    fun rejectsExpiredTaskDeadline() {
        val result = GlafiraRuntimeBudgetPolicy.evaluate(
            task = task(deadline = 2_000L),
            nowMillis = 2_000L,
            budgetStartedAtMillis = 1_000L,
            budget = GlafiraRuntimeBudget(),
            measuredCostMicros = null
        )
        assertFalse(result.allowed)
        assertEquals("TASK_DEADLINE_EXPIRED", result.reason)
    }

    @Test
    fun rejectsRetryBeyondConfiguredLimit() {
        val result = GlafiraRuntimeBudgetPolicy.evaluate(
            task = task(attempts = 3),
            nowMillis = 2_000L,
            budgetStartedAtMillis = 1_000L,
            budget = GlafiraRuntimeBudget(maxRetries = 1),
            measuredCostMicros = null
        )
        assertFalse(result.allowed)
        assertEquals("RUNTIME_RETRY_LIMIT_EXCEEDED", result.reason)
    }

    @Test
    fun costCeilingFailsClosedWhenUsageIsUnknown() {
        val result = GlafiraRuntimeBudgetPolicy.evaluate(
            task = task(),
            nowMillis = 2_000L,
            budgetStartedAtMillis = 1_000L,
            budget = GlafiraRuntimeBudget(maxCostMicros = 500L),
            measuredCostMicros = null
        )
        assertFalse(result.allowed)
        assertEquals("RUNTIME_COST_UNMETERED", result.reason)
    }

    @Test
    fun rejectsCostAboveCeiling() {
        val result = GlafiraRuntimeBudgetPolicy.evaluate(
            task = task(),
            nowMillis = 2_000L,
            budgetStartedAtMillis = 1_000L,
            budget = GlafiraRuntimeBudget(maxCostMicros = 500L),
            measuredCostMicros = 501L
        )
        assertFalse(result.allowed)
        assertEquals("RUNTIME_COST_LIMIT_EXCEEDED", result.reason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun doesNotAllowUninstrumentedMultiStepExecution() {
        GlafiraRuntimeBudget(maxSteps = 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun doesNotAllowUnboundedConcurrency() {
        GlafiraRuntimeBudget(maxConcurrentTasks = 2)
    }
    @Test
    fun allowsInitialAttemptAndConfiguredRetryBoundary() {
        val result = GlafiraRuntimeBudgetPolicy.evaluate(
            task = task(attempts = 2),
            nowMillis = 2_000L,
            budgetStartedAtMillis = 1_000L,
            budget = GlafiraRuntimeBudget(maxRetries = 1),
            measuredCostMicros = null
        )
        assertTrue(result.allowed)
    }

    @Test
    fun deadlineShortensHandlerTimeout() {
        val result = GlafiraRuntimeBudgetPolicy.evaluate(
            task = task(deadline = 4_000L),
            nowMillis = 3_000L,
            budgetStartedAtMillis = 1_000L,
            budget = GlafiraRuntimeBudget(maxWallClockMillis = 10_000L),
            measuredCostMicros = null
        )
        assertTrue(result.allowed)
        assertEquals(1_000L, result.timeoutMillis)
    }

    @Test
    fun rejectsNegativeMeasuredCostWhenCostCeilingIsEnabled() {
        val result = GlafiraRuntimeBudgetPolicy.evaluate(
            task = task(),
            nowMillis = 2_000L,
            budgetStartedAtMillis = 1_000L,
            budget = GlafiraRuntimeBudget(maxCostMicros = 500L),
            measuredCostMicros = -1L
        )
        assertFalse(result.allowed)
        assertEquals("RUNTIME_COST_METER_INVALID", result.reason)
    }


    @Test
    fun allowsMeasuredCostExactlyAtCeiling() {
        val result = GlafiraRuntimeBudgetPolicy.evaluate(
            task = task(), nowMillis = 2_000L, budgetStartedAtMillis = 1_000L,
            budget = GlafiraRuntimeBudget(maxCostMicros = 500L), measuredCostMicros = 500L
        )
        assertTrue(result.allowed)
    }

    @Test
    fun allowsFirstAttemptWhenRetryBudgetIsZero() {
        val result = GlafiraRuntimeBudgetPolicy.evaluate(
            task(attempts = 1), nowMillis = 2_000L, budgetStartedAtMillis = 1_000L,
            budget = GlafiraRuntimeBudget(maxRetries = 0), measuredCostMicros = null
        )
        assertTrue(result.allowed)
    }

    @Test
    fun rejectsSecondAttemptWhenRetryBudgetIsZero() {
        val result = GlafiraRuntimeBudgetPolicy.evaluate(
            task(attempts = 2), nowMillis = 2_000L, budgetStartedAtMillis = 1_000L,
            budget = GlafiraRuntimeBudget(maxRetries = 0), measuredCostMicros = null
        )
        assertFalse(result.allowed)
        assertEquals("RUNTIME_RETRY_LIMIT_EXCEEDED", result.reason)
    }
}
