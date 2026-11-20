package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GlafiraCoreTaskPlanPolicyTest {
    private fun task(id: String) = GlafiraTask(
        taskId = id,
        projectId = "project-a",
        taskType = "project.read",
        payload = "{}",
        origin = "plan-policy-test",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "idem-$id"
    )

    private fun plan(nodes: List<GlafiraCoreTaskPlanNode>) = GlafiraCoreTaskPlan(
        planId = "plan-policy-0001",
        projectId = "project-a",
        idempotencyKey = "instruction-0001",
        nodes = nodes,
        createdAtMillis = 1_000L
    )

    @Test fun acceptsIndependentBranchesAndAJoinNode() {
        val plan = plan(listOf(
            GlafiraCoreTaskPlanNode(task("root")),
            GlafiraCoreTaskPlanNode(task("branch-a"), setOf("root")),
            GlafiraCoreTaskPlanNode(task("branch-b"), setOf("root")),
            GlafiraCoreTaskPlanNode(task("join"), setOf("branch-a", "branch-b"))
        ))
        GlafiraCoreTaskPlanPolicy.validate(plan)
        assertEquals(4, plan.nodes.size)
    }

    @Test fun rejectsCyclesAndDependenciesOutsideThePlan() {
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreTaskPlanPolicy.validate(plan(listOf(
                GlafiraCoreTaskPlanNode(task("a"), setOf("b")),
                GlafiraCoreTaskPlanNode(task("b"), setOf("a"))
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreTaskPlanPolicy.validate(plan(listOf(
                GlafiraCoreTaskPlanNode(task("a"), setOf("external-task"))
            )))
        }
    }

    @Test fun rejectsDuplicateNodeIdsAndCrossProjectNodes() {
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreTaskPlanPolicy.validate(plan(listOf(
                GlafiraCoreTaskPlanNode(task("duplicate")),
                GlafiraCoreTaskPlanNode(task("duplicate"))
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreTaskPlanPolicy.validate(plan(listOf(
                GlafiraCoreTaskPlanNode(task("local")),
                GlafiraCoreTaskPlanNode(task("foreign").copy(projectId = "project-b"))
            )))
        }
    }

    @Test fun enforcesThePlanNodeBudget() {
        val nodes = (0..GlafiraCoreTaskPlanPolicy.MAX_NODES).map {
            GlafiraCoreTaskPlanNode(task("node-$it"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreTaskPlanPolicy.validate(plan(nodes))
        }
    }
}
