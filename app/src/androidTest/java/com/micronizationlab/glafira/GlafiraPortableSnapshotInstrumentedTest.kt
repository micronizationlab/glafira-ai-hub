package com.micronizationlab.glafira

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraPortableSnapshotInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: GlafiraTaskStore
    private lateinit var snapshot: File
    private val journal get() = File(context.noBackupFilesDir, "glafira-restore-journal")

    @Before fun prepare() {
        GlafiraRollbackSnapshotCipher.deleteKey()
        context.deleteDatabase("glafira_core_tasks.db")
        journal.deleteRecursively()
        store = GlafiraTaskStore(context)
        snapshot = File(context.cacheDir, "portable-test.json")
    }
    @After fun clean() {
        GlafiraRollbackSnapshotCipher.deleteKey()
        store.close()
        context.deleteDatabase("glafira_core_tasks.db")
        snapshot.delete()
        journal.deleteRecursively()
    }
    private fun task(id: String) = GlafiraTask(taskId = id, projectId = "portable-project",
        taskType = "project.read", payload = "payload-$id", origin = "owner",
        createdAtMillis = 1000, updatedAtMillis = 1000, idempotencyKey = "portable-$id")

    @Test fun importedPayloadAndResultAreReencryptedWithRecipientKey() {
        store.enqueue(task("completed"))
        store.claimNext(nowMillis = 2000)
        assertTrue(store.persistCoreTaskResult("completed", setOf(GlafiraTaskState.RUNNING), "actual-output", 3000))
        val source = GlafiraPortableCoreSnapshot(store)
        source.exportTo(snapshot)
        val parsed = source.readVerified(snapshot)
        val recipientKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        fun crypt(input: String, aad: String, writing: Boolean): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            if (writing) {
                cipher.init(Cipher.ENCRYPT_MODE, recipientKey)
                cipher.updateAAD(aad.toByteArray())
                return Base64.encodeToString(cipher.iv + cipher.doFinal(input.toByteArray()), Base64.NO_WRAP)
            }
            val bytes = Base64.decode(input, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, recipientKey, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(aad.toByteArray())
            return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
        }
        GlafiraPortableCoreSnapshot(store, encrypt = { text, aad -> crypt(text, aad, true) }).restore(parsed)
        val payload = store.readableDatabase.rawQuery("SELECT payload_ciphertext FROM core_tasks WHERE task_id='completed'", null)
            .use { assertTrue(it.moveToFirst()); it.getString(0) }
        val result = store.readableDatabase.rawQuery("SELECT result_ciphertext FROM core_task_results WHERE task_id='completed'", null)
            .use { assertTrue(it.moveToFirst()); it.getString(0) }
        assertEquals("payload-completed", crypt(payload, "completed|portable-project", false))
        assertEquals("actual-output", crypt(result, "completed|core-result|portable-project", false))
        assertThrows(Exception::class.java) { GlafiraTaskPayloadCrypto.decrypt(payload, "completed|portable-project") }
    }

    @Test fun taskPlanAndDependenciesSurviveWithoutExecutionAuthority() {
        store.enqueuePlan(GlafiraCoreTaskPlan("portable-plan-1", "portable-project", "plan-key-1", listOf(
            GlafiraCoreTaskPlanNode(task("parent")),
            GlafiraCoreTaskPlanNode(task("child"), setOf("parent"))
        ), 1000))
        val portable = GlafiraPortableCoreSnapshot(store)
        portable.exportTo(snapshot)
        portable.restore(portable.readVerified(snapshot))
        val plan = requireNotNull(store.taskPlan("portable-plan-1", "portable-project"))
        assertEquals(2, plan.nodes.size)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("parent")?.state)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("child")?.state)
        assertFalse(snapshot.readText().contains("core_desktop_leases"))
    }

    @Test fun interruptedRestoreRequiresCorrectPasswordAndRecoversLocalRollback() = runBlocking {
        store.enqueue(task("preserved"))
        check(journal.mkdirs())
        store.exportConsistentSnapshot(File(journal, "database.db"))
        val passphrase = "portable-recovery-passphrase".toCharArray()
        val safety = BackupArchive.create(context, passphrase)
        safety.copyTo(File(journal, "files.glb")); safety.delete()
        File(journal, "restore.pending").writeText("GLAFIRA_RESTORE_ROLLBACK_V2")
        store.writableDatabase.delete("core_tasks", null, null)
        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        assertTrue(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
        assertThrows(Exception::class.java) { runBlocking { coordinator.recover("wrong-password-123".toCharArray()) } }
        assertTrue(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
        coordinator.recover(passphrase)
        assertFalse(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
        assertNotNull(store.getTask("preserved"))
        passphrase.fill('\u0000')
    }

    @Test fun coordinatorEncryptsLocalRollbackDatabaseAndRecoversIt() = runBlocking {
        store.enqueue(task("preserved-encrypted"))
        val passphrase = "encrypted-local-rollback-passphrase".toCharArray()
        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        coordinator.prepareRollback(passphrase)

        val encrypted = File(journal, "database.db")
        assertTrue(encrypted.isFile)
        assertEquals("GLAFIRA_RESTORE_ROLLBACK_V3", File(journal, "restore.pending").readText())
        assertFalse(encrypted.readBytes().take(15).toByteArray()
            .contentEquals("SQLite format 3".toByteArray(Charsets.US_ASCII)))

        val raw = File(context.cacheDir, "glafira-rollback-test-${java.util.UUID.randomUUID()}.tmp")
        try {
            GlafiraRollbackSnapshotCipher.decrypt(encrypted, raw)
            assertTrue(raw.readBytes().copyOfRange(0, 15).toString(Charsets.US_ASCII).startsWith("SQLite format 3"))
            store.writableDatabase.delete("core_tasks", null, null)
            assertNull(store.getTask("preserved-encrypted"))

            coordinator.recover(passphrase)
            assertNotNull(store.getTask("preserved-encrypted"))
            assertFalse(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
        } finally {
            raw.delete()
            passphrase.fill('\u0000')
        }
    }

    @Test fun missingRollbackKeyKeepsCoreBlockedAndDoesNotRegenerateKey() = runBlocking {
        store.enqueue(task("preserved-missing-key"))
        val passphrase = "encrypted-local-rollback-passphrase".toCharArray()
        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        coordinator.prepareRollback(passphrase)
        store.writableDatabase.delete("core_tasks", null, null)
        assertNull(store.getTask("preserved-missing-key"))

        GlafiraRollbackSnapshotCipher.deleteKey()
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(keyStore.containsAlias("glafira_core_rollback_v1"))

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.recover(passphrase) }
        }
        assertTrue(error.message.orEmpty().contains("Core остаётся заблокирован"))
        assertEquals("ROLLBACK_KEY_MISSING", error.cause?.message)
        assertTrue(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
        val afterFailure = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(afterFailure.containsAlias("glafira_core_rollback_v1"))
        assertNull(store.getTask("preserved-missing-key"))
        passphrase.fill('\u0000')
    }

    @Test fun missingTablesAreRejectedBeforeAnyReplacement() {
        store.enqueue(task("unchanged"))
        val portable = GlafiraPortableCoreSnapshot(store)
        portable.exportTo(snapshot)
        val invalid = JSONObject(snapshot.readText())
        invalid.getJSONObject("tables").remove("core_task_dependencies")
        assertThrows(IllegalArgumentException::class.java) { portable.restore(invalid) }
        assertNotNull(store.getTask("unchanged"))
    }
}
