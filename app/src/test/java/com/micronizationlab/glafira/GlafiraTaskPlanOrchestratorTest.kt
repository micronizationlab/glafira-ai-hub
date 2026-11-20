package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GlafiraTaskPlanOrchestratorTest {
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

    @Test fun decomposesAnInstructionIntoIndependentBranchesAndAJoinThenPersistsOnce() {
        val request = GlafiraTaskPlanRequest(
            instruction = "Research sources and prepare a reviewed summary",
            projectId = "project-a",
            idempotencyKey = "instruction-0001",
            createdAtMillis = 1_000L
        )
        val plan = GlafiraTaskPlan(
            planId = "plan-00000001",
            projectId = request.projectId,
            idempotencyKey = request.idempotencyKey,
            createdAtMillis = request.createdAtMillis,
            nodes = listOf(
                GlafiraTaskPlanNode(task("root")),
                GlafiraTaskPlanNode(task("research"), setOf("root")),
                GlafiraTaskPlanNode(task("review"), setOf("root")),
                GlafiraTaskPlanNode(task("summary"), setOf("research", "review"))
            )
        )
        var persisted: GlafiraTaskPlan? = null
        val orchestrator = GlafiraTaskPlanOrchestrator(
            GlafiraTaskPlanDecomposer { received ->
                assertEquals(request, received)
                plan
            },
            persist = {
                persisted = it
                GlafiraTaskPlanEnqueueResult(
                    GlafiraTaskPlanRecord(it.planId, it.projectId, it.idempotencyKey,
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

    @Test fun rejectsPlannerOutputThatChangesProjectOrIdempotencyScope() {
        val request = GlafiraTaskPlanRequest(
            instruction = "Do scoped work",
            projectId = "project-a",
            idempotencyKey = "instruction-0002",
            createdAtMillis = 2_000L
        )
        val foreignPlan = GlafiraTaskPlan(
            planId = "plan-00000002",
            projectId = "project-b",
            idempotencyKey = request.idempotencyKey,
            createdAtMillis = request.createdAtMillis,
            nodes = listOf(GlafiraTaskPlanNode(task("foreign").copy(projectId = "project-b")))
        )
        val orchestrator = GlafiraTaskPlanOrchestrator(
            GlafiraTaskPlanDecomposer { foreignPlan },
            persist = { throw AssertionError("Invalid plan must not reach persistence.") }
        )
        assertThrows(IllegalArgumentException::class.java) { orchestrator.submit(request) }
    }
}
