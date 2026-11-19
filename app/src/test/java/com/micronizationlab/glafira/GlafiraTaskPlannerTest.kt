package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraTaskPlannerTest {
    private fun task(id: String, dependsOn: String = ""): String =
        """{"id":"$id","title":"Task $id","instruction":"Do task $id","executor":"GLAFIRA","dependsOn":[$dependsOn]}"""

    @Test
    fun dependentSubtasksArePlacedOnlyAfterEveryPrerequisiteBatch() {
        val plan = GlafiraTaskPlanner.parseAndValidate(
            """{"goal":"Validate dependency order","tasks":[
                ${task("a")},
                ${task("b")},
                ${task("c", "\"a\"")},
                ${task("d", "\"b\",\"c\"")}
            ]}"""
        )

        val batches = GlafiraTaskPlanner.readyBatches(plan)
        assertEquals(
            listOf(listOf("a", "b"), listOf("c"), listOf("d")),
            batches.map { batch -> batch.map { it.id } }
        )
    }

    @Test
    fun independentSubtasksShareAParallelBatch() {
        val plan = GlafiraTaskPlanner.parseAndValidate(
            """{"goal":"Parallel independent work","tasks":[${task("a")},${task("b")},${task("c")}]}"""
        )
        val batches = GlafiraTaskPlanner.readyBatches(plan)
        assertEquals(listOf("a", "b"), batches.first().map { it.id })
        assertEquals(listOf("c"), batches.last().map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCyclicSubtaskDependencies() {
        GlafiraTaskPlanner.parseAndValidate(
            """{"goal":"Cycle","tasks":[${task("a", "\"b\"")},${task("b", "\"a\"")}]}"""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownSubtaskDependency() {
        GlafiraTaskPlanner.parseAndValidate(
            """{"goal":"Unknown dependency","tasks":[${task("a", "\"missing\"")}]}"""
        )
    }

    @Test
    fun completedSubtaskCannotBeMarkedDoneBeforeItsDependencies() {
        val plan = GlafiraTaskPlanner.parseAndValidate(
            """{"goal":"Completion guard","tasks":[${task("a")},${task("b", "\"a\"")}]}"""
        )
        val accepted = runCatching { GlafiraTaskPlanner.readyBatches(plan, setOf("b")) }
        assertTrue(accepted.isFailure)
    }
}
