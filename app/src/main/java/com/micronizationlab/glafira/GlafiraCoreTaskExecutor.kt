package com.micronizationlab.glafira

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    IDLE, SUCCEEDED, DRY_RUN_PREVIEWED, RETRY_SCHEDULED, WAITING_FOR_NETWORK, WAITING_FOR_OWNER, FAILED
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
    private val costMeter: GlafiraTaskCostMeter? = null,
    private val executionMode: () -> GlafiraExecutionMode = { GlafiraExecutionMode.LIVE },
    private val liveTaskAdmission: GlafiraLiveTaskAdmission = GlafiraLiveTaskAdmission.ALLOW_ALL,
    private val dryRunPreviewPersistence: GlafiraDryRunPreviewPersistence? = null,
    private val resultReviewer: GlafiraCoreTaskResultReviewer? = null,
    private val executionLimiter: GlafiraCoreExecutionLimiter = GlafiraCoreExecutionLimiter()
) {
    private val recoveryMutex = Mutex()
    @Volatile private var recoveredMaintenanceEpoch = -1L
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

    suspend fun executeNext(): GlafiraTaskExecutionResult {
        while (true) {
            val configuredBudget = runtimeBudget()
            val effectiveBudget = configuredBudget.copy(
                maxRetries = minOf(configuredBudget.maxRetries, maxAttempts - 1)
            )
            ensureInterruptedTaskRecovery(effectiveBudget.maxRetries)

            // The permit is acquired before the maintenance lease. Waiting workers therefore
            // do not block a snapshot/restore from draining the handlers already in flight.
            val result = executionLimiter.withPermit(configuredBudget.maxConcurrentTasks) {
                GlafiraCoreMaintenanceGate.withExecution {
                    // A backup/restore may have completed after recovery but before this lease.
                    // In that case release the lease and run recovery against the new DB epoch.
                    if (recoveredMaintenanceEpoch != GlafiraCoreMaintenanceGate.maintenanceEpoch) {
                        null
                    } else {
                        withContext(dispatcher) {
                            val now = clock()
                            val task = queue.claimNext(now)
                                ?: return@withContext GlafiraTaskExecutionResult(
                                    null, GlafiraTaskExecutionStatus.IDLE
                                )
                            executeClaimed(task, now, effectiveBudget)
                        }
                    }
                }
            }
            if (result != null) return result
        }
    }

    /**
     * Recovery is performed once per maintenance epoch, before new work can enter.
     * It is never run beside live handlers, so one worker cannot requeue another worker's task.
     */
    private suspend fun ensureInterruptedTaskRecovery(maxRetries: Int) {
        recoveryMutex.withLock {
            if (recoveredMaintenanceEpoch == GlafiraCoreMaintenanceGate.maintenanceEpoch) return
            var recoveryEpoch = -1L
            GlafiraCoreMaintenanceGate.withExclusive {
                // withExclusive advances the epoch exactly once when it leaves the gate.
                recoveryEpoch = GlafiraCoreMaintenanceGate.maintenanceEpoch + 1L
                withContext(dispatcher) {
                    (queue as? GlafiraInterruptedTaskRecovery)
                        ?.recoverInterruptedCoreTasks(clock(), maxRetries)
                }
            }
            recoveredMaintenanceEpoch = recoveryEpoch
        }
    }

    private suspend fun executeClaimed(
        task: GlafiraTask,
        nowMillis: Long,
        effectiveBudget: GlafiraRuntimeBudget
    ): GlafiraTaskExecutionResult {
        val configuredMode = executionMode()
        val mode = if (configuredMode == GlafiraExecutionMode.LIVE &&
            liveTaskAdmission.allows(task, nowMillis)
        ) GlafiraExecutionMode.LIVE else GlafiraExecutionMode.DRY_RUN
        val authorization = try {
            if (mode == GlafiraExecutionMode.DRY_RUN) {
                authorizer.inspectForDryRun(task, nowMillis)
            } else {
                authorizer.authorize(task, nowMillis)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return fail(task, "AUTHORIZATION_ERROR")
        }

        when (authorization.status) {
            GlafiraTaskAuthorizationStatus.DENIED -> {
                if (mode == GlafiraExecutionMode.LIVE) {
                    return fail(task, authorization.reason ?: "AUTHORIZATION_DENIED")
                }
            }
            GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER -> {
                if (mode == GlafiraExecutionMode.LIVE) {
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
            }
            GlafiraTaskAuthorizationStatus.AUTHORIZED -> Unit
        }

        val handler = handlerMap[task.taskType] ?: return fail(task, "NO_HANDLER_REGISTERED")
        val budgetDecision = GlafiraRuntimeBudgetPolicy.evaluate(
            task = task,
            nowMillis = clock(),
            budgetStartedAtMillis = task.createdAtMillis,
            budget = effectiveBudget,
            measuredCostMicros = runCatching { costMeter?.measuredCostMicros(task.taskId) }.getOrNull()
        )
        if (!budgetDecision.allowed) return fail(task, budgetDecision.reason ?: "RUNTIME_BUDGET_REJECTED")

        // Dry Run is a planning path, not a mock invocation of the live handler.
        // When Core supplies the typed persistence port, the encrypted preview row and
        // WAITING_FOR_OWNER transition are committed atomically. The handler is never called.
        if (mode == GlafiraExecutionMode.DRY_RUN) {
            val plan = GlafiraDryRunPlanner.build(task, authorization)
            val preview = "DRY_RUN_PREVIEW:" + plan.toSafeJson()
            val changed = dryRunPreviewPersistence?.persistDryRunPreview(
                task.taskId, setOf(GlafiraTaskState.RUNNING), plan, clock()
            ) ?: queue.transition(
                task.taskId, setOf(GlafiraTaskState.RUNNING),
                GlafiraTaskState.WAITING_FOR_OWNER, clock(),
                lastError = preview
            )
            return GlafiraTaskExecutionResult(
                task.taskId,
                if (changed) GlafiraTaskExecutionStatus.DRY_RUN_PREVIEWED
                else GlafiraTaskExecutionStatus.FAILED,
                if (changed) plan.toSafeJson() else "DRY_RUN_PREVIEW_PERSIST_FAILED"
            )
        }

        val outcome = try {
            // One registered Core handler is one typed execution step. The timeout also
            // bounds cooperative suspend handlers and cancels them when their allowance ends.
            withTimeout(budgetDecision.timeoutMillis) { handler.execute(task) }
        } catch (timeout: TimeoutCancellationException) {
            return scheduleRetry(task, "RUNTIME_TIME_LIMIT_EXCEEDED", clock(), effectiveBudget.maxRetries)
        } catch (cancelled: CancellationException) {
            // Persist a risk-aware recovery state even while the parent coroutine is cancelling,
            // then preserve structured concurrency by propagating cancellation.
            withContext(NonCancellable) {
                runCatching {
                    scheduleRetry(task, "EXECUTION_CANCELLED", clock(), effectiveBudget.maxRetries)
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            return scheduleRetry(task, "HANDLER_ERROR:${failure.javaClass.simpleName}", clock(), effectiveBudget.maxRetries)
        }

        return when (outcome) {
            is GlafiraTaskExecutionOutcome.Succeeded -> {
                val rawSummary = outcome.summary.orEmpty()
                val summary = if (rawSummary.length > GlafiraCoreTaskResultPersistence.MAX_RESULT_CHARS) {
                    rawSummary.take(GlafiraCoreTaskResultPersistence.MAX_RESULT_CHARS - 14) + "...[TRUNCATED]"
                } else rawSummary
                reviewResult(task, summary, effectiveBudget.maxRetries)?.let { return it }
                val now = clock()
                val resultPersistence = queue as? GlafiraCoreTaskResultPersistence
                val changed = try {
                    resultPersistence?.persistCoreTaskResult(
                        task.taskId, setOf(GlafiraTaskState.RUNNING), summary, now
                    ) ?: queue.transition(
                        task.taskId, setOf(GlafiraTaskState.RUNNING),
                        GlafiraTaskState.SUCCEEDED, now
                    )
                } catch (_: Exception) {
                    return reconcileResultPersistenceFailure(task, effectiveBudget.maxRetries, now)
                }
                if (!changed && resultPersistence != null) {
                    return reconcileResultPersistenceFailure(task, effectiveBudget.maxRetries, now)
                }
                GlafiraTaskExecutionResult(
                    task.taskId,
                    if (changed) GlafiraTaskExecutionStatus.SUCCEEDED
                    else GlafiraTaskExecutionStatus.FAILED,
                    if (changed) summary else "CORE_RESULT_PERSIST_FAILED"
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

    private suspend fun reviewResult(
        task: GlafiraTask,
        summary: String,
        maxRetries: Int
    ): GlafiraTaskExecutionResult? {
        val reviewer = resultReviewer ?: return null
        val review = try {
            reviewer.review(task, summary)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching {
                    scheduleRetry(task, "RESULT_REVIEW_CANCELLED", clock(), maxRetries)
                }
            }
            throw cancelled
        } catch (_: Exception) {
            GlafiraCoreTaskResultReviewOutcome(GlafiraCoreResultReviewStatus.UNAVAILABLE)
        }
        if (review.status == GlafiraCoreResultReviewStatus.ACCEPTED) return null
        val reason = "CORE_RESULT_REVIEW_${review.status.name}"
        val changed = queue.transition(
            task.taskId,
            setOf(GlafiraTaskState.RUNNING),
            GlafiraTaskState.WAITING_FOR_OWNER,
            clock(),
            lastError = reason
        )
        return GlafiraTaskExecutionResult(
            task.taskId,
            if (changed) GlafiraTaskExecutionStatus.WAITING_FOR_OWNER
            else GlafiraTaskExecutionStatus.FAILED,
            reason
        )
    }

    /**
     * If a completed handler cannot commit its result, never blindly replay a possible write.
     * Read-only work may retry; writes are quarantined for owner reconciliation.
     */
    private fun reconcileResultPersistenceFailure(
        task: GlafiraTask,
        maxRetries: Int,
        nowMillis: Long
    ): GlafiraTaskExecutionResult {
        val decision = GlafiraTaskRecoveryPolicy.interrupted(task, maxRetries)
        val changed = runCatching {
            queue.transition(
                task.taskId,
                setOf(GlafiraTaskState.RUNNING),
                decision.state,
                nowMillis,
                retryAtMillis = if (decision.state == GlafiraTaskState.RETRY_SCHEDULED) nowMillis else null,
                lastError = "CORE_RESULT_PERSIST_FAILED:${decision.reason}"
            )
        }.getOrDefault(false)
        val status = if (!changed) {
            GlafiraTaskExecutionStatus.FAILED
        } else {
            when (decision.state) {
                GlafiraTaskState.RETRY_SCHEDULED -> GlafiraTaskExecutionStatus.RETRY_SCHEDULED
                GlafiraTaskState.WAITING_FOR_OWNER -> GlafiraTaskExecutionStatus.WAITING_FOR_OWNER
                else -> GlafiraTaskExecutionStatus.FAILED
            }
        }
        return GlafiraTaskExecutionResult(task.taskId, status, "CORE_RESULT_PERSIST_FAILED")
    }

    /** Automatic retries are restricted to registry-classified read-only operations. */
    private fun scheduleRetry(
        task: GlafiraTask,
        reason: String,
        nowMillis: Long,
        configuredMaxRetries: Int
    ): GlafiraTaskExecutionResult {
        // An exception, timeout or cancellation may happen after an external side effect.
        // Only registry-classified read-only operations are safe for automatic replay.
        if (GlafiraToolRegistry.find(task.taskType)?.risk != ToolRiskLevel.READ_ONLY) {
            val changed = queue.transition(
                task.taskId,
                setOf(GlafiraTaskState.RUNNING),
                GlafiraTaskState.WAITING_FOR_OWNER,
                nowMillis,
                lastError = "EXECUTION_OUTCOME_UNCERTAIN"
            )
            return GlafiraTaskExecutionResult(
                task.taskId,
                if (changed) GlafiraTaskExecutionStatus.WAITING_FOR_OWNER
                else GlafiraTaskExecutionStatus.FAILED,
                "EXECUTION_OUTCOME_UNCERTAIN"
            )
        }
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
