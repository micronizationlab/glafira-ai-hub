package com.micronizationlab.glafira

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed class GlafiraTaskExecutionOutcome {
    data class Succeeded(val summary: String? = null) : GlafiraTaskExecutionOutcome()
    data class RetryableFailure(val reason: String) : GlafiraTaskExecutionOutcome()
    data class PermanentFailure(val reason: String) : GlafiraTaskExecutionOutcome()
    data object WaitingForNetwork : GlafiraTaskExecutionOutcome()
    data object WaitingForOwner : GlafiraTaskExecutionOutcome()
}

/** A handler performs one allow-listed Core operation and must honor the task idempotency key. */
fun interface GlafiraTaskHandler {
    suspend fun execute(task: GlafiraTask): GlafiraTaskExecutionOutcome
}

enum class GlafiraTaskExecutionStatus {
    IDLE, SUCCEEDED, RETRY_SCHEDULED, WAITING_FOR_NETWORK, WAITING_FOR_OWNER, FAILED
}

data class GlafiraTaskExecutionResult(
    val taskId: String?,
    val status: GlafiraTaskExecutionStatus,
    val detail: String? = null
)

/**
 * Single execution path for every GLAFIRA entry point. No shell, reflection, arbitrary URL
 * or arbitrary code execution is exposed. Authorization runs immediately before dispatch.
 */
