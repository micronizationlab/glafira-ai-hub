package com.micronizationlab.glafira.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopTaskQueueTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = 1_800_000_000_000L
    private fun queue(max: Int = 10) =
        DesktopTaskQueue(temp.root.toPath().resolve("tasks.json"), max) { now }

    private fun task(
        id: String = "task_1",
        priority: Int = 5,
        expiresAt: Long = now + 60_000
    ) = DesktopTaskEnvelope(
        taskId = id,
        projectId = "project-a",
        taskType = "analysis.document",
        instruction = "Analyze the supplied document references.",
        contextRefs = listOf("file-ref-1"),
        requiredCapabilities = setOf(DesktopTaskCapability.DOCUMENT_ANALYSIS),
        requiredScopes = setOf("project.read"),
        priority = priority,
        createdAt = now,
        expiresAt = expiresAt,
        idempotencyKey = "idempotency-key-$id"
    )

    @Test
    fun enqueuePersistsAndRejectsDuplicateTaskAndIdempotencyKeys() {
        val firstQueue = queue()
        firstQueue.enqueue(task())
        val reopened = queue()
        assertNotNull(reopened.get("task_1"))
        assertThrows(IllegalArgumentException::class.java) { reopened.enqueue(task()) }
        assertThrows(IllegalArgumentException::class.java) {
            reopened.enqueue(task(id = "task_2").copy(idempotencyKey = "idempotency-key-task_1"))
        }
    }

    @Test
    fun claimUsesPriorityAndPreservesFifoForEqualPriority() {
        val q = queue()
        q.enqueue(task(id = "low", priority = 1))
        q.enqueue(task(id = "high", priority = 9))
        q.enqueue(task(id = "normal", priority = 5))
        assertEquals("high", q.claimNext()?.envelope?.taskId)
        assertEquals("normal", q.claimNext()?.envelope?.taskId)
        assertEquals("low", q.claimNext()?.envelope?.taskId)
    }

    @Test
    fun taskLifecycleRequiresValidTransitionsAndStoresProgressAndResult() {
        val q = queue()
        q.enqueue(task())
        q.claimNext()
        q.transition("task_1", DesktopTaskState.ACCEPTED)
        q.transition("task_1", DesktopTaskState.RUNNING)
        val progress = q.reportProgress("task_1", 45, "Reading pages")
        assertEquals(45, progress.progressPercent)
        val completed = q.complete("task_1", "Analysis complete")
        assertEquals(DesktopTaskState.SUCCEEDED, completed.state)
        assertEquals("Analysis complete", completed.result)
        assertThrows(IllegalArgumentException::class.java) {
            q.transition("task_1", DesktopTaskState.RUNNING)
        }
    }

    @Test
    fun expiredQueuedTasksAreNotClaimed() {
        val q = queue()
        q.enqueue(task(expiresAt = now + 10))
        now += 11
        assertNull(q.claimNext())
        assertEquals(DesktopTaskState.EXPIRED, q.get("task_1")?.state)
    }

    @Test
    fun queueCapacityIsBounded() {
        val q = queue(max = 1)
        q.enqueue(task())
        assertThrows(IllegalArgumentException::class.java) { q.enqueue(task(id = "task_2")) }
    }
}
