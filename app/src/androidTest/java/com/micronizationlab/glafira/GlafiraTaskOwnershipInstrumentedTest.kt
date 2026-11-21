package com.micronizationlab.glafira

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraTaskOwnershipInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val stores = mutableListOf<GlafiraTaskStore>()
    private val snapshotFile get() = File(context.cacheDir, "ownership-portable-snapshot.json")

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        snapshotFile.delete()
    }

    @After
    fun tearDown() {
        stores.forEach { runCatching { it.close() } }
        stores.clear()
        context.deleteDatabase(DATABASE_NAME)
        snapshotFile.delete()
    }

    private fun store(coreId: String? = null) =
        GlafiraTaskStore(context, coreId).also(stores::add)

    private fun task(
        id: String,
        taskType: String = "project.read",
        payload: String = """{"record":"$id"}"""
    ) = GlafiraTask(
        taskId = id,
        projectId = "ownership-project",
        taskType = taskType,
        payload = payload,
        origin = "ownership-instrumentation",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "idem-$id"
    )

    private fun ownership(store: GlafiraTaskStore, taskId: String): Map<String, String?>? =
        store.readableDatabase.rawQuery(
            """SELECT owner_core_id, owner_generation, state_version, payload_sha256,
                idempotency_key, effect_risk, approval_ref
                FROM core_task_ownership WHERE task_id = ?""",
            arrayOf(taskId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else mapOf(
                "owner_core_id" to cursor.getString(0),
                "owner_generation" to cursor.getLong(1).toString(),
                "state_version" to cursor.getLong(2).toString(),
                "payload_sha256" to cursor.getString(3),
                "idempotency_key" to cursor.getString(4),
                "effect_risk" to cursor.getString(5),
                "approval_ref" to if (cursor.isNull(6)) null else cursor.getString(6)
            )
        }

    private fun sha256(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun newTasksPersistCoreOwnerGenerationPayloadDigestAndRiskAtomically() {
        val store = store("android-core-0001")
        val item = task("ownership-task-01")
        assertTrue(store.enqueue(item).inserted)

        val record = requireNotNull(ownership(store, item.taskId))
        assertEquals("android-core-0001", record["owner_core_id"])
        assertEquals("1", record["owner_generation"])
        assertEquals("0", record["state_version"])
        assertEquals(sha256(item.payload), record["payload_sha256"])
        assertEquals(item.idempotencyKey, record["idempotency_key"])
        assertEquals("READ_ONLY", record["effect_risk"])
        assertNull(record["approval_ref"])

        assertEquals(item.taskId, store.claimNext(2_000L)?.taskId)
        assertEquals("1", ownership(store, item.taskId)?.get("state_version"))
        assertTrue(store.transition(
            item.taskId,
            setOf(GlafiraTaskState.RUNNING),
            GlafiraTaskState.SUCCEEDED,
            3_000L,
            null,
            null
        ))
        assertEquals("2", ownership(store, item.taskId)?.get("state_version"))
    }

    @Test
    fun externalAndUnknownToolsAreClassifiedConservativelyForTransferPolicy() {
        val store = store("android-core-0002")
        val external = task("ownership-external-01", taskType = "message.send")
        val unknown = task("ownership-unknown-01", taskType = "future.unregistered.tool")

        assertTrue(store.enqueue(external).inserted)
        assertTrue(store.enqueue(unknown).inserted)
        assertEquals("EXTERNAL_SIDE_EFFECT", ownership(store, external.taskId)?.get("effect_risk"))
        assertEquals("DESTRUCTIVE", ownership(store, unknown.taskId)?.get("effect_risk"))
    }

    @Test
    fun ownershipRecordFailureRollsBackTheTaskInsert() {
        val store = store("android-core-atomic-01")
        store.writableDatabase.execSQL("""
            CREATE TRIGGER fail_core_task_ownership_insert
            BEFORE INSERT ON core_task_ownership
            BEGIN SELECT RAISE(ABORT, 'INJECTED_OWNERSHIP_INSERT_FAILURE'); END
        """.trimIndent())

        assertThrows(SQLiteException::class.java) {
            store.enqueue(task("ownership-atomic-failure"))
        }
        assertNull(store.getTask("ownership-atomic-failure"))
        assertNull(ownership(store, "ownership-atomic-failure"))
    }

    @Test
    fun duplicateEnqueueDoesNotResetExistingOwnerGenerationOrStateVersion() {
        val store = store("android-core-0003")
        val original = task("ownership-idempotent-01")
        assertTrue(store.enqueue(original).inserted)
        assertEquals(original.taskId, store.claimNext(2_000L)?.taskId)
        val before = requireNotNull(ownership(store, original.taskId))

        val duplicate = store.enqueue(original.copy(updatedAtMillis = 4_000L))
        assertFalse(duplicate.inserted)
        assertEquals("1", ownership(store, original.taskId)?.get("owner_generation"))
        assertEquals(before["state_version"], ownership(store, original.taskId)?.get("state_version"))
        assertEquals(before["payload_sha256"], ownership(store, original.taskId)?.get("payload_sha256"))
    }

    @Test
    fun versionTenMigrationBackfillsExistingTasksToTheCurrentInstallationCore() {
        val legacy = store()
        val item = task("ownership-legacy-01")
        assertTrue(legacy.enqueue(item).inserted)
        val db = legacy.writableDatabase
        db.insertOrThrow("core_desktop_leases", null, ContentValues().apply {
            put("task_id", item.taskId)
            put("device_id", "legacy-device")
            put("lease_id", "legacy-lease-0001")
            put("fencing_token", 1)
            put("lease_expires_at", 9_000L)
            put("attempt_count", 1)
            put("progress_percent", 25)
            put("updated_at", 1_500L)
        })
        db.insertOrThrow("core_desktop_nodes", null, ContentValues().apply {
            put("device_id", "legacy-device")
            put("last_seen_at", 1_500L)
            put("capabilities_json", "[]")
            put("max_parallel_tasks", 1)
        })
        GlafiraCoreOwnerApprovalStore(legacy)
        db.insertOrThrow("core_action_approvals", null, ContentValues().apply {
            put("task_id", item.taskId)
            put("project_id", item.projectId)
            put("tool_id", item.taskType)
            put("action_hash", "a".repeat(64))
            put("requested_scopes", "[]")
            put("requested_at", 1_000L)
            put("expires_at", 9_000L)
            put("status", "APPROVED")
        })
        GlafiraCoreLiveRunStore.createSchema(db)
        db.insertOrThrow(GlafiraCoreLiveRunStore.TABLE, null, ContentValues().apply {
            put("task_id", item.taskId)
            put("project_id", item.projectId)
            put("tool_id", item.taskType)
            put("plan_hash", "b".repeat(64))
            put("action_hash", "c".repeat(64))
            put("approved_at", 1_000L)
            put("expires_at", 9_000L)
            put("approved_session_id", "legacy-session-01")
        })
        db.execSQL("DROP TRIGGER IF EXISTS core_task_ownership_state_version")
        db.execSQL("DROP TABLE core_task_ownership")
        db.execSQL("PRAGMA user_version = 10")
        legacy.close()
        stores.remove(legacy)

        val migrated = store("android-core-migrated-01")
        // v10 databases have no proof binding their rows to the noBackup Core identity.
        // A reinstall may have generated this new Core ID, so legacy tasks stay unowned.
        assertNull(ownership(migrated, item.taskId))
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, migrated.getTask(item.taskId)?.state)
        assertEquals("LEGACY_TASK_OWNER_REVIEW_REQUIRED", migrated.getTask(item.taskId)?.lastError)
        val staleLeaseExists = migrated.readableDatabase.rawQuery(
            "SELECT 1 FROM core_desktop_leases WHERE task_id = ?",
            arrayOf(item.taskId)
        ).use { it.moveToFirst() }
        assertFalse("Legacy executor lease must be revoked before owner review", staleLeaseExists)
        val staleNodeExists = migrated.readableDatabase.rawQuery(
            "SELECT 1 FROM core_desktop_nodes WHERE device_id = ?",
            arrayOf("legacy-device")
        ).use { it.moveToFirst() }
        assertFalse("Legacy paired-node metadata must be cleared", staleNodeExists)
        val staleApprovalExists = migrated.readableDatabase.rawQuery(
            "SELECT 1 FROM core_action_approvals WHERE task_id = ?",
            arrayOf(item.taskId)
        ).use { it.moveToFirst() }
        assertFalse("Legacy owner approvals must be cleared", staleApprovalExists)
        val staleLiveGrantExists = migrated.readableDatabase.rawQuery(
            "SELECT 1 FROM core_live_run_grants WHERE task_id = ?",
            arrayOf(item.taskId)
        ).use { it.moveToFirst() }
        assertFalse("Legacy LIVE grants must be cleared", staleLiveGrantExists)
    }

    @Test
    fun portableRestoreDropsOwnershipAuthorityAndLeavesImportedTasksWaitingForOwner() {
        val store = store("android-core-source-01")
        val item = task("ownership-portable-01")
        assertTrue(store.enqueue(item).inserted)
        assertNotNull(ownership(store, item.taskId))

        val portable = GlafiraPortableCoreSnapshot(store)
        portable.exportTo(snapshotFile)
        val verified = portable.readVerified(snapshotFile)
        portable.restore(verified)

        assertNull(ownership(store, item.taskId))
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask(item.taskId)?.state)
        assertTrue(store.readableDatabase.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='core_task_ownership'",
            null
        ).use { it.moveToFirst() })
    }

    @Test
    fun coreB_cannotClaimTaskOwnedByCoreA() {
        val coreA = store("android-core-owner-a")
        val coreB = store("android-core-owner-b")
        val item = task("ownership-isolated-claim")
        assertTrue(coreA.enqueue(item).inserted)

        assertNull(coreB.claimNextOwned(2_000L))
        val claim = requireNotNull(coreA.claimNextOwned(2_000L))
        assertEquals(item.taskId, claim.task.taskId)
        assertEquals("android-core-owner-a", claim.ownershipToken.ownerCoreId)
        assertEquals(1L, claim.ownershipToken.ownerGeneration)
    }

    @Test
    fun staleOwnerGenerationCannotTransitionOrCommitResultAfterGenerationChanges() {
        val core = store("android-core-generation-a")
        val item = task("ownership-stale-generation")
        assertTrue(core.enqueue(item).inserted)
        val staleClaim = requireNotNull(core.claimNextOwned(2_000L))

        // Simulate a committed ownership-generation handoff while an old executor is still alive.
        core.writableDatabase.execSQL(
            "UPDATE core_task_ownership SET owner_generation = 2 WHERE task_id = ?",
            arrayOf(item.taskId)
        )
        core.writableDatabase.execSQL(
            "UPDATE core_tasks SET state = ?, updated_at = ? WHERE task_id = ?",
            arrayOf(GlafiraTaskState.QUEUED.databaseValue, 3_000L, item.taskId)
        )

        assertFalse(core.transitionOwned(
            staleClaim.ownershipToken,
            setOf(GlafiraTaskState.QUEUED),
            GlafiraTaskState.RUNNING,
            4_000L
        ))
        val currentClaim = requireNotNull(core.claimNextOwned(5_000L))
        assertEquals(2L, currentClaim.ownershipToken.ownerGeneration)
        assertFalse(core.persistCoreTaskResultOwned(
            staleClaim.ownershipToken,
            setOf(GlafiraTaskState.RUNNING),
            "stale-result",
            6_000L
        ))
        assertTrue(core.persistCoreTaskResultOwned(
            currentClaim.ownershipToken,
            setOf(GlafiraTaskState.RUNNING),
            "current-result",
            6_000L
        ))
        assertEquals("current-result", core.coreTaskResult(item.taskId, item.projectId)?.result)
    }

    companion object {
        private const val DATABASE_NAME = "glafira_core_tasks.db"
    }
}
