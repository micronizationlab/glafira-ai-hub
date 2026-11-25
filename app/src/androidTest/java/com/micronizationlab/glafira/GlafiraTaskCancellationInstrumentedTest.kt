package com.micronizationlab.glafira

import android.content.Context
import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File
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
    private fun task(id: String, projectId: String = "cancel-project") = GlafiraTask(
        taskId = id, projectId = projectId, taskType = "project.read",
        payload = "{}", origin = "owner", createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L, idempotencyKey = "$projectId:$id"
    )
    @Test fun cancellationRecordsHistoryFailsChildrenAndExcludesClaim() {
        store.enqueue(task("parent"))
        store.enqueueWithDependencies(task("child"), setOf("parent"), 1_500L)
        assertTrue(store.cancelUnclaimedTask("parent", "cancel-project", 2_000L))
        assertEquals(GlafiraTaskState.CANCELLED, store.getTask("parent")?.state)
        assertEquals(GlafiraTaskState.FAILED, store.getTask("child")?.state)
        store.writableDatabase.rawQuery(
            "SELECT 1 FROM core_task_history WHERE task_id=? AND to_state='cancelled' AND reason='OWNER_CANCELLED'",
            arrayOf("parent")
        ).use { assertTrue(it.moveToFirst()) }
        assertFalse(store.cancelUnclaimedTask("parent", "cancel-project", 3_000L))
        assertNull(store.claimNext(4_000L))
    }
    @Test fun runningOrWrongProjectTaskCannotBeCancelled() {
        store.enqueue(task("running"))
        assertEquals("running", store.claimNext(2_000L)?.taskId)
        assertFalse(store.cancelUnclaimedTask("running", "cancel-project", 3_000L))
        assertEquals(GlafiraTaskState.RUNNING, store.getTask("running")?.state)
        store.enqueue(task("wrong-project"))
        assertFalse(store.cancelUnclaimedTask("wrong-project", "another-project", 4_000L))
        assertEquals(GlafiraTaskState.QUEUED, store.getTask("wrong-project")?.state)
    }
    @Test fun unboundOrDifferentCoreCannotCancelAnotherCoresTask() {
        store.enqueue(task("owned-by-test-core"))
        val unboundStore = GlafiraTaskStore(context)
        val otherCoreStore = GlafiraTaskStore(context, testOtherCoreId)
        try {
            assertFalse(unboundStore.cancelUnclaimedTask("owned-by-test-core", "cancel-project", 2_000L))
            assertFalse(otherCoreStore.cancelUnclaimedTask("owned-by-test-core", "cancel-project", 2_000L))
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
        assertFalse(store.cancelUnclaimedTask("prior-attempt-waiting", "cancel-project", 3_000L))
        assertEquals(GlafiraTaskState.WAITING_FOR_NETWORK, store.getTask("prior-attempt-waiting")?.state)
    }

    @Test fun disallowedStateWithZeroAttemptsCannotBeCancelled() {
        store.enqueue(task("running-without-claim"))
        assertTrue(store.transition("running-without-claim", setOf(GlafiraTaskState.QUEUED),
            GlafiraTaskState.RUNNING, 2_000L))
        assertEquals(0, store.getTask("running-without-claim")?.attemptCount)
        assertFalse(store.cancelUnclaimedTask("running-without-claim", "cancel-project", 3_000L))
        assertEquals(GlafiraTaskState.RUNNING, store.getTask("running-without-claim")?.state)
    }

    @Test fun waitingTasksCanBeCancelledBeforeAnyClaim() {
        listOf(GlafiraTaskState.WAITING_FOR_NETWORK, GlafiraTaskState.WAITING_FOR_OWNER).forEachIndexed { i, state ->
            val id = "waiting-$i"
            store.enqueue(task(id))
            assertTrue(store.transition(id, setOf(GlafiraTaskState.QUEUED), state, 2_000L))
            assertTrue(store.cancelUnclaimedTask(id, "cancel-project", 3_000L))
        }
    }

    @Test fun unknownExternalOutcomesCannotBeCancelled() {
        listOf("CORE_EXECUTION_OUTCOME_UNKNOWN", "DESKTOP_EXECUTION_OUTCOME_UNKNOWN", "CORE_RESULT_PERSIST_FAILED:disk-write", "CORE_RESULT_PERSIST_FAILED:timeout-after-commit").forEachIndexed { index, marker ->
            val id = "unknown-$index"
            store.enqueue(task(id))
            assertTrue(store.transition(id, setOf(GlafiraTaskState.QUEUED),
                GlafiraTaskState.WAITING_FOR_OWNER, 2_000L, null, marker))
            assertFalse(store.cancelUnclaimedTask(id, "cancel-project", 3_000L))
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
        assertTrue(store.cancelUnclaimedTask("parent", "cancel-project", 3_000L))
        assertEquals(GlafiraTaskState.FAILED, store.getTask("network-child")?.state)
        assertEquals(GlafiraTaskState.FAILED, store.getTask("approval-child")?.state)
        assertEquals("DEPENDENCY_FAILED", store.getTask("approval-child")?.lastError)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("core-unknown-child")?.state)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("desktop-unknown-child")?.state)
    }

    @Test fun wideDependencyGraphIsPropagatedInBoundedSqlBatches() {
        store.enqueue(task("wide-root"))
        repeat(1_005) { index ->
            val childId = "wide-child-$index"
            store.enqueueWithDependencies(task(childId), setOf("wide-root"), 1_500L + index)
        }
        assertTrue(store.cancelUnclaimedTask("wide-root", "cancel-project", 10_000L))
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
        assertTrue(store.cancelUnclaimedTask("approval-task", "cancel-project", 3_000L))
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
        assertFalse(store.cancelUnclaimedTask("retry", "cancel-project", 3_000L))
        assertEquals(GlafiraTaskState.RETRY_SCHEDULED, store.getTask("retry")?.state)
    }

    @Test fun cancellationDoesNotFailAnotherProjectsDependents() {
        store.enqueue(task("root"))
        store.enqueueWithDependencies(task("same-project-child"), setOf("root"), 1_500L)
        store.enqueue(task("other-parent", "other-project"))
        store.enqueueWithDependencies(task("other-child", "other-project"), setOf("other-parent"), 1_600L)
        assertTrue(store.transition("other-parent", setOf(GlafiraTaskState.QUEUED),
            GlafiraTaskState.FAILED, 2_000L, lastError = "PRIOR_FAILURE"))
        assertTrue(store.cancelUnclaimedTask("root", "cancel-project", 3_000L))
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
        assertTrue(store.cancelUnclaimedTask("approved-task", "cancel-project", 3_000L))
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

    @Test fun restoreMarkerWinsOverAStaleCancellationPrecheck() {
        store.enqueue(task("restore-guard"))
        val markerDir = File(context.noBackupFilesDir, "glafira-restore-journal")
        val marker = File(markerDir, "restore.pending")
        val enteredRestoreLock = CountDownLatch(1)
        val releaseRestoreLock = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertFalse(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
            val stalePrecheck = !GlafiraCoreBackupCoordinator.hasInterruptedRestore(context)
            assertTrue(stalePrecheck)
            val restore = pool.submit {
                synchronized(store) {
                    markerDir.mkdirs()
                    assertTrue(marker.createNewFile())
                    enteredRestoreLock.countDown()
                    assertTrue(releaseRestoreLock.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(enteredRestoreLock.await(10, TimeUnit.SECONDS))
            releaseRestoreLock.countDown()
            restore.get(10, TimeUnit.SECONDS)
            val result = runCatching {
                synchronized(store) {
                    check(!GlafiraCoreBackupCoordinator.hasInterruptedRestore(context)) {
                        "RESTORE_RECOVERY_REQUIRED"
                    }
                    store.cancelUnclaimedTask("restore-guard", "cancel-project", 3_000L)
                }
            }
            assertTrue(result.isFailure)
            assertEquals(GlafiraTaskState.QUEUED, store.getTask("restore-guard")?.state)
        } finally {
            releaseRestoreLock.countDown()
            pool.shutdownNow()
            marker.delete()
            markerDir.delete()
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
                store.cancelUnclaimedTask("race", "cancel-project", 2_000L)
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