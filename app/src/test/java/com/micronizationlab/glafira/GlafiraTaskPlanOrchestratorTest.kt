package com.micronizationlab.glafira

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GlafiraCoreTaskPlanOrchestratorTest {
    private fun task(id: String) = GlafiraTask(
        taskId = id,
        projectId = "project-a",
        taskType = "project.read",
        payload = "{}",
        origin = "planner-test",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "idem-$id"
    )

    @Test fun decomposesAnInstructionIntoIndependentBranchesAndAJoinThenPersistsOnce() = runBlocking {
        val request = GlafiraCoreTaskPlanRequest(
            instruction = "Research sources and prepare a reviewed summary",
            projectId = "project-a",
            idempotencyKey = "instruction-0001",
            createdAtMillis = 1_000L
        )
        val plan = GlafiraCoreTaskPlan(
            planId = "plan-00000001",
            projectId = request.projectId,
            idempotencyKey = request.idempotencyKey,
            createdAtMillis = request.createdAtMillis,
            nodes = listOf(
                GlafiraCoreTaskPlanNode(task("root")),
                GlafiraCoreTaskPlanNode(task("research"), setOf("root")),
                GlafiraCoreTaskPlanNode(task("review"), setOf("root")),
                GlafiraCoreTaskPlanNode(task("summary"), setOf("research", "review"))
            )
        )
        var persisted: GlafiraCoreTaskPlan? = null
        val orchestrator = GlafiraCoreTaskPlanOrchestrator(
            GlafiraCoreTaskPlanDecomposer { received ->
                assertEquals(request, received)
                plan
            },
            persist = {
                persisted = it
                GlafiraCoreTaskPlanEnqueueResult(
                    GlafiraCoreTaskPlanRecord(it.planId, it.projectId, it.idempotencyKey,
                        it.createdAtMillis, it.createdAtMillis, it.nodes.map { node -> node.task }),
                    true
                )
            }
        )

        val result = orchestrator.submit(request)
        assertEquals(plan, persisted)
        assertTrue(result.inserted)
        assertEquals(4, result.plan.nodes.size)
    }

    @Test fun rejectsPlannerOutputThatChangesProjectOrIdempotencyScope() = runBlocking {
        val request = GlafiraCoreTaskPlanRequest(
            instruction = "Do scoped work",
            projectId = "project-a",
            idempotencyKey = "instruction-0002",
            createdAtMillis = 2_000L
        )
        val foreignPlan = GlafiraCoreTaskPlan(
            planId = "plan-00000002",
            projectId = "project-b",
            idempotencyKey = request.idempotencyKey,
            createdAtMillis = request.createdAtMillis,
            nodes = listOf(GlafiraCoreTaskPlanNode(task("foreign").copy(projectId = "project-b")))
        )
        val orchestrator = GlafiraCoreTaskPlanOrchestrator(
            GlafiraCoreTaskPlanDecomposer { foreignPlan },
            persist = { throw AssertionError("Invalid plan must not reach persistence.") }
        )
        assertThrows(IllegalArgumentException::class.java) { orchestrator.submit(request) }
    }
}
