package com.micronizationlab.glafira

import kotlinx.coroutines.delay
import org.json.JSONObject

/** Core-side submit-and-await adapter backed by the durable Android/Desktop Bridge queue. */
internal class GlafiraAndroidDesktopTaskPort(
    private val store: GlafiraDesktopTaskStore,
    private val pollIntervalMs: Long = 750L
) : GlafiraDesktopTaskPort {
    init { require(pollIntervalMs in 100L..5_000L) }

    override suspend fun submit(task: GlafiraDistributedTask): GlafiraDistributedTaskResult {
        store.enqueue(task)
        while (System.currentTimeMillis() < task.expiresAtEpochMs) {
            val record = store.get(task.taskId) ?: return GlafiraDistributedTaskResult(
                task.taskId, GlafiraExecutionNode.DESKTOP, false, errorCode = "TASK_RECORD_MISSING"
            )
            when (record.optString("state")) {
                "SUCCEEDED" -> return GlafiraDistributedTaskResult(
                    task.taskId, GlafiraExecutionNode.DESKTOP, true,
                    output = record.optString("result").takeIf { it.isNotBlank() }
                )
                "FAILED", "CANCELLED", "EXPIRED" -> return GlafiraDistributedTaskResult(
                    task.taskId, GlafiraExecutionNode.DESKTOP, false,
                    errorCode = record.optString("errorCode").ifBlank { record.optString("state") }
                )
            }
            delay(pollIntervalMs)
        }
        return GlafiraDistributedTaskResult(
            task.taskId, GlafiraExecutionNode.DESKTOP, false, errorCode = "TASK_EXPIRED"
        )
    }
}
