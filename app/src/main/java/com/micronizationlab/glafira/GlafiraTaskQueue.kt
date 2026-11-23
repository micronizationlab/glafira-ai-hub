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
