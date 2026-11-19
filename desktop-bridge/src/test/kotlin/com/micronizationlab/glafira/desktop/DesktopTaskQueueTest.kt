package com.micronizationlab.glafira.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

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
    fun completedOperationPreservesItsActualResultAfterDeadline() {
        val q = queue()
        q.enqueue(task(expiresAt = now + 10))
        q.claimNext()
        q.transition("task_1", DesktopTaskState.ACCEPTED)
        q.transition("task_1", DesktopTaskState.RUNNING)
        now += 11

        val completed = q.complete("task_1", "late result")
        assertEquals(DesktopTaskState.SUCCEEDED, completed.state)
        assertNull(completed.errorCode)
        assertEquals("late result", completed.result)
    }

    @Test
    fun taskExpiredBeforeRunningIsNotDispatched() {
        val q = queue()
        q.enqueue(task(expiresAt = now + 10))
        q.claimNext()
        now += 11

        val accepted = q.transition("task_1", DesktopTaskState.ACCEPTED)
        assertEquals(DesktopTaskState.EXPIRED, accepted.state)
    }

    @Test
    fun retainedTaskHistoryIsBoundedWithoutDroppingActiveWork() {
        val q = DesktopTaskQueue(null, 10) { now }
        repeat(DesktopTaskQueue.MAX_RETAINED_RECORDS + 2) { index ->
            val id = "task_$index"
            q.enqueue(task(id = id))
            q.claimNext()
            q.transition(id, DesktopTaskState.ACCEPTED)
            q.transition(id, DesktopTaskState.RUNNING)
            val completed = q.complete(id, "result-$index")
            q.acknowledgeResult(id, completed.sequence)
        }

        assertEquals(DesktopTaskQueue.MAX_RETAINED_RECORDS, q.list().size)
        assertNull(q.get("task_0"))
        assertNotNull(q.get("task_${DesktopTaskQueue.MAX_RETAINED_RECORDS + 1}"))
    }

    @Test
    fun terminalResultSurvivesRestartUntilMatchingAcknowledgement() {
        val q = queue()
        q.enqueue(task())
        q.claimNext()
        q.transition("task_1", DesktopTaskState.ACCEPTED)
        q.transition("task_1", DesktopTaskState.RUNNING)
        val terminal = q.complete("task_1", "actual output")
        val reopened = queue()
        assertEquals("actual output", reopened.pendingResults("project-a").single().result)
        assertEquals(0, reopened.pendingResults("other-project").size)
        assertEquals(false, reopened.acknowledgeResult("task_1", terminal.sequence - 1))
        assertEquals(1, reopened.pendingResults("project-a").size)
        assertEquals(true, reopened.acknowledgeResult("task_1", terminal.sequence))
        assertEquals(0, queue().pendingResults("project-a").size)
        assertNull(reopened.claimNext())
    }

    @Test
    fun unacknowledgedResultsAreNeverPrunedWhenHistoryIsFull() {
        val q = queue()
        repeat(DesktopTaskQueue.MAX_RETAINED_RECORDS) { index ->
            val id = "task_$index"
            q.enqueue(task(id = id))
            q.claimNext()
            q.transition(id, DesktopTaskState.ACCEPTED)
            q.transition(id, DesktopTaskState.RUNNING)
            q.complete(id, "result-$index")
        }
        assertThrows(IllegalStateException::class.java) { q.enqueue(task(id = "overflow")) }
        assertEquals(DesktopTaskQueue.MAX_RETAINED_RECORDS, queue().pendingResults("project-a").size)
        assertEquals("result-0", queue().get("task_0")?.result)
    }

    @Test
    fun rejectsOversizedQueueFileBeforeReadingItIntoMemory() {
        val path = temp.root.toPath().resolve("oversized-tasks.json")
        Files.writeString(path, " ".repeat(16 * 1024 * 1024 + 1))
        val q = DesktopTaskQueue(path, 10) { now }
        assertThrows(IllegalArgumentException::class.java) { q.list() }
    }

    @Test
    fun queueCapacityIsBounded() {
        val q = queue(max = 1)
        q.enqueue(task())
        assertThrows(IllegalArgumentException::class.java) { q.enqueue(task(id = "task_2")) }
    }
}
