package com.micronizationlab.glafira

/**
 * Builds the exact high-level planning request explicitly submitted by the owner.
 * The supplied idempotency key belongs to one open UI form and is reused on a retry, so an
 * uncertain response cannot silently create a second durable plan.
 */
internal object GlafiraOwnerPlanRequestFactory {
    fun create(
        projectId: String,
        title: String,
        instruction: String,
        idempotencyKey: String,
        createdAtMillis: Long = System.currentTimeMillis(),
        maxNodes: Int = GlafiraTaskPlanner.MAX_TASKS
    ): GlafiraCoreTaskPlanRequest {
        val safeProjectId = projectId.trim()
        val safeTitle = title.trim()
        val safeInstruction = instruction.trim()
        require(safeProjectId.isNotBlank()) { "Выбери проект." }
        require(safeTitle.isNotBlank() && safeTitle.length <= 160) {
            "Название задачи должно содержать от 1 до 160 символов."
        }
        require(safeInstruction.isNotBlank() && safeInstruction.length <= 8_000) {
            "Описание задачи должно содержать от 1 до 8000 символов."
        }
        require(idempotencyKey.matches(Regex("[A-Za-z0-9_.:-]{8,128}"))) {
            "Некорректный ключ формы планирования."
        }
        require(createdAtMillis > 0L)
        require(maxNodes in 1..GlafiraCoreTaskPlanPolicy.MAX_NODES)
        return GlafiraCoreTaskPlanRequest(
            instruction = buildString {
                appendLine(safeTitle)
                append(safeInstruction)
            },
            projectId = safeProjectId,
            idempotencyKey = idempotencyKey,
            createdAtMillis = createdAtMillis,
            maxNodes = maxNodes
        )
    }
}
