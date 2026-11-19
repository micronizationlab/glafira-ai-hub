package com.micronizationlab.glafira

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
