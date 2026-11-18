package com.micronizationlab.glafira

import android.content.Context
import java.io.File

/**
 * Coordinates encrypted file backups with the persistent Core task database.
 * All methods pause the shared executor while the database snapshot is taken or restored.
 */
class GlafiraCoreBackupCoordinator(
    context: Context,
    private val taskStore: GlafiraTaskStore
) {
    private val appContext = context.applicationContext

    suspend fun create(passphrase: CharArray): File =
        GlafiraCoreMaintenanceGate.withExclusive {
            createSnapshotArchive(passphrase)
        }

    suspend fun verify(archive: File, passphrase: CharArray): BackupVerification =
        GlafiraCoreMaintenanceGate.withExclusive {
            BackupArchive.verifyContains(appContext, archive, passphrase, CORE_DATABASE_SNAPSHOT_PATH)
        }

    suspend fun restore(archive: File, passphrase: CharArray): BackupVerification =
        GlafiraCoreMaintenanceGate.withExclusive {
            // Reject legacy/incomplete archives before touching the current installation.
            BackupArchive.verifyContains(appContext, archive, passphrase, CORE_DATABASE_SNAPSHOT_PATH)

            val safetyDatabase = File(
                appContext.cacheDir,
                "glafira-core-safety-${System.currentTimeMillis()}.db"
            )
            var safetyArchive: File? = null
            var restoreCompleted = false
            var rollbackCompleted = false

            try {
                taskStore.exportConsistentSnapshot(safetyDatabase)
                val createdSafetyArchive = createSnapshotArchive(passphrase)
                safetyArchive = createdSafetyArchive

                try {
                    val verification = BackupArchive.restore(appContext, archive, passphrase)
                    val restoredDatabase = File(appContext.filesDir, CORE_DATABASE_SNAPSHOT_PATH)
                    require(restoredDatabase.isFile) {
                        "Восстановленный архив не содержит базу очереди GLAFIRA Core."
                    }
                    taskStore.restoreConsistentSnapshot(restoredDatabase)
                    restoreCompleted = true
                    return@withExclusive verification
                } catch (restoreError: Exception) {
                    try {
                        BackupArchive.restore(appContext, createdSafetyArchive, passphrase)
                        taskStore.restoreConsistentSnapshot(safetyDatabase)
                        rollbackCompleted = true
                    } catch (rollbackError: Exception) {
                        restoreError.addSuppressed(rollbackError)
                        restoreError.addSuppressed(
                            IllegalStateException(
                                "Файлы восстановления сохранены для ручного восстановления: " +
                                    createdSafetyArchive.absolutePath + " ; " + safetyDatabase.absolutePath
                            )
                        )
                    }
                    throw restoreError
                }
            } finally {
                if (restoreCompleted || rollbackCompleted || safetyArchive == null) {
                    File(appContext.filesDir, CORE_DATABASE_SNAPSHOT_DIRECTORY).deleteRecursively()
                    safetyDatabase.delete()
                    safetyArchive?.delete()
                } else if (safetyDatabase.length() == 0L) {
                    safetyDatabase.delete()
                }
            }
        }

    private fun createSnapshotArchive(passphrase: CharArray): File {
        val directory = File(appContext.filesDir, CORE_DATABASE_SNAPSHOT_DIRECTORY)
        check(directory.exists() || directory.mkdirs()) {
            "Не удалось создать каталог снимка базы GLAFIRA Core."
        }
        val snapshot = File(directory, CORE_DATABASE_SNAPSHOT_NAME)
        try {
            taskStore.exportConsistentSnapshot(snapshot)
            return BackupArchive.create(appContext, passphrase)
        } finally {
            // The encrypted archive owns the snapshot from this point onward.
            directory.deleteRecursively()
        }
    }

    companion object {
        private const val CORE_DATABASE_SNAPSHOT_DIRECTORY = ".glafira-core-snapshot"
        private const val CORE_DATABASE_SNAPSHOT_NAME = "glafira_core_tasks.db"
        private const val CORE_DATABASE_SNAPSHOT_PATH =
            "$CORE_DATABASE_SNAPSHOT_DIRECTORY/$CORE_DATABASE_SNAPSHOT_NAME"
    }
}
