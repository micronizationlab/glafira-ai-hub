package com.micronizationlab.glafira

import org.json.JSONObject

enum class GlafiraOwnerPreviewState { NOT_READY, READY, INVALID }

/** Owner-safe projection of one durable task-plan node. Raw payload and provider text are absent. */
data class GlafiraOwnerPlanNodeRow(
    val title: String,
    val executor: String,
    val state: GlafiraTaskState,
    val stage: Int,
    val dependencyCount: Int,
    val previewState: GlafiraOwnerPreviewState,
    val previewRisk: ToolRiskLevel?,
    val simulationStatus: GlafiraDryRunSimulationStatus?,
    val reviewStatus: GlafiraDryRunReviewStatus?,
    val resultReviewStatus: GlafiraCoreResultReviewStatus?
)

/** Owner-safe projection of one project-scoped durable plan. */
data class GlafiraOwnerPlanRow(
    val displayId: String,
    val status: GlafiraCoreTaskPlanStatus,
    val completedCount: Int,
    val totalCount: Int,
    val nodes: List<GlafiraOwnerPlanNodeRow>
)

internal object GlafiraOwnerPlanPresentation {
    fun build(
        plans: List<GlafiraCoreTaskPlanRecord>,
        previews: List<GlafiraDryRunPreviewRecord>,
        resultReviews: Map<String, GlafiraCoreResultReviewStatus> = emptyMap()
    ): List<GlafiraOwnerPlanRow> {
        val previewsByTask = previews.associateBy { it.taskId }
        return plans.sortedByDescending { it.createdAtMillis }.map { plan ->
            val tasksById = plan.nodes.associateBy { it.taskId }
            val stages = mutableMapOf<String, Int>()

            fun stage(taskId: String, visiting: Set<String> = emptySet()): Int {
                stages[taskId]?.let { return it }
                if (taskId in visiting) return 0
                val dependencies = plan.dependencies[taskId].orEmpty()
                    .filter { it in tasksById }
                val value = if (dependencies.isEmpty()) 1 else
                    1 + dependencies.maxOf { stage(it, visiting + taskId) }
                stages[taskId] = value
                return value
            }

            val rows = plan.nodes.map { task ->
                val payload = runCatching { JSONObject(task.payload) }.getOrNull()
                val title = safeLabel(payload?.optString("title").orEmpty())
                    .ifBlank { task.taskType }
                val executor = runCatching {
                    GlafiraTaskExecutor.valueOf(
                        payload?.optString("executor").orEmpty().trim().uppercase()
                    ).name
                }.getOrDefault("CORE")
                val preview = previewsByTask[task.taskId]
                val safePreview = preview?.let { parsePreview(task, it) }
                GlafiraOwnerPlanNodeRow(
                    title = title,
                    executor = executor,
                    state = task.state,
                    stage = stage(task.taskId),
                    dependencyCount = plan.dependencies[task.taskId].orEmpty().size,
                    previewState = when {
                        preview == null -> GlafiraOwnerPreviewState.NOT_READY
                        safePreview == null -> GlafiraOwnerPreviewState.INVALID
                        else -> GlafiraOwnerPreviewState.READY
                    },
                    previewRisk = safePreview?.first,
                    simulationStatus = safePreview?.second,
                    reviewStatus = safePreview?.third,
                    resultReviewStatus = resultReviews[task.taskId]
                )
            }.sortedWith(compareBy<GlafiraOwnerPlanNodeRow> { it.stage }.thenBy { it.title })

            GlafiraOwnerPlanRow(
                displayId = plan.planId.takeLast(12),
                status = plan.status,
                completedCount = plan.completedCount,
                totalCount = plan.nodes.size,
                nodes = rows
            )
        }
    }

    private fun parsePreview(
        task: GlafiraTask,
        record: GlafiraDryRunPreviewRecord
    ): Triple<ToolRiskLevel, GlafiraDryRunSimulationStatus, GlafiraDryRunReviewStatus>? =
        runCatching {
            val json = JSONObject(record.planJson)
            val expected = GlafiraDryRunPlanner.build(task)
            require(record.taskId == task.taskId)
            require(record.projectId == task.projectId)
            require(record.toolId == task.taskType)
            require(record.planHash == expected.planHash)
            require(record.risk == expected.risk.name)
            require(record.simulationStatus == expected.simulationStatus.name)
            require(json.getString("mode") == GlafiraExecutionMode.DRY_RUN.name)
            require(json.getString("taskId") == task.taskId)
            require(json.getString("projectId") == task.projectId)
            require(json.getString("toolId") == task.taskType)
            require(json.getString("planHash") == record.planHash)
            val risk = ToolRiskLevel.valueOf(json.getString("risk"))
            val simulation = GlafiraDryRunSimulationStatus.valueOf(
                json.getString("simulationStatus")
            )
            require(risk.name == record.risk)
            require(simulation.name == record.simulationStatus)
            Triple(risk, simulation, record.reviewStatus)
        }.getOrNull()

    private fun safeLabel(value: String): String =
        value.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").trim().take(160)
}
