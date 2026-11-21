package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GlafiraCoreOwnerApprovalStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var taskStore: GlafiraTaskStore
    private lateinit var approvals: GlafiraCoreOwnerApprovalStore

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        taskStore = GlafiraTaskStore(context)
        approvals = GlafiraCoreOwnerApprovalStore(taskStore)
    }

    @After
    fun tearDown() {
        taskStore.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    private fun task(id: String = "approval-task") = GlafiraTask(
        taskId = id,
        projectId = "approval-project",
        taskType = "message.send",
        payload = """{"actionSummary":"Send a test message to test@example.invalid","recipient":"test@example.invalid","message":"test"}""",
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "approval-project:$id",
        requiredScopes = setOf("communications.send")
    )

    private fun authenticatedOwnerSession(): GlafiraCoreOwnerSession {
        // Test-only reflection creates a short-lived proof object; production code can
        // create this type only from the BiometricPrompt success callback.
        val constructor = GlafiraCoreOwnerSession::class.java.getDeclaredConstructor(
            String::class.java,
            Long::class.javaPrimitiveType
        )
        constructor.isAccessible = true
        return constructor.newInstance(
            UUID.randomUUID().toString(),
            SystemClock.elapsedRealtime() + 120_000L
        )
    }

    @Test
    fun approvalRequestPersistsAcrossReopenAndIssuesExactOneUseTaskGrant() {
        val original = task()
        taskStore.enqueue(original)
        val running = requireNotNull(taskStore.claimNext(2_000L))
        assertEquals(GlafiraTaskState.RUNNING, running.state)

        assertTrue(approvals.requestApproval(
            running,
            "Send a test message to test@example.invalid",
            3_000L
        ))
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, taskStore.getTask(original.taskId)?.state)
        assertEquals(1, approvals.pendingApprovals(3_001L).size)

        taskStore.close()
        taskStore = GlafiraTaskStore(context)
        approvals = GlafiraCoreOwnerApprovalStore(taskStore)
        val persisted = approvals.pendingApprovals(3_002L).single()
        assertEquals(original.taskId, persisted.task.taskId)
        assertEquals(setOf("communications.send"), persisted.requestedScopes)

        assertEquals(
            GlafiraCoreOwnerApprovalMutation.APPROVED,
            approvals.approve(
                original.taskId,
                setOf("communications.send"),
                authenticatedOwnerSession(),
                4_000L
            )
        )
        val queued = requireNotNull(taskStore.getTask(original.taskId))
        assertEquals(GlafiraTaskState.QUEUED, queued.state)

        val grant = requireNotNull(approvals.approvalGrant(
            queued,
            "Send a test message to test@example.invalid",
            4_001L
        ))
        assertEquals(original.taskId, grant.taskId)
        assertEquals("approval-project", grant.projectId)
        assertEquals("message.send", grant.toolId)
        assertEquals(
            ToolAuthorizationDecision.ALLOWED,
            GlafiraToolRegistry.authorize(
                requireNotNull(GlafiraToolRegistry.find("message.send")),
                queued,
                "Send a test message to test@example.invalid",
                grant,
                4_002L
            )
        )
        assertNull("An approved grant is consumed exactly once", approvals.approvalGrant(
            queued,
            "Send a test message to test@example.invalid",
            4_003L
        ))
    }

    @Test
    fun approvalRejectsWrongScopeAndChangedActionHash() {
        val original = task()
        taskStore.enqueue(original)
        val running = requireNotNull(taskStore.claimNext(2_000L))
        assertTrue(approvals.requestApproval(
            running,
            "Send a test message to test@example.invalid",
            3_000L
        ))

        assertEquals(
            GlafiraCoreOwnerApprovalMutation.INVALID_SCOPE_SELECTION,
            approvals.approve(
                original.taskId,
                setOf("project.read"),
                authenticatedOwnerSession(),
                4_000L
            )
        )
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, taskStore.getTask(original.taskId)?.state)

        assertEquals(
            GlafiraCoreOwnerApprovalMutation.APPROVED,
            approvals.approve(
                original.taskId,
                setOf("communications.send"),
                authenticatedOwnerSession(),
                4_100L
            )
        )
        taskStore.writableDatabase.update(
            "core_tasks",
            ContentValues().apply {
                put("payload", """{"actionSummary":"Send a DIFFERENT test message","recipient":"other@example.invalid","message":"changed"}""")
            },
            "task_id = ?",
            arrayOf(original.taskId)
        )
        val changed = requireNotNull(taskStore.getTask(original.taskId))
        assertNull(approvals.approvalGrant(
            changed,
            "Send a DIFFERENT test message",
            4_200L
        ))
    }

    @Test
    fun pendingApprovalExpiryFailsWaitingTaskAndRemovesItFromReviewQueue() {
        val original = task("approval-expiry")
        taskStore.enqueue(original)
        val running = requireNotNull(taskStore.claimNext(2_000L))
        assertTrue(approvals.requestApproval(
            running,
            "Send a test message to test@example.invalid",
            3_000L
        ))

        val afterExpiry = 3_000L + 24L * 60L * 60L * 1_000L + 1L
        assertTrue(approvals.pendingApprovals(afterExpiry).isEmpty())
        val expired = requireNotNull(taskStore.getTask(original.taskId))
        assertEquals(GlafiraTaskState.FAILED, expired.state)
        assertEquals("OWNER_APPROVAL_EXPIRED", expired.lastError)
    }

    @Test
    fun consumedOwnerSessionCannotApproveTwice() {
        val original = task("approval-session-once")
        taskStore.enqueue(original)
        val running = requireNotNull(taskStore.claimNext(2_000L))
        assertTrue(approvals.requestApproval(
            running,
            "Send a test message to test@example.invalid",
            3_000L
        ))
        val session = authenticatedOwnerSession()

        assertEquals(
            GlafiraCoreOwnerApprovalMutation.APPROVED,
            approvals.approve(
                original.taskId,
                setOf("communications.send"),
                session,
                4_000L
            )
        )
        assertEquals(
            GlafiraCoreOwnerApprovalMutation.OWNER_AUTHENTICATION_REQUIRED,
            approvals.approve(
                original.taskId,
                setOf("communications.send"),
                session,
                4_001L
            )
        )
    }

    companion object {
        private const val DATABASE_NAME = "glafira_core_tasks.db"
    }
}
