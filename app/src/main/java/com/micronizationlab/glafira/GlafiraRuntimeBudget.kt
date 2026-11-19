package com.micronizationlab.glafira

/**
 * Conservative per-task execution limits enforced by the Core executor.
 *
 * Current Core handlers are single typed operations, so one handler invocation is one
 * execution step. Multi-step agents must use a future step-aware execution context rather
 * than increasing maxSteps on this single-operation executor.
 */
data class GlafiraRuntimeBudget(
    val maxWallClockMillis: Long = 60_000L,
    val maxSteps: Int = 1,
    val maxRetries: Int = 5,
    val maxConcurrentTasks: Int = 2,
    val maxCostMicros: Long? = null
) {
    init {
        require(maxWallClockMillis in 1_000L..86_400_000L) {
            "Runtime wall-clock budget must be between 1 second and 24 hours."
        }
        require(maxSteps == 1) {
            "This Core executor currently supports exactly one typed handler step per task."
        }
        require(maxRetries in 0..20) { "Runtime retry budget must be between 0 and 20." }
        require(maxConcurrentTasks in 1..MAX_CONCURRENT_TASKS) {
            "Core concurrency must be between 1 and $MAX_CONCURRENT_TASKS tasks."
        }
        require(maxCostMicros == null || maxCostMicros >= 0L) {
            "Runtime cost budget cannot be negative."
        }
    }

    companion object {
        const val MAX_CONCURRENT_TASKS = 8
    }
}

/** Cost is deliberately supplied by a trusted provider adapter, never by model output. */
fun interface GlafiraTaskCostMeter {
    fun measuredCostMicros(taskId: String): Long?
}

data class GlafiraRuntimeBudgetDecision(
    val allowed: Boolean,
    val reason: String? = null,
    val timeoutMillis: Long = 0L
)

internal object GlafiraRuntimeBudgetPolicy {
    fun evaluate(
        task: GlafiraTask,
        nowMillis: Long,
        budgetStartedAtMillis: Long,
        budget: GlafiraRuntimeBudget,
        measuredCostMicros: Long?
    ): GlafiraRuntimeBudgetDecision {
        val elapsed = (nowMillis - budgetStartedAtMillis).coerceAtLeast(0L)
        val wallRemaining = budget.maxWallClockMillis - elapsed
        if (wallRemaining <= 0L) return GlafiraRuntimeBudgetDecision(false, "RUNTIME_TIME_LIMIT_EXCEEDED")
        if (task.deadlineAtMillis != null && task.deadlineAtMillis <= nowMillis) {
            return GlafiraRuntimeBudgetDecision(false, "TASK_DEADLINE_EXPIRED")
        }
        // claimNext increments attemptCount before this check; the first attempt is not a retry.
        if (task.attemptCount > budget.maxRetries + 1) {
            return GlafiraRuntimeBudgetDecision(false, "RUNTIME_RETRY_LIMIT_EXCEEDED")
        }
        if (budget.maxCostMicros != null) {
            if (measuredCostMicros == null) {
                return GlafiraRuntimeBudgetDecision(false, "RUNTIME_COST_UNMETERED")
            }
            if (measuredCostMicros < 0L) {
                return GlafiraRuntimeBudgetDecision(false, "RUNTIME_COST_METER_INVALID")
            }
            if (measuredCostMicros > budget.maxCostMicros) {
                return GlafiraRuntimeBudgetDecision(false, "RUNTIME_COST_LIMIT_EXCEEDED")
            }
        }
        val deadlineRemaining = task.deadlineAtMillis?.minus(nowMillis) ?: Long.MAX_VALUE
        val timeout = minOf(budget.maxWallClockMillis, wallRemaining, deadlineRemaining)
        if (timeout <= 0L) return GlafiraRuntimeBudgetDecision(false, "TASK_DEADLINE_EXPIRED")
        return GlafiraRuntimeBudgetDecision(true, timeoutMillis = timeout)
    }
}
