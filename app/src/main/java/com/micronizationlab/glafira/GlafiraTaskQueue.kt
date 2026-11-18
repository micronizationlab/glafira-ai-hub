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
