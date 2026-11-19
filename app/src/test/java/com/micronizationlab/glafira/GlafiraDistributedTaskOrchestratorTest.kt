package com.micronizationlab.glafira

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

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
    @Test
    fun independentAndroidSubtasksRunConcurrentlyWithinConfiguredLimit() = runBlocking {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val orchestrator = GlafiraDistributedTaskOrchestrator(
            desktop = GlafiraDesktopTaskPort { error("Desktop is not available in this test.") },
            android = GlafiraLocalTaskPort { task ->
                if (task.subtaskId == "a" || task.subtaskId == "b") {
                    val current = active.incrementAndGet()
                    maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                    if (current == 2) bothStarted.complete(Unit)
                    try { release.await() } finally { active.decrementAndGet() }
                }
                GlafiraDistributedTaskResult(task.taskId, GlafiraExecutionNode.ANDROID, true)
            },
            desktopCapabilities = { null },
            safeAndroidCapabilities = safeCapabilities,
            localMaxParallelTasks = { 2 },
            clock = { 10_000L }
        )

        val execution = async { orchestrator.execute("project-1", plan()) }
        withTimeout(5_000L) { bothStarted.await() }
        assertEquals(2, maximumActive.get())
        release.complete(Unit)
        assertEquals(4, withTimeout(5_000L) { execution.await() }.size)
    }

    @Test
    fun desktopBridgeWorkNeverExceedsAdvertisedNodeParallelism() = runBlocking {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val desktopNode = GlafiraNodeCapabilities(
            nodeId = "desktop-node-0001",
            available = true,
            maxParallelTasks = 1,
            capabilities = safeCapabilities
        )
        val orchestrator = GlafiraDistributedTaskOrchestrator(
            desktop = GlafiraDesktopTaskPort { task ->
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    delay(25L)
                    GlafiraDistributedTaskResult(task.taskId, GlafiraExecutionNode.DESKTOP, true)
                } finally {
                    active.decrementAndGet()
                }
            },
            android = GlafiraLocalTaskPort { error("All tasks should be routed through Desktop.") },
            desktopCapabilities = { desktopNode },
            safeAndroidCapabilities = emptySet(),
            localMaxParallelTasks = { 2 },
            clock = { 10_000L }
        )

        val results = orchestrator.execute("project-1", plan())

        assertEquals(4, results.size)
        assertEquals(1, maximumActive.get())
    }

    @Test
    fun ownerGlobalBudgetCapsDesktopEvenWhenNodeAdvertisesMoreCapacity() = runBlocking {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val desktopNode = GlafiraNodeCapabilities(
            nodeId = "desktop-node-0002",
            available = true,
            maxParallelTasks = 8,
            capabilities = safeCapabilities
        )
        val orchestrator = GlafiraDistributedTaskOrchestrator(
            desktop = GlafiraDesktopTaskPort { task ->
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    delay(25L)
                    GlafiraDistributedTaskResult(task.taskId, GlafiraExecutionNode.DESKTOP, true)
                } finally {
                    active.decrementAndGet()
                }
            },
            android = GlafiraLocalTaskPort { error("All tasks should be routed through Desktop.") },
            desktopCapabilities = { desktopNode },
            safeAndroidCapabilities = emptySet(),
            localMaxParallelTasks = { 1 },
            clock = { 10_000L }
        )

        val results = orchestrator.execute("project-1", plan())

        assertEquals(4, results.size)
        assertEquals(1, maximumActive.get())
    }

    @Test
    fun thrownAgentFailureDoesNotCancelIndependentSiblingOrRunDependents() = runBlocking {
        val started = Collections.synchronizedSet(mutableSetOf<String>())
        val orchestrator = GlafiraDistributedTaskOrchestrator(
            desktop = GlafiraDesktopTaskPort { error("Desktop is not available in this test.") },
            android = GlafiraLocalTaskPort { task ->
                started += task.subtaskId
                if (task.subtaskId == "a") throw IllegalStateException("simulated local agent crash")
                if (task.subtaskId == "b") delay(50L)
                GlafiraDistributedTaskResult(task.taskId, GlafiraExecutionNode.ANDROID, true)
            },
            desktopCapabilities = { null },
            safeAndroidCapabilities = safeCapabilities,
            localMaxParallelTasks = { 2 },
            clock = { 10_000L }
        )

        val results = orchestrator.execute("project-1", plan())

        assertEquals(2, results.size)
        assertEquals(1, results.count { !it.succeeded })
        assertEquals(1, results.count { it.succeeded })
        assertTrue(started.containsAll(setOf("a", "b")))
        assertFalse(started.contains("c"))
        assertFalse(started.contains("d"))
    }

    @Test
    fun globalBudgetIsSharedAcrossIndependentProjectDispatchers() = runBlocking {
        val limiter = GlafiraCoreExecutionLimiter()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        fun orchestrator() = GlafiraDistributedTaskOrchestrator(
            desktop = GlafiraDesktopTaskPort { error("Desktop is not available in this test.") },
            android = GlafiraLocalTaskPort { task ->
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    delay(25L)
                    GlafiraDistributedTaskResult(task.taskId, GlafiraExecutionNode.ANDROID, true)
                } finally {
                    active.decrementAndGet()
                }
            },
            desktopCapabilities = { null },
            safeAndroidCapabilities = safeCapabilities,
            localMaxParallelTasks = { 2 },
            clock = { 10_000L },
            globalExecutionLimiter = limiter
        )

        val results = listOf(
            async { orchestrator().execute("project-a", plan()) },
            async { orchestrator().execute("project-b", plan()) }
        ).awaitAll()

        assertEquals(8, results.sumOf { it.size })
        assertEquals(2, maximumActive.get())
    }

}
