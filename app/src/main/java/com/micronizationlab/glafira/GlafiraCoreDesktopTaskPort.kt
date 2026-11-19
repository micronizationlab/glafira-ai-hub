package com.micronizationlab.glafira

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Desktop orchestrator adapter backed exclusively by the durable Core SQLite queue.
 * It never creates a second inbox or writes task state outside GlafiraTaskStore.
 */
internal class GlafiraCoreDesktopTaskPort(
    private val runtime: GlafiraCoreRuntimeOwner,
    private val pollIntervalMs: Long = 750L
) : GlafiraDesktopTaskPort {
    init { require(pollIntervalMs in 100L..5_000L) }

    override suspend fun submit(task: GlafiraDistributedTask): GlafiraDistributedTaskResult {
        val now = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("actionSummary", "Execute Desktop task: ${task.title}")
            put("parentTaskId", task.parentTaskId)
            put("subtaskId", task.subtaskId)
            put("title", task.title)
            put("instruction", task.instruction)
            put("executor", task.executor.name)
            put("requiredCapabilities", JSONArray(task.requiredCapabilities.sorted()))
        }.toString()
        runtime.enqueue(
            GlafiraTask(
                taskId = task.taskId,
                projectId = task.projectId,
                taskType = GlafiraTaskStore.DESKTOP_TASK_TYPE,
                payload = payload,
                origin = "glafira-core-orchestrator",
                priority = task.priority,
                createdAtMillis = now,
                updatedAtMillis = now,
                deadlineAtMillis = task.expiresAtEpochMs,
                idempotencyKey = "desktop-${task.taskId}",
                requiredScopes = setOf("desktop.task.execute")
            )
        )

        while (System.currentTimeMillis() < task.expiresAtEpochMs) {
            val current = runtime.taskStore.getTask(task.taskId)
                ?: return GlafiraDistributedTaskResult(
                    task.taskId, GlafiraExecutionNode.DESKTOP, false,
                    errorCode = "CORE_TASK_RECORD_MISSING"
                )
            when (current.state) {
                GlafiraTaskState.SUCCEEDED -> return GlafiraDistributedTaskResult(
                    task.taskId, GlafiraExecutionNode.DESKTOP, true,
                    output = runtime.taskStore.desktopTaskResult(task.taskId)
                )
                GlafiraTaskState.FAILED,
                GlafiraTaskState.CANCELLED -> return GlafiraDistributedTaskResult(
                    task.taskId, GlafiraExecutionNode.DESKTOP, false,
                    errorCode = current.lastError ?: current.state.databaseValue.uppercase()
                )
                GlafiraTaskState.WAITING_FOR_OWNER -> {
                    val previewJson = runtime.dryRunPreviewJson(task.taskId)
                    if (previewJson != null) {
                        return GlafiraDistributedTaskResult(
                            task.taskId, GlafiraExecutionNode.DESKTOP, false,
                            output = previewJson,
                            errorCode = "DRY_RUN_PREVIEWED"
                        )
                    }
                    return GlafiraDistributedTaskResult(
                        task.taskId, GlafiraExecutionNode.DESKTOP, false,
                        errorCode = current.lastError ?: "WAITING_FOR_OWNER"
                    )
                }
                else -> delay(pollIntervalMs)
            }
        }
        return GlafiraDistributedTaskResult(
            task.taskId, GlafiraExecutionNode.DESKTOP, false,
            errorCode = "DESKTOP_TASK_EXPIRED"
        )
    }
}
