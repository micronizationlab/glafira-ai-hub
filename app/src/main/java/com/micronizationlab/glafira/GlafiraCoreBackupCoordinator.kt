package com.micronizationlab.glafira

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Runtime boundary used by backup maintenance. The production implementation pauses pending delivery and the Core runtime. Only tests that explicitly inject fixture-local hooks bypass those production side effects; tests using the public two-argument constructor still exercise the real pause behavior. */
internal class CommittedRestoreCleanupPendingException(
    val verification: BackupVerification,
    cause: Throwable
) : IOException(
    "Восстановление завершено, но очистка временных данных не завершена; Core остаётся заблокирован до recovery.",
    cause
)

class CommittedRestoreArchiveCleanupException(
    val verification: BackupVerification,
    val archivePath: String,
    cause: Throwable
) : IOException(
    "Восстановление завершено, но временная зашифрованная копия архива не удалена: $archivePath",
    cause
)

internal interface GlafiraBackupRuntimeHooks {
    suspend fun <T> withPaused(block: () -> T): T
    fun pauseForRestore(context: Context)
    fun beforeCommittedJournalCleanup()
    fun beforeCommittedJournalDirectoryDelete() = Unit
    fun beforeSelectedArchiveCleanup(selectedArchive: File) = Unit
    fun beforeDirectorySync(directory: File) = Unit
    fun afterDirectorySync(directory: File) = Unit
}

private fun pathExistsNoFollow(path: Path): Boolean =
    try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        true
    } catch (_: NoSuchFileException) {
        false
    }

private object ProductionGlafiraBackupRuntimeHooks : GlafiraBackupRuntimeHooks {
    override suspend fun <T> withPaused(block: () -> T): T = PendingDeliveryRuntime.withPaused(block)
    override fun pauseForRestore(context: Context) = GlafiraCoreRuntimeOwner.pauseForRestore(context)
    override fun beforeCommittedJournalCleanup() = Unit
}

