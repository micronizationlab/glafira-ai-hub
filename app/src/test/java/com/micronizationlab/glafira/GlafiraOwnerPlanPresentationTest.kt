package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraOwnerPlanPresentationTest {
    private val canary = "SECRET_PROVIDER_CANARY"

    @Test
    fun presentsStableDagStagesAndCorrelatedSafePreview() {
        val root = task("root", "Исследование", "RESEARCHER")
        val summary = task("summary", "Резюме", "ANALYST").copy(
            state = GlafiraTaskState.WAITING_FOR_OWNER
        )
        val plan = plan(
            listOf(root, summary),
            mapOf(summary.taskId to setOf(root.taskId))
        )
        val previewJson = GlafiraDryRunPlanner.build(summary).toSafeJson()
        val planHash = org.json.JSONObject(previewJson).getString("planHash")
        val preview = GlafiraDryRunPreviewRecord(
            taskId = summary.taskId,
            projectId = summary.projectId,
            toolId = summary.taskType,
            risk = ToolRiskLevel.DRAFT.name,
            simulationStatus = GlafiraDryRunSimulationStatus.PREVIEW_ONLY.name,
            planHash = planHash,
            createdAtMillis = 2_000L,
            reviewStatus = GlafiraDryRunReviewStatus.PENDING,
            reviewedAtMillis = null,
            planJson = previewJson
        )

        val row = GlafiraOwnerPlanPresentation.build(listOf(plan), listOf(preview)).single()

        assertEquals(2, row.totalCount)
        assertEquals(GlafiraCoreTaskPlanStatus.WAITING_FOR_OWNER, row.status)
        assertEquals(listOf(1, 2), row.nodes.map { it.stage })
        assertEquals(listOf(0, 1), row.nodes.map { it.dependencyCount })
        assertEquals(GlafiraOwnerPreviewState.READY, row.nodes.last().previewState)
        assertEquals(GlafiraDryRunSimulationStatus.PREVIEW_ONLY, row.nodes.last().simulationStatus)
    }

    @Test
    fun presentationNeverCopiesInstructionProviderResponseOrInvalidPreviewJson() {
        val task = task("node", "Безопасный заголовок", "ANALYST")
        val validJson = GlafiraDryRunPlanner.build(task).toSafeJson()
        val invalidJson = org.json.JSONObject(validJson)
            .put("rawProvider", canary)
            .toString()
        val invalidPreview = GlafiraDryRunPreviewRecord(
            taskId = task.taskId,
            projectId = task.projectId,
            toolId = task.taskType,
            risk = ToolRiskLevel.DRAFT.name,
            simulationStatus = GlafiraDryRunSimulationStatus.PREVIEW_ONLY.name,
            planHash = "0".repeat(64),
            createdAtMillis = 2_000L,
            reviewStatus = GlafiraDryRunReviewStatus.PENDING,
            reviewedAtMillis = null,
            planJson = invalidJson
        )

        val row = GlafiraOwnerPlanPresentation.build(
            listOf(plan(listOf(task), emptyMap())),
            listOf(invalidPreview)
        ).single().nodes.single()

        assertEquals(GlafiraOwnerPreviewState.INVALID, row.previewState)
        assertNull(row.previewRisk)
        assertNull(row.simulationStatus)
        assertFalse(row.toString().contains(canary))
        assertFalse(row.toString().contains("Секретная инструкция"))
        assertTrue(row.title.contains("Безопасный заголовок"))
    }

    private fun task(id: String, title: String, executor: String) = GlafiraTask(
        taskId = "task-${id}",
        projectId = "project-0001",
        taskType = GlafiraTaskStore.DESKTOP_TASK_TYPE,
        payload = """{"actionSummary":"${title}","title":"${title}","instruction":"Секретная инструкция ${canary}","executor":"${executor}","planGoal":"${canary}"}""",
        origin = "instruction-planner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "idem-task-${id}",
        requiredScopes = setOf("desktop.task.execute")
    )

    private fun plan(
        tasks: List<GlafiraTask>,
        dependencies: Map<String, Set<String>>
    ) = GlafiraCoreTaskPlanRecord(
        planId = "plan-000000000001",
        projectId = "project-0001",
        idempotencyKey = "owner-plan-0001",
        createdAtMillis = 1_000L,
        updatedAtMillis = 2_000L,
        nodes = tasks,
        dependencies = dependencies
    )
}
