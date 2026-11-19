package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GlafiraTaskResultInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun runningTask(store: GlafiraTaskStore, desktop: Boolean = false): GlafiraTask {
        val now = System.currentTimeMillis()
        val id = "result-${UUID.randomUUID()}"
        val task = GlafiraTask(taskId = id, projectId = "result-project",
            taskType = if (desktop) GlafiraTaskStore.DESKTOP_TASK_TYPE else "project.read",
            payload = "{}", origin = "test", createdAtMillis = now, updatedAtMillis = now,
            idempotencyKey = "operation-$id")
        store.enqueue(task)
        assertTrue(store.transition(id, setOf(GlafiraTaskState.QUEUED), GlafiraTaskState.RUNNING, now))
        if (desktop) store.writableDatabase.execSQL("""INSERT INTO core_desktop_leases
            (task_id, device_id, lease_expires_at, attempt_count, updated_at) VALUES (?, ?, ?, ?, ?)""".trimIndent(),
            arrayOf(id, "desktop-device", now + 600_000, 1, now))
        return task
    }

    @Test
    fun bridgeAppendUsesSharedStorageAndStableOperationIdentity() {
        val projectId = "result-project-${UUID.randomUUID()}"
        saveProjects(context, loadProjects(context) + Project(projectId, "Result test"))
        val conversationId = "${projectId}_ГЛАФИРА"
        val now = System.currentTimeMillis()
        val request = GlafiraBridgeRequest(1, "request-001", "operation-001", "device-001", projectId,
            GlafiraBridgeOperation.CONVERSATION_APPEND, now, now + 60_000,
            mapOf("conversationId" to conversationId, "messageText" to "append once"))
        val dispatcher = GlafiraBridgeOperationDispatcher(context)
        assertEquals(GlafiraBridgeStatus.SUCCEEDED, dispatcher.dispatch(request).status)
        appendHistory(context, conversationId, listOf(Msg("concurrent UI message", true, "12:00", operationId = "ui-message")))
        assertEquals(GlafiraBridgeStatus.SUCCEEDED, dispatcher.dispatch(request).status)
        val messages = loadHistory(context, conversationId)
        assertEquals(1, messages.count { it.text == "append once" })
        assertEquals(1, messages.count { it.text == "concurrent UI message" })
    }

    @Test
    fun coreCompletionPersistsActualResultAndScopedStableIdentity() {
        GlafiraTaskStore(context).use { store ->
            val journal = GlafiraTaskResultStore(store)
            val task = runningTask(store)
            val values = mapOf("messageText" to "actual output", "messageId" to "id-17")
            assertTrue(journal.complete(task, values, System.currentTimeMillis()))
            assertEquals(GlafiraTaskState.SUCCEEDED, store.getTask(task.taskId)?.state)
            val reopened = GlafiraTaskResultStore(store).get(task.taskId, task.projectId)!!
            assertEquals(values, reopened.values)
            assertEquals("result-${task.taskId}", reopened.resultId)
            assertNull(journal.get(task.taskId, "other-project"))
            assertTrue(journal.complete(task, values, System.currentTimeMillis()))
            assertFalse(journal.complete(task, mapOf("messageText" to "different"), System.currentTimeMillis()))
            assertTrue(journal.acknowledge(task.taskId, task.projectId, reopened.resultId, "desktop-device", 123))
            assertTrue(journal.acknowledge(task.taskId, task.projectId, reopened.resultId, "desktop-device", 124))
            assertNotNull(journal.get(task.taskId, task.projectId))
        }
    }

    @Test
    fun cancellationWinningCompletionRaceStillPreservesActualOutput() {
        GlafiraTaskStore(context).use { store ->
            val journal = GlafiraTaskResultStore(store)
            val task = runningTask(store)
            assertTrue(store.transition(task.taskId, setOf(GlafiraTaskState.RUNNING), GlafiraTaskState.CANCELLED, System.currentTimeMillis()))
            assertFalse(journal.complete(task, mapOf("output" to "finished while cancel was requested"), System.currentTimeMillis()))
            assertEquals(GlafiraTaskState.CANCELLED, store.getTask(task.taskId)?.state)
            assertEquals("finished while cancel was requested", journal.get(task.taskId, task.projectId)?.values?.get("output"))
        }
    }

    @Test
    fun lostTerminalAcknowledgementCanRetryAfterLeaseHasBeenDeleted() {
        GlafiraTaskStore(context).use { store ->
            val journal = GlafiraTaskResultStore(store)
            val task = runningTask(store, desktop = true)
            assertTrue(journal.receiveDesktop(task.taskId, task.projectId, "desktop-device", "SUCCEEDED", 100, "done", "none", null))
            assertNull(store.desktopTaskLeaseExpiry(task.taskId))
            assertTrue(journal.receiveDesktop(task.taskId, task.projectId, "desktop-device", "SUCCEEDED", 100, "done", "none", null))
            assertFalse(journal.receiveDesktop(task.taskId, task.projectId, "another-device", "SUCCEEDED", 100, "done", "none", null))
            assertFalse(journal.receiveDesktop(task.taskId, "another-project", "desktop-device", "SUCCEEDED", 100, "done", "none", null))
            assertFalse(journal.receiveDesktop(task.taskId, task.projectId, "desktop-device", "SUCCEEDED", 100, "done", "changed", null))
            assertEquals("none", journal.get(task.taskId, task.projectId)?.values?.get("result"))
        }
    }

    @Test
    fun receiptWriteFailureRollsBackTaskLegacyResultAndLeaseDeletionTogether() {
        GlafiraTaskStore(context).use { store ->
            val journal = GlafiraTaskResultStore(store)
            val task = runningTask(store, desktop = true)
            store.writableDatabase.execSQL("""CREATE TEMP TRIGGER result_failure BEFORE INSERT ON core_task_results
                WHEN NEW.task_id = '${task.taskId}' BEGIN SELECT RAISE(ABORT, 'injected result write failure'); END""".trimIndent())
            try {
                assertThrows(Exception::class.java) {
                    journal.receiveDesktop(task.taskId, task.projectId, "desktop-device", "SUCCEEDED", 100, "done", "actual result", null)
                }
                assertEquals(GlafiraTaskState.RUNNING, store.getTask(task.taskId)?.state)
                assertNotNull(store.desktopTaskLeaseExpiry(task.taskId))
                assertNull(store.desktopTaskResult(task.taskId))
                assertNull(journal.get(task.taskId, task.projectId))
            } finally { store.writableDatabase.execSQL("DROP TRIGGER result_failure") }
        }
    }
}