class GlafiraCoreTaskExecutor(
    private val queue: GlafiraTaskQueue,
    private val authorizer: GlafiraTaskAuthorizer,
    handlers: Collection<Pair<String, GlafiraTaskHandler>>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = 6,
    private val baseRetryDelayMillis: Long = 5_000L,
    private val maxRetryDelayMillis: Long = 15 * 60_000L,
    private val runtimeBudget: () -> GlafiraRuntimeBudget = { GlafiraRuntimeBudget() },
    private val costMeter: GlafiraTaskCostMeter? = null
) {
    private val executionMutex = Mutex()
    private val handlerMap = handlers.toMap()

    init {
        require(maxAttempts in 1..20)
        require(baseRetryDelayMillis > 0L)
        require(maxRetryDelayMillis >= baseRetryDelayMillis)
        require(handlerMap.size == handlers.size) { "Duplicate GLAFIRA task handler registration." }
        require(handlerMap.keys.all { GlafiraToolRegistry.find(it) != null }) {
            "Only registered Core tools may have task handlers."
        }
    }

    suspend fun executeNext(): GlafiraTaskExecutionResult =
        GlafiraCoreMaintenanceGate.withExclusive {
            withContext(dispatcher) {
                executionMutex.withLock {
                    val now = clock()
                    val task = queue.claimNext(now)
                        ?: return@withLock GlafiraTaskExecutionResult(null, GlafiraTaskExecutionStatus.IDLE)
                    executeClaimed(task, now)
                }
            }
        }

    private suspend fun executeClaimed(
        task: GlafiraTask,
        nowMillis: Long
    ): GlafiraTaskExecutionResult {
        val authorization = try {
            authorizer.authorize(task, nowMillis)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return fail(task, "AUTHORIZATION_ERROR")
        }

        when (authorization.status) {
            GlafiraTaskAuthorizationStatus.DENIED ->
                return fail(task, authorization.reason ?: "AUTHORIZATION_DENIED")
            GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER -> {
                val currentState = queue.getTask(task.taskId)?.state
                val changed = when (currentState) {
                    GlafiraTaskState.WAITING_FOR_OWNER -> true
                    GlafiraTaskState.RUNNING -> queue.transition(
                        task.taskId, setOf(GlafiraTaskState.RUNNING),
                        GlafiraTaskState.WAITING_FOR_OWNER, nowMillis,
                        lastError = authorization.reason
                    )
                    else -> false
                }
                return GlafiraTaskExecutionResult(
                    task.taskId,
                    if (changed) GlafiraTaskExecutionStatus.WAITING_FOR_OWNER
                    else GlafiraTaskExecutionStatus.FAILED,
                    authorization.reason
                )
            }
            GlafiraTaskAuthorizationStatus.AUTHORIZED -> Unit
        }

        val handler = handlerMap[task.taskType] ?: return fail(task, "NO_HANDLER_REGISTERED")
        // Read the owner-controlled budget once so the policy and retry path use one
        // coherent configuration snapshot for this attempt.
        val configuredBudget = runtimeBudget()
        val effectiveBudget = configuredBudget.copy(
            maxRetries = minOf(configuredBudget.maxRetries, maxAttempts - 1)
        )
        val budgetDecision = GlafiraRuntimeBudgetPolicy.evaluate(
            task = task,
            nowMillis = clock(),
            budgetStartedAtMillis = task.createdAtMillis,
            budget = effectiveBudget,
            measuredCostMicros = runCatching { costMeter?.measuredCostMicros(task.taskId) }.getOrNull()
        )
        if (!budgetDecision.allowed) return fail(task, budgetDecision.reason ?: "RUNTIME_BUDGET_REJECTED")

        val outcome = try {
            // One registered Core handler is one typed execution step. The timeout also
            // bounds cooperative suspend handlers and cancels them when their allowance ends.
            withTimeout(budgetDecision.timeoutMillis) { handler.execute(task) }
        } catch (timeout: TimeoutCancellationException) {
            return fail(task, "RUNTIME_TIME_LIMIT_EXCEEDED")
        } catch (cancelled: CancellationException) {
            return scheduleRetry(task, "EXECUTION_CANCELLED", clock(), effectiveBudget.maxRetries)
        } catch (failure: Exception) {
            return scheduleRetry(task, "HANDLER_ERROR:${failure.javaClass.simpleName}", clock(), effectiveBudget.maxRetries)
        }

        return when (outcome) {
            is GlafiraTaskExecutionOutcome.Succeeded -> {
                val changed = queue.transition(
                    task.taskId, setOf(GlafiraTaskState.RUNNING),
                    GlafiraTaskState.SUCCEEDED, clock()
                )
                GlafiraTaskExecutionResult(
                    task.taskId,
                    if (changed) GlafiraTaskExecutionStatus.SUCCEEDED
                    else GlafiraTaskExecutionStatus.FAILED,
                    outcome.summary
                )
            }
            is GlafiraTaskExecutionOutcome.RetryableFailure ->
                scheduleRetry(task, outcome.reason, clock(), effectiveBudget.maxRetries)
            is GlafiraTaskExecutionOutcome.PermanentFailure -> fail(task, outcome.reason)
            GlafiraTaskExecutionOutcome.WaitingForNetwork -> {
                val changed = queue.transition(
                    task.taskId, setOf(GlafiraTaskState.RUNNING),
                    GlafiraTaskState.WAITING_FOR_NETWORK, clock()
                )
                GlafiraTaskExecutionResult(
                    task.taskId,
                    if (changed) GlafiraTaskExecutionStatus.WAITING_FOR_NETWORK
                    else GlafiraTaskExecutionStatus.FAILED
                )
            }
            GlafiraTaskExecutionOutcome.WaitingForOwner -> {
                val changed = queue.transition(
                    task.taskId, setOf(GlafiraTaskState.RUNNING),
                    GlafiraTaskState.WAITING_FOR_OWNER, clock(),
                    lastError = "OWNER_APPROVAL_REQUIRED"
                )
                GlafiraTaskExecutionResult(
                    task.taskId,
                    if (changed) GlafiraTaskExecutionStatus.WAITING_FOR_OWNER
                    else GlafiraTaskExecutionStatus.FAILED
                )
            }
        }
    }

    private fun scheduleRetry(
        task: GlafiraTask,
        reason: String,
        nowMillis: Long,
        configuredMaxRetries: Int
    ): GlafiraTaskExecutionResult {
        val retryLimit = minOf(maxAttempts - 1, configuredMaxRetries)
        if (task.attemptCount >= retryLimit + 1) return fail(task, "RETRY_LIMIT_REACHED:$reason")
        val exponent = (task.attemptCount - 1).coerceIn(0, 20)
        val delay = (baseRetryDelayMillis * (1L shl exponent)).coerceAtMost(maxRetryDelayMillis)
        val retryAt = if (Long.MAX_VALUE - nowMillis < delay) Long.MAX_VALUE else nowMillis + delay
        val changed = queue.transition(
            task.taskId, setOf(GlafiraTaskState.RUNNING),
            GlafiraTaskState.RETRY_SCHEDULED, nowMillis,
            retryAtMillis = retryAt, lastError = reason
        )
        return GlafiraTaskExecutionResult(
            task.taskId,
            if (changed) GlafiraTaskExecutionStatus.RETRY_SCHEDULED
            else GlafiraTaskExecutionStatus.FAILED,
            reason
        )
    }

    private fun fail(task: GlafiraTask, reason: String): GlafiraTaskExecutionResult {
        val changed = queue.transition(
            task.taskId, setOf(GlafiraTaskState.RUNNING),
            GlafiraTaskState.FAILED, clock(), lastError = reason
        )
        return GlafiraTaskExecutionResult(
            task.taskId, GlafiraTaskExecutionStatus.FAILED,
            if (changed) reason else "STATE_TRANSITION_REJECTED:$reason"
        )
    }
}
