package com.micronizationlab.glafira

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
        val trace = GlafiraCoreTraceStore(taskStore).query(
            GlafiraCoreTraceQuery("approval-project", taskId = original.taskId),
            GlafiraCoreTraceAccessContext("approval-project", taskIds = setOf(original.taskId))
        )
        assertEquals(
            setOf(
                GlafiraCoreTraceEventType.APPROVAL_REQUESTED,
                GlafiraCoreTraceEventType.APPROVAL_GRANTED,
                GlafiraCoreTraceEventType.APPROVAL_CONSUMED
            ),
            trace.events.map { it.eventType }.filter {
                it in setOf(
                    GlafiraCoreTraceEventType.APPROVAL_REQUESTED,
                    GlafiraCoreTraceEventType.APPROVAL_GRANTED,
                    GlafiraCoreTraceEventType.APPROVAL_CONSUMED
                )
            }.toSet()
        )
    }

    @Test
    fun approvalTraceInsertFailureRollsBackRequestGrantAndConsumption() {
        val original = task("approval-trace-failure")
        taskStore.enqueue(original)
        val running = requireNotNull(taskStore.claimNext(2_000L))
        val db = taskStore.writableDatabase
        val summary = "Send a test message to test@example.invalid"

        db.execSQL("""
            CREATE TRIGGER fail_approval_requested
            BEFORE INSERT ON ${GlafiraCoreTraceStore.TABLE}
            WHEN NEW.event_type = 'APPROVAL_REQUESTED'
            BEGIN SELECT RAISE(ABORT, 'TRACE_APPROVAL_INJECTED_FAILURE'); END
        """.trimIndent())
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            approvals.requestApproval(running, summary, 3_000L)
        }
        assertEquals(GlafiraTaskState.RUNNING, taskStore.getTask(original.taskId)?.state)
        assertTrue(approvals.pendingApprovals(3_001L).isEmpty())
        db.execSQL("DROP TRIGGER fail_approval_requested")

        assertTrue(approvals.requestApproval(running, summary, 3_100L))
        db.execSQL("""
            CREATE TRIGGER fail_approval_granted
            BEFORE INSERT ON ${GlafiraCoreTraceStore.TABLE}
            WHEN NEW.event_type = 'APPROVAL_GRANTED'
            BEGIN SELECT RAISE(ABORT, 'TRACE_APPROVAL_INJECTED_FAILURE'); END
        """.trimIndent())
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            approvals.approve(
                original.taskId,
                setOf("communications.send"),
                authenticatedOwnerSession(),
                4_000L
            )
        }
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, taskStore.getTask(original.taskId)?.state)
        assertEquals(1, approvals.pendingApprovals(4_001L).size)
        db.execSQL("DROP TRIGGER fail_approval_granted")

        assertEquals(
            GlafiraCoreOwnerApprovalMutation.APPROVED,
            approvals.approve(
                original.taskId,
                setOf("communications.send"),
                authenticatedOwnerSession(),
                4_100L
            )
        )
        val queued = requireNotNull(taskStore.getTask(original.taskId))
        db.execSQL("""
            CREATE TRIGGER fail_approval_consumed
            BEFORE INSERT ON ${GlafiraCoreTraceStore.TABLE}
            WHEN NEW.event_type = 'APPROVAL_CONSUMED'
            BEGIN SELECT RAISE(ABORT, 'TRACE_APPROVAL_INJECTED_FAILURE'); END
        """.trimIndent())
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            approvals.approvalGrant(queued, summary, 4_200L)
        }
        assertNotNull(approvals.peekApprovalGrant(queued, summary, 4_201L))
        db.execSQL("DROP TRIGGER fail_approval_consumed")
        assertNotNull(approvals.approvalGrant(queued, summary, 4_300L))
        assertNull(approvals.peekApprovalGrant(queued, summary, 4_301L))
    }

    @Test
    fun ownerRejectionIsRecordedAsApprovalRejectedTrace() {
        val original = task("approval-trace-rejected")
        taskStore.enqueue(original)
        val running = requireNotNull(taskStore.claimNext(2_000L))
        assertTrue(approvals.requestApproval(
            running,
            "Send a test message to test@example.invalid",
            3_000L
        ))
        assertEquals(
            GlafiraCoreOwnerApprovalMutation.REJECTED,
            approvals.reject(original.taskId, authenticatedOwnerSession(), 4_000L)
        )
        val events = GlafiraCoreTraceStore(taskStore).query(
            GlafiraCoreTraceQuery("approval-project", taskId = original.taskId),
            GlafiraCoreTraceAccessContext("approval-project", taskIds = setOf(original.taskId))
        ).events
        val rejected = events.single { it.eventType == GlafiraCoreTraceEventType.APPROVAL_REJECTED }
        assertEquals(GlafiraCoreTraceActorType.OWNER, rejected.actorType)
        assertEquals("owner", rejected.actorId)
        assertEquals("OWNER_APPROVAL_REJECTED", rejected.actionCode)
    }

    @Test
    fun expiredOwnerApprovalIsRecordedAndTaskFailsClosed() {
        val original = task("approval-trace-expired")
        taskStore.enqueue(original)
        val running = requireNotNull(taskStore.claimNext(2_000L))
        assertTrue(approvals.requestApproval(
            running,
            "Send a test message to test@example.invalid",
            3_000L
        ))
        val pending = approvals.pendingApprovals(3_001L).single()
        assertTrue(approvals.pendingApprovals(pending.expiresAt).isEmpty())
        assertEquals(GlafiraTaskState.FAILED, taskStore.getTask(original.taskId)?.state)
        val expired = GlafiraCoreTraceStore(taskStore).query(
            GlafiraCoreTraceQuery("approval-project", taskId = original.taskId),
            GlafiraCoreTraceAccessContext("approval-project", taskIds = setOf(original.taskId))
        ).events.single { it.eventType == GlafiraCoreTraceEventType.APPROVAL_EXPIRED }
        assertEquals("OWNER_APPROVAL_EXPIRED", expired.actionCode)
        assertEquals("expired", expired.resultStatus)
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
        val queued = requireNotNull(taskStore.getTask(original.taskId))
        val changed = queued.copy(
            payload = """{"actionSummary":"Send a DIFFERENT test message","recipient":"other@example.invalid","message":"changed"}"""
        )
        assertNull(approvals.approvalGrant(
            changed,
            "Send a DIFFERENT test message",
            4_200L
        ))
    }

    @Test
    fun unknownTaskRequestedScopeCannotEnterAnOwnerApprovalGrant() {
        val invalid = task("approval-unknown-scope").copy(
            requiredScopes = setOf("communications.send", "unregistered.admin")
        )
        taskStore.enqueue(invalid)
        val running = requireNotNull(taskStore.claimNext(2_000L))

        assertFalse(approvals.requestApproval(
            running,
            "Send a test message to test@example.invalid",
            3_000L
        ))
        assertTrue(approvals.pendingApprovals(3_001L).isEmpty())
        assertEquals(GlafiraTaskState.RUNNING, taskStore.getTask(invalid.taskId)?.state)
    }

    @Test
    fun legacyApprovalRowWithUnknownScopeFailsClosedWhenReadingGrantedScopes() {
        val now = System.currentTimeMillis()
        val original = task("approval-legacy-unknown").copy(
            createdAtMillis = now,
            updatedAtMillis = now
        )
        taskStore.enqueue(original)
        val running = requireNotNull(taskStore.claimNext(now + 1L))
        assertTrue(approvals.requestApproval(
            running,
            "Send a test message to test@example.invalid",
            now + 2L
        ))
        assertEquals(
            GlafiraCoreOwnerApprovalMutation.APPROVED,
            approvals.approve(
                original.taskId,
                setOf("communications.send"),
                authenticatedOwnerSession(),
                now + 3L
            )
        )
        taskStore.writableDatabase.execSQL(
            "UPDATE core_action_approvals SET requested_scopes = ? WHERE task_id = ?",
            arrayOf("""["communications.send","unregistered.admin"]""", original.taskId)
        )

        val restoredTask = requireNotNull(taskStore.getTask(original.taskId))
        val actionSummary = "Send a test message to test@example.invalid"
        assertTrue(approvals.grantedScopes(restoredTask).isEmpty())
        assertNull(approvals.peekApprovalGrant(restoredTask, actionSummary, now + 4L))
        assertNull(approvals.approvalGrant(restoredTask, actionSummary, now + 4L))

        // Simulate a Bridge credential supplying the known device scope. The owner row itself
        // must still be rejected, rather than letting device authority bypass a legacy extra scope.
        val bridgeBackedSource = object : GlafiraTaskGrantSource {
            override fun grantedScopes(task: GlafiraTask) = setOf("communications.send")
            override fun approvalGrant(task: GlafiraTask, summary: String, nowMillis: Long) =
                approvals.approvalGrant(task, summary, nowMillis)
            override fun peekApprovalGrant(task: GlafiraTask, summary: String, nowMillis: Long) =
                approvals.peekApprovalGrant(task, summary, nowMillis)
            override fun requestApproval(task: GlafiraTask, summary: String, nowMillis: Long) = false
        }
        assertNotEquals(
            GlafiraTaskAuthorizationStatus.AUTHORIZED,
            RegistryBackedGlafiraTaskAuthorizer(bridgeBackedSource)
                .authorize(restoredTask, now + 5L).status
        )
    }

    @Test
    fun legacyApprovalRowWithUnexpectedKnownScopeFailsClosed() {
        val now = System.currentTimeMillis()
        val original = task("approval-legacy-extra-known").copy(
            createdAtMillis = now,
            updatedAtMillis = now
        )
        taskStore.enqueue(original)
        val running = requireNotNull(taskStore.claimNext(now + 1L))
        val actionSummary = "Send a test message to test@example.invalid"
        assertTrue(approvals.requestApproval(running, actionSummary, now + 2L))
        assertEquals(
            GlafiraCoreOwnerApprovalMutation.APPROVED,
            approvals.approve(
                original.taskId,
                setOf("communications.send"),
                authenticatedOwnerSession(),
                now + 3L
            )
        )
        taskStore.writableDatabase.execSQL(
            "UPDATE core_action_approvals SET requested_scopes = ? WHERE task_id = ?",
            arrayOf("""["communications.send","project.read"]""", original.taskId)
        )

        val restoredTask = requireNotNull(taskStore.getTask(original.taskId))
        assertTrue(approvals.grantedScopes(restoredTask).isEmpty())
        assertNull(approvals.peekApprovalGrant(restoredTask, actionSummary, now + 4L))
        assertNull(approvals.approvalGrant(restoredTask, actionSummary, now + 4L))
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
    fun ownerGrantExpiryNeverOutlivesTheTaskDeadline() {
        val now = System.currentTimeMillis()
        val deadline = now + 30_000L
        val original = task("approval-deadline").copy(
            createdAtMillis = now,
            updatedAtMillis = now,
            deadlineAtMillis = deadline
        )
        taskStore.enqueue(original)
        val running = requireNotNull(taskStore.claimNext(now + 1L))
        assertTrue(approvals.requestApproval(
            running,
            "Send a test message to test@example.invalid",
            now + 2L
        ))
        assertEquals(
            GlafiraCoreOwnerApprovalMutation.APPROVED,
            approvals.approve(
                original.taskId,
                setOf("communications.send"),
                authenticatedOwnerSession(),
                now + 3L
            )
        )

        val queued = requireNotNull(taskStore.getTask(original.taskId))
        val grant = requireNotNull(approvals.approvalGrant(
            queued,
            "Send a test message to test@example.invalid",
            now + 4L
        ))
        assertEquals(deadline, grant.expiresAt)
        assertNull(approvals.approvalGrant(
            queued,
            "Send a test message to test@example.invalid",
            deadline + 1L
        ))
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
