package com.micronizationlab.glafira

import android.content.Context
import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GlafiraCoreBackupInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: GlafiraTaskStore

    @Before
    fun setUp() {
        context.deleteDatabase("glafira_core_tasks.db")
        store = GlafiraTaskStore(context)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("glafira_core_tasks.db")
    }

    private fun task(id: String = "backup-task") = GlafiraTask(
        taskId = id,
        projectId = "backup-project",
        taskType = "project.read",
        payload = "{\"record\":\"persist-me\"}",
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "backup-project:$id"
    )

    @Test
    fun bridgeHostServiceIsRegisteredAsPrivateConnectedDeviceForegroundService() {
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, GlafiraBridgeHostService::class.java),
            0
        )
        assertFalse(service.exported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                service.foregroundServiceType
            )
        }
    }

    @Test
    fun queueStateAndEncryptedPayloadSurviveStoreReopen() {
        store.enqueue(task())
        assertTrue(
            store.transition(
                "backup-task",
                setOf(GlafiraTaskState.QUEUED),
                GlafiraTaskState.WAITING_FOR_OWNER,
                nowMillis = 2_000L
            )
        )
        store.close()

        store = GlafiraTaskStore(context)
        val restored = requireNotNull(store.getTask("backup-task"))
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, restored.state)
        assertEquals("{\"record\":\"persist-me\"}", restored.payload)
        assertEquals(0, restored.attemptCount)
    }


    @Test
    fun retryScheduleAttemptCountAndIdempotencySurviveStoreReopen() {
        store.enqueue(task())
        val claimed = requireNotNull(store.claimNext(nowMillis = 2_000L))
        assertEquals(1, claimed.attemptCount)
        assertTrue(
            store.transition(
                "backup-task",
                setOf(GlafiraTaskState.RUNNING),
                GlafiraTaskState.RETRY_SCHEDULED,
                nowMillis = 3_000L,
                retryAtMillis = 9_000L,
                lastError = "temporary"
            )
        )
        val duplicate = store.enqueue(task())
        assertTrue(!duplicate.inserted)
        store.close()

        store = GlafiraTaskStore(context)
        val persisted = requireNotNull(store.getTask("backup-task"))
        assertEquals(GlafiraTaskState.RETRY_SCHEDULED, persisted.state)
        assertEquals(1, persisted.attemptCount)
        assertEquals(9_000L, persisted.retryAtMillis)
        assertEquals("temporary", persisted.lastError)
        assertTrue(!store.enqueue(task()).inserted)
    }

    @Test
    fun ownerApprovalExpiresAndCannotRemainInPendingQueueForever() {
        val approvalTask = task("approval-expiry-task").copy(
            payload = """{"actionSummary":"Read project details"}"""
        )
        store.enqueue(approvalTask)
        assertTrue(
            store.transition(
                approvalTask.taskId,
                setOf(GlafiraTaskState.QUEUED),
                GlafiraTaskState.RUNNING,
                nowMillis = 2_000L
            )
        )
        val approvals = GlafiraCoreOwnerApprovalStore(store)
        assertTrue(approvals.requestApproval(approvalTask, "Read project details", 3_000L))
        assertEquals(1, approvals.pendingApprovals(nowMillis = 4_000L).size)

        val expired = approvals.pendingApprovals(nowMillis = 86_404_000L)
        assertTrue(expired.isEmpty())
        assertEquals(GlafiraTaskState.FAILED, store.getTask(approvalTask.taskId)?.state)
        store.writableDatabase.query(
            "core_action_approvals",
            arrayOf("status"),
            "task_id = ?",
            arrayOf(approvalTask.taskId),
            null, null, null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("EXPIRED", cursor.getString(0))
        }
    }

    private fun previewTask(id: String = "preview-task") = GlafiraTask(
        taskId = id,
        projectId = "preview-project",
        taskType = "project.read",
        payload = """{"actionSummary":"Inspect project","projectId":"preview-project","token":"never-store-this-value"}""",
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "preview-project:$id"
    )

    @Test
    fun databaseUsesReviewedSchemaVersionThree() {
        store.writableDatabase.rawQuery("PRAGMA user_version", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
        }
    }

    @Test
    fun typedDryRunPreviewPersistsEncryptedAndCanBeReadAfterReopen() {
        val task = previewTask()
        store.enqueue(task)
        val plan = GlafiraDryRunPlanner.build(task)
        assertTrue(store.persistDryRunPreview(task.taskId, setOf(GlafiraTaskState.QUEUED), plan, 2_000L))
        val row = requireNotNull(store.dryRunPreview(task.taskId))
        assertEquals(plan.planHash, row.planHash)
        assertEquals(GlafiraDryRunReviewStatus.PENDING, row.reviewStatus)
        assertFalse(row.planJson.contains("never-store-this-value"))
        val ciphertext = store.writableDatabase.rawQuery(
            "SELECT plan_ciphertext FROM core_dry_run_previews WHERE task_id = ?",
            arrayOf(task.taskId)
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }
        assertFalse(ciphertext.contains("Inspect project"))
        store.close()
        store = GlafiraTaskStore(context)
        assertEquals(plan.planHash, requireNotNull(store.dryRunPreview(task.taskId)).planHash)
    }

    @Test
    fun previewPersistenceMovesTaskAndStoresOnlyMarkerInTaskMetadata() {
        val task = previewTask()
        store.enqueue(task)
        val plan = GlafiraDryRunPlanner.build(task)
        assertTrue(store.persistDryRunPreview(task.taskId, setOf(GlafiraTaskState.QUEUED), plan, 2_000L))
        val savedTask = requireNotNull(store.getTask(task.taskId))
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, savedTask.state)
        assertEquals("DRY_RUN_PREVIEW_STORED", savedTask.lastError)
    }

    @Test
    fun samePreviewHashIsIdempotentAndDifferentHashIsRejected() {
        val task = previewTask()
        store.enqueue(task)
        val plan = GlafiraDryRunPlanner.build(task)
        assertTrue(store.persistDryRunPreview(task.taskId, setOf(GlafiraTaskState.QUEUED), plan, 2_000L))
        assertTrue(store.persistDryRunPreview(task.taskId, setOf(GlafiraTaskState.QUEUED), plan, 3_000L))
        val changed = GlafiraDryRunPlanner.build(task.copy(payload = """{"actionSummary":"Different action"}"""))
        try {
            store.persistDryRunPreview(task.taskId, setOf(GlafiraTaskState.QUEUED), changed, 4_000L)
            throw AssertionError("Expected hash conflict.")
        } catch (_: IllegalStateException) { }
    }

    @Test
    fun previewCannotBePersistedFromUnexpectedTaskState() {
        val task = previewTask()
        store.enqueue(task)
        assertTrue(store.transition(task.taskId, setOf(GlafiraTaskState.QUEUED), GlafiraTaskState.WAITING_FOR_OWNER, 2_000L))
        val plan = GlafiraDryRunPlanner.build(task)
        assertFalse(store.persistDryRunPreview(task.taskId, setOf(GlafiraTaskState.QUEUED), plan, 3_000L))
    }

    @Test
    fun reviewRequiresExactHashAndCanBeRecordedOnlyOnce() {
        val task = previewTask()
        store.enqueue(task)
        val plan = GlafiraDryRunPlanner.build(task)
        assertTrue(store.persistDryRunPreview(task.taskId, setOf(GlafiraTaskState.QUEUED), plan, 2_000L))
        assertFalse(store.reviewDryRunPreview(task.taskId, "0".repeat(64), GlafiraDryRunReviewStatus.ACKNOWLEDGED))
        assertTrue(store.reviewDryRunPreview(task.taskId, plan.planHash, GlafiraDryRunReviewStatus.ACKNOWLEDGED, 3_000L))
        assertFalse(store.reviewDryRunPreview(task.taskId, plan.planHash, GlafiraDryRunReviewStatus.REJECTED, 4_000L))
        val reviewed = requireNotNull(store.dryRunPreview(task.taskId))
        assertEquals(GlafiraDryRunReviewStatus.ACKNOWLEDGED, reviewed.reviewStatus)
        assertEquals(3_000L, reviewed.reviewedAtMillis)
    }

    @Test
    fun pendingPreviewListExcludesReviewedRecords() {
        val pending = previewTask("preview-pending")
        val reviewed = previewTask("preview-reviewed")
        store.enqueue(pending)
        store.enqueue(reviewed)
        val pendingPlan = GlafiraDryRunPlanner.build(pending)
        val reviewedPlan = GlafiraDryRunPlanner.build(reviewed)
        assertTrue(store.persistDryRunPreview(pending.taskId, setOf(GlafiraTaskState.QUEUED), pendingPlan, 2_000L))
        assertTrue(store.persistDryRunPreview(reviewed.taskId, setOf(GlafiraTaskState.QUEUED), reviewedPlan, 2_000L))
        assertTrue(store.reviewDryRunPreview(reviewed.taskId, reviewedPlan.planHash, GlafiraDryRunReviewStatus.REJECTED))
        assertEquals(listOf(pending.taskId), store.dryRunPreviews(includeReviewed = false).map { it.taskId })
    }

    @Test
    fun deletingCoreTaskCascadesItsTypedDryRunPreview() {
        val task = previewTask()
        store.enqueue(task)
        val plan = GlafiraDryRunPlanner.build(task)
        assertTrue(store.persistDryRunPreview(task.taskId, setOf(GlafiraTaskState.QUEUED), plan, 2_000L))
        val deleted = store.writableDatabase.delete("core_tasks", "task_id = ?", arrayOf(task.taskId))
        assertEquals(1, deleted)
        assertNull(store.dryRunPreview(task.taskId))
    }

    @Test
    fun backupRestoreRecoversCoreQueueStateAndPayload() = runBlocking {
        store.enqueue(task())
        assertTrue(
            store.transition(
                "backup-task",
                setOf(GlafiraTaskState.QUEUED),
                GlafiraTaskState.WAITING_FOR_OWNER,
                nowMillis = 2_000L
            )
        )

        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val archive = coordinator.create(passphrase)
        try {
            assertTrue(
                store.transition(
                    "backup-task",
                    setOf(GlafiraTaskState.WAITING_FOR_OWNER),
                    GlafiraTaskState.QUEUED,
                    nowMillis = 3_000L
                )
            )
            assertTrue(
                store.transition(
                    "backup-task",
                    setOf(GlafiraTaskState.QUEUED),
                    GlafiraTaskState.RUNNING,
                    nowMillis = 4_000L
                )
            )
            assertTrue(
                store.transition(
                    "backup-task",
                    setOf(GlafiraTaskState.RUNNING),
                    GlafiraTaskState.FAILED,
                    nowMillis = 5_000L,
                    lastError = "changed-after-backup"
                )
            )

            coordinator.restore(archive, passphrase)
            val recovered = requireNotNull(store.getTask("backup-task"))
            assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, recovered.state)
            assertEquals("{\"record\":\"persist-me\"}", recovered.payload)
            assertEquals(0, recovered.attemptCount)
        } finally {
            archive.delete()
            passphrase.fill('\u0000')
            File(context.filesDir, ".glafira-core-snapshot").deleteRecursively()
        }
    }
}
