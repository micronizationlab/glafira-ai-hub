package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Android Keystore + real SQLite tests; host-only unit tests cannot prove portability. */
@RunWith(AndroidJUnit4::class)
class GlafiraPortableBackupInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: GlafiraTaskStore
    private val passphrase = "GLAFIRA-portable-device-transfer-test".toCharArray()
    private val files = mutableListOf<File>()
    private val pendingBefore = mutableMapOf<String, ByteArray>()
    private val pendingNames = listOf("pending_queue.json", "pending_queue.json.bak")

    @Before fun setUp() {
        context.deleteDatabase("glafira_core_tasks.db")
        File(context.noBackupFilesDir, "glafira-restore-journal").deleteRecursively()
        store = GlafiraTaskStore(context)
        pendingNames.forEach { name ->
            File(context.filesDir, name).also { file -> if (file.exists()) { pendingBefore[name] = file.readBytes(); file.delete() } }
        }
    }

    @After fun tearDown() {
        store.close()
        context.deleteDatabase("glafira_core_tasks.db")
        File(context.noBackupFilesDir, "glafira-restore-journal").deleteRecursively()
        File(context.filesDir, ".glafira-core-snapshot").deleteRecursively()
        files.forEach { it.delete() }
        pendingNames.forEach { File(context.filesDir, it).delete() }
        pendingBefore.forEach { (name, bytes) -> File(context.filesDir, name).writeBytes(bytes); bytes.fill(0) }
        passphrase.fill('\u0000')
    }

    private fun temporary(name: String) = File(context.cacheDir, "$name-${UUID.randomUUID()}").also { files.add(it) }
    private fun task(id: String) = GlafiraTask(
        taskId = id, projectId = "portable-project", taskType = "project.read", payload = "{\"value\":\"actual payload\"}",
        origin = "owner", createdAtMillis = 1_000, updatedAtMillis = 1_000, idempotencyKey = "portable:$id"
    )
    private fun succeed(id: String) {
        assertTrue(store.transition(id, setOf(GlafiraTaskState.QUEUED), GlafiraTaskState.RUNNING, 2_000))
        assertTrue(store.transition(id, setOf(GlafiraTaskState.RUNNING), GlafiraTaskState.SUCCEEDED, 3_000))
    }
    private fun createResultSchema() {
        store.writableDatabase.execSQL("""CREATE TABLE IF NOT EXISTS core_task_results(
            task_id TEXT PRIMARY KEY NOT NULL REFERENCES core_tasks(task_id) ON DELETE CASCADE,
            project_id TEXT NOT NULL,result_id TEXT NOT NULL UNIQUE,terminal_state TEXT NOT NULL,
            source_device_id TEXT,result_ciphertext TEXT NOT NULL,completed_at INTEGER NOT NULL)""")
        store.writableDatabase.execSQL("""CREATE TABLE IF NOT EXISTS core_result_acks(
            result_id TEXT NOT NULL REFERENCES core_task_results(result_id),device_id TEXT NOT NULL,
            acknowledged_at INTEGER NOT NULL,PRIMARY KEY(result_id,device_id))""")
    }

    @Test fun cleanDeviceRestoreReencryptsPayloadsResultsAndPreservesCompletedIdempotency() = runBlocking {
        store.enqueue(task("finished")); succeed("finished")
        store.enqueue(task("incomplete")); store.claimNext(4_000)
        createResultSchema()
        val result = "{\"answer\":\"real completed result\"}"
        store.writableDatabase.insertOrThrow("core_task_results", null, ContentValues().apply {
            put("task_id", "finished"); put("project_id", "portable-project"); put("result_id", "stable-result-id")
            put("terminal_state", "SUCCEEDED"); put("source_device_id", "old-desktop")
            put("result_ciphertext", GlafiraTaskPayloadCrypto.encrypt(result, "finished|core-result|portable-project"))
            put("completed_at", 3_000L)
        })
        store.writableDatabase.execSQL("INSERT INTO core_result_acks VALUES ('stable-result-id','old-phone',4000)")
        val archive = GlafiraCoreBackupCoordinator(context, store).create(passphrase).also { files.add(it) }

        // Emulate reinstall/new device: destroy the source database AND non-exportable key.
        store.close(); context.deleteDatabase("glafira_core_tasks.db")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("glafira_task_payload_aes") }
        store = GlafiraTaskStore(context); createResultSchema()
        GlafiraCoreBackupCoordinator(context, store).restore(archive, passphrase)

        assertEquals(task("finished").payload, store.getTask("finished")?.payload)
        assertEquals(GlafiraTaskState.SUCCEEDED, store.getTask("finished")?.state)
        assertFalse(store.enqueue(task("finished")).inserted) // same operation is never replayed
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("incomplete")?.state)
        assertEquals("RESTORED_REQUIRES_OWNER_REVIEW", store.getTask("incomplete")?.lastError)
        assertNull(store.claimNext(10_000))
        store.readableDatabase.rawQuery("SELECT * FROM core_task_results WHERE task_id='finished'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("stable-result-id", cursor.getString(cursor.getColumnIndexOrThrow("result_id")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("source_device_id")))
            assertEquals(result, GlafiraTaskPayloadCrypto.decrypt(
                cursor.getString(cursor.getColumnIndexOrThrow("result_ciphertext")), "finished|core-result|portable-project"))
        }
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM core_result_acks", null).use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0))
        }
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM core_task_history WHERE task_id='finished'", null).use { cursor ->
            assertTrue(cursor.moveToFirst()); assertTrue(cursor.getInt(0) >= 4)
        }
        assertFalse(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
    }

    @Test fun foreignProjectResultFailsValidationWithoutChangingLiveQueue() {
        store.enqueue(task("finished")); succeed("finished"); createResultSchema()
        store.writableDatabase.insertOrThrow("core_task_results", null, ContentValues().apply {
            put("task_id", "finished"); put("project_id", "portable-project"); put("result_id", "stable-result-id")
            put("terminal_state", "SUCCEEDED"); putNull("source_device_id")
            put("result_ciphertext", GlafiraTaskPayloadCrypto.encrypt("{}", "finished|core-result|portable-project")); put("completed_at", 3_000L)
        })
        val snapshot = temporary("portable.json")
        val adapter = GlafiraPortableCoreSnapshot(store)
        adapter.exportTo(snapshot)
        val json = JSONObject(snapshot.readText())
        json.getJSONObject("tables").getJSONArray("core_task_results").getJSONObject(0).put("project_id", "another-project")
        snapshot.writeText(json.toString())
        assertThrows(IllegalArgumentException::class.java) { adapter.readVerified(snapshot) }
        assertEquals(GlafiraTaskState.SUCCEEDED, store.getTask("finished")?.state)
        assertEquals(task("finished").payload, store.getTask("finished")?.payload)
    }

    @Test fun fullRestoreRejectsLegacyFileOnlyArchiveBeforeTouchingTasks() = runBlocking {
        store.enqueue(task("untouched"))
        val archive = BackupArchive.create(context, passphrase).also { files.add(it) }
        try {
            GlafiraCoreBackupCoordinator(context, store).restore(archive, passphrase)
            fail("Expected missing portable Core snapshot")
        } catch (_: IllegalStateException) { }
        assertEquals(GlafiraTaskState.QUEUED, store.getTask("untouched")?.state)
        assertFalse(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
    }

    @Test fun interruptedRestoreJournalRecoversOriginalStateBeforeAcceptingAnotherRestore() = runBlocking {
        store.enqueue(task("original"))
        val directory = File(context.noBackupFilesDir, "glafira-restore-journal").apply { mkdirs() }
        store.exportConsistentSnapshot(File(directory, "database.db"))
        val safety = BackupArchive.create(context, passphrase).also { files.add(it) }
        safety.copyTo(File(directory, "files.glb"))
        File(directory, "restore.pending").writeText("GLAFIRA_RESTORE_ROLLBACK_V1")
        // Partial mutation of live SQLite; emulate the process disappearing before commit marker cleanup.
        store.enqueue(task("partial-restore-only"))
        store.close(); store = GlafiraTaskStore(context)
        assertTrue(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
        val wrongPassphrase = "wrong-recovery-passphrase".toCharArray()
        try {
            GlafiraCoreBackupCoordinator(context, store).restore(temporary("missing-wrong.glb"), wrongPassphrase)
            fail("Wrong password must retain the recovery barrier")
        } catch (_: IllegalStateException) { }
        finally { wrongPassphrase.fill('\u0000') }
        assertTrue(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
        assertNotNull(store.getTask("partial-restore-only"))
        try {
            GlafiraCoreBackupCoordinator(context, store).restore(temporary("missing.glb"), passphrase)
            fail("Expected missing selected archive")
        } catch (_: IllegalArgumentException) { }
        assertNotNull(store.getTask("original"))
        assertNull(store.getTask("partial-restore-only"))
        assertFalse(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
    }

    @Test fun liveRequestBlocksRestoreAndHistoricalQueuedRequestRequiresReview() = runBlocking {
        val pending = PendingMessage("portable-project", "АНАЛИТИК", "original request", "10:00")
        addToPendingQueue(context, pending)
        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        val archive = coordinator.create(passphrase).also { files.add(it) }
        val messages = PendingMessageStore(context.filesDir)
        assertNotNull(messages.claim(pending.id))
        try {
            coordinator.restore(archive, passphrase)
            fail("Live request must not write across snapshot replacement")
        } catch (_: IllegalStateException) { }
        assertEquals(PendingDeliveryState.IN_FLIGHT, messages.all().single().state)
        assertFalse(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
        messages.failed(pending.id, "unknown external outcome", false, true)
        coordinator.restore(archive, passphrase)
        assertEquals(PendingDeliveryState.UNCERTAIN, messages.all().single().state)
        assertNull(messages.claim(pending.id))
    }

    @Test fun vaultCredentialsAreNotExportedAndDestinationVaultIsPreserved() = runBlocking {
        val vault = File(context.filesDir, "password_vault.enc")
        val previous = if (vault.exists()) vault.readBytes() else null
        try {
            vault.writeText("source-device-secret-envelope")
            val coordinator = GlafiraCoreBackupCoordinator(context, store)
            val archive = coordinator.create(passphrase).also { files.add(it) }
            vault.writeText("destination-device-secret-envelope")
            coordinator.restore(archive, passphrase)
            assertEquals("destination-device-secret-envelope", vault.readText())
            try {
                BackupArchive.verifyContains(context, archive, passphrase, "password_vault.enc")
                fail("Vault must never be included")
            } catch (_: IllegalArgumentException) { }
        } finally {
            if (previous == null) vault.delete() else { vault.writeBytes(previous); previous.fill(0) }
        }
    }
}
