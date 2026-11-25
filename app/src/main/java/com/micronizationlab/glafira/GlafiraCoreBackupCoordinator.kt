package com.micronizationlab.glafira

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Portable logical Core data inside an authenticated archive; raw SQLite is local rollback only. */
class GlafiraCoreBackupCoordinator(
    context: Context,
    private val taskStore: GlafiraTaskStore,
    private val recoveryTempPrefix: String = DEFAULT_RECOVERY_TEMP_PREFIX
) {
    private val app = context.applicationContext
    private val portable = GlafiraPortableCoreSnapshot(taskStore)
    private val journal get() = File(app.noBackupFilesDir, JOURNAL_DIRECTORY)
    private val marker get() = File(journal, JOURNAL_MARKER)
    private val safetyDatabase get() = File(journal, "database.db")
    private val safetyArchive get() = File(journal, "files.glb")

    init {
        require(recoveryTempPrefix.matches(Regex("[A-Za-z0-9_-]{1,80}"))) {
            "Invalid rollback recovery temp prefix."
        }
    }

    suspend fun create(passphrase: CharArray): File = GlafiraCoreMaintenanceGate.withExclusive {
        PendingDeliveryRuntime.withPaused {
            GlafiraFileMaintenance.withExclusive { synchronized(taskStore) {
                check(!hasInterruptedRestore(app)) { "Сначала завершите восстановление с прежним паролем." }
                createArchive(passphrase)
            } }
        }
    }

    suspend fun verify(archive: File, passphrase: CharArray): BackupVerification =
        GlafiraCoreMaintenanceGate.withExclusive {
            BackupArchive.withVerifiedEntry(app, archive, passphrase, GlafiraPortableCoreSnapshot.PATH) { entry, verification ->
                portable.readVerified(entry)
                verification
            }
        }

    suspend fun recover(passphrase: CharArray) = GlafiraCoreMaintenanceGate.withExclusive {
        PendingDeliveryRuntime.withPaused {
            GlafiraFileMaintenance.withExclusive { synchronized(taskStore) {
                recoverInterruptedRestore(passphrase)
            } }
        }
    }

    suspend fun restore(archive: File, passphrase: CharArray): BackupVerification = GlafiraCoreMaintenanceGate.withExclusive {
        PendingDeliveryRuntime.withPaused {
            GlafiraFileMaintenance.withExclusive { synchronized(taskStore) {
                recoverInterruptedRestore(passphrase)
                val selected = File(app.cacheDir, "glafira-selected-${UUID.randomUUID()}.glb")
                try {
                    require(archive.isFile && archive.length() in 1..MAX_ARCHIVE_BYTES)
                    archive.inputStream().use { input -> selected.outputStream().use { output ->
                        BackupArchive.copyBounded(input, output)
                    } }
                    val snapshot = BackupArchive.withVerifiedEntry(app, selected, passphrase, GlafiraPortableCoreSnapshot.PATH) { entry, _ ->
                        portable.readVerified(entry).also(portable::checkRestoreSupported)
                    }
                    prepareRollback(passphrase)
                    try {
                        val verification = BackupArchive.restore(app, selected, passphrase)
                        portable.restore(snapshot)
                        recoverPendingAfterRestore(app)
                        GlafiraCoreRuntimeOwner.pauseForRestore(app)
                        checkpointDatabase()
                        clearJournal()
                        verification
                    } catch (error: Exception) {
                        try { recoverInterruptedRestore(passphrase) }
                        catch (rollback: Exception) { error.addSuppressed(rollback) }
                        throw error
                    }
                } finally {
                    selected.delete()
                    File(app.filesDir, ".glafira-core-snapshot").deleteRecursively()
                }
            } }
        }
    }

    private fun createArchive(passphrase: CharArray): File {
        val directory = File(app.filesDir, ".glafira-core-snapshot")
        if (directory.exists()) check(directory.deleteRecursively())
        check(directory.mkdirs())
        return try {
            portable.exportTo(File(app.filesDir, GlafiraPortableCoreSnapshot.PATH))
            BackupArchive.create(app, passphrase)
        } finally { directory.deleteRecursively() }
    }

    internal fun prepareRollback(passphrase: CharArray) {
        check(!marker.exists())
        if (journal.exists()) check(journal.deleteRecursively())
        check(journal.mkdirs())
        try {
            synchronized(taskStore) {
                val consistentDatabase = taskStore.prepareConsistentSnapshotSource()
                sync(consistentDatabase)
                GlafiraRollbackSnapshotCipher.encrypt(consistentDatabase, safetyDatabase)
            }
            sync(safetyDatabase)
            val archive = BackupArchive.create(app, passphrase)
            try { archive.copyTo(safetyArchive, overwrite = false); sync(safetyArchive) }
            finally { archive.delete() }
            val pendingMarker = File(journal, "$JOURNAL_MARKER.tmp")
            FileOutputStream(pendingMarker).use {
                it.write("GLAFIRA_RESTORE_ROLLBACK_V3".toByteArray(Charsets.UTF_8)); it.fd.sync()
            }
            check(pendingMarker.renameTo(marker))
            syncDirectory(journal)
            syncDirectory(app.noBackupFilesDir)
        } catch (error: Exception) {
            if (!marker.exists()) journal.deleteRecursively()
            throw error
        }
    }

    private fun cleanupStaleDecryptedRollbackSnapshots() {
        val stale = app.cacheDir.listFiles { file ->
            file.name.startsWith(recoveryTempPrefix) && file.name.endsWith(".tmp")
        }.orEmpty()
        stale.forEach { file ->
            check(!file.exists() || file.delete()) {
                "Не удалось удалить временный расшифрованный снимок; Core остаётся заблокирован."
            }
        }
    }

    private fun recoverInterruptedRestore(passphrase: CharArray) {
        cleanupStaleDecryptedRollbackSnapshots()
        if (!marker.exists()) return
        require(safetyArchive.isFile && safetyDatabase.isFile) { "Повреждён журнал восстановления; Core заблокирован." }
        val markerVersion = marker.readText(Charsets.UTF_8).trim()
        val encryptedRollback = markerVersion == "GLAFIRA_RESTORE_ROLLBACK_V3"
        require(encryptedRollback || markerVersion == "GLAFIRA_RESTORE_ROLLBACK_V2") {
            "Неподдерживаемая версия журнала восстановления; Core заблокирован."
        }
        val decryptedDatabase = if (encryptedRollback) {
            File(app.cacheDir, "$recoveryTempPrefix${UUID.randomUUID()}.tmp")
        } else null
        val rollbackDatabase = decryptedDatabase ?: safetyDatabase
        try {
            // Decrypt and validate the database before applying the safety archive to live files.
            // A missing Keystore key must fail closed without regenerating a key or partially restoring files.
            if (encryptedRollback) GlafiraRollbackSnapshotCipher.decrypt(safetyDatabase, rollbackDatabase)
            taskStore.verifyConsistentSnapshot(rollbackDatabase)
            BackupArchive.verify(app, safetyArchive, passphrase)
            BackupArchive.restore(app, safetyArchive, passphrase)
            taskStore.restoreConsistentSnapshot(rollbackDatabase)
            GlafiraCoreRuntimeOwner.pauseForRestore(app)
            checkpointDatabase()
            clearJournal()
        } catch (error: Exception) {
            throw IllegalStateException("Не удалось проверить или восстановить safety snapshot. Core остаётся заблокирован до успешного отката.", error)
        } finally {
            decryptedDatabase?.delete()
        }
    }

    private fun clearJournal() {
        check(marker.delete()) { "Не удалось завершить журнал восстановления." }
        syncDirectory(journal)
        check(journal.deleteRecursively()) { "Не удалось удалить журнал восстановления." }
        syncDirectory(app.noBackupFilesDir)
        // Delete the installation-local key only after the pending journal and encrypted
        // rollback snapshot are gone. A cleanup failure must not destroy an otherwise usable
        // rollback snapshot while its pending marker still exists.
        GlafiraRollbackSnapshotCipher.deleteKey()
    }

    private fun checkpointDatabase() {
        taskStore.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use {
            require(it.moveToFirst() && it.getInt(0) == 0) { "Не удалось зафиксировать базу Core." }
        }
        val database = app.getDatabasePath("glafira_core_tasks.db")
        sync(database)
        syncDirectory(database.parentFile!!)
    }

    private fun sync(file: File) { FileOutputStream(file, true).use { it.fd.sync() } }
    private fun syncDirectory(directory: File) {
        val fd = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L
        private const val JOURNAL_DIRECTORY = "glafira-restore-journal"
        private const val JOURNAL_MARKER = "restore.pending"
        private const val DEFAULT_RECOVERY_TEMP_PREFIX = "glafira-rollback-recovery-"
        fun hasInterruptedRestore(context: Context): Boolean {
            val app = context.applicationContext
            val marker = File(File(app.noBackupFilesDir, JOURNAL_DIRECTORY), JOURNAL_MARKER)
            if (marker.exists()) return true
            val stale = app.cacheDir.listFiles { file ->
                file.name.startsWith("glafira-rollback-recovery-") && file.name.endsWith(".tmp")
            }.orEmpty()
            return stale.any { it.exists() && !it.delete() }
        }
    }
}

/** Reuse the runtime's store, so maintenance and ordinary queue operations share the same monitor. */
internal suspend fun <T> withCoreBackupCoordinator(context: Context, block: suspend (GlafiraCoreBackupCoordinator) -> T): T =
    block(GlafiraCoreBackupCoordinator(context, GlafiraCoreRuntimeOwner.get(context).taskStore))
