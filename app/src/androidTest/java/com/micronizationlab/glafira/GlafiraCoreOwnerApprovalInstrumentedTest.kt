package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GlafiraCoreOwnerApprovalInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun pendingOwnerApprovalSurvivesRecreatingTheStoreAdapter() {
        val taskId = "approval-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val task = GlafiraTask(
            taskId = taskId,
            projectId = "instrumented-project",
            taskType = "message.send",
            payload = """{"actionSummary":"Send one explicitly approved test message"}""",
            origin = "instrumented-test",
            createdAtMillis = now,
            updatedAtMillis = now,
            idempotencyKey = "idempotency-$taskId"
        )

        val queue = GlafiraTaskStore(context)
        assertTrue(queue.enqueue(task).inserted)
        assertTrue(
            queue.transition(
                taskId,
                setOf(GlafiraTaskState.QUEUED),
                GlafiraTaskState.RUNNING,
                now + 1L,
                null,
                null
            )
        )

        val firstAdapter = GlafiraCoreOwnerApprovalStore(queue)
        assertTrue(firstAdapter.requestApproval(task, "Send one explicitly approved test message", now + 2L))
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, queue.getTask(taskId)?.state)

        val secondAdapter = GlafiraCoreOwnerApprovalStore(GlafiraTaskStore(context))
        val pending = secondAdapter.pendingApprovals(now + 3L)

        assertEquals(taskId, pending.single { it.task.taskId == taskId }.task.taskId)
        assertEquals(setOf("communications.send"), pending.single { it.task.taskId == taskId }.requestedScopes)
    }
}
