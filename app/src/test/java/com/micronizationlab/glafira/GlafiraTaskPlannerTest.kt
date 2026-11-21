package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class GlafiraTaskPlannerTest {
    @Test fun acceptsSixteenBoundedNodesAndKeepsParallelBatchesAtTwo() {
        val tasks = JSONArray()
        repeat(GlafiraTaskPlanner.MAX_TASKS) { index ->
            tasks.put(JSONObject().apply {
                put("id", "task_$index")
                put("title", "Task $index")
                put("instruction", "Perform bounded task number $index")
                put("executor", "GLAFIRA")
                put("dependsOn", JSONArray())
            })
        }
        val plan = GlafiraTaskPlanner.parseAndValidate(
            JSONObject().put("goal", "Execute a sixteen-node bounded plan").put("tasks", tasks).toString()
        )
        assertEquals(16, plan.tasks.size)
        val batches = GlafiraTaskPlanner.readyBatches(plan)
        assertEquals(8, batches.size)
        assertEquals(listOf(2, 2, 2, 2, 2, 2, 2, 2), batches.map { it.size })
    }

    @Test fun rejectsMissingDependencyAndDuplicateDependencyEdges() {
        fun planWith(dependencies: JSONArray): String {
            val tasks = JSONArray()
            tasks.put(JSONObject().apply {
                put("id", "parent")
                put("title", "Parent")
                put("instruction", "Complete parent")
                put("executor", "GLAFIRA")
                put("dependsOn", JSONArray())
            })
            tasks.put(JSONObject().apply {
                put("id", "child")
                put("title", "Child")
                put("instruction", "Complete child")
                put("executor", "GLAFIRA")
                put("dependsOn", dependencies)
            })
            return JSONObject().put("goal", "Validate dependency references").put("tasks", tasks).toString()
        }

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            GlafiraTaskPlanner.parseAndValidate(planWith(JSONArray().put("missing")))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            GlafiraTaskPlanner.parseAndValidate(planWith(JSONArray().put("parent").put("parent")))
        }
    }

    @Test fun rejectsSeventeenthNodeBeforeExecution() {
        val tasks = JSONArray()
        repeat(GlafiraTaskPlanner.MAX_TASKS + 1) { index ->
            tasks.put(JSONObject().apply {
                put("id", "task_$index")
                put("title", "Task $index")
                put("instruction", "Perform bounded task number $index")
                put("executor", "GLAFIRA")
                put("dependsOn", JSONArray())
            })
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            GlafiraTaskPlanner.parseAndValidate(
                JSONObject().put("goal", "Too many tasks").put("tasks", tasks).toString()
            )
        }
    }
}
