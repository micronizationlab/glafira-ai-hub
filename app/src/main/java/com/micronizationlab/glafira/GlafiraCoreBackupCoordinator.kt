package com.micronizationlab.glafira

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Full application backup, including a portable logical Core queue and actual results. */
class GlafiraCoreBackupCoordinator(
    context: Context,
    private val taskStore: GlafiraTaskStore
) {
    private val appContext = context.applicationContext
    private val portable = GlafiraPortableCoreSnapshot(taskStore)
    private val journalDirectory get() = File(appContext.noBackupFilesDir, JOURNAL_DIRECTORY)
    private val marker get() = File(journalDirectory, JOURNAL_MARKER)
    private val safetyDatabase get() = File(journalDirectory, "database.db")
    private val safetyArchive get() = File(journalDirectory, "files.glb")

    suspend fun create(passphrase: CharArray): File = GlafiraCoreMaintenanceGate.withExclusive {
        synchronized(taskStore) { GlafiraFileMaintenance.withExclusive {
            check(!hasInterruptedRestore(appContext)) {
                "Восстановление было прервано. Откройте восстановление с прежним паролем для безопасного отката."
            }
            createSnapshotArchive(passphrase)
        } }
    }

    suspend fun verify(archive: File, passphrase: CharArray): BackupVerification =
        GlafiraCoreMaintenanceGate.withExclusive {
            BackupArchive.withVerifiedEntry(appContext, archive, passphrase, GlafiraPortableCoreSnapshot.PATH) { entry, verification ->
                portable.readVerified(entry)
                verification
            }
        }

    suspend fun restore(archive: File, passphrase: CharArray): BackupVerification =
        GlafiraCoreMaintenanceGate.withExclusive {
            synchronized(taskStore) { GlafiraFileMaintenance.withExclusive {
                check(loadPendingQueue(appContext).none { it.state == PendingDeliveryState.IN_FLIGHT }) {
                    "Дождитесь завершения текущих запросов перед восстановлением резервной копии."
                }
                // A previous process may have died between replacing files and committing
                // the SQL transaction. Roll it back before accepting any further operation.
                recoverInterruptedRestore(passphrase)
                val selected = File(appContext.cacheDir, "glafira-selected-${UUID.randomUUID()}.glb")
                try {
                    require(archive.isFile && archive.length() in 1..MAX_ARCHIVE_BYTES) { "Недопустимый размер архива." }
                    // A document provider must not swap the archive between validation and use.
                    archive.inputStream().use { input ->
                        selected.outputStream().use { output -> BackupArchive.copyBounded(input, output) }
                    }
                    val snapshot = BackupArchive.withVerifiedEntry(
                        appContext, selected, passphrase, GlafiraPortableCoreSnapshot.PATH
                    ) { entry, _ -> portable.readVerified(entry).also(portable::checkRestoreSupported) }
                    prepareRollback(passphrase)
                    try {
                        val verification = BackupArchive.restore(appContext, selected, passphrase)
                        portable.restore(snapshot)
                        recoverPendingAfterRestore(appContext)
                        checkpointDatabase()
                        clearJournal()
                        verification
                    } catch (restoreError: Exception) {
                        try {
                            recoverInterruptedRestore(passphrase)
                        } catch (rollbackError: Exception) {
                            restoreError.addSuppressed(rollbackError)
                            restoreError.addSuppressed(IllegalStateException(
                                "Откат не завершён. Core заблокирован; данные отката сохранены в ${journalDirectory.absolutePath}. Повторите восстановление с прежним паролем."
                            ))
                        }
                        throw restoreError
                    }
                } finally {
                    selected.delete()
                    File(appContext.filesDir, ".glafira-core-snapshot").deleteRecursively()
                }
            } }
        }

    private fun prepareRollback(passphrase: CharArray) {
        check(!marker.exists()) { "Не завершён предыдущий откат." }
        if (journalDirectory.exists()) check(journalDirectory.deleteRecursively())
        check(journalDirectory.mkdirs())
        try {
            // This raw snapshot is local rollback material only, never portable/exported.
            taskStore.exportConsistentSnapshot(safetyDatabase)
            sync(safetyDatabase)
            val archive = BackupArchive.create(appContext, passphrase)
            try { archive.copyTo(safetyArchive, overwrite = false); sync(safetyArchive) }
            finally { archive.delete() }
            val pendingMarker = File(journalDirectory, "$JOURNAL_MARKER.tmp")
            FileOutputStream(pendingMarker).use { output ->
                output.write("GLAFIRA_RESTORE_ROLLBACK_V1".toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(pendingMarker.renameTo(marker)) { "Не удалось зафиксировать журнал отката." }
            syncDirectory(journalDirectory)
            syncDirectory(appContext.noBackupFilesDir)
        } catch (error: Exception) {
            if (!marker.exists()) journalDirectory.deleteRecursively()
            throw error
        }
    }

    private fun recoverInterruptedRestore(passphrase: CharArray) {
        if (!marker.exists()) return
        require(safetyArchive.isFile && safetyDatabase.isFile) { "Повреждён журнал восстановления. Core заблокирован; требуется ручное восстановление." }
        try {
            // Authenticate before touching files. A different recovery passphrase must not
            // discard the journal or unblock Core with an inconsistent installation.
            BackupArchive.verify(appContext, safetyArchive, passphrase)
            BackupArchive.restore(appContext, safetyArchive, passphrase)
            taskStore.restoreConsistentSnapshot(safetyDatabase)
            checkpointDatabase()
            clearJournal()
        } catch (error: Exception) {
            throw IllegalStateException(
                "Не удалось восстановить состояние до прерывания. Введите пароль, использованный при прерванном восстановлении; Core остаётся заблокирован.", error
            )
        }
    }

    private fun clearJournal() {
        // Delete the marker only after both stores are consistent. If cleanup fails, keep
        // the marker and rollback material; the runtime must continue failing closed.
        check(marker.delete()) { "Не удалось завершить журнал восстановления." }
        syncDirectory(journalDirectory)
        journalDirectory.deleteRecursively()
    }

    private fun createSnapshotArchive(passphrase: CharArray): File {
        val snapshot = File(appContext.cacheDir, "glafira-portable-${UUID.randomUUID()}.json")
        return try {
            portable.exportTo(snapshot)
            BackupArchive.createWithEntries(appContext, passphrase, mapOf(GlafiraPortableCoreSnapshot.PATH to snapshot))
        } finally { snapshot.delete() }
    }

    private fun checkpointDatabase() {
        taskStore.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            require(cursor.moveToFirst() && cursor.getInt(0) == 0) { "Не удалось зафиксировать восстановленную базу Core." }
        }
        sync(appContext.getDatabasePath("glafira_core_tasks.db"))
        syncDirectory(appContext.getDatabasePath("glafira_core_tasks.db").parentFile!!)
    }

    private fun sync(file: File) { FileOutputStream(file, true).use { it.fd.sync() } }

    private fun syncDirectory(directory: File) {
        val descriptor = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L
        private const val JOURNAL_DIRECTORY = "glafira-restore-journal"
        private const val JOURNAL_MARKER = "restore.pending"

        /** Runtime admissions and dispatch must fail closed until recovery is complete. */
        fun hasInterruptedRestore(context: Context): Boolean =
            File(File(context.applicationContext.noBackupFilesDir, JOURNAL_DIRECTORY), JOURNAL_MARKER).exists()
    }
}
