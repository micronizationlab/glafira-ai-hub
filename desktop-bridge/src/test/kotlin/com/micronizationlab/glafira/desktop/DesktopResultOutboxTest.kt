package com.micronizationlab.glafira.desktop

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopResultOutboxTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private fun queue() = DesktopTaskQueue(
        temp.root.toPath().resolve("results.json"), clock = { now },
        protector = desktopTaskQueueTestProtector()
    )
    private fun completed(queue: DesktopTaskQueue, id: String): DesktopTaskRecord {
        queue.enqueue(DesktopTaskEnvelope(taskId = id, projectId = "project-a", taskType = "analysis.document",
            instruction = "Analyze", contextRefs = emptyList(), requiredCapabilities = emptySet(), requiredScopes = emptySet(),
            priority = 1, createdAt = now, expiresAt = now + 10_000, idempotencyKey = "idempotency-key-$id"))
        queue.claimNext()
        queue.transition(id, DesktopTaskState.ACCEPTED)
        queue.transition(id, DesktopTaskState.RUNNING)
        return queue.complete(id, "actual-$id")
    }

    @Test
    fun lostAckAfterRemoteCommitRetriesSameImmutableOutputAfterRestart() {
        val q = queue()
        val result = completed(q, "task-001")
        val remoteReceipts = mutableMapOf<String, String?>()
        val failed = DesktopResultOutbox(q, "project-a") { record ->
            remoteReceipts[record.envelope.taskId] = record.result
            throw IllegalStateException("connection dropped after remote commit")
        }
        assertEquals(0, failed.flush())
        assertEquals(result.result, queue().pendingResults("project-a").single().result)
        val restarted = queue()
        var sends = 0
        val recovered = DesktopResultOutbox(restarted, "project-a") { record ->
            sends++
            assertEquals(remoteReceipts[record.envelope.taskId], record.result)
        }
        assertEquals(1, recovered.flush())
        assertEquals(0, recovered.flush())
        assertEquals(1, remoteReceipts.size)
        assertEquals(1, sends)
        assertTrue(queue().pendingResults("project-a").isEmpty())
        assertNull(restarted.claimNext())
    }

    @Test
    fun oneUnavailableReceiptDoesNotBlockOtherResultDeliveries() {
        val q = queue()
        completed(q, "task-001")
        completed(q, "task-002")
        val outbox = DesktopResultOutbox(q, "project-a") { record ->
            if (record.envelope.taskId == "task-001") throw IllegalStateException("temporary failure")
        }
        assertEquals(1, outbox.flush())
        assertEquals(listOf("task-001"), queue().pendingResults("project-a").map { it.envelope.taskId })
    }
}