/** Portable logical Core data inside an authenticated archive; raw SQLite is local rollback only. */
class GlafiraCoreBackupCoordinator internal constructor(
    context: Context,
    private val taskStore: GlafiraTaskStore,
    private val rollbackCipher: GlafiraRollbackSnapshotCipher = GlafiraRollbackSnapshotCipher(),
    private val runtimeHooks: GlafiraBackupRuntimeHooks = ProductionGlafiraBackupRuntimeHooks
) {
    /** Preserve the original public construction API for callers outside this module. */
    constructor(context: Context, taskStore: GlafiraTaskStore) : this(
        context, taskStore, GlafiraRollbackSnapshotCipher(), ProductionGlafiraBackupRuntimeHooks
    )

    private val app = context.applicationContext
    private val portable = GlafiraPortableCoreSnapshot(taskStore)
    private val journal get() = File(app.noBackupFilesDir, JOURNAL_DIRECTORY)
    private val marker get() = File(journal, JOURNAL_MARKER)
    private val committedMarker get() = File(journal, COMMITTED_MARKER)
    private val safetyDatabase get() = File(journal, "database.db")
    private val safetyArchive get() = File(journal, "files.glb")

    suspend fun create(passphrase: CharArray): File = GlafiraCoreMaintenanceGate.withExclusive {
        runtimeHooks.withPaused {
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

    suspend fun recover(passphrase: CharArray): Unit = GlafiraCoreMaintenanceGate.withExclusive {
        runtimeHooks.withPaused {
            GlafiraFileMaintenance.withExclusive { synchronized(taskStore) {
                recoverInterruptedRestore(passphrase)
                Unit
            } }
        }
    }

    suspend fun restore(archive: File, passphrase: CharArray): BackupVerification = GlafiraCoreMaintenanceGate.withExclusive {
        runtimeHooks.withPaused {
            GlafiraFileMaintenance.withExclusive { synchronized(taskStore) {
                var committedThisAttempt = false
                var completedRestoreVerification: BackupVerification? = null
                var primaryRestoreFailure: Exception? = null
                val selected = File(app.cacheDir, "glafira-selected-${UUID.randomUUID()}.glb")
                try {
                    // Preflight is inside the protected try/finally: even a failed recovery
                    // must still attempt plaintext-export and selected-archive cleanup.
                    recoverInterruptedRestore(passphrase)
                    require(archive.isFile && archive.length() in 1..MAX_ARCHIVE_BYTES)
                    archive.inputStream().use { input -> selected.outputStream().use { output ->
                        BackupArchive.copyBounded(input, output)
                    } }
                    val snapshot = BackupArchive.withVerifiedEntry(app, selected, passphrase, GlafiraPortableCoreSnapshot.PATH) { entry, _ ->
                        portable.readVerified(entry).also(portable::checkRestoreSupported)
                    }
                    prepareRollback(passphrase)
                    var completedVerification: BackupVerification? = null
                    var recoveryCleanedSnapshot = false
                    try {
                        val verification = BackupArchive.restore(app, selected, passphrase)
                        completedVerification = verification
                        completedRestoreVerification = verification
                        syncRestoredFiles()
                        portable.restore(snapshot)
                        recoverPendingAfterRestore(app)
                        runtimeHooks.pauseForRestore(app)
                        checkpointDatabase()
                        clearJournal(
                            onCommitted = { committedThisAttempt = true },
                            onSnapshotCleaned = { recoveryCleanedSnapshot = true }
                        )
                        verification
                    } catch (error: Exception) {
                        val commitMarkerProbe = runCatching {
                            pathExistsNoFollow(committedMarker.toPath())
                        }
                        commitMarkerProbe.exceptionOrNull()?.let(error::addSuppressed)
                        var recoveryConfirmedCommit = false
                        val cleanupSucceeded = try {
                            recoverInterruptedRestore(
                                passphrase,
                                onCommitted = { recoveryConfirmedCommit = true },
                                onSnapshotCleaned = { recoveryCleanedSnapshot = true },
                                snapshotAlreadyDurable = recoveryCleanedSnapshot
                            )
                            !pathExistsNoFollow(journal.toPath())
                        } catch (rollback: Exception) {
                            error.addSuppressed(rollback)
                            false
                        }
                        if (!recoveryCleanedSnapshot) {
                            try {
                                deleteCommittedPortableSnapshot {
                                    recoveryCleanedSnapshot = true
                                }
                            } catch (cleanup: Exception) {
                                error.addSuppressed(cleanup)
                            }
                        }
                        val restoreWasCommitted = committedThisAttempt || recoveryConfirmedCommit
                        if (restoreWasCommitted && !cleanupSucceeded) {
                            committedThisAttempt = true
                            throw CommittedRestoreCleanupPendingException(
                                completedVerification ?: throw error,
                                error
                            )
                        }
                        if (restoreWasCommitted) {
                            committedThisAttempt = true
                            completedVerification ?: throw error
                        } else {
                            throw error
                        }
                    }
                } catch (error: Exception) {
                    primaryRestoreFailure = error
                    throw error
                } finally {
                    var selectedArchiveCleanupFailure: Exception? = null
                    var portableSnapshotCleanupFailure: Exception? = null
                    try {
                        runtimeHooks.beforeSelectedArchiveCleanup(selected)
                        if (pathExistsNoFollow(selected.toPath())) {
                            check(selected.delete()) { "Не удалось удалить временную копию выбранного архива." }
                        }
                    } catch (cleanup: Exception) {
                        selectedArchiveCleanupFailure = cleanup
                    }
                    try {
                        cleanupPortableSnapshotPreservingUnexpected()
                    } catch (cleanup: Exception) {
                        portableSnapshotCleanupFailure = cleanup
                    }
                    val primary = primaryRestoreFailure
                    if (primary != null) {
                        selectedArchiveCleanupFailure?.let(primary::addSuppressed)
                        portableSnapshotCleanupFailure?.let(primary::addSuppressed)
                    }
                    if (committedThisAttempt) {
                        val verification = completedRestoreVerification ?: throw (
                            primary ?: portableSnapshotCleanupFailure ?: selectedArchiveCleanupFailure
                                ?: IllegalStateException("Committed restore verification is unavailable.")
                        )
                        if (portableSnapshotCleanupFailure != null) {
                            throw CommittedRestoreCleanupPendingException(
                                verification,
                                primary ?: portableSnapshotCleanupFailure
                            )
                        }
                        if (selectedArchiveCleanupFailure != null) {
                            throw CommittedRestoreArchiveCleanupException(
                                verification,
                                selected.absolutePath,
                                primary ?: selectedArchiveCleanupFailure
                            )
                        }
                    } else if (primary == null) {
                        val first = selectedArchiveCleanupFailure ?: portableSnapshotCleanupFailure
                        if (first != null) {
                            val second = if (first === selectedArchiveCleanupFailure)
                                portableSnapshotCleanupFailure else selectedArchiveCleanupFailure
                            second?.let(first::addSuppressed)
                            throw first
                        }
                    }
                }
            } }
        }
    }

    private fun createArchive(passphrase: CharArray): File {
        val directory = File(app.filesDir, ".glafira-core-snapshot")
        if (pathExistsNoFollow(directory.toPath())) {
            cleanupPortableSnapshotPreservingUnexpected()
            if (pathExistsNoFollow(directory.toPath())) {
                throw IOException("Каталог переносимого снимка содержит данные, не принадлежащие текущему экспорту.")
            }
        }
        check(directory.mkdirs())
        var createdArchive: File? = null
        var primaryFailure: Exception? = null
        try {
            portable.exportTo(File(app.filesDir, GlafiraPortableCoreSnapshot.PATH))
            val archive = BackupArchive.create(app, passphrase)
            createdArchive = archive
            return archive
        } catch (error: Exception) {
            primaryFailure = error
            throw error
        } finally {
            try {
                cleanupPortableSnapshotPreservingUnexpected()
            } catch (cleanup: Exception) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanup)
                } else {
                    val archive = createdArchive
                    if (archive != null) {
                        val archiveRemovalFailure = runCatching {
                            if (pathExistsNoFollow(archive.toPath())) {
                                check(archive.delete()) { "Не удалось удалить временный архив ${archive.absolutePath}." }
                            }
                        }.exceptionOrNull()
                        if (archiveRemovalFailure != null) {
                            cleanup.addSuppressed(archiveRemovalFailure)
                            throw IOException(
                                "Очистка снимка Core не удалась; временный зашифрованный архив остался по пути ${archive.absolutePath}.",
                                cleanup
                            )
                        }
                    }
                    throw cleanup
                }
            }
        }
    }

    internal fun prepareRollback(passphrase: CharArray) {
        check(!marker.exists())
        if (journal.exists()) check(journal.deleteRecursively())
        check(journal.mkdirs())
        try {
            synchronized(taskStore) {
                val consistentDatabase = taskStore.prepareConsistentSnapshotSource()
                sync(consistentDatabase)
                rollbackCipher.encrypt(consistentDatabase, safetyDatabase)
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
            file.name.startsWith("glafira-rollback-recovery-") && file.name.endsWith(".tmp")
        } ?: throw IOException("Не удалось прочитать каталог временных снимков восстановления.")
        stale.forEach { file ->
            val path = file.toPath()
            if (pathExistsNoFollow(path)) {
                try {
                    Files.delete(path)
                } catch (error: IOException) {
                    throw IOException(
                        "Не удалось удалить временный расшифрованный снимок; Core остаётся заблокирован.",
                        error
                    )
                }
            }
        }
    }

    /** Returns true when journal recovery already performed portable-snapshot cleanup. */
    private fun recoverInterruptedRestore(
        passphrase: CharArray,
        onCommitted: () -> Unit = {},
        onSnapshotCleaned: () -> Unit = {},
        snapshotAlreadyDurable: Boolean = false
    ): Boolean {
        if (pathExistsNoFollow(journal.toPath()) && Files.isSymbolicLink(journal.toPath())) {
            throw IOException("Обнаружена символическая ссылка вместо журнала восстановления; чтение и очистка остановлены.")
        }
        if ((pathExistsNoFollow(marker.toPath()) && Files.isSymbolicLink(marker.toPath())) ||
            (pathExistsNoFollow(committedMarker.toPath()) && Files.isSymbolicLink(committedMarker.toPath()))
        ) {
            throw IOException("Обнаружена символическая ссылка вместо маркера восстановления; откатные данные сохранены.")
        }
        cleanupStaleDecryptedRollbackSnapshots()
        BackupArchive.cleanupRestoreReplacementTemps(app)
        if (pathExistsNoFollow(committedMarker.toPath())) {
            // Validate the exact marker before telling callers that restored data is committed.
            validateCommittedMarker()
            // Re-sync this exact journal before deleting rollback evidence, including
            // when recovery runs through a newly constructed coordinator.
            syncDirectory(journal)
            onCommitted()
            cleanupCommittedJournal(
                onSnapshotCleaned = onSnapshotCleaned,
                snapshotAlreadyDurable = snapshotAlreadyDurable
            )
            return true
        }
        if (!pathExistsNoFollow(marker.toPath())) {
            if (pathExistsNoFollow(journal.toPath())) {
                val expectedPreparationArtifacts = setOf(
                    safetyDatabase.name,
                    safetyArchive.name,
                    "${JOURNAL_MARKER}.tmp"
                )
                val entries = Files.newDirectoryStream(journal.toPath()).use { stream ->
                    stream.map { it.fileName.toString() }.toList()
                }
                val unexpected = entries.filterNot { it in expectedPreparationArtifacts }
                if (unexpected.isNotEmpty()) {
                    throw IOException(
                        "В журнале восстановления обнаружены неизвестные данные без маркера; " +
                            "Core остаётся заблокирован для проверки: ${unexpected.joinToString()}."
                    )
                }
                entries.forEach { name ->
                    val artifact = File(journal, name)
                    if (Files.isSymbolicLink(artifact.toPath()) ||
                        !Files.isRegularFile(artifact.toPath(), LinkOption.NOFOLLOW_LINKS)
                    ) {
                        throw IOException("Небезопасный артефакт незавершённой подготовки отката: $name")
                    }
                }
                // No pending marker means live files were never replaced. Remove only the
                // exact artifacts that prepareRollback() is allowed to publish before its marker.
                entries.forEach { name ->
                    if (!Files.deleteIfExists(File(journal, name).toPath())) {
                        throw IOException("Не удалось удалить незавершённый артефакт отката: $name")
                    }
                }
                rollbackCipher.deleteKey()
                if (!Files.deleteIfExists(journal.toPath())) {
                    throw IOException("Не удалось удалить пустой журнал незавершённой подготовки отката.")
                }
                syncDirectory(app.noBackupFilesDir)
            }
            cleanupPortableSnapshotPreservingUnexpected()
            if (pathExistsNoFollow(File(app.filesDir, ".glafira-core-snapshot").toPath())) {
                throw IOException("В каталоге переносимого снимка Core остались данные; Core остаётся заблокирован для проверки.")
            }
            return false
        }
        require(
            Files.isRegularFile(safetyArchive.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                Files.isRegularFile(safetyDatabase.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) { "Повреждён журнал восстановления; Core заблокирован." }
        val markerVersion = marker.readText(Charsets.UTF_8).trim()
        val encryptedRollback = markerVersion == "GLAFIRA_RESTORE_ROLLBACK_V3"
        require(encryptedRollback || markerVersion == "GLAFIRA_RESTORE_ROLLBACK_V2") {
            "Неподдерживаемая версия журнала восстановления; Core заблокирован."
        }
        val decryptedDatabase = if (encryptedRollback) {
            File(app.cacheDir, "glafira-rollback-recovery-${UUID.randomUUID()}.tmp")
        } else null
        val rollbackDatabase = decryptedDatabase ?: safetyDatabase
        try {
            // Remove only the known portable plaintext export before applying the safety archive.
            // Unknown staging siblings must be preserved and keep recovery blocked; BackupArchive
            // restores an exact file set and would otherwise delete post-marker operator data.
            cleanupPortableSnapshotPreservingUnexpected()
            if (pathExistsNoFollow(File(app.filesDir, ".glafira-core-snapshot").toPath())) {
                throw IOException("В каталоге переносимого снимка обнаружены неизвестные данные; safety restore остановлен.")
            }
            // Decrypt and validate the database before applying the safety archive to live files.
            // A missing Keystore key must fail closed without regenerating a key or partially restoring files.
            if (encryptedRollback) rollbackCipher.decrypt(safetyDatabase, rollbackDatabase)
            taskStore.verifyConsistentSnapshot(rollbackDatabase)
            BackupArchive.verify(app, safetyArchive, passphrase)
            BackupArchive.restore(app, safetyArchive, passphrase)
            // The rollback archive's files must be durable before SQLite is checkpointed
            // and the committed marker makes this recovery irreversible.
            syncRestoredFiles()
            taskStore.restoreConsistentSnapshot(rollbackDatabase)
            runtimeHooks.pauseForRestore(app)
            checkpointDatabase()
            clearJournal(onSnapshotCleaned = onSnapshotCleaned)
            return true
        } catch (error: Exception) {
            throw IllegalStateException("Не удалось проверить или восстановить safety snapshot. Core остаётся заблокирован до успешного отката.", error)
        } finally {
            decryptedDatabase?.delete()
        }
    }

    /**
     * Commit is made durable before destructive cleanup starts. If cleanup is interrupted,
     * recover() sees COMMITTED_MARKER and resumes cleanup instead of attempting an obsolete rollback.
     * The pending marker and rollback artifacts remain intact until the commit marker is durable.
     */
    private fun clearJournal(
        onCommitted: () -> Unit = {},
        onSnapshotCleaned: () -> Unit = {}
    ) {
        publishCommittedMarker(onCommitted)
        cleanupCommittedJournal(onSnapshotCleaned)
    }

    private fun publishCommittedMarker(onCommitted: () -> Unit = {}) {
        if (pathExistsNoFollow(committedMarker.toPath())) {
            validateCommittedMarker()
            syncDirectory(journal)
            onCommitted()
            return
        }
        validatePendingMarker()
        val temporary = File(journal, "$COMMITTED_MARKER.tmp")
        FileOutputStream(temporary).use {
            it.write(COMMITTED_VALUE.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        check(temporary.renameTo(committedMarker)) {
            "Не удалось зафиксировать завершение восстановления; журнал сохранён."
        }
        syncDirectory(journal)
        onCommitted()
    }

    /** Idempotent after the durable commit marker; never discards that marker on partial cleanup. */
    private fun validatePendingMarker() {
        if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Отметка начала восстановления отсутствует или небезопасна; commit запрещён.")
        }
        if (marker.readText(Charsets.UTF_8).trim() !in setOf(
                "GLAFIRA_RESTORE_ROLLBACK_V2",
                "GLAFIRA_RESTORE_ROLLBACK_V3"
            )
        ) {
            throw IOException("Некорректная отметка начала восстановления; commit запрещён.")
        }
    }

    private fun validateCommittedMarker() {
        if (!Files.isRegularFile(committedMarker.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Отметка завершения восстановления отсутствует или небезопасна; журнал сохранён.")
        }
        if (committedMarker.readText(Charsets.UTF_8) != COMMITTED_VALUE) {
            throw IOException("Некорректная отметка завершения восстановления; журнал сохранён.")
        }
    }

    private fun cleanupCommittedJournal(
        onSnapshotCleaned: () -> Unit = {},
        snapshotAlreadyDurable: Boolean = false
    ) {
        validateCommittedMarker()
        runtimeHooks.beforeCommittedJournalCleanup()
        // Portable Core exports contain decrypted task/result data. Remove a crash-left
        // export before deleting the rollback evidence that makes recovery safe.
        val portableSnapshotDirectory = File(app.filesDir, ".glafira-core-snapshot")
        if (!snapshotAlreadyDurable || pathExistsNoFollow(portableSnapshotDirectory.toPath())) {
            deleteCommittedPortableSnapshot(onSnapshotCleaned)
        }
        check(!safetyArchive.exists() || safetyArchive.delete()) {
            "Не удалось удалить архив отката; журнал завершения сохранён."
        }
        check(!safetyDatabase.exists() || safetyDatabase.delete()) {
            "Не удалось удалить снимок отката; журнал завершения сохранён."
        }
        syncDirectory(journal)
        // The rollback key is no longer needed because the restored DB was checkpointed before
        // the durable commit marker was published. A key-delete failure leaves COMMITTED_MARKER.
        rollbackCipher.deleteKey()
        check(!marker.exists() || marker.delete()) {
            "Не удалось удалить маркер восстановления; маркер завершения сохранён."
        }
        syncDirectory(journal)
        val committedPath = committedMarker.toPath()
        val hasUnexpectedJournalEntries = Files.newDirectoryStream(journal.toPath()).use { entries ->
            entries.any { it != committedPath }
        }
        if (hasUnexpectedJournalEntries) {
            throw IOException("В журнале восстановления остались неизвестные данные; маркер завершения сохранён.")
        }
        check(Files.deleteIfExists(committedPath)) { "Не удалось удалить маркер завершения восстановления." }
        try {
            syncDirectory(journal)
            // Non-recursive deletion refuses to remove any unexpected entry. If either the
            // directory removal or parent fsync fails, recreate the durable commit marker.
            runtimeHooks.beforeCommittedJournalDirectoryDelete()
            Files.delete(journal.toPath())
            syncDirectory(app.noBackupFilesDir)
        } catch (cleanupFailure: Exception) {
            try {
                if (!pathExistsNoFollow(journal.toPath())) {
                    check(journal.mkdirs()) { "Не удалось восстановить каталог журнала завершения." }
                }
                publishCommittedMarker()
            } catch (markerRestoreFailure: Exception) {
                cleanupFailure.addSuppressed(markerRestoreFailure)
            }
            throw cleanupFailure
        }
    }

    /**
     * Best-effort restore-finally cleanup. Delete only the known regular export and remove
     * the directory only when empty; never follow links or recursively delete unknown data.
     */
    private fun cleanupPortableSnapshotPreservingUnexpected() {
        val directory = File(app.filesDir, ".glafira-core-snapshot").toPath().toAbsolutePath().normalize()
        val export = File(app.filesDir, GlafiraPortableCoreSnapshot.PATH).toPath().toAbsolutePath().normalize()
        if (export.parent != directory) throw IOException("Некорректный путь переносимого снимка Core.")
        if (!pathExistsNoFollow(directory)) return
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Каталог переносимого снимка Core заменён ссылкой или файлом.")
        }
        if (pathExistsNoFollow(export)) {
            if (Files.isSymbolicLink(export) || !Files.isRegularFile(export, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Файл переносимого снимка Core заменён ссылкой или каталогом.")
            }
            Files.delete(export)
            syncDirectory(directory.toFile())
        }
        val empty = Files.newDirectoryStream(directory).use { entries -> !entries.iterator().hasNext() }
        if (empty) {
            Files.delete(directory)
            syncDirectory(app.filesDir)
        }
    }

    /** Remove the known plaintext export first, then reject unexpected siblings without deleting them. */
    private fun deleteCommittedPortableSnapshot(onSnapshotCleaned: () -> Unit = {}) {
        val directory = File(app.filesDir, ".glafira-core-snapshot").toPath().toAbsolutePath().normalize()
        val export = File(app.filesDir, GlafiraPortableCoreSnapshot.PATH).toPath().toAbsolutePath().normalize()
        if (export.parent != directory) throw IOException("Некорректный путь переносимого снимка Core.")
        if (!pathExistsNoFollow(directory)) {
            syncDirectory(app.filesDir)
            onSnapshotCleaned()
            return
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Каталог переносимого снимка Core заменён ссылкой или файлом.")
        }
        val exportExists = pathExistsNoFollow(export)
        if (exportExists && (Files.isSymbolicLink(export) || !Files.isRegularFile(export, LinkOption.NOFOLLOW_LINKS))) {
            throw IOException("Файл переносимого снимка Core заменён ссылкой или каталогом.")
        }
        if (exportExists) {
            Files.delete(export)
            syncDirectory(directory.toFile())
        }
        val empty = Files.newDirectoryStream(directory).use { entries -> !entries.iterator().hasNext() }
        if (!empty) {
            throw IOException("В каталоге переносимого снимка Core обнаружены посторонние данные.")
        }
        Files.delete(directory)
        // Report cleanup only after the parent directory sync makes the deletion durable.
        syncDirectory(app.filesDir)
        onSnapshotCleaned()
    }

    /** Make restored payload and directory entries durable before publishing COMMITTED_MARKER. */
    private fun syncRestoredFiles() {
        // Safe preferences are restored under dataDir/shared_prefs, not filesDir.
        // Their contents and directory entries must be durable before COMMITTED is published.
        val roots = listOf(
            app.filesDir,
            File(app.applicationInfo.dataDir, "shared_prefs")
        ).filter { it.exists() && it.isDirectory }
        roots.forEach { root ->
            root.walkTopDown().filter { it.isFile }.toList().forEach(::sync)
            root.walkTopDown().filter { it.isDirectory }.toList().asReversed().forEach(::syncDirectory)
        }
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
        runtimeHooks.beforeDirectorySync(directory)
        val fd = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
        runtimeHooks.afterDirectorySync(directory)
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L
        private const val JOURNAL_DIRECTORY = "glafira-restore-journal"
        private const val JOURNAL_MARKER = "restore.pending"
        private const val COMMITTED_MARKER = "restore.committed"
        private const val COMMITTED_VALUE = "GLAFIRA_RESTORE_COMMITTED_V1"
        // Marker durability is re-confirmed by fsyncing its exact journal directory
        // whenever a committed marker is observed, including from a fresh coordinator.

        fun hasInterruptedRestore(context: Context): Boolean {
            val app = context.applicationContext
            return runCatching {
                val journal = File(app.noBackupFilesDir, JOURNAL_DIRECTORY)
                if (pathExistsNoFollow(journal.toPath()) && Files.isSymbolicLink(journal.toPath())) {
                    return@runCatching true
                }
                val marker = File(journal, JOURNAL_MARKER)
                val committed = File(journal, COMMITTED_MARKER)
                if (pathExistsNoFollow(marker.toPath()) || pathExistsNoFollow(committed.toPath())) {
                    return@runCatching true
                }
                if (pathExistsNoFollow(journal.toPath())) {
                    val unexpectedEntries = Files.newDirectoryStream(journal.toPath()).use { entries ->
                        entries.any()
                    }
                    if (unexpectedEntries) return@runCatching true
                }
                val stale = app.cacheDir.listFiles { file ->
                    file.name.startsWith("glafira-rollback-recovery-") && file.name.endsWith(".tmp")
                } ?: throw IOException("Не удалось проверить каталог временных снимков восстановления.")
                val portableSnapshotDirectory = File(app.filesDir, ".glafira-core-snapshot")
                stale.any { pathExistsNoFollow(it.toPath()) } ||
                    BackupArchive.hasRestoreReplacementTemps(app) ||
                    pathExistsNoFollow(portableSnapshotDirectory.toPath())
            }.getOrDefault(true)
        }
    }
}

/** Reuse the runtime's store, so maintenance and ordinary queue operations share the same monitor. */
internal suspend fun <T> withCoreBackupCoordinator(context: Context, block: suspend (GlafiraCoreBackupCoordinator) -> T): T =
    block(GlafiraCoreBackupCoordinator(context, GlafiraCoreRuntimeOwner.get(context).taskStore))
