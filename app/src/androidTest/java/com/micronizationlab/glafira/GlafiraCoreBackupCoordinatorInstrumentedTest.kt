package com.micronizationlab.glafira

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraCoreBackupCoordinatorInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: GlafiraTaskStore

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        store = GlafiraTaskStore(context)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    private fun task(id: String) = GlafiraTask(
        taskId = id,
        projectId = "backup-project",
        taskType = "project.read",
        payload = """{"record":"persist-me"}""",
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "backup-project:$id"
    )

    @Test
    fun coordinatorArchiveContainsCoreDatabaseSnapshot() = runBlocking {
        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val archive = coordinator.create(passphrase)
        try {
            val verification = coordinator.verify(archive, passphrase)
            assertTrue(verification.fileCount > 0)
            assertTrue(verification.totalBytes > 0)
        } finally {
            archive.delete()
            passphrase.fill('\u0000')
        }
    }

    @Test
    fun legacyArchiveWithoutCoreDatabaseIsRejectedBeforeRestore() = runBlocking {
        store.enqueue(task("existing-task"))
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val legacyArchive = BackupArchive.create(context, passphrase)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    GlafiraCoreBackupCoordinator(context, store).restore(legacyArchive, passphrase)
                }
            }
            assertEquals("existing-task", store.getTask("existing-task")?.taskId)
        } finally {
            legacyArchive.delete()
            passphrase.fill('\u0000')
        }
    }

    @Test
    fun restoreReturnsCoreTaskStateAndEncryptedResultFromSnapshot() = runBlocking {
        store.enqueue(task("backup-result-task"))
        assertEquals("backup-result-task", store.claimNext(nowMillis = 2_500L)?.taskId)
        assertTrue(store.persistCoreTaskResult(
            "backup-result-task",
            setOf(GlafiraTaskState.RUNNING),
            "result-preserved-by-backup",
            3_000L
        ))

        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val archive = coordinator.create(passphrase)
        try {
            store.writableDatabase.delete(
                "core_task_results", "task_id = ?", arrayOf("backup-result-task")
            )
            assertNull(store.coreTaskResult("backup-result-task", "backup-project"))
            store.enqueue(task("second-task"))
            assertEquals("second-task", store.getTask("second-task")?.taskId)

            coordinator.restore(archive, passphrase)

            assertEquals(
                "result-preserved-by-backup",
                store.coreTaskResult("backup-result-task", "backup-project")?.result
            )
            assertNull(store.getTask("second-task"))
        } finally {
            archive.delete()
            passphrase.fill('\u0000')
        }
    }

    @Test
    fun restoreResetsRunningTasksAndDropsSourceDeviceLeasesAndAuthority() = runBlocking {
        store.enqueue(task("running-task"))
        assertEquals("running-task", store.claimNext(nowMillis = 2_500L)?.taskId)
        store.writableDatabase.insertOrThrow(
            "core_desktop_leases", null,
            android.content.ContentValues().apply {
                put("task_id", "running-task")
                put("device_id", "source-device")
                put("lease_id", "source-lease")
                put("fencing_token", 7L)
                put("lease_expires_at", 9_000L)
                put("attempt_count", 1)
                put("updated_at", 2_500L)
            }
        )
        store.writableDatabase.insertOrThrow(
            "core_desktop_nodes", null,
            android.content.ContentValues().apply {
                put("device_id", "source-device")
                put("last_seen_at", 2_500L)
                put("capabilities_json", """["research"]""")
                put("max_parallel_tasks", 1)
            }
        )

        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val archive = coordinator.create(passphrase)
        try {
            store.enqueue(task("temporary-task"))
            coordinator.restore(archive, passphrase)

            val restored = store.getTask("running-task")
            assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, restored?.state)
            assertEquals("RESTORED_REQUIRES_OWNER_REVIEW", restored?.lastError)
            assertEquals(0, store.writableDatabase.rawQuery(
                "SELECT COUNT(*) FROM core_desktop_leases", null
            ).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(0, store.writableDatabase.rawQuery(
                "SELECT COUNT(*) FROM core_desktop_nodes", null
            ).use { it.moveToFirst(); it.getInt(0) })
            assertNull(store.getTask("temporary-task"))
        } finally {
            archive.delete()
            passphrase.fill('\u0000')
        }
    }

    @Test
    fun exportRejectsUnknownCoreTablesInsteadOfSilentlyOmittingThem(): Unit = runBlocking {
        store.writableDatabase.execSQL(
            "CREATE TABLE core_future_sensitive (id TEXT PRIMARY KEY, secret TEXT NOT NULL)"
        )
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { GlafiraCoreBackupCoordinator(context, store).create(passphrase) }
            }
        } finally {
            store.writableDatabase.execSQL("DROP TABLE core_future_sensitive")
            passphrase.fill('\u0000')
        }
    }

    @Test
    fun portableRestoreInsertFailureRollsBackDatabaseAndKeepsCurrentQueue(): Unit = runBlocking {
        store.enqueue(task("backup-result-task"))
        assertEquals("backup-result-task", store.claimNext(nowMillis = 2_500L)?.taskId)
        assertTrue(store.persistCoreTaskResult(
            "backup-result-task",
            setOf(GlafiraTaskState.RUNNING),
            "known-good-result",
            3_000L
        ))
        val trace = GlafiraCoreTraceStore(store)
        trace.append(GlafiraCoreTraceDraft(
            eventId = "restore-failure-trace",
            projectId = "backup-project",
            taskId = "backup-result-task",
            correlationId = "restore-failure-correlation",
            occurredAtMillis = 3_500L,
            actorType = GlafiraCoreTraceActorType.CORE,
            actorId = "core-main",
            component = "BackupCoordinator",
            eventType = GlafiraCoreTraceEventType.TASK_SUCCEEDED,
            actionCode = "core.result.committed"
        ))

        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val archive = coordinator.create(passphrase)
        try {
            store.enqueue(task("created-after-backup"))
        val expectedTraceEventIds = trace.query(
            GlafiraCoreTraceQuery("backup-project"),
            GlafiraCoreTraceAccessContext("backup-project", allowProjectEvents = true)
        ).events.map { it.eventId }

            store.writableDatabase.execSQL("""
                CREATE TRIGGER inject_portable_restore_result_failure
                BEFORE INSERT ON core_task_results
                BEGIN
                    SELECT RAISE(ABORT, 'injected-portable-restore-result-failure');
                END
            """.trimIndent())

            assertThrows(Exception::class.java) {
                runBlocking { coordinator.restore(archive, passphrase) }
            }

            assertEquals(
                "known-good-result",
                store.coreTaskResult("backup-result-task", "backup-project")?.result
            )
            assertEquals("created-after-backup", store.getTask("created-after-backup")?.taskId)
            assertEquals(false, GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
            assertEquals(
                expectedTraceEventIds,
                trace.query(
                    GlafiraCoreTraceQuery("backup-project"),
                    GlafiraCoreTraceAccessContext("backup-project", allowProjectEvents = true)
                ).events.map { it.eventId }
            )
            assertThrows(android.database.sqlite.SQLiteException::class.java) {
                store.writableDatabase.delete(
                    GlafiraCoreTraceStore.TABLE,
                    "event_id = ?",
                    arrayOf("restore-failure-trace")
                )
            }
            assertTrue(store.readableDatabase.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name = 'core_trace_task_created'",
                null
            ).use { it.moveToFirst() })
            assertTrue(store.readableDatabase.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name = 'core_trace_task_state_changed'",
                null
            ).use { it.moveToFirst() })
            store.writableDatabase.execSQL("DROP TRIGGER inject_portable_restore_result_failure")
        } finally {
            archive.delete()
            passphrase.fill('\u0000')
        }
    }

    @Test
    fun portableBackupRestoresAppendOnlyTraceEventsAndReinstatesGuards() = runBlocking {
        val trace = GlafiraCoreTraceStore(store)
        val original = GlafiraCoreTraceDraft(
            eventId = "backup-trace-before",
            projectId = "backup-trace-project",
            correlationId = "backup-trace-correlation",
            occurredAtMillis = 10_000L,
            actorType = GlafiraCoreTraceActorType.SYSTEM,
            actorId = "system",
            component = "core",
            eventType = GlafiraCoreTraceEventType.BACKUP_STARTED,
            actionCode = "BACKUP_STARTED",
            dataClassification = GlafiraCoreTraceDataClassification.INTERNAL
        )
        trace.append(original)

        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        val passphrase = "GLAFIRA-trace-backup-passphrase".toCharArray()
        val archive = coordinator.create(passphrase)
        try {
            trace.append(original.copy(
                eventId = "backup-trace-after",
                correlationId = "backup-trace-after-correlation",
                occurredAtMillis = 20_000L,
                eventType = GlafiraCoreTraceEventType.BACKUP_VERIFIED,
                actionCode = "BACKUP_VERIFIED"
            ))

            coordinator.restore(archive, passphrase)

            val restored = trace.query(
                GlafiraCoreTraceQuery("backup-trace-project"),
                GlafiraCoreTraceAccessContext("backup-trace-project", allowProjectEvents = true)
            )
            assertEquals(listOf("backup-trace-before"), restored.events.map { it.eventId })
            assertTrue(store.readableDatabase.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name = 'core_trace_task_created'",
                null
            ).use { it.moveToFirst() })
            assertTrue(store.readableDatabase.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name = 'core_trace_task_state_changed'",
                null
            ).use { it.moveToFirst() })
            assertThrows(SQLiteException::class.java) {
                store.writableDatabase.delete(
                    GlafiraCoreTraceStore.TABLE,
                    "event_id = ?",
                    arrayOf("backup-trace-before")
                )
            }
        } finally {
            archive.delete()
            passphrase.fill('\u0000')
        }
        Unit
    }

    @Test
    fun wrongPassphraseLeavesCurrentCoreQueueUntouched() = runBlocking {
        store.enqueue(task("must-remain"))
        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        val correctPassphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val wrongPassphrase = "GLAFIRA-wrong-passphrase-2026".toCharArray()
        val archive = coordinator.create(correctPassphrase)
        try {
            assertThrows(Exception::class.java) {
                runBlocking { coordinator.restore(archive, wrongPassphrase) }
            }
            assertEquals("must-remain", store.getTask("must-remain")?.taskId)
        } finally {
            archive.delete()
            correctPassphrase.fill('\u0000')
            wrongPassphrase.fill('\u0000')
        }
    }

    @Test
    fun truncatedAesGcmTagIsRejectedDuringVerification() {
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val archive = BackupArchive.create(context, passphrase)
        val truncated = File(context.cacheDir, "glafira-truncated-test.glb")
        try {
            archive.copyTo(truncated, overwrite = true)
            RandomAccessFile(truncated, "rw").use { file -> file.setLength(file.length() - 1L) }
            assertThrows(Exception::class.java) {
                BackupArchive.verify(context, truncated, passphrase)
            }
        } finally {
            archive.delete()
            truncated.delete()
            passphrase.fill('\u0000')
        }
    }

    @Test
    fun archiveFromAnotherApplicationIdIsRejected() {
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val otherAppContext = object : ContextWrapper(context) {
            override fun getPackageName(): String = "com.example.other.glafira"
        }
        val foreignArchive = BackupArchive.create(otherAppContext, passphrase)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                BackupArchive.verify(context, foreignArchive, passphrase)
            }
        } finally {
            foreignArchive.delete()
            passphrase.fill('\u0000')
        }
    }

    @Test
    fun requiredArchivePathRejectsTraversalBeforeReadingArchive() {
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val nonexistent = File(context.cacheDir, "not-created.glb")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                BackupArchive.verifyContains(context, nonexistent, passphrase, "../glafira_core_tasks.db")
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    @Test
    fun authenticatedArchiveWithMalformedPortableCoreIsRejectedBeforeRollbackOrMutation() = runBlocking {
        store.enqueue(task("must-survive-invalid-snapshot"))
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val snapshotDirectory = File(context.filesDir, ".glafira-core-snapshot")
        snapshotDirectory.deleteRecursively()
        assertTrue(snapshotDirectory.mkdirs())
        val snapshot = File(snapshotDirectory, "core-portable-v2.json")
        val archive = try {
            snapshot.writeText("""{"format":"GLAFIRA_PORTABLE_CORE","version":2,"tables":{}}""")
            BackupArchive.create(context, passphrase)
        } finally {
            snapshotDirectory.deleteRecursively()
        }

        try {
            assertThrows(Exception::class.java) {
                runBlocking {
                    GlafiraCoreBackupCoordinator(context, store).restore(archive, passphrase)
                }
            }
            assertEquals("must-survive-invalid-snapshot", store.getTask("must-survive-invalid-snapshot")?.taskId)
            assertEquals(false, GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
            assertEquals(0, File(context.noBackupFilesDir, "glafira-restore-journal").listFiles()?.size ?: 0)
        } finally {
            archive.delete()
            passphrase.fill('\u0000')
        }
    }

    @Test
    fun backupRestorePreservesProviderModelPreferencesWithoutTransferringCredentials() {
        val preferences = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
        val providersKey = "peer_consultation_providers_model_backup"
        val enabledKey = "peer_consultation_enabled_model_backup"
        val modelKey = "provider_model_${ProviderId.OPENAI.name}"
        val apiKey = "openai_api_key"
        val previous = preferences.all.filterKeys { it in setOf(providersKey, enabledKey, modelKey, apiKey) }
        val passphrase = "GLAFIRA-provider-model-backup-test".toCharArray()
        var archive: File? = null
        try {
            assertTrue(preferences.edit()
                .putString(providersKey, listOf(ProviderId.OPENAI, ProviderId.DEEPSEEK).joinToString(",") { it.name })
                .putBoolean(enabledKey, true)
                .putString(modelKey, "owner-selected-model-v2")
                .putString(apiKey, "source-secret-must-not-transfer")
                .commit())
            archive = BackupArchive.create(context, passphrase)
            assertTrue(preferences.edit()
                .putString(providersKey, ProviderId.GEMINI.name)
                .putBoolean(enabledKey, false)
                .putString(modelKey, "changed-after-backup")
                .putString(apiKey, "destination-secret-must-remain")
                .commit())

            BackupArchive.restore(context, archive!!, passphrase)

            assertEquals(listOf(ProviderId.OPENAI, ProviderId.DEEPSEEK).joinToString(",") { it.name },
                preferences.getString(providersKey, null))
            assertTrue(preferences.getBoolean(enabledKey, false))
            assertEquals("owner-selected-model-v2", loadProviderModel(context, ProviderId.OPENAI, "safe-default"))
            assertEquals("destination-secret-must-remain", preferences.getString(apiKey, null))
        } finally {
            archive?.delete()
            passphrase.fill('\u0000')
            val editor = preferences.edit()
            listOf(providersKey, enabledKey, modelKey, apiKey).forEach { editor.remove(it) }
            previous.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            assertTrue(editor.commit())
        }
    }

    @Test
    fun backupRestoreRejectsInvalidProviderModelAndLeavesUnknownOrCredentialSlotsLocal() {
        val preferences = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
        val modelKey = "provider_model_${ProviderId.OPENAI.name}"
        val unknownKey = "provider_model_NOT_A_PROVIDER"
        val apiKey = "openai_api_key"
        val keys = listOf(modelKey, unknownKey, apiKey)
        val previous = preferences.all.filterKeys { it in keys }
        val passphrase = "GLAFIRA-invalid-model-backup-test".toCharArray()
        var archive: File? = null
        try {
            assertTrue(preferences.edit()
                .putString(modelKey, "invalid model\n")
                .putString(unknownKey, "source-unknown-model")
                .putString(apiKey, "source-secret-must-not-transfer")
                .commit())
            archive = BackupArchive.create(context, passphrase)
            assertTrue(preferences.edit()
                .putString(modelKey, "destination-model")
                .putString(unknownKey, "destination-unknown-model")
                .putString(apiKey, "destination-secret-must-remain")
                .commit())

            BackupArchive.restore(context, archive!!, passphrase)

            assertEquals("safe-default", loadProviderModel(context, ProviderId.OPENAI, "safe-default"))
            assertEquals("destination-unknown-model", preferences.getString(unknownKey, null))
            assertEquals("destination-secret-must-remain", preferences.getString(apiKey, null))
        } finally {
            archive?.delete()
            passphrase.fill('\u0000')
            val editor = preferences.edit()
            keys.forEach { editor.remove(it) }
            previous.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            assertTrue(editor.commit())
        }
    }


    @Test
    fun portableRestoreOnCleanInstallationCreatesFreshCoreIdentity() = runBlocking {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val identityFile = File(context.noBackupFilesDir, "glafira_test_restore_identity_$suffix.properties")
        val keyAliasPrefix = "glafira.test.backup.restore.$suffix."
        val identityStore = AndroidCoreInstallationIdentityStore(context, identityFile, keyAliasPrefix)
        val sourceIdentity = identityStore.loadOrCreate()
        val passphrase = "GLAFIRA-clean-install-restore-test-2026".toCharArray()
        val archive = GlafiraCoreBackupCoordinator(context, store).create(passphrase)
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val sourceAlias = "$keyAliasPrefix${sourceIdentity.keyEpoch}"
            assertTrue(keyStore.containsAlias(sourceAlias))
            // Simulate a replacement installation without the source noBackup identity or private key.
            assertTrue(identityFile.delete())
            keyStore.deleteEntry(sourceAlias)

            GlafiraCoreBackupCoordinator(context, store).restore(archive, passphrase)

            val restored = identityStore.loadOrCreate()
            assertNotEquals(sourceIdentity.coreId, restored.coreId)
            assertNotEquals(sourceIdentity.publicKeyBase64, restored.publicKeyBase64)
            assertEquals(1L, restored.keyEpoch)
            assertTrue(identityFile.isFile)
            assertTrue(keyStore.containsAlias("$keyAliasPrefix${restored.keyEpoch}"))
        } finally {
            archive.delete()
            identityFile.delete()
            File(identityFile.parentFile, identityFile.name + ".tmp").delete()
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.aliases().toList().filter { it.startsWith(keyAliasPrefix) }.forEach(keyStore::deleteEntry)
            passphrase.fill('\u0000')
        }
    }


    @Test
    fun successfulRestoreDeletesInstallationLocalRollbackKey() = runBlocking {
        val keyAlias = "glafira_core_rollback_v1"
        GlafiraRollbackSnapshotCipher.deleteKey()
        store.enqueue(task("rollback-key-lifecycle"))
        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        val passphrase = "GLAFIRA-rollback-key-lifecycle".toCharArray()
        val archive = coordinator.create(passphrase)
        try {
            coordinator.restore(archive, passphrase)
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse("Rollback key must be deleted after the pending journal is cleared", keyStore.containsAlias(keyAlias))
        } finally {
            archive.delete()
            passphrase.fill('\\u0000')
            GlafiraRollbackSnapshotCipher.deleteKey()
        }
    }

    companion object {
        private const val DATABASE_NAME = "glafira_core_tasks.db"
    }
}
