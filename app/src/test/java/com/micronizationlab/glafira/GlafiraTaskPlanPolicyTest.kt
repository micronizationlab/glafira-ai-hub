package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GlafiraTaskPlanPolicyTest {
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

    private fun plan(nodes: List<GlafiraTaskPlanNode>) = GlafiraTaskPlan(
        planId = "plan-policy-0001",
        projectId = "project-a",
        idempotencyKey = "instruction-0001",
        nodes = nodes,
        createdAtMillis = 1_000L
    )

    @Test fun acceptsIndependentBranchesAndAJoinNode() {
        val plan = plan(listOf(
            GlafiraTaskPlanNode(task("root")),
            GlafiraTaskPlanNode(task("branch-a"), setOf("root")),
            GlafiraTaskPlanNode(task("branch-b"), setOf("root")),
            GlafiraTaskPlanNode(task("join"), setOf("branch-a", "branch-b"))
        ))
        GlafiraTaskPlanPolicy.validate(plan)
        assertEquals(4, plan.nodes.size)
    }

    @Test fun rejectsCyclesAndDependenciesOutsideThePlan() {
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraTaskPlanPolicy.validate(plan(listOf(
                GlafiraTaskPlanNode(task("a"), setOf("b")),
                GlafiraTaskPlanNode(task("b"), setOf("a"))
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraTaskPlanPolicy.validate(plan(listOf(
                GlafiraTaskPlanNode(task("a"), setOf("external-task"))
            )))
        }
    }

    @Test fun rejectsDuplicateNodeIdsAndCrossProjectNodes() {
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraTaskPlanPolicy.validate(plan(listOf(
                GlafiraTaskPlanNode(task("duplicate")),
                GlafiraTaskPlanNode(task("duplicate"))
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraTaskPlanPolicy.validate(plan(listOf(
                GlafiraTaskPlanNode(task("local")),
                GlafiraTaskPlanNode(task("foreign").copy(projectId = "project-b"))
            )))
        }
    }

    @Test fun enforcesThePlanNodeBudget() {
        val nodes = (0..GlafiraTaskPlanPolicy.MAX_NODES).map {
            GlafiraTaskPlanNode(task("node-$it"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraTaskPlanPolicy.validate(plan(nodes))
        }
    }
}
