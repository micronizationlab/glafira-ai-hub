package com.micronizationlab.glafira

import org.json.JSONObject
import java.util.UUID

/** Creates owner-authored, non-executable draft tasks for the durable Core queue. */
internal object GlafiraManualTaskFactory {
    fun create(
        projectId: String,
        title: String,
        instruction: String,
        priority: Int,
        nowMillis: Long = System.currentTimeMillis(),
        taskId: String = UUID.randomUUID().toString(),
        idempotencyKey: String = UUID.randomUUID().toString()
    ): GlafiraTask {
        require(projectId.isNotBlank()) { "Выбери проект." }
        val safeTitle = title.trim()
        val safeInstruction = instruction.trim()
        require(safeTitle.isNotBlank() && safeTitle.length <= 160) { "Название задачи должно содержать от 1 до 160 символов." }
        require(safeInstruction.isNotBlank() && safeInstruction.length <= 8_000) { "Описание задачи должно содержать от 1 до 8000 символов." }
        require(priority in 0..10) { "Приоритет должен быть от 0 до 10." }
        val payload = JSONObject()
            .put("title", safeTitle)
            .put("instruction", safeInstruction)
            .put("actionSummary", "Подготовить черновой план выполнения задачи: $safeTitle")
            .toString()
        return GlafiraTask(
            taskId = taskId,
            projectId = projectId,
            taskType = "content.draft",
            payload = payload,
            origin = "owner.android.task_queue",
            priority = priority,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            idempotencyKey = idempotencyKey,
            requiredScopes = emptySet()
        ).also { GlafiraTaskPolicy.validate(it) }
    }
}
