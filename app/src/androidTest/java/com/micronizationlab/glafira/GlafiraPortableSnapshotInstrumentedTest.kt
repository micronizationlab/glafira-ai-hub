package com.micronizationlab.glafira

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
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
    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private lateinit var context: Context
    private lateinit var testRoot: File
    private lateinit var store: GlafiraTaskStore
    private lateinit var snapshot: File
    private lateinit var rollbackCipher: GlafiraRollbackSnapshotCipher
    private val journal get() = File(context.noBackupFilesDir, "glafira-restore-journal")

    private class IsolatedSnapshotContext(base: Context, private val root: File) : ContextWrapper(base) {
        private val databases = File(root, "databases").apply { mkdirs() }
        private val files = File(root, "files").apply { mkdirs() }
        private val noBackup = File(root, "no-backup").apply { mkdirs() }
        private val cache = File(root, "cache").apply { mkdirs() }
        private val fixturePreferencePrefix = "glafira_fixture_${root.name}_"
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(databases, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).absolutePath, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        override fun deleteDatabase(name: String): Boolean = getDatabasePath(name).delete()
        override fun databaseList(): Array<String> = databases.list() ?: emptyArray()
        override fun getFilesDir(): File = files
        override fun getNoBackupFilesDir(): File = noBackup
        override fun getCacheDir(): File = cache
        // Keep the production preference name, but namespace its backing file per fixture.
        // BackupArchive.restoreSafePreferences must never mutate the installed app's settings.
        private fun fixturePreferenceName(name: String) = fixturePreferencePrefix + name
        override fun getSharedPreferences(name: String, mode: Int) =
            baseContext.getSharedPreferences(fixturePreferenceName(name), mode)
        override fun deleteSharedPreferences(name: String): Boolean =
            baseContext.deleteSharedPreferences(fixturePreferenceName(name))
    }

    private var fixturePauseCalls = 0
    private var fixtureRestorePauseCalls = 0
    private val fixtureHooks = object : GlafiraBackupRuntimeHooks {
        override suspend fun <T> withPaused(block: () -> T): T {
            fixturePauseCalls++
            return block()
        }
        override fun pauseForRestore(context: Context) {
            fixtureRestorePauseCalls++
        }
        override fun beforeCommittedJournalCleanup() = Unit
    }

    private fun coordinator() = GlafiraCoreBackupCoordinator(context, store, rollbackCipher, fixtureHooks)

    @Before fun prepare() {
        val runId = java.util.UUID.randomUUID().toString()
        testRoot = File(appContext.cacheDir, "glafira-portable-snapshot-$runId")
        check(testRoot.mkdir()) { "Could not create isolated portable snapshot fixture." }
        context = IsolatedSnapshotContext(appContext, testRoot)
        rollbackCipher = GlafiraRollbackSnapshotCipher("glafira_test_portable_rollback_" + runId.replace("-", ""))
        store = GlafiraTaskStore(context)
        snapshot = File(context.cacheDir, "portable-test.json")
    }
    @After fun clean() {
        if (::store.isInitialized) store.close()
        if (::context.isInitialized) context.deleteSharedPreferences("glafira_prefs")
        if (::rollbackCipher.isInitialized) rollbackCipher.deleteKey()
        if (::testRoot.isInitialized && testRoot.exists()) check(testRoot.deleteRecursively())
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
        val coordinator = coordinator()
        assertTrue(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
        assertThrows(Exception::class.java) { runBlocking { coordinator.recover("wrong-password-123".toCharArray()) } }
        assertTrue(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
        coordinator.recover(passphrase)
        assertFalse(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
        assertNotNull(store.getTask("preserved"))
        assertTrue("Recovery must use fixture-local delivery hooks", fixturePauseCalls > 0)
        assertTrue("Recovery must use fixture-local restore hooks", fixtureRestorePauseCalls > 0)
        passphrase.fill('\u0000')
    }

    @Test fun coordinatorEncryptsLocalRollbackDatabaseAndRecoversIt() = runBlocking {
        store.enqueue(task("preserved-encrypted"))
        val passphrase = "encrypted-local-rollback-passphrase".toCharArray()
        val coordinator = coordinator()
        coordinator.prepareRollback(passphrase)

        val encrypted = File(journal, "database.db")
        assertTrue(encrypted.isFile)
        assertEquals("GLAFIRA_RESTORE_ROLLBACK_V3", File(journal, "restore.pending").readText())
        assertFalse(encrypted.readBytes().take(15).toByteArray()
            .contentEquals("SQLite format 3".toByteArray(Charsets.US_ASCII)))

        val raw = File(context.cacheDir, "glafira-rollback-test-${java.util.UUID.randomUUID()}.tmp")
        try {
            rollbackCipher.decrypt(encrypted, raw)
            assertTrue(raw.readBytes().copyOfRange(0, 15).toString(Charsets.US_ASCII).startsWith("SQLite format 3"))
            store.writableDatabase.delete("core_tasks", null, null)
            assertNull(store.getTask("preserved-encrypted"))

            val staleRecovery = File(context.cacheDir, "glafira-rollback-recovery-stale.tmp")
            staleRecovery.writeText("SQLite format 3")
            assertTrue(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
            coordinator.recover(passphrase)
            assertFalse(staleRecovery.exists())
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
        val coordinator = coordinator()
        coordinator.prepareRollback(passphrase)
        store.writableDatabase.delete("core_tasks", null, null)
        assertNull(store.getTask("preserved-missing-key"))

        rollbackCipher.deleteKey()
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(keyStore.containsAlias(rollbackCipher.keyAlias))

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.recover(passphrase) }
        }
        assertTrue(error.message.orEmpty().contains("Core остаётся заблокирован"))
        assertEquals("ROLLBACK_KEY_MISSING", error.cause?.message)
        assertTrue(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
        val afterFailure = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(afterFailure.containsAlias(rollbackCipher.keyAlias))
        assertNull(store.getTask("preserved-missing-key"))
        passphrase.fill('\u0000')
    }

    @Test fun tamperedRollbackDatabaseIsRejectedBeforeSafetyArchiveChangesLiveFiles() = runBlocking {
        val guard = File(context.filesDir, "rollback-order-guard.txt").apply { writeText("before") }
        store.enqueue(task("preserved-tampered"))
        val passphrase = "encrypted-local-rollback-passphrase".toCharArray()
        val coordinator = coordinator()
        try {
            coordinator.prepareRollback(passphrase)
            guard.writeText("after")
            val encrypted = File(journal, "database.db")
            val bytes = encrypted.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            encrypted.writeBytes(bytes)
            store.writableDatabase.delete("core_tasks", null, null)

            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { coordinator.recover(passphrase) }
            }
            assertTrue(error.message.orEmpty().contains("Core остаётся заблокирован"))
            assertEquals("after", guard.readText())
            assertTrue(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
            assertNull(store.getTask("preserved-tampered"))
        } finally {
            guard.delete()
            passphrase.fill('\u0000')
        }
    }

    @Test fun hasInterruptedRestoreCleansOrphanedDecryptedRollbackTemporary() {
        val stale = File(context.cacheDir, "glafira-rollback-recovery-orphan.tmp")
        stale.writeText("sensitive plaintext")
        assertFalse(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
        assertFalse(stale.exists())
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
