package com.micronizationlab.glafira

import android.content.Context
import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
/** GLAFIRA-S093-W01 executable acceptance coverage for the Core cancellation path. */
class GlafiraTaskCancellationInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: GlafiraTaskStore
    private val testCoreId = "glafira-cancel-test-core"
    private val testOtherCoreId = "glafira-cancel-other-core"
    @Before fun setUp() {
        context.deleteDatabase("glafira_core_tasks.db")
        store = GlafiraTaskStore(context, testCoreId)
    }
    @After fun tearDown() {
        store.close()
        context.deleteDatabase("glafira_core_tasks.db")
    }
    private fun task(
        id: String,
        projectId: String = "cancel-project",
        taskType: String = "project.read",
        deadlineAtMillis: Long? = null
    ) = GlafiraTask(
        taskId = id, projectId = projectId, taskType = taskType,
        payload = "{}", origin = "owner", createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L, idempotencyKey = "$projectId:$id", deadlineAtMillis = deadlineAtMillis
    )
    private fun cancel(
        taskId: String,
        projectId: String = "cancel-project",
        nowMillis: Long,
        targetStore: GlafiraTaskStore = store
    ): Boolean {
        val ownership = targetStore.taskOwnership(taskId, projectId) ?: return false
        return targetStore.cancelUnclaimedTask(
            taskId, projectId, testCoreId, ownership.ownerGeneration, ownership.stateVersion, nowMillis
        )
    }

    @Test fun dependencyPropagationDoesNotFailAnotherCoresDescendant() {
        store.enqueue(task("parent-owned-by-test-core"))
        val otherCoreStore = GlafiraTaskStore(context, "glafira-cancel-peer-core")
        try {
            otherCoreStore.enqueueWithDependencies(
                task("peer-core-child"), setOf("parent-owned-by-test-core"), 1_500L
            )
            assertTrue(cancel("parent-owned-by-test-core", "cancel-project", 2_000L))
            assertEquals(
                GlafiraTaskState.QUEUED,
                otherCoreStore.getTask("peer-core-child")?.state
            )
        } finally {
            otherCoreStore.close()
        }
    }

    @Test fun cancellationTraceIncludesExplicitOwnerActor() {
        store.enqueue(task("owner-trace"))
        assertTrue(cancel("owner-trace", "cancel-project", 2_000L))
        val page = GlafiraCoreTraceStore(store).query(
            GlafiraCoreTraceQuery(
                projectId = "cancel-project",
                taskId = "owner-trace",
                eventTypes = setOf(GlafiraCoreTraceEventType.TASK_CANCELLED)
            ),
            GlafiraCoreTraceAccessContext(
                projectId = "cancel-project",
                taskIds = setOf("owner-trace")
            )
        )
        val ownerAction = page.events.firstOrNull {
            it.actionCode == "OWNER_CANCELLED_UNCLAIMED_TASK"
        }
        assertNotNull(ownerAction)
        assertEquals(GlafiraCoreTraceActorType.OWNER, ownerAction?.actorType)
        assertEquals("owner", ownerAction?.actorId)
    }

    @Test fun cancellationRecordsHistoryFailsChildrenAndExcludesClaim() {
        store.enqueue(task("parent"))
        store.enqueueWithDependencies(task("child"), setOf("parent"), 1_500L)
        assertTrue(cancel("parent", "cancel-project", 2_000L))
        assertEquals(GlafiraTaskState.CANCELLED, store.getTask("parent")?.state)
        assertEquals(GlafiraTaskState.FAILED, store.getTask("child")?.state)
        store.writableDatabase.rawQuery(
            "SELECT 1 FROM core_task_history WHERE task_id=? AND to_state='cancelled' AND reason='OWNER_CANCELLED'",
            arrayOf("parent")
        ).use { assertTrue(it.moveToFirst()) }
        assertFalse(cancel("parent", "cancel-project", 3_000L))
        assertNull(store.claimNext(4_000L))
    }
    @Test fun runningOrWrongProjectTaskCannotBeCancelled() {
        store.enqueue(task("running"))
        assertEquals("running", store.claimNext(2_000L)?.taskId)
        assertFalse(cancel("running", "cancel-project", 3_000L))
        assertEquals(GlafiraTaskState.RUNNING, store.getTask("running")?.state)
        store.enqueue(task("wrong-project"))
        assertFalse(cancel("wrong-project", "another-project", 4_000L))
        assertEquals(GlafiraTaskState.QUEUED, store.getTask("wrong-project")?.state)
    }
    @Test fun staleOwnerGenerationOrStateVersionCannotCancelTask() {
        store.enqueue(task("stale-owner-fence"))
        val captured = checkNotNull(store.taskOwnership("stale-owner-fence", "cancel-project"))
        store.writableDatabase.execSQL(
            "UPDATE core_task_ownership SET owner_generation = owner_generation + 1, state_version = state_version + 1 WHERE task_id = ?",
            arrayOf("stale-owner-fence")
        )
        assertFalse(store.cancelUnclaimedTask(
            "stale-owner-fence", "cancel-project", testCoreId,
            captured.ownerGeneration, captured.stateVersion, 3_000L
        ))
        assertEquals(GlafiraTaskState.QUEUED, store.getTask("stale-owner-fence")?.state)
    }

    @Test fun unboundOrDifferentCoreCannotCancelAnotherCoresTask() {
        store.enqueue(task("owned-by-test-core"))
        val unboundStore = GlafiraTaskStore(context)
        val otherCoreStore = GlafiraTaskStore(context, testOtherCoreId)
        try {
            assertFalse(cancel("owned-by-test-core", "cancel-project", 2_000L, unboundStore))
            assertFalse(cancel("owned-by-test-core", "cancel-project", 2_000L, otherCoreStore))
            assertEquals(GlafiraTaskState.QUEUED, store.getTask("owned-by-test-core")?.state)
        } finally {
            unboundStore.close()
            otherCoreStore.close()
        }
    }

    @Test fun priorAttemptInWaitingStateCannotBeCancelled() {
        store.enqueue(task("prior-attempt-waiting"))
        assertEquals("prior-attempt-waiting", store.claimNext(2_000L)?.taskId)
        assertTrue(store.transition("prior-attempt-waiting", setOf(GlafiraTaskState.RUNNING),
            GlafiraTaskState.WAITING_FOR_NETWORK, 2_500L))
        assertEquals(1, store.getTask("prior-attempt-waiting")?.attemptCount)
        assertFalse(cancel("prior-attempt-waiting", "cancel-project", 3_000L))
        assertEquals(GlafiraTaskState.WAITING_FOR_NETWORK, store.getTask("prior-attempt-waiting")?.state)
    }

    @Test fun disallowedStateWithZeroAttemptsCannotBeCancelled() {
        store.enqueue(task("running-without-claim"))
        assertTrue(store.transition("running-without-claim", setOf(GlafiraTaskState.QUEUED),
            GlafiraTaskState.RUNNING, 2_000L))
        assertEquals(0, store.getTask("running-without-claim")?.attemptCount)
        assertFalse(cancel("running-without-claim", "cancel-project", 3_000L))
        assertEquals(GlafiraTaskState.RUNNING, store.getTask("running-without-claim")?.state)
    }

    @Test fun waitingTasksCanBeCancelledBeforeAnyClaim() {
        listOf(GlafiraTaskState.WAITING_FOR_NETWORK, GlafiraTaskState.WAITING_FOR_OWNER).forEachIndexed { i, state ->
            val id = "waiting-$i"
            store.enqueue(task(id))
            assertTrue(store.transition(id, setOf(GlafiraTaskState.QUEUED), state, 2_000L))
            assertTrue(cancel(id, "cancel-project", 3_000L))
        }
    }

    @Test fun unknownExternalOutcomesCannotBeCancelled() {
        listOf("CORE_EXECUTION_OUTCOME_UNKNOWN", "DESKTOP_EXECUTION_OUTCOME_UNKNOWN", "CORE_RESULT_PERSIST_FAILED:disk-write", "CORE_RESULT_PERSIST_FAILED:timeout-after-commit").forEachIndexed { index, marker ->
            val id = "unknown-$index"
            store.enqueue(task(id, taskType = if (marker.startsWith("CORE_RESULT_PERSIST_FAILED:")) "unregistered.external.write" else "project.read"))
            assertTrue(store.transition(id, setOf(GlafiraTaskState.QUEUED),
                GlafiraTaskState.WAITING_FOR_OWNER, 2_000L, null, marker))
            assertFalse(cancel(id, "cancel-project", 3_000L))
            assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask(id)?.state)
            assertEquals(marker, store.getTask(id)?.lastError)
        }
    }

    @Test fun waitingDependentsFailButUnknownOutcomeDependentsRemainForReconciliation() {
        store.enqueue(task("parent"))
        listOf(
            Triple("network-child", GlafiraTaskState.WAITING_FOR_NETWORK, "NETWORK_UNAVAILABLE"),
            Triple("approval-child", GlafiraTaskState.WAITING_FOR_OWNER, "OWNER_APPROVAL_REQUIRED"),
            Triple("core-unknown-child", GlafiraTaskState.WAITING_FOR_OWNER, "CORE_EXECUTION_OUTCOME_UNKNOWN"),
            Triple("desktop-unknown-child", GlafiraTaskState.WAITING_FOR_OWNER, "DESKTOP_EXECUTION_OUTCOME_UNKNOWN")
        ).forEachIndexed { index, item ->
            store.enqueueWithDependencies(task(item.first), setOf("parent"), 1_500L + index)
            assertTrue(store.transition(item.first, setOf(GlafiraTaskState.QUEUED),
                item.second, 2_000L + index, null, item.third))
        }
        assertTrue(cancel("parent", "cancel-project", 3_000L))
        assertEquals(GlafiraTaskState.FAILED, store.getTask("network-child")?.state)
        assertEquals(GlafiraTaskState.FAILED, store.getTask("approval-child")?.state)
        assertEquals("DEPENDENCY_FAILED", store.getTask("approval-child")?.lastError)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("core-unknown-child")?.state)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("desktop-unknown-child")?.state)
    }

    @Test fun readOnlyPersistenceRetriesExpireButUncertainWritesDoNot() {
        store.enqueue(task("read-only-persist-retry", deadlineAtMillis = 1_500L))
        assertTrue(store.transition(
            "read-only-persist-retry", setOf(GlafiraTaskState.QUEUED),
            GlafiraTaskState.RETRY_SCHEDULED, 1_200L, 1_000L,
            "CORE_RESULT_PERSIST_FAILED:read-only-result"
        ))

        store.enqueue(task("uncertain-write-retry", taskType = "unknown.external.write"))
        assertTrue(store.transition(
            "uncertain-write-retry", setOf(GlafiraTaskState.QUEUED),
            GlafiraTaskState.RETRY_SCHEDULED, 1_200L, 1_000L,
            "CORE_RESULT_PERSIST_FAILED:write-after-commit"
        ))

        store.enqueue(task("near-match-retry", deadlineAtMillis = 1_500L))
        assertTrue(store.transition(
            "near-match-retry", setOf(GlafiraTaskState.QUEUED),
            GlafiraTaskState.RETRY_SCHEDULED, 1_200L, 1_000L,
            "CORE-RESULT-PERSIST-FAILED:ordinary"
        ))

        assertNull(store.claimNext(2_000L))
        assertEquals(GlafiraTaskState.FAILED, store.getTask("read-only-persist-retry")?.state)
        assertEquals("TASK_DEADLINE_EXPIRED", store.getTask("read-only-persist-retry")?.lastError)
        assertEquals(GlafiraTaskState.RETRY_SCHEDULED, store.getTask("uncertain-write-retry")?.state)
        assertEquals("CORE_RESULT_PERSIST_FAILED:write-after-commit", store.getTask("uncertain-write-retry")?.lastError)
        assertEquals(GlafiraTaskState.FAILED, store.getTask("near-match-retry")?.state)
    }

    @Test fun taskAtExactDeadlineIsExpiredBeforeClaim() {
        store.enqueue(task("deadline-equality", deadlineAtMillis = 2_000L))
        assertNull(store.claimNext(2_000L))
        assertEquals(GlafiraTaskState.FAILED, store.getTask("deadline-equality")?.state)
        assertEquals("TASK_DEADLINE_EXPIRED", store.getTask("deadline-equality")?.lastError)
    }

    @Test fun wideDependencyGraphIsPropagatedInBoundedSqlBatches() {
        store.enqueue(task("wide-root"))
        repeat(1_005) { index ->
            val childId = "wide-child-$index"
            store.enqueueWithDependencies(task(childId), setOf("wide-root"), 1_500L + index)
        }
        assertTrue(cancel("wide-root", "cancel-project", 10_000L))
        val failedChildren = store.writableDatabase.rawQuery(
            "SELECT COUNT(*) FROM core_tasks WHERE project_id=? AND state='failed' AND last_error='DEPENDENCY_FAILED'",
            arrayOf("cancel-project")
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) }
        assertEquals(1_005, failedChildren)
    }

    @Test fun cancellationExpiresOnlyItsOwnPendingApproval() {
        GlafiraCoreOwnerApprovalStore(store)
        store.enqueue(task("approval-task"))
        assertTrue(store.transition("approval-task", setOf(GlafiraTaskState.QUEUED),
            GlafiraTaskState.WAITING_FOR_OWNER, 2_000L, null, "OWNER_APPROVAL_REQUIRED"))
        store.writableDatabase.insertOrThrow("core_action_approvals", null, ContentValues().apply {
            put("task_id", "approval-task")
            put("project_id", "cancel-project")
            put("tool_id", "project.read")
            put("action_hash", "hash")
            put("requested_scopes", "[]")
            put("requested_at", 2_000L)
            put("expires_at", 99_000L)
            put("status", "PENDING")
        })
        assertTrue(cancel("approval-task", "cancel-project", 3_000L))
        store.writableDatabase.rawQuery(
            "SELECT status FROM core_action_approvals WHERE task_id=?",
            arrayOf("approval-task")
        ).use { assertTrue(it.moveToFirst()); assertEquals("EXPIRED", it.getString(0)) }
    }

    @Test fun retryScheduledTaskFromPriorAttemptCannotBeCancelled() {
        store.enqueue(task("retry"))
        assertEquals("retry", store.claimNext(2_000L)?.taskId)
        assertTrue(store.transition("retry", setOf(GlafiraTaskState.RUNNING),
            GlafiraTaskState.RETRY_SCHEDULED, 2_500L, retryAtMillis = 6_000L))
        assertFalse(cancel("retry", "cancel-project", 3_000L))
        assertEquals(GlafiraTaskState.RETRY_SCHEDULED, store.getTask("retry")?.state)
    }

    @Test fun cancellationDoesNotFailAnotherProjectsDependents() {
        store.enqueue(task("root"))
        store.enqueueWithDependencies(task("same-project-child"), setOf("root"), 1_500L)
        store.enqueue(task("other-parent", "other-project"))
        store.enqueueWithDependencies(task("other-child", "other-project"), setOf("other-parent"), 1_600L)
        assertTrue(store.transition("other-parent", setOf(GlafiraTaskState.QUEUED),
            GlafiraTaskState.FAILED, 2_000L, lastError = "PRIOR_FAILURE"))
        assertTrue(cancel("root", "cancel-project", 3_000L))
        assertEquals(GlafiraTaskState.FAILED, store.getTask("same-project-child")?.state)
        assertEquals(GlafiraTaskState.QUEUED, store.getTask("other-child", "other-project")?.state)
    }

    @Test fun cancellationPreservesApprovedDecisionAudit() {
        GlafiraCoreOwnerApprovalStore(store)
        store.enqueue(task("approved-task"))
        store.writableDatabase.insertOrThrow("core_action_approvals", null, ContentValues().apply {
            put("task_id", "approved-task")
            put("project_id", "cancel-project")
            put("tool_id", "project.read")
            put("action_hash", "approved-hash")
            put("requested_scopes", "[]")
            put("requested_at", 2_000L)
            put("expires_at", 99_000L)
            put("status", "APPROVED")
            put("approved_at", 2_500L)
            put("grant_expires_at", 90_000L)
            put("decided_by", "owner-session-audit")
        })
        assertTrue(cancel("approved-task", "cancel-project", 3_000L))
        store.writableDatabase.rawQuery(
            "SELECT status,approved_at,grant_expires_at,decided_by FROM core_action_approvals WHERE task_id=?",
            arrayOf("approved-task")
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("EXPIRED", it.getString(0))
            assertEquals(2_500L, it.getLong(1))
            assertTrue(it.isNull(2))
            assertEquals("owner-session-audit", it.getString(3))
        }
    }

    @Test fun concurrentClaimAndCancelCannotOverwriteEachOther() {
        val secondStore = GlafiraTaskStore(context, testCoreId)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            store.enqueue(task("race"))
            val cancel = pool.submit<Boolean> {
                gate.await()
                cancel("race", "cancel-project", 2_000L)
            }
            val claim = pool.submit<GlafiraTask?> { gate.await(); secondStore.claimNext(2_000L) }
            gate.countDown()
            val cancelled = cancel.get(10, TimeUnit.SECONDS)
            val claimed = claim.get(10, TimeUnit.SECONDS)
            val finalState = store.getTask("race")?.state
            assertTrue((cancelled && claimed == null && finalState == GlafiraTaskState.CANCELLED) ||
                (!cancelled && claimed?.taskId == "race" && finalState == GlafiraTaskState.RUNNING))
        } finally {
            pool.shutdownNow()
            secondStore.close()
        }
    }
}