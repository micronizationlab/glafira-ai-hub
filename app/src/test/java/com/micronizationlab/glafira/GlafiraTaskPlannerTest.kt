package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraTaskPlannerTest {
    @Test
    fun createsDependencyAwareBatchesWithMaximumTwoTasks() {
        val plan = GlafiraTaskPlanner.parseAndValidate(
            """{"goal":"Prepare a release","tasks":[
                {"id":"a","title":"Audit","instruction":"Audit code","executor":"ANALYST","dependsOn":[]},
                {"id":"b","title":"Review UI","instruction":"Review UI","executor":"ANALYST","dependsOn":[]},
                {"id":"c","title":"Implement","instruction":"Implement fixes","executor":"PROGRAMMER","dependsOn":["a","b"]},
                {"id":"d","title":"Verify","instruction":"Verify changes","executor":"GLAFIRA","dependsOn":["c"]}
            ]}"""
        )

        val batches = GlafiraTaskPlanner.readyBatches(plan)
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d")), batches.map { batch -> batch.map { it.id } })
        assertTrue(batches.all { it.size <= GlafiraTaskPlanner.MAX_PARALLEL_TASKS })
    }

    @Test
    fun rejectsCompletedTaskWhenItsDependenciesAreNotCompleted() {
        val plan = GlafiraTaskPlanner.parseAndValidate(
            """{"goal":"x","tasks":[
                {"id":"a","title":"A","instruction":"Do A","executor":"GLAFIRA","dependsOn":[]},
                {"id":"b","title":"B","instruction":"Do B","executor":"GLAFIRA","dependsOn":["a"]}
            ]}"""
        )
        val error = runCatching {
            GlafiraTaskPlanner.readyBatches(plan, completedIds = setOf("b"))
        }.exceptionOrNull()
        assertTrue(error?.message?.contains("зависимостей") == true)
    }

    @Test
    fun rejectsUnknownDependencies() {
        val raw = """{"goal":"x","tasks":[{"id":"a","title":"A","instruction":"Do A","executor":"GLAFIRA","dependsOn":["missing"]}]}"""
        val error = runCatching { GlafiraTaskPlanner.parseAndValidate(raw) }.exceptionOrNull()
        assertTrue(error?.message?.contains("неизвестной") == true)
    }

    @Test
    fun rejectsCycles() {
        val raw = """{"goal":"x","tasks":[
            {"id":"a","title":"A","instruction":"Do A","executor":"GLAFIRA","dependsOn":["b"]},
            {"id":"b","title":"B","instruction":"Do B","executor":"ANALYST","dependsOn":["a"]}
        ]}"""
        val error = runCatching { GlafiraTaskPlanner.parseAndValidate(raw) }.exceptionOrNull()
        assertTrue(error?.message?.contains("циклическая") == true)
    }

    @Test
    fun rejectsMoreThanFourSubtasks() {
        val raw = """{"goal":"x","tasks":[
            {"id":"a","title":"A","instruction":"Do A","executor":"GLAFIRA","dependsOn":[]},
            {"id":"b","title":"B","instruction":"Do B","executor":"GLAFIRA","dependsOn":[]},
            {"id":"c","title":"C","instruction":"Do C","executor":"GLAFIRA","dependsOn":[]},
            {"id":"d","title":"D","instruction":"Do D","executor":"GLAFIRA","dependsOn":[]},
            {"id":"e","title":"E","instruction":"Do E","executor":"GLAFIRA","dependsOn":[]}
        ]}"""
        val error = runCatching { GlafiraTaskPlanner.parseAndValidate(raw) }.exceptionOrNull()
        assertTrue(error?.message?.contains("от 1 до 4") == true)
    }
}
