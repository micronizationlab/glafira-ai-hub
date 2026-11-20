package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraDesktopDispatchPlannerTest {
    private fun task(id: String, capabilities: String, priority: Int = 0, deadline: Long? = null) =
        GlafiraTask(
            taskId = id,
            projectId = "project-a",
            taskType = GlafiraTaskStore.DESKTOP_TASK_TYPE,
            payload = """{"requiredCapabilities":$capabilities}""",
            origin = "dispatch-planner-test",
            priority = priority,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
            deadlineAtMillis = deadline,
            idempotencyKey = "idem-$id"
        )

    @Test fun distributesByCapabilityAndNormalizedNodeLoad() {
        val nodes = listOf(
            GlafiraNodeCapabilities("desktop-01", true, 2, setOf("cpu", "gpu")),
            GlafiraNodeCapabilities("desktop-02", true, 4, setOf("cpu"))
        )
        val tasks = listOf(
            task("gpu-task", """["gpu"]""", priority = 10),
            task("cpu-task-1", """["cpu"]"""),
            task("cpu-task-2", """["cpu"]""")
        )

        val assignments = GlafiraDesktopDispatchPlanner.plan(
            tasks, nodes, mapOf("desktop-01" to 1), nowMillis = 2_000L
        )

        assertEquals("desktop-01", assignments.first { it.taskId == "gpu-task" }.deviceId)
        assertEquals("desktop-02", assignments.first { it.taskId == "cpu-task-1" }.deviceId)
        assertEquals("desktop-02", assignments.first { it.taskId == "cpu-task-2" }.deviceId)
        assertEquals(2, assignments.first { it.taskId == "gpu-task" }.projectedActiveTasks)
        assertTrue(assignments.all { it.projectId == "project-a" })
    }

    @Test fun respectsNodeCapacityAndSkipsExpiredOrUnmatchedTasks() {
        val nodes = listOf(
            GlafiraNodeCapabilities("desktop-01", true, 1, setOf("cpu")),
            GlafiraNodeCapabilities("desktop-02", false, 8, setOf("gpu"))
        )
        val tasks = listOf(
            task("expired", """["cpu"]""", deadline = 1_500L),
            task("gpu", """["gpu"]"""),
            task("cpu-a", """["cpu"]"""),
            task("cpu-b", """["cpu"]""")
        )
        val assignments = GlafiraDesktopDispatchPlanner.plan(
            tasks, nodes, emptyMap(), nowMillis = 2_000L
        )
        assertEquals(listOf("cpu-a"), assignments.map { it.taskId })
        assertEquals("desktop-01", assignments.single().deviceId)
    }
}
