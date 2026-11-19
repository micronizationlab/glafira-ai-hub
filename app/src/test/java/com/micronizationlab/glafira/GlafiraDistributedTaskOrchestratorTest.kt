package com.micronizationlab.glafira

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class GlafiraDistributedTaskOrchestratorTest {
    private val safeCapabilities = setOf(
        "core.orchestration", "document.analysis", "code.build", "web.research"
    )

    private fun plan() = GlafiraTaskPlanner.parseAndValidate(
        """{"goal":"Dependency ordering","tasks":[
            {"id":"a","title":"A","instruction":"A","executor":"GLAFIRA","dependsOn":[]},
            {"id":"b","title":"B","instruction":"B","executor":"ANALYST","dependsOn":[]},
            {"id":"c","title":"C","instruction":"C","executor":"PROGRAMMER","dependsOn":["a"]},
            {"id":"d","title":"D","instruction":"D","executor":"RESEARCHER","dependsOn":["b","c"]}
        ]}"""
    )

    @Test
    fun dependentTasksStartOnlyAfterEveryPrerequisiteSucceeded() = runBlocking {
        val completed = Collections.synchronizedSet(mutableSetOf<String>())
        val orchestrator = GlafiraDistributedTaskOrchestrator(
            desktop = GlafiraDesktopTaskPort { error("Desktop is not available in this test.") },
            android = GlafiraLocalTaskPort { task ->
                when (task.subtaskId) {
                    "c" -> assertTrue(completed.contains("a"))
                    "d" -> assertTrue(completed.containsAll(setOf("a", "b", "c")))
                }
                completed += task.subtaskId
                GlafiraDistributedTaskResult(
                    task.taskId, GlafiraExecutionNode.ANDROID, true, output = "done-${task.subtaskId}"
                )
            },
            desktopCapabilities = { null },
            safeAndroidCapabilities = safeCapabilities,
            clock = { 10_000L }
        )

        val results = orchestrator.execute("project-1", plan())

        assertEquals(4, results.size)
        assertEquals(setOf("a", "b", "c", "d"), completed)
        assertTrue(results.all { it.succeeded })
    }

    @Test
    fun failedPrerequisitePreventsDependentSubtasksFromStarting() = runBlocking {
        val started = Collections.synchronizedSet(mutableSetOf<String>())
        val firstBatchFinished = CompletableDeferred<Unit>()
        val orchestrator = GlafiraDistributedTaskOrchestrator(
            desktop = GlafiraDesktopTaskPort { error("Desktop is not available in this test.") },
            android = GlafiraLocalTaskPort { task ->
                started += task.subtaskId
                if (task.subtaskId == "a") {
                    firstBatchFinished.complete(Unit)
                    GlafiraDistributedTaskResult(task.taskId, GlafiraExecutionNode.ANDROID, false,
                        errorCode = "DEPENDENCY_FAILED")
                } else {
                    GlafiraDistributedTaskResult(task.taskId, GlafiraExecutionNode.ANDROID, true)
                }
            },
            desktopCapabilities = { null },
            safeAndroidCapabilities = safeCapabilities,
            clock = { 10_000L }
        )

        val results = orchestrator.execute("project-1", plan())

        assertTrue(firstBatchFinished.isCompleted)
        assertTrue(started.containsAll(setOf("a", "b")))
        assertFalse(started.contains("c"))
        assertFalse(started.contains("d"))
        assertTrue(results.any { !it.succeeded })
    }
}
