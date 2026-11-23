package com.micronizationlab.glafira

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Shared persistence boundary for interactive, foreground and scheduled execution. */
interface GlafiraTaskQueue {
    fun enqueue(task: GlafiraTask): GlafiraTaskEnqueueResult
    fun getTask(taskId: String): GlafiraTask?
    fun claimNext(nowMillis: Long = System.currentTimeMillis()): GlafiraTask?
    fun transition(
        taskId: String,
        expectedStates: Set<GlafiraTaskState>,
        nextState: GlafiraTaskState,
        nowMillis: Long = System.currentTimeMillis(),
        retryAtMillis: Long? = null,
        lastError: String? = null
    ): Boolean
}

/** Ownership-aware Dry Run persistence used by the production Core executor. */
interface GlafiraOwnedDryRunPreviewPersistence : GlafiraDryRunPreviewPersistence {
    fun persistDryRunPreviewOwned(
        token: GlafiraTaskOwnershipExecutionToken,
        expectedStates: Set<GlafiraTaskState>,
        plan: GlafiraDryRunPlan,
        nowMillis: Long
    ): Boolean
}

/** Immutable authority captured atomically with a Core task claim. */
data class GlafiraTaskOwnershipExecutionToken(
    val taskId: String,
    val ownerCoreId: String,
    val ownerGeneration: Long,
    val stateVersion: Long
) {
    init {
        require(taskId.isNotBlank())
        require(ownerCoreId.matches(Regex("^[A-Za-z0-9._-]{8,128}$")))
        require(ownerGeneration > 0L)
        require(stateVersion > 0L)
    }
}

data class GlafiraOwnedTaskClaim(
    val task: GlafiraTask,
    val ownershipToken: GlafiraTaskOwnershipExecutionToken
)

/** Optional strict ownership-aware execution seam for installation-backed Core stores. */
interface GlafiraTaskOwnershipExecutionFence {
    /** False only for legacy/test stores that have no installation identity to fence against. */
    val ownershipFencingEnabled: Boolean get() = true
    fun claimNextOwned(nowMillis: Long = System.currentTimeMillis()): GlafiraOwnedTaskClaim?
    fun transitionOwned(
        token: GlafiraTaskOwnershipExecutionToken,
        expectedStates: Set<GlafiraTaskState>,
        nextState: GlafiraTaskState,
        nowMillis: Long = System.currentTimeMillis(),
        retryAtMillis: Long? = null,
        lastError: String? = null
    ): Boolean
}

/** Optional result persistence that verifies the exact owner generation captured at claim. */
interface GlafiraOwnedCoreTaskResultPersistence : GlafiraCoreTaskResultPersistence {
    fun persistCoreTaskResultOwned(
        token: GlafiraTaskOwnershipExecutionToken,
        expectedStates: Set<GlafiraTaskState>,
        result: String,
        nowMillis: Long
    ): Boolean
}

/**
 * Optional durable recovery seam for Core tasks left RUNNING by process death.
 * Desktop tasks use independent expiring leases and must not be recovered here.
 */
interface GlafiraInterruptedTaskRecovery {
    fun recoverInterruptedCoreTasks(
        nowMillis: Long,
        maxRetries: Int
    ): Int
}

/** Optional atomic result persistence for successful Core handlers. */
interface GlafiraCoreTaskResultPersistence {
    fun persistCoreTaskResult(
        taskId: String,
        expectedStates: Set<GlafiraTaskState>,
        result: String,
        nowMillis: Long
    ): Boolean

    companion object {
        const val MAX_RESULT_CHARS = 24_000
    }
}


/** Returns true only when a bounded batch needs a follow-up drain. */
internal suspend fun drainGlafiraCoreBatch(
    maxTasks: Int,
    isStopped: () -> Boolean,
    executeNext: suspend () -> GlafiraTaskExecutionResult
): Boolean {
    require(maxTasks > 0)
    repeat(maxTasks) {
        if (isStopped()) return false
        if (executeNext().status == GlafiraTaskExecutionStatus.IDLE) return false
    }
    return true
}

/** Runs bounded drain lanes concurrently; every lane still claims through the same durable queue. */
internal suspend fun drainGlafiraCoreParallelBatch(
    maxParallelTasks: Int,
    maxTasksPerWorker: Int,
    isStopped: () -> Boolean,
    executeNext: suspend () -> GlafiraTaskExecutionResult
): Boolean = coroutineScope {
    require(maxParallelTasks in 1..GlafiraRuntimeBudget.MAX_CONCURRENT_TASKS)
    require(maxTasksPerWorker > 0)
    (0 until maxParallelTasks).map {
        async {
            drainGlafiraCoreBatch(maxTasksPerWorker, isStopped, executeNext)
        }
    }.awaitAll().any { it }
}
