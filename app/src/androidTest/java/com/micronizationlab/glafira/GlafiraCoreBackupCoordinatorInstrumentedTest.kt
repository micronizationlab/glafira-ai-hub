package com.micronizationlab.glafira
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.system.Os
import android.system.OsConstants
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Path
import java.security.KeyStore
import java.util.UUID
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private lateinit var context: Context
    private lateinit var testRoot: File
    private lateinit var store: GlafiraTaskStore
    private lateinit var rollbackCipher: GlafiraRollbackSnapshotCipher
    private var ownsFixture = false
    private var createdFixtureRoot = false
    private var createdFixtureStagingRoot = false
    private lateinit var fixtureStagingRoot: File
    private lateinit var testRunId: String
    private var fixtureLifetimeFile: RandomAccessFile? = null
    private var fixtureLifetimeFileLock: java.nio.channels.FileLock? = null
    private inner class FixtureBackupRuntimeHooks : GlafiraBackupRuntimeHooks {
        var failCommittedCleanupOnce = false
        var failCommittedCleanupCount = 0
        var pauseCount = 0
        var failPauseForRestoreCount = 0
        var failPlaintextSnapshotDirectorySyncOnce = false
        var plaintextSnapshotDirectorySyncCalls = 0
        var plaintextSnapshotDirectorySyncCompletedCalls = 0
        var failCommittedMarkerSyncCount = 0
        var committedMarkerSyncCalls = 0
        var committedMarkerSyncCompletedCalls = 0
        var committedCleanupCalls = 0
        override suspend fun <T> withPaused(block: () -> T): T {
            pauseCount++
            return block()
        }
        val pauseContexts = mutableListOf<Context>()
        override fun pauseForRestore(context: Context) {
            pauseContexts += context
            if (failPauseForRestoreCount > 0) {
                failPauseForRestoreCount--
                throw IOException("injected pause failure after restore")
            }
        }
        override fun beforeCommittedJournalCleanup() {
            committedCleanupCalls++
            if (failCommittedCleanupOnce || failCommittedCleanupCount > 0) {
                failCommittedCleanupOnce = false
                if (failCommittedCleanupCount > 0) failCommittedCleanupCount--
                throw IOException("injected interruption before committed journal cleanup")
            }
        }
        override fun beforeDirectorySync(directory: File) {
            val journalDirectory = File(context.noBackupFilesDir, "glafira-restore-journal").absoluteFile
            if (directory.absoluteFile == journalDirectory &&
                Files.isRegularFile(File(journalDirectory, "restore.committed").toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                committedMarkerSyncCalls++
                if (failCommittedMarkerSyncCount > 0) {
                    failCommittedMarkerSyncCount--
                    throw IOException("injected committed-marker directory fsync failure")
                }
            }
            if (directory.absoluteFile == context.filesDir.absoluteFile) {
                plaintextSnapshotDirectorySyncCalls++
                if (failPlaintextSnapshotDirectorySyncOnce) {
                    failPlaintextSnapshotDirectorySyncOnce = false
                    throw IOException("injected plaintext snapshot parent-directory fsync failure")
                }
            }
        }
        override fun afterDirectorySync(directory: File) {
            val journalDirectory = File(context.noBackupFilesDir, "glafira-restore-journal").absoluteFile
            if (directory.absoluteFile == journalDirectory &&
                Files.isRegularFile(File(journalDirectory, "restore.committed").toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                committedMarkerSyncCompletedCalls++
            }
            if (directory.absoluteFile == context.filesDir.absoluteFile) {
                plaintextSnapshotDirectorySyncCompletedCalls++
            }
        }
    }
    private val fixtureRuntimeHooks = FixtureBackupRuntimeHooks()

    @Before
    fun setUp() = runBlocking {
        acquireFixtureLifetimeLock()
        try {
            // Keep the lifetime lock from before orphan reclamation through teardown so
            // another test setup cannot reclaim a fixture that is still in use.
            cleanupOwnedLeftoverRollbackFixture()
            testRunId = UUID.randomUUID().toString()
            fixtureRuntimeHooks.pauseContexts.clear()
            fixtureRuntimeHooks.pauseCount = 0
            fixtureRuntimeHooks.failPauseForRestoreCount = 0
            fixtureRuntimeHooks.failCommittedMarkerSyncCount = 0
            fixtureRuntimeHooks.committedMarkerSyncCalls = 0
            fixtureRuntimeHooks.committedMarkerSyncCompletedCalls = 0
            fixtureRuntimeHooks.committedCleanupCalls = 0
            fixtureRuntimeHooks.failPlaintextSnapshotDirectorySyncOnce = false
            val aliasSuffix = testRunId.replace("-", "")
            rollbackCipher = GlafiraRollbackSnapshotCipher("glafira_test_rollback_$aliasSuffix")
            check(rollbackCipher.keyAlias != GlafiraRollbackSnapshotCipher.PRODUCTION_KEY_ALIAS)
            fixtureStagingRoot = File(appContext.cacheDir, ".glafira-backup-test-staging-$testRunId")
            check(fixtureStagingRoot.mkdir()) { "Could not exclusively create backup fixture staging root." }
            createdFixtureStagingRoot = true
            writeFixtureRootOwnerMarker(testRunId, fixtureStagingRoot, rollbackCipher.keyAlias)
            testRoot = File(appContext.cacheDir, "glafira-backup-test-$testRunId")
            check(!testRoot.exists() && !Files.isSymbolicLink(testRoot.toPath())) {
                "Refusing to replace an existing backup fixture root."
            }
            check(fixtureStagingRoot.renameTo(testRoot)) { "Could not atomically publish the owned backup fixture directory." }
            createdFixtureStagingRoot = false
            createdFixtureRoot = true
            syncMarkerDirectory(appContext.cacheDir)
            ownsFixture = true
            context = IsolatedBackupTestContext(appContext, testRoot)
            registerFixtureKeyAlias(rollbackCipher.keyAlias, testRunId)
            store = GlafiraTaskStore(context)
            assertEquals(File(testRoot, "databases/glafira_core_tasks.db").absolutePath, store.writableDatabase.path)
        } catch (error: Throwable) {
            // Once a fixture is claimed, @After owns lock release and cleanup even if
            // store construction fails. Before ownership publication, only remove the
            // directory this setup just created exclusively; never infer ownership later.
            if (!ownsFixture) {
                try {
                    if (createdFixtureStagingRoot && ::fixtureStagingRoot.isInitialized) {
                        cleanupUnclaimedFixtureRoot(fixtureStagingRoot, true, if (::testRunId.isInitialized) testRunId else null)
                    }
                    cleanupUnclaimedFixtureRoot(
                        root = if (::testRoot.isInitialized) testRoot else null,
                        createdByThisSetup = createdFixtureRoot,
                        runId = if (::testRunId.isInitialized) testRunId else null
                    )
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                } finally {
                    releaseFixtureLifetimeLock()
                }
            }
            throw error
        }
    }
    @After
    fun tearDown(): Unit = runBlocking<Unit> {
        try {
            if (!ownsFixture) return@runBlocking
            var failure: Throwable? = null
            try {
                if (::store.isInitialized) store.close()
            } catch (error: Throwable) {
                failure = error
            }
            try {
                if (::testRoot.isInitialized && (testRoot.exists() || Files.isSymbolicLink(testRoot.toPath()))) {
                    check(!Files.isSymbolicLink(testRoot.toPath())) {
                        "Preserving fixture root because it was replaced by a symbolic link."
                    }
                    val installationMarker = rollbackOwnerMarker()
                    val fixtureJournal = File(testRoot, "no-backup/glafira-restore-journal")
                    check(!installationMarker.exists() && !fixtureJournal.exists()) {
                        "Preserving fixture root because rollback ownership/recovery evidence remains."
                    }
                    checkFixtureRootOwnership(testRunId, testRoot)
                    rollbackCipher.deleteKey()
                    deleteTreeWithoutFollowingLinks(testRoot.toPath())
                    syncMarkerDirectory(checkNotNull(testRoot.parentFile))
                    clearFixtureKeyAliasOwner(rollbackCipher.keyAlias, testRunId)
                }
            } catch (cleanup: Throwable) {
                if (failure != null) failure!!.addSuppressed(cleanup) else failure = cleanup
            }
            if (failure != null) throw failure!!
        } finally {
            releaseFixtureLifetimeLock()
        }
    }
    private fun acquireFixtureLifetimeLock() {
        FIXTURE_LIFETIME_PROCESS_LOCK.lock()
        try {
            val lockFile = File(appContext.noBackupFilesDir, "glafira-backup-fixture-lifetime.lock")
            fixtureLifetimeFile = RandomAccessFile(lockFile, "rw")
            fixtureLifetimeFileLock = fixtureLifetimeFile!!.channel.lock()
            check(fixtureLifetimeFileLock!!.isValid) { "Could not hold backup fixture lifetime lock." }
        } catch (error: Throwable) {
            try {
                fixtureLifetimeFile?.close()
            } finally {
                fixtureLifetimeFile = null
                FIXTURE_LIFETIME_PROCESS_LOCK.unlock()
            }
            throw error
        }
    }

    private fun releaseFixtureLifetimeLock() {
        try {
            fixtureLifetimeFileLock?.release()
        } finally {
            fixtureLifetimeFileLock = null
            try {
                fixtureLifetimeFile?.close()
            } finally {
                fixtureLifetimeFile = null
                if (FIXTURE_LIFETIME_PROCESS_LOCK.isHeldByCurrentThread) {
                    FIXTURE_LIFETIME_PROCESS_LOCK.unlock()
                }
            }
        }
    }

    private fun isolatedCoordinator() =
        GlafiraCoreBackupCoordinator(context, store, rollbackCipher, fixtureRuntimeHooks)

    /**
     * Production restore pauses the real Core scheduler. This fixture injects a recorder/no-op
     * runtime hook and must not cancel app-owned work. Skip while any known unique Core queue
     * work is unfinished so the isolated fixture cannot race the application's active work.
     */
    private fun assertNoActiveCoreWork() {
        val workManager = androidx.work.WorkManager.getInstance(appContext)
        listOf("glafira-core-queue-drain", "glafira-core-queue-retry", "glafira-core-queue-network")
            .forEach { uniqueName ->
                val active = workManager.getWorkInfosForUniqueWork(uniqueName).get()
                    .filterNot { it.state.isFinished }
                org.junit.Assume.assumeTrue(
                    "Skip isolated backup test while app-owned Core work is active: $uniqueName",
                    active.isEmpty()
                )
            }
    }

    private class IsolatedBackupTestContext(base: Context, private val root: File) : ContextWrapper(base) {
        private val databases = File(root, "databases").apply { mkdirs() }
        private val files = File(root, "files").apply { mkdirs() }; private val noBackup = File(root, "no-backup").apply { mkdirs() }; private val cache = File(root, "cache").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(databases, name)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?) =
            android.database.sqlite.SQLiteDatabase.openDatabase(
                getDatabasePath(name).absolutePath, factory, android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler
            )
        override fun deleteDatabase(name: String): Boolean = getDatabasePath(name).delete()
        override fun databaseList(): Array<String> = databases.list() ?: emptyArray()
        override fun getFilesDir(): File = files; override fun getNoBackupFilesDir(): File = noBackup; override fun getCacheDir(): File = cache
        private val preferences = java.util.concurrent.ConcurrentHashMap<String, SharedPreferences>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            preferences.computeIfAbsent(name) { InMemoryBackupPreferences() }
    }

    private class InMemoryBackupPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any>()
        override fun getAll(): MutableMap<String, *> = synchronized(values) { values.toMutableMap() }
        override fun getString(key: String, defValue: String?): String? = synchronized(values) { values[key] as? String ?: defValue }
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            synchronized(values) { (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues?.toSet() }
        override fun getInt(key: String, defValue: Int): Int = synchronized(values) { values[key] as? Int ?: defValue }
        override fun getLong(key: String, defValue: Long): Long = synchronized(values) { values[key] as? Long ?: defValue }
        override fun getFloat(key: String, defValue: Float): Float = synchronized(values) { values[key] as? Float ?: defValue }
        override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(values) { values[key] as? Boolean ?: defValue }
        override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }
        override fun edit(): SharedPreferences.Editor = MemoryEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        private inner class MemoryEditor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private var clearAll = false
            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { updates[key] = value }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { updates[key] = values?.toSet() }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { updates[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { updates[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { updates[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { updates[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { updates[key] = null }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                synchronized(values) {
                    if (clearAll) values.clear()
                    updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                }
                return true
            }
            override fun apply() { commit() }
        }
    }
    private suspend fun <T> withFixtureLocks(block: () -> T): T = withTimeout(MAINTENANCE_TIMEOUT_MILLIS) {
        GlafiraCoreMaintenanceGate.withExclusive {
            block()
        }
    }
    /**
     * Read persisted enablement without constructing GlafiraCoreRuntimeOwner: its constructor
     * migrates preferences and creates/updates installation databases, which this fixture does not own.
     */
    private fun appCoreRuntimeEnabledWithoutInitialization(): Boolean {
        val database = appContext.getDatabasePath("glafira_core_runtime_config.db")
        if (database.isFile) {
            try {
                val enabled = android.database.sqlite.SQLiteDatabase.openDatabase(
                    database.absolutePath,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                ).use { db ->
                    db.rawQuery(
                        "SELECT config_version, enabled FROM runtime_config WHERE singleton_id = 1",
                        null
                    ).use { cursor ->
                        if (!cursor.moveToFirst()) null
                        else if (cursor.getInt(0) != 1) true
                        else cursor.getInt(1) == 1
                    }
                }
                if (enabled != null) return enabled
            } catch (_: Exception) {
                return true // Unknown persistent state is treated as enabled; setup must skip.
            }
        }
        val legacyPreferencesFile = File(
            appContext.applicationInfo.dataDir,
            "shared_prefs/glafira_core_runtime.xml"
        )
        if (!legacyPreferencesFile.isFile) return false
        return appContext.getSharedPreferences("glafira_core_runtime", Context.MODE_PRIVATE)
            .getBoolean("runtime_enabled", false)
    }

    private fun rollbackOwnerMarker(): File =
        File(context.noBackupFilesDir, "glafira-backup-instrumentation-owner")

    private fun hasAppOwnedRollbackArtifactsReadOnly(): Boolean {
        val journal = File(appContext.noBackupFilesDir, "glafira-restore-journal")
        if (journal.exists()) return true
        return appContext.cacheDir.listFiles { file ->
            file.name.startsWith("glafira-rollback-recovery-") && file.name.endsWith(".tmp")
        }.orEmpty().any { it.exists() }
    }


    private fun syncMarkerDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private fun writeRollbackOwnerMarker(runId: String, beforePublish: () -> Unit = {}) {
        val marker = rollbackOwnerMarker()
        val parent = checkNotNull(marker.parentFile)
        check(!Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "A previous test-owned rollback operation must be recovered first."
        }
        val temporary = File(parent, ".${marker.name}.$runId.tmp")
        check(temporary.createNewFile()) { "Could not exclusively create temporary rollback owner marker." }
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(runId.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            // Fault injection occurs only after the complete temporary marker is synced.
            beforePublish()
            Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncMarkerDirectory(parent)
        } catch (error: Throwable) {
            if (temporary.exists()) {
                try {
                    check(temporary.delete()) { "Could not remove failed temporary owner marker." }
                    syncMarkerDirectory(parent)
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
    }

    private fun clearRollbackOwnerMarker(runId: String) {
        val marker = rollbackOwnerMarker()
        if (marker.exists()) {
            check(marker.readText(Charsets.UTF_8) == runId) { "Refusing to clear another rollback generation." }
            check(marker.delete()) { "Could not clear test rollback owner marker." }
            syncMarkerDirectory(checkNotNull(marker.parentFile))
        }
    }

    private fun cleanupUnclaimedFixtureRoot(root: File?, createdByThisSetup: Boolean, runId: String?) {
        // The caller must prove it won the exclusive mkdir or staging-directory creation.
        // A fixture-looking path or absent marker is never ownership evidence.
        if (!createdByThisSetup || root == null || runId == null) return
        check(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
        deleteTreeWithoutFollowingLinks(root.toPath())
        if (::rollbackCipher.isInitialized) {
            val alias = rollbackCipher.keyAlias
            val owner = fixtureKeyOwnerMarker(alias, runId)
            val ownedAlias = owner.isFile && !Files.isSymbolicLink(owner.toPath()) &&
                owner.readText(Charsets.UTF_8) == alias
            if (ownedAlias && rollbackCipher.keyExists()) rollbackCipher.deleteKey()
            if (ownedAlias) clearFixtureKeyAliasOwner(alias, runId)
        }
        syncMarkerDirectory(appContext.cacheDir)
    }

    private fun fixtureRootOwnerMarker(root: File): File = File(root, "fixture.owner")

    private fun writeFixtureRootOwnerMarker(runId: String, root: File, alias: String) {
        val marker = fixtureRootOwnerMarker(root)
        check(!Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS))
        val temporary = File(root, ".fixture.owner.$runId.tmp")
        check(temporary.createNewFile()) { "Fixture root ownership temporary marker already exists." }
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write("$runId\n$alias".toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncMarkerDirectory(root)
        } catch (error: Throwable) {
            if (temporary.exists()) temporary.delete()
            throw error
        }
    }

    private fun checkFixtureRootOwnership(runId: String, root: File) {
        val marker = fixtureRootOwnerMarker(root)
        val expectedAlias = "glafira_test_rollback_" + runId.replace("-", "")
        check(marker.isFile && !Files.isSymbolicLink(marker.toPath()) &&
            marker.readText(Charsets.UTF_8) == "$runId\n$expectedAlias"
        ) {
            "Refusing to reclaim a fixture root without its pre-published ownership record."
        }
        check(root.parentFile?.canonicalFile == appContext.cacheDir.canonicalFile) {
            "Fixture root escaped the application cache directory."
        }
        check(root.name == "glafira-backup-test-$runId") {
            "Fixture root name does not match its canonical run UUID."
        }
    }

    private fun deleteTreeWithoutFollowingLinks(root: Path) {
        check(!Files.isSymbolicLink(root)) { "Refusing to recursively delete a symbolic-link fixture root." }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun fixtureKeyOwnerMarker(alias: String, runId: String): File {
        val expected = "glafira_test_rollback_" + runId.replace("-", "")
        val kind = when (alias) {
            expected -> "primary"
            expected + "_second" -> "second"
            expected + "_third" -> "third"
            else -> error("Alias is not owned by this fixture run.")
        }
        return File(appContext.noBackupFilesDir, "glafira-test-key-owner-$runId-$kind")
    }

    private fun registerFixtureKeyAlias(alias: String, runId: String) {
        val expected = "glafira_test_rollback_" + runId.replace("-", "")
        require(alias == expected || alias == expected + "_second" || alias == expected + "_third") {
            "Only this run's uniquely scoped fixture aliases may be registered."
        }
        val marker = fixtureKeyOwnerMarker(alias, runId)
        check(!marker.exists()) { "Fixture key ownership marker already exists." }
        val temporary = File(
            checkNotNull(marker.parentFile),
            ".${marker.name}.${UUID.randomUUID()}.tmp"
        )
        try {
            FileOutputStream(temporary, false).use {
                it.write(alias.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            check(temporary.renameTo(marker)) { "Could not atomically publish fixture key ownership." }
            // If this fsync fails, retain the complete published marker: the alias is unique
            // to this run and a later setup can safely reclaim it, even after process death.
            syncMarkerDirectory(checkNotNull(marker.parentFile))
        } catch (error: Throwable) {
            if (temporary.exists()) temporary.delete()
            throw error
        }
    }

    private fun clearFixtureKeyAliasOwner(alias: String, runId: String) {
        val marker = fixtureKeyOwnerMarker(alias, runId)
        if (marker.exists()) {
            check(marker.readText(Charsets.UTF_8) == alias) {
                "Refusing to clear a fixture-key owner marker for another alias."
            }
            check(marker.delete()) { "Could not remove fixture-key owner marker." }
            syncMarkerDirectory(checkNotNull(marker.parentFile))
        }
    }

    // Reclaim only aliases whose durable owner marker proves this test created them.
    // If a matching fixture journal remains, preserve its key and recovery evidence.
    private fun cleanupOwnedLeftoverRollbackFixture() {
        check(fixtureLifetimeFileLock?.isValid == true) {
            "Orphan fixture reclamation requires the lifetime lock held from setup through teardown."
        }
        val ownerDirectory = appContext.noBackupFilesDir
        val temporaryMarkerPattern = Regex(
            """\.glafira-test-key-owner-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-(primary|second|third)\.([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.tmp"""
        )
        val abandonedTemporaryMarkers = ownerDirectory.listFiles { file ->
            temporaryMarkerPattern.matches(file.name) &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        }.orEmpty()
        abandonedTemporaryMarkers.forEach { temporary ->
            check(!temporary.exists() || temporary.delete()) {
                "Could not reclaim interrupted fixture alias registration."
            }
        }
        if (abandonedTemporaryMarkers.isNotEmpty()) syncMarkerDirectory(ownerDirectory)
        ownerDirectory.listFiles { file ->
            file.name.startsWith("glafira-test-key-owner-")
        }.orEmpty().forEach { marker ->
            if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
            val markerName = marker.name.removePrefix("glafira-test-key-owner-")
            val kind = when {
                markerName.endsWith("-second") -> "second"
                markerName.endsWith("-third") -> "third"
                markerName.endsWith("-primary") -> "primary"
                else -> return@forEach
            }
            val runId = markerName.removeSuffix("-" + kind)
            val parsed = try { UUID.fromString(runId) } catch (_: IllegalArgumentException) { null }
            if (parsed == null || parsed.toString() != runId) return@forEach
            val expected = "glafira_test_rollback_" + runId.replace("-", "")
            val alias = marker.readText(Charsets.UTF_8)
            val expectedAlias = when (kind) {
                "second" -> expected + "_second"
                "third" -> expected + "_third"
                else -> expected
            }
            if (alias != expectedAlias) return@forEach
            // The lifetime lock protects the scan from other test processes, but this
            // process's current fixture is still live and must never be reclaimed.
            if (::testRunId.isInitialized && runId == testRunId) return@forEach
            val root = File(appContext.cacheDir, "glafira-backup-test-$runId")
            if (Files.isSymbolicLink(root.toPath())) return@forEach
            if (root.exists()) {
                if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
                val rootOwner = fixtureRootOwnerMarker(root)
                // The root owner always records the primary alias, even while
                // secondary alias markers are being reclaimed.
                if (!rootOwner.isFile || Files.isSymbolicLink(rootOwner.toPath()) ||
                    rootOwner.readText(Charsets.UTF_8) != "$runId\n$expected"
                ) return@forEach
            }
            val journal = File(root, "no-backup/glafira-restore-journal")
            if (journal.exists()) return@forEach
            GlafiraRollbackSnapshotCipher(alias).deleteKey()
            if (root.exists()) {
                deleteTreeWithoutFollowingLinks(root.toPath())
                syncMarkerDirectory(appContext.cacheDir)
            }
            check(marker.delete()) { "Could not remove stale fixture-key owner marker." }
            syncMarkerDirectory(ownerDirectory)
        }
        // Reclaim staging/final fixture roots whose process died before publishing the
        // separate Keystore-alias marker. Their owner record was fsynced before atomic rename.
        val orphanRoots = appContext.cacheDir.listFiles { file ->
            file.name.startsWith(".glafira-backup-test-staging-") ||
                file.name.startsWith("glafira-backup-test-")
        }.orEmpty()
        orphanRoots.forEach { root ->
            if (Files.isSymbolicLink(root.toPath()) ||
                !Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) return@forEach
            val prefix = if (root.name.startsWith(".glafira-backup-test-staging-"))
                ".glafira-backup-test-staging-" else "glafira-backup-test-"
            val runId = root.name.removePrefix(prefix)
            val parsed = try { UUID.fromString(runId) } catch (_: IllegalArgumentException) { null }
            if (parsed == null || parsed.toString() != runId) return@forEach
            if (::testRunId.isInitialized && runId == testRunId) return@forEach
            val owner = fixtureRootOwnerMarker(root)
            val alias = "glafira_test_rollback_" + runId.replace("-", "")
            if (!owner.isFile || Files.isSymbolicLink(owner.toPath()) ||
                owner.readText(Charsets.UTF_8) != "$runId\n$alias"
            ) return@forEach
            if (File(root, "no-backup/glafira-restore-journal").exists()) return@forEach
            GlafiraRollbackSnapshotCipher(alias).deleteKey()
            deleteTreeWithoutFollowingLinks(root.toPath())
            syncMarkerDirectory(appContext.cacheDir)
        }
    }

    @Test
    fun failedExclusiveMkdirNeverDeletesPreexistingFixtureLookingDirectory() {
        val runId = UUID.randomUUID().toString()
        val existing = File(appContext.cacheDir, "glafira-backup-test-" + runId)
        assertTrue(existing.mkdir())
        val canary = File(existing, "unowned.txt").apply { writeText("must-survive") }
        try {
            // This is the exact failure path when mkdir() returns false: the setup did not
            // create the directory, so its cleanup helper must be a no-op.
            cleanupUnclaimedFixtureRoot(existing, createdByThisSetup = false, runId = runId)
            assertTrue("Pre-existing fixture-looking directory is preserved", canary.isFile)
        } finally {
            existing.deleteRecursively()
        }
    }

    @Test
    fun nonFileKeyOwnerRecordsArePreservedWithoutBlockingCleanup() {
        val runId = UUID.randomUUID().toString()
        val directoryMarker = File(appContext.noBackupFilesDir, "glafira-test-key-owner-" + runId + "-primary")
        assertTrue(directoryMarker.mkdir())
        val temporaryDirectory = File(appContext.noBackupFilesDir, ".glafira-test-key-owner-" + runId + "-primary." + UUID.randomUUID() + ".tmp")
        assertTrue(temporaryDirectory.mkdir())
        try {
            cleanupOwnedLeftoverRollbackFixture()
            assertTrue("Directory masquerading as a key-owner marker is preserved", directoryMarker.isDirectory)
            assertTrue("Directory masquerading as a temporary marker is preserved", temporaryDirectory.isDirectory)
        } finally {
            directoryMarker.delete()
            temporaryDirectory.delete()
        }
    }

    @Test
    fun orphanCleanupPreservesSymlinkRootsAndTheirTargets() {
        val runId = UUID.randomUUID().toString()
        val alias = "glafira_test_rollback_" + runId.replace("-", "")
        val marker = File(appContext.noBackupFilesDir, "glafira-test-key-owner-" + runId + "-primary")
        FileOutputStream(marker).use { output ->
            output.write(alias.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        val target = File(appContext.cacheDir, "unrelated-target-" + runId).apply {
            mkdir()
            File(this, "keep.txt").writeText("must-survive")
        }
        val root = File(appContext.cacheDir, "glafira-backup-test-" + runId)
        try {
            Os.symlink(target.absolutePath, root.absolutePath)
            cleanupOwnedLeftoverRollbackFixture()
            assertTrue("Symlink target remains untouched", File(target, "keep.txt").isFile)
            assertTrue("Symlink root is preserved", Files.isSymbolicLink(root.toPath()))
            assertTrue("Alias owner marker is preserved for manual recovery", marker.exists())
        } finally {
            if (Files.isSymbolicLink(root.toPath())) Os.remove(root.absolutePath)
            target.deleteRecursively()
            marker.delete()
        }
    }

    @Test
    fun orphanedTemporaryAliasRegistrationIsReclaimedOnlyForCanonicalOwnedNames() {
        val validRun = UUID.randomUUID().toString()
        val validTemp = File(appContext.noBackupFilesDir, ".glafira-test-key-owner-" + validRun + "-primary." + UUID.randomUUID() + ".tmp").apply { writeText("partial-registration") }
        val malformed = listOf(
            File(appContext.noBackupFilesDir, ".glafira-test-key-owner-abandoned-" + UUID.randomUUID() + ".tmp"),
            File(appContext.noBackupFilesDir, ".glafira-test-key-owner-" + validRun + "-unknown." + UUID.randomUUID() + ".tmp"),
            File(appContext.noBackupFilesDir, ".glafira-test-key-owner-" + validRun.uppercase() + "-primary." + UUID.randomUUID() + ".tmp")
        ).onEach { it.writeText("unowned") }
        try {
            val activeDatabase = File(store.writableDatabase.path)
            val activeAliasMarker = fixtureKeyOwnerMarker(rollbackCipher.keyAlias, testRunId)
            cleanupOwnedLeftoverRollbackFixture()
            assertTrue("Current test database survives orphan reclamation", activeDatabase.exists())
            assertTrue("Current fixture owner marker survives orphan reclamation", activeAliasMarker.exists())
            assertTrue("Current fixture root ownership record survives orphan reclamation", fixtureRootOwnerMarker(testRoot).exists())
            assertFalse("Canonical interrupted registration is reclaimed", validTemp.exists())
            malformed.forEach { assertTrue("Malformed unrelated marker is preserved", it.exists()) }
        } finally {
            validTemp.delete()
            malformed.forEach { it.delete() }
        }
    }

    private suspend fun <T> withOwnedRollbackKey(block: suspend () -> T): T {
        assertNoActiveCoreWork()
        val runId = testRunId
        var markerPublished = false
        var primaryFailure: Throwable? = null
        try {
            // Set the flag inside the protected block: withTimeout can throw after the
            // block publishes the marker but before withFixtureLocks returns to its caller.
            withFixtureLocks {
                writeRollbackOwnerMarker(runId)
                markerPublished = true
            }
            return block()
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            if (markerPublished) {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        withFixtureLocks {
                            val marker = rollbackOwnerMarker()
                            if (marker.isFile && marker.readText(Charsets.UTF_8) == runId) {
                                val journal = File(context.noBackupFilesDir, "glafira-restore-journal")
                                val journalOwner = File(journal, "test-owner")
                                if (primaryFailure != null && (journal.exists() || rollbackKeyExists())) {
                                    throw IllegalStateException(
                                        "Preserving fixture root, journal and fixture key after a failed test."
                                    )
                                }
                                if (journal.exists()) {
                                    check(journalOwner.isFile && journalOwner.readText(Charsets.UTF_8) == runId) {
                                        "Refusing to claim or delete a rollback journal without matching fixture ownership."
                                    }
                                    // Keep the ownership journal until Keystore deletion succeeds.
                                    if (rollbackKeyExists()) deleteFixtureRollbackKey()
                                    check(journal.deleteRecursively()) { "Could not remove test-owned rollback journal." }
                                } else {
                                    check(!rollbackKeyExists()) {
                                        "A fixture owner marker without its journal cannot authorize deletion of its fixture key."
                                    }
                                }
                                clearRollbackOwnerMarker(runId)
                            }
                        }
                    }
                } catch (cleanup: Throwable) {
                    if (primaryFailure != null) primaryFailure!!.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    private suspend fun restoreOwned(
        coordinator: GlafiraCoreBackupCoordinator,
        archive: File,
        passphrase: CharArray
    ): BackupVerification = withOwnedRollbackKey { coordinator.restore(archive, passphrase) }
    private suspend fun <T> withOwnedRestoreJournal(block: suspend () -> T): T = withOwnedRollbackKey(block)
    private fun deleteFixtureRollbackKey() {
        rollbackCipher.deleteKey()
    }

    private fun rollbackKeyExists(): Boolean = rollbackCipher.keyExists()
    private fun assertCauseContains(error: Throwable, expected: String) {
        val messages = generateSequence(error as Throwable?) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue("Expected failure cause to contain '$expected', got: $messages", messages.any { expected in it })
    }
    private fun assertCauseType(error: Throwable, expected: Class<out Throwable>) {
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        assertTrue(
            "Expected cause " + expected.name + ", got: " + causes.map { it.javaClass.name },
            causes.any { expected.isInstance(it) }
        )
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
    fun markerPublishFailureLeavesNoActiveMarkerOrTemporaryFile() {
        val marker = rollbackOwnerMarker()
        assertFalse(marker.exists())
        assertThrows(IOException::class.java) {
            writeRollbackOwnerMarker(testRunId) { throw IOException("injected before atomic publish") }
        }
        assertFalse(marker.exists())
        assertTrue(marker.parentFile?.listFiles { file -> file.name.startsWith(".${marker.name}.") }?.isEmpty() != false)
    }

    @Test
    fun unclaimedFixtureCleanupDoesNotDeleteAliasWithoutItsOwnerMarker() {
        val alias = rollbackCipher.keyAlias
        clearFixtureKeyAliasOwner(alias, testRunId)
        val source = File(context.cacheDir, "unclaimed-alias-source-$testRunId").apply { writeText("fixture-key-canary") }
        val encrypted = File(context.cacheDir, "unclaimed-alias-encrypted-$testRunId")
        val root = File(appContext.cacheDir, ".unclaimed-fixture-$testRunId").apply { check(mkdir()) }
        try {
            rollbackCipher.encrypt(source, encrypted)
            cleanupUnclaimedFixtureRoot(root, createdByThisSetup = true, runId = testRunId)
            assertFalse("The directory exclusively created by this setup is removed", root.exists())
            assertTrue("A key without this setup's published owner marker is preserved", rollbackCipher.keyExists())
        } finally {
            rollbackCipher.deleteKey()
            source.delete()
            encrypted.delete()
        }
    }

    @Test
    fun orphanCleanupReclaimsSecondaryKeysUsingPrimaryRootOwnerRecord() {
        val staleRunId = UUID.randomUUID().toString()
        val primaryAlias = "glafira_test_rollback_" + staleRunId.replace("-", "")
        val secondAlias = primaryAlias + "_second"
        val thirdAlias = primaryAlias + "_third"
        val root = File(appContext.cacheDir, "glafira-backup-test-$staleRunId").apply { check(mkdir()) }
        val source = File(appContext.cacheDir, "orphan-key-source-$staleRunId").apply { writeText("orphan-fixture") }
        val secondCipher = GlafiraRollbackSnapshotCipher(secondAlias)
        val thirdCipher = GlafiraRollbackSnapshotCipher(thirdAlias)
        val secondEncrypted = File(appContext.cacheDir, "orphan-second-$staleRunId.enc")
        val thirdEncrypted = File(appContext.cacheDir, "orphan-third-$staleRunId.enc")
        try {
            writeFixtureRootOwnerMarker(staleRunId, root, primaryAlias)
            registerFixtureKeyAlias(secondAlias, staleRunId)
            registerFixtureKeyAlias(thirdAlias, staleRunId)
            secondCipher.encrypt(source, secondEncrypted)
            thirdCipher.encrypt(source, thirdEncrypted)

            cleanupOwnedLeftoverRollbackFixture()

            assertFalse("Stale fixture root is reclaimed", root.exists())
            assertFalse("Secondary alias key is reclaimed", secondCipher.keyExists())
            assertFalse("Third alias key is reclaimed", thirdCipher.keyExists())
            assertFalse("Secondary owner marker is reclaimed", fixtureKeyOwnerMarker(secondAlias, staleRunId).exists())
            assertFalse("Third owner marker is reclaimed", fixtureKeyOwnerMarker(thirdAlias, staleRunId).exists())
        } finally {
            secondCipher.deleteKey()
            thirdCipher.deleteKey()
            fixtureKeyOwnerMarker(secondAlias, staleRunId).delete()
            fixtureKeyOwnerMarker(thirdAlias, staleRunId).delete()
            if (root.exists()) deleteTreeWithoutFollowingLinks(root.toPath())
            source.delete()
            secondEncrypted.delete()
            thirdEncrypted.delete()
        }
    }

    @Test
    fun coordinatorArchiveContainsCoreDatabaseSnapshot() = runBlocking {
        val coordinator = isolatedCoordinator()
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val archive = coordinator.create(passphrase)
        try {
            val verification = coordinator.verify(archive, passphrase)
            assertEquals("Archive creation must enter the pending-delivery pause exactly once", 1, fixtureRuntimeHooks.pauseCount)
            assertTrue(verification.fileCount > 0)
            assertTrue(verification.totalBytes > 0)
        } finally {
            archive.delete()
            passphrase.fill(0.toChar())
        }
    }
    @Test
    fun legacyArchiveWithoutCoreDatabaseIsRejectedBeforeRestore() = runBlocking {
        store.enqueue(task("existing-task"))
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val legacyArchive = BackupArchive.create(context, passphrase)
        try {
            withOwnedRollbackKey {
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { isolatedCoordinator().restore(legacyArchive, passphrase) }
                }
                assertEquals("existing-task", store.getTask("existing-task")?.taskId)
            }
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

        val coordinator = isolatedCoordinator()
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val archive = coordinator.create(passphrase)
        try {
            store.writableDatabase.delete(
                "core_task_results", "task_id = ?", arrayOf("backup-result-task")
            )
            assertNull(store.coreTaskResult("backup-result-task", "backup-project"))
            store.enqueue(task("second-task"))
            assertEquals("second-task", store.getTask("second-task")?.taskId)

            restoreOwned(coordinator, archive, passphrase)

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
    fun failedRestoreRemovesPlaintextExportWhenRollbackAlsoFails() = runBlocking {
        store.enqueue(task("restore-failure-existing-task"))
        val coordinator = isolatedCoordinator()
        val passphrase = "GLAFIRA-failed-restore-plaintext".toCharArray()
        val archive = coordinator.create(passphrase)
        val journal = File(context.noBackupFilesDir, "glafira-restore-journal")
        val snapshotDirectory = File(context.filesDir, ".glafira-core-snapshot")
        try {
            withOwnedRollbackKey {
                fixtureRuntimeHooks.failPauseForRestoreCount = 2
                val failure = assertThrows(IOException::class.java) {
                    runBlocking { coordinator.restore(archive, passphrase) }
                }
                assertTrue("The rollback failure remains attached to the original restore failure",
                    failure.suppressed.any { it is IllegalStateException })
                assertFalse("Failed restore must not leave its plaintext snapshot directory", snapshotDirectory.exists())
                assertTrue("Rollback journal remains available for owner recovery", File(journal, "restore.pending").isFile)
                assertTrue("Original Core task remains present after failed restore",
                    store.getTask("restore-failure-existing-task") != null)
                fixtureRuntimeHooks.failPauseForRestoreCount = 0
                isolatedCoordinator().recover(passphrase)
                assertFalse("Owner recovery clears the retained rollback journal", journal.exists())
                assertTrue("Original Core task remains present after owner recovery",
                    store.getTask("restore-failure-existing-task") != null)
            }
        } finally {
            fixtureRuntimeHooks.failPauseForRestoreCount = 0
            archive.delete()
            passphrase.fill(0.toChar())
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

        val coordinator = isolatedCoordinator()
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val archive = coordinator.create(passphrase)
        try {
            store.enqueue(task("temporary-task"))
            // The first post-commit cleanup is interrupted; restore() must retry cleanup
            // and still report success because this exact restore attempt was committed.
            fixtureRuntimeHooks.failCommittedCleanupOnce = true
            restoreOwned(coordinator, archive, passphrase)

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
                runBlocking { isolatedCoordinator().create(passphrase) }
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

        val coordinator = isolatedCoordinator()
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

            withOwnedRestoreJournal {
                val restoreFailure = assertThrows(android.database.sqlite.SQLiteException::class.java) {
                    runBlocking { coordinator.restore(archive, passphrase) }
                }
                assertCauseContains(restoreFailure, "injected-portable-restore-result-failure")
                assertEquals(
                    "known-good-result",
                    store.coreTaskResult("backup-result-task", "backup-project")?.result
                )
                assertEquals("created-after-backup", store.getTask("created-after-backup")?.taskId)
                assertFalse(File(context.noBackupFilesDir, "glafira-restore-journal/restore.pending").exists())
                assertEquals(expectedTraceEventIds, trace.query(
                    GlafiraCoreTraceQuery("backup-project"),
                    GlafiraCoreTraceAccessContext("backup-project", allowProjectEvents = true)
                ).events.map { it.eventId })
                assertThrows(android.database.sqlite.SQLiteException::class.java) {
                    store.writableDatabase.delete(GlafiraCoreTraceStore.TABLE, "event_id = ?", arrayOf("restore-failure-trace"))
                }
                assertEquals(2, store.readableDatabase.rawQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name IN ('core_trace_task_created', 'core_trace_task_state_changed')",
                    null
                ).use { it.moveToFirst(); it.getInt(0) })
                store.writableDatabase.execSQL("DROP TRIGGER inject_portable_restore_result_failure")
            }
        } finally {
            store.writableDatabase.execSQL("DROP TRIGGER IF EXISTS inject_portable_restore_result_failure")
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

        val coordinator = isolatedCoordinator()
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

            restoreOwned(coordinator, archive, passphrase)

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
        val coordinator = isolatedCoordinator()
        val correctPassphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val wrongPassphrase = "GLAFIRA-wrong-passphrase-2026".toCharArray()
        val archive = coordinator.create(correctPassphrase)
        try {
            withOwnedRollbackKey {
                val failure = assertThrows(Exception::class.java) {
                    runBlocking { coordinator.restore(archive, wrongPassphrase) }
                }
                assertCauseType(failure, AEADBadTagException::class.java)
                assertEquals("must-remain", store.getTask("must-remain")?.taskId)
            }
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
            val journal = File(context.noBackupFilesDir, "glafira-restore-journal")
            check(journal.mkdirs())
            val sentinel = File(journal, "must-survive-pre-rollback-validation")
            sentinel.writeText("pre-rollback-sentinel", Charsets.UTF_8)
            withOwnedRestoreJournal {
                try {
                    val failure = assertThrows(IllegalArgumentException::class.java) {
                        runBlocking { isolatedCoordinator().restore(archive, passphrase) }
                    }
                    assertEquals("Неполный набор таблиц Core.", failure.message)
                    assertEquals("must-survive-invalid-snapshot", store.getTask("must-survive-invalid-snapshot")?.taskId)
                    assertFalse(File(journal, "restore.pending").exists())
                    assertTrue("Validation must reject before prepareRollback can clear the journal directory.", sentinel.isFile)
                    assertEquals("pre-rollback-sentinel", sentinel.readText(Charsets.UTF_8))
                } finally {
                    // This sentinel journal is deliberately test-created and has no rollback
                    // ownership record. Remove only our sentinel and then its empty directory
                    // before withOwnedRestoreJournal performs installation-key cleanup.
                    if (sentinel.exists()) check(sentinel.delete())
                    if (journal.exists() && journal.list().orEmpty().isEmpty()) {
                        check(journal.delete()) { "Could not remove the empty test sentinel journal." }
                    }
                }
            }
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
        val archive = isolatedCoordinator().create(passphrase)
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val sourceAlias = "$keyAliasPrefix${sourceIdentity.keyEpoch}"
            assertTrue(keyStore.containsAlias(sourceAlias))
            // Simulate a replacement installation without the source noBackup identity or private key.
            assertTrue(identityFile.delete())
            keyStore.deleteEntry(sourceAlias)

            restoreOwned(isolatedCoordinator(), archive, passphrase)

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
    fun rollbackJournalAndCacheNeverExposePlaintextMetadataCanary() = runBlocking<Unit> {
        val taskId = "rollback-journal-canary"
        val canary = "GLAFIRA_ROLLBACK_METADATA_CANARY_91C7"
        val journal = File(context.noBackupFilesDir, "glafira-restore-journal")
        val journalOwner = File(journal, "test-owner")
        val owner = File(context.noBackupFilesDir, "glafira-rollback-canary-owner")
        val runId = testRunId
        val passphrase = "GLAFIRA-rollback-canary-test-2026".toCharArray()
        var ownsFixture = false
        var primaryFailure: Throwable? = null
        try {
            withFixtureLocks {
                run {
                    assertFalse("Refuse to overwrite an unowned rollback journal", journal.exists())
                    assertFalse("Refuse to overwrite an existing rollback key", rollbackKeyExists())
                    assertFalse("Refuse to overwrite an existing canary owner marker", owner.exists())
                    // The journal and key were absent under the exclusive maintenance gate.
                    store.enqueue(task(taskId))
                    store.writableDatabase.execSQL(
                        "UPDATE core_tasks SET last_error = ? WHERE task_id = ?",
                        arrayOf(canary, taskId)
                    )
                    store.writableDatabase.execSQL(
                        "UPDATE core_task_history SET reason = ? WHERE task_id = ?",
                        arrayOf(canary, taskId)
                    )
                    assertEquals(listOf(canary, canary), store.readableDatabase.rawQuery(
                        "SELECT last_error FROM core_tasks WHERE task_id = ? UNION ALL SELECT reason FROM core_task_history WHERE task_id = ?",
                        arrayOf(taskId, taskId)
                    ).use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    })
                    try {
                    writeRollbackOwnerMarker(runId)
                    owner.writeText(runId, Charsets.UTF_8)
                    ownsFixture = true
                        isolatedCoordinator().prepareRollback(passphrase)
                        journalOwner.writeText(runId, Charsets.UTF_8)
                    } catch (error: Exception) {
                        // Never manufacture an ownership record after a partial failure.
                        // Recovery must preserve the journal/key if ownership is ambiguous.
                        throw error
                    }
                    val journalFiles = journal.walkTopDown().filter { it.isFile && it != journalOwner }.toList()
                    assertTrue(
                        "Rollback journal must contain both encrypted snapshot artifacts, excluding its ownership marker",
                        journalFiles.map { it.name }.toSet().containsAll(setOf("database.db", "files.glb"))
                    )
                    val cacheFiles = context.cacheDir.walkTopDown().filter { it.isFile }.toList()
                    (journalFiles + cacheFiles).forEach { file ->
                        val contents = file.readBytes().toString(Charsets.ISO_8859_1)
                        assertFalse(
                            "Plaintext rollback metadata leaked into ${file.name}",
                            contents.contains(canary)
                        )
                    }
                    assertTrue(
                        "No decrypted rollback recovery temp may remain after prepareRollback",
                        context.cacheDir.listFiles { file ->
                            file.name.startsWith("glafira-rollback-recovery-") && file.name.endsWith(".tmp")
                        }.orEmpty().isEmpty()
                    )
                }
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            passphrase.fill('\u0000')
            if (ownsFixture) try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    withFixtureLocks {
                        if (owner.isFile && owner.readText(Charsets.UTF_8) == runId) {
                            if (journal.exists()) {
                                check(journalOwner.isFile && journalOwner.readText(Charsets.UTF_8) == runId) {
                                    "Refusing to delete a journal not proven to belong to this canary generation."
                                }
                                if (rollbackKeyExists()) deleteFixtureRollbackKey()
                                check(journal.deleteRecursively())
                            } else check(!rollbackKeyExists()) {
                                "Refusing to delete an installation rollback key without its owned journal."
                            }
                            clearRollbackOwnerMarker(runId)
                            check(owner.delete())
                        }
                    }
                }
            } catch (cleanup: Throwable) {
                if (primaryFailure != null) primaryFailure!!.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
    @Test
    fun successfulRestoreDeletesInstallationLocalRollbackKey() = runBlocking {
        assertFalse("Refuse to overwrite an existing rollback key", rollbackKeyExists())
        store.enqueue(task("rollback-key-lifecycle"))
        val coordinator = isolatedCoordinator()
        val passphrase = "GLAFIRA-rollback-key-lifecycle".toCharArray()
        val archive = coordinator.create(passphrase)
        try {
            withOwnedRollbackKey {
                coordinator.restore(archive, passphrase)
                assertFalse("Rollback key must be deleted after the pending journal is cleared", rollbackKeyExists())
            }
        } finally {
            archive.delete()
            passphrase.fill('\u0000')
        }
    }
    @Test
    fun restoreReportsSuccessWhenRecoveryResyncsCommitBeforeCleanupFailure() = runBlocking {
        val taskId = "commit-resync-before-cleanup"
        store.enqueue(task(taskId))
        val coordinator = isolatedCoordinator()
        val passphrase = "GLAFIRA-commit-resync-cleanup".toCharArray()
        val archive = coordinator.create(passphrase)
        val expectedVerification = coordinator.verify(archive, passphrase)
        assertTrue(
            "Test must remove the archived task before restore",
            store.transition(
                taskId,
                setOf(GlafiraTaskState.QUEUED),
                GlafiraTaskState.CANCELLED,
                System.currentTimeMillis(),
                null,
                "REMOVED_TO_VERIFY_RESTORE"
            )
        )
        val journal = File(context.noBackupFilesDir, "glafira-restore-journal")
        try {
            withOwnedRollbackKey {
                fixtureRuntimeHooks.committedMarkerSyncCalls = 0
                fixtureRuntimeHooks.committedMarkerSyncCompletedCalls = 0
                fixtureRuntimeHooks.committedCleanupCalls = 0
                fixtureRuntimeHooks.failCommittedMarkerSyncCount = 1
                fixtureRuntimeHooks.failCommittedCleanupOnce = true
                val verification = coordinator.restore(archive, passphrase)
                assertTrue("Restore result discloses that committed cleanup is still required", verification.recoveryRequired)
                assertEquals("Initial marker sync and recovery re-sync are both attempted", 2,
                    fixtureRuntimeHooks.committedMarkerSyncCalls)
                assertEquals("Recovery completes the actual committed-marker directory fsync", 1,
                    fixtureRuntimeHooks.committedMarkerSyncCompletedCalls)
                assertEquals("Cleanup is attempted after the recovery fsync", 1, fixtureRuntimeHooks.committedCleanupCalls)
                assertFalse("The injected cleanup failure is consumed", fixtureRuntimeHooks.failCommittedCleanupOnce)
                assertTrue("Committed marker remains after cleanup interruption", File(journal, "restore.committed").isFile)
                assertTrue("Rollback evidence remains for the next cleanup pass", File(journal, "database.db").isFile)
                isolatedCoordinator().recover(passphrase)
                assertFalse("Recovery removes the committed journal", journal.exists())
                assertEquals("The selected archive task is restored for owner review",
                    GlafiraTaskState.WAITING_FOR_OWNER, store.getTask(taskId)?.state)
                assertEquals(expectedVerification.fileCount, verification.fileCount)
                assertEquals(expectedVerification.totalBytes, verification.totalBytes)
                assertEquals(expectedVerification.createdAt, verification.createdAt)
            }
        } finally {
            fixtureRuntimeHooks.failCommittedMarkerSyncCount = 0
            fixtureRuntimeHooks.failCommittedCleanupOnce = false
            archive.delete()
            passphrase.fill(0.toChar())
        }
    }

    @Test
    fun restoreDoesNotReportSuccessUntilRecoveryReFsyncsCommit() = runBlocking {
        val taskId = "commit-resync-must-succeed"
        store.enqueue(task(taskId))
        val coordinator = isolatedCoordinator()
        val passphrase = "GLAFIRA-commit-resync-required".toCharArray()
        val archive = coordinator.create(passphrase)
        assertTrue(
            "Test must remove the archived task before restore",
            store.transition(
                taskId,
                setOf(GlafiraTaskState.QUEUED),
                GlafiraTaskState.CANCELLED,
                System.currentTimeMillis(),
                null,
                "REMOVED_TO_VERIFY_RESTORE"
            )
        )
        val journal = File(context.noBackupFilesDir, "glafira-restore-journal")
        try {
            withOwnedRollbackKey {
                fixtureRuntimeHooks.committedMarkerSyncCalls = 0
                fixtureRuntimeHooks.committedMarkerSyncCompletedCalls = 0
                fixtureRuntimeHooks.failCommittedMarkerSyncCount = 2
                assertThrows(IOException::class.java) {
                    runBlocking { coordinator.restore(archive, passphrase) }
                }
                assertEquals("Initial publish and recovery re-sync are both attempted", 2,
                    fixtureRuntimeHooks.committedMarkerSyncCalls)
                assertEquals("Neither failed marker sync is counted as complete", 0,
                    fixtureRuntimeHooks.committedMarkerSyncCompletedCalls)
                assertTrue("Unconfirmed commit marker remains for later recovery", File(journal, "restore.committed").isFile)
                assertTrue("Rollback evidence remains until a later re-sync", File(journal, "database.db").isFile)
                fixtureRuntimeHooks.failCommittedMarkerSyncCount = 0
                isolatedCoordinator().recover(passphrase)
                assertEquals("Later recovery completes the real marker-directory fsync", 1,
                    fixtureRuntimeHooks.committedMarkerSyncCompletedCalls)
                assertFalse("Later recovery removes the committed journal", journal.exists())
                assertEquals("The archive task is restored only after a successful re-sync",
                    GlafiraTaskState.WAITING_FOR_OWNER, store.getTask(taskId)?.state)
            }
        } finally {
            fixtureRuntimeHooks.failCommittedMarkerSyncCount = 0
            archive.delete()
            passphrase.fill(0.toChar())
        }
    }

    @Test
    fun committedRestoreReportsSuccessWhenJournalCleanupMustBeRetried() = runBlocking {
        store.enqueue(task("restore-cleanup-pending"))
        val coordinator = isolatedCoordinator()
        val passphrase = "GLAFIRA-committed-cleanup-pending".toCharArray()
        val archive = coordinator.create(passphrase)
        val journal = File(context.noBackupFilesDir, "glafira-restore-journal")
        try {
            withOwnedRollbackKey {
                store.enqueue(task("restore-created-after-backup"))
                // Fail both the initial cleanup and its immediate recovery retry. The
                // durable commit is already published, so restore must report success
                // while preserving the journal for a later cleanup pass.
                fixtureRuntimeHooks.failCommittedCleanupCount = 2
                val verification = coordinator.restore(archive, passphrase)
                assertTrue("Restore result must disclose pending committed-journal cleanup", verification.recoveryRequired)
                assertNull("Tasks created after the selected archive must not survive its restore",
                    store.getTask("restore-created-after-backup"))
                assertTrue("Committed journal remains available for later cleanup", File(journal, "restore.committed").isFile)
                assertTrue("Rollback evidence remains while cleanup is pending", File(journal, "database.db").isFile)
                fixtureRuntimeHooks.failCommittedCleanupCount = 0
                isolatedCoordinator().recover(passphrase)
                assertFalse("A later recovery pass removes the committed journal", journal.exists())
                assertTrue("The selected archive's task remains restored", store.getTask("restore-cleanup-pending") != null)
                assertNull("The post-archive task remains absent after recovery", store.getTask("restore-created-after-backup"))
            }
        } finally {
            fixtureRuntimeHooks.failCommittedCleanupCount = 0
            archive.delete()
            passphrase.fill(0.toChar())
        }
    }

    @Test
    fun v3RecoveryReentersAfterFreshCoordinatorConstruction() = run {
        runBlocking {
            val journal = File(context.noBackupFilesDir, "glafira-restore-journal")
            val marker = File(journal, "restore.pending")
            val journalOwner = File(journal, "test-owner")
            val rollbackDatabase = File(journal, "database.db")
            val rollbackArchive = File(journal, "files.glb")
            val owner = File(context.noBackupFilesDir, "glafira-v3-reentry-test-owner")
            val knownGood = File(context.filesDir, "v3-reentry-known-good.txt")
            val createdAfterMarker = File(context.filesDir, "v3-reentry-created-after-marker.txt")
            val runId = testRunId
                val passphrase = "GLAFIRA-${UUID.randomUUID()}-${UUID.randomUUID()}".toCharArray()
            var expectedTask: GlafiraTask? = null
            var ownsFixture = false
            var primaryFailure: Throwable? = null

            try {
                withFixtureLocks {
                    run {
                        if (owner.exists()) {
                            val previous = owner.readText(Charsets.UTF_8)
                            if (journal.exists()) {
                                check(journalOwner.isFile && journalOwner.readText(Charsets.UTF_8) == previous)
                                check(journal.deleteRecursively())
                                if (rollbackKeyExists()) deleteFixtureRollbackKey()
                            } else check(!rollbackKeyExists()) {
                                "Refusing to delete a rollback key without its owned journal."
                            }
                            check(!knownGood.exists() || knownGood.delete())
                            check(!createdAfterMarker.exists() || createdAfterMarker.delete())
                            check(owner.delete())
                        }
                        assertFalse("Refuse to overwrite an unowned journal", journal.exists())
                        assertFalse("Refuse to overwrite an existing rollback key", rollbackKeyExists())
                        assertFalse("Known-good fixture must start absent", knownGood.exists())
                        assertFalse("Mutation fixture must start absent", createdAfterMarker.exists())
                        knownGood.writeText("known-good-before-marker", Charsets.UTF_8)
                        val task = task("known-good-before-marker")
                        store.enqueue(task)
                        expectedTask = checkNotNull(store.getTask(task.taskId))
                        writeRollbackOwnerMarker(runId)
                        owner.writeText(runId, Charsets.UTF_8)
                        ownsFixture = true
                        try {
                            isolatedCoordinator().prepareRollback(passphrase)
                            journalOwner.writeText(runId, Charsets.UTF_8)
                        } catch (error: Exception) {
                            // A partial prepareRollback is not proof that this fixture owns
                            // the installation-wide key. Leave ambiguous recovery state intact.
                            throw error
                        }
                        assertTrue("V3 marker must be durable before live-state mutation", marker.isFile)
                        assertEquals("GLAFIRA_RESTORE_ROLLBACK_V3", marker.readText(Charsets.UTF_8))
                        assertTrue(rollbackDatabase.isFile && rollbackDatabase.length() > 0L)
                        assertTrue(rollbackArchive.isFile && rollbackArchive.length() > 0L)
                    }
                }
                withFixtureLocks {
                    run {
                        store.enqueue(task("mutated-after-marker"))
                        assertTrue(store.transition(
                            "known-good-before-marker", setOf(GlafiraTaskState.QUEUED),
                            GlafiraTaskState.WAITING_FOR_OWNER, System.currentTimeMillis(), null,
                            "mutated-after-marker"
                        ))
                        knownGood.writeText("mutated-after-marker", Charsets.UTF_8)
                        createdAfterMarker.writeText("created-after-marker", Charsets.UTF_8)
                    }
                }
                // The injected fixture runtime has no authority over the application scheduler.
                withTimeout(MAINTENANCE_TIMEOUT_MILLIS) {
                    isolatedCoordinator().recover(passphrase)
                }
                assertEquals(expectedTask, store.getTask("known-good-before-marker"))
                assertNull(store.getTask("mutated-after-marker"))
                assertEquals("known-good-before-marker", knownGood.readText(Charsets.UTF_8))
                assertFalse("Files created after the marker must be removed", createdAfterMarker.exists())
                assertFalse("Successful recovery must clear the pending marker", marker.exists())
                assertFalse("Successful recovery must remove the rollback journal", journal.exists())
                assertFalse("Successful recovery must delete only this fixture's key", rollbackKeyExists())
                val remainingTemps = checkNotNull(context.cacheDir.listFiles { file ->
                    file.name.startsWith("glafira-rollback-recovery-") && file.name.endsWith(".tmp")
                })
                assertTrue("No decrypted rollback recovery temp may remain", remainingTemps.isEmpty())
                assertEquals("Rollback recovery must invoke the pause hook exactly once", 1, fixtureRuntimeHooks.pauseContexts.size)
                assertTrue("Rollback recovery must pass the fixture context by identity", fixtureRuntimeHooks.pauseContexts.single() === context)
                assertFalse(GlafiraCoreBackupCoordinator.hasInterruptedRestore(context))
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                passphrase.fill('\u0000')
                if (ownsFixture) try {
                    withFixtureLocks {
                        run {
                            if (owner.isFile && owner.readText(Charsets.UTF_8) == runId) {
                                if (primaryFailure != null && (journal.exists() || rollbackKeyExists())) {
                                    throw IllegalStateException(
                                        "Preserving V3 fixture journal and key after a failed recovery assertion."
                                    )
                                }
                                if (journal.exists()) {
                                    check(journalOwner.isFile && journalOwner.readText(Charsets.UTF_8) == runId)
                                    if (rollbackKeyExists()) deleteFixtureRollbackKey()
                                    check(journal.deleteRecursively())
                                } else check(!rollbackKeyExists()) {
                                    "Refusing to delete an installation rollback key without its owned journal."
                                }
                                clearRollbackOwnerMarker(runId)
                                check(!knownGood.exists() || knownGood.delete())
                                check(!createdAfterMarker.exists() || createdAfterMarker.delete())
                                check(owner.delete())
                            }
                        }
                    }
                } catch (cleanup: Throwable) {
                    if (primaryFailure != null) primaryFailure!!.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    @Test
    fun fixtureCipherUsesIndependentAliasesAndCannotDeleteOtherFixtureKeys() = runBlocking {
        withFixtureLocks {
            val suffix = testRunId.replace("-", "")
            val secondFixture = GlafiraRollbackSnapshotCipher("glafira_test_rollback_" + suffix + "_second")
            val thirdFixture = GlafiraRollbackSnapshotCipher("glafira_test_rollback_" + suffix + "_third")
            registerFixtureKeyAlias(secondFixture.keyAlias, testRunId)
            registerFixtureKeyAlias(thirdFixture.keyAlias, testRunId)
            val source = File(context.cacheDir, "alias-isolation-source.db")
            val firstEncrypted = File(context.cacheDir, "alias-isolation-first.enc")
            val secondEncrypted = File(context.cacheDir, "alias-isolation-second.enc")
            val thirdEncrypted = File(context.cacheDir, "alias-isolation-third.enc")
            try {
                source.writeText("fixture-alias-isolation")
                rollbackCipher.encrypt(source, firstEncrypted)
                secondFixture.encrypt(source, secondEncrypted)
                thirdFixture.encrypt(source, thirdEncrypted)
                assertTrue(rollbackCipher.keyExists())
                assertTrue(secondFixture.keyExists())
                assertTrue(thirdFixture.keyExists())
                assertNotEquals(rollbackCipher.keyAlias, secondFixture.keyAlias)
                assertNotEquals(rollbackCipher.keyAlias, thirdFixture.keyAlias)
                rollbackCipher.deleteKey()
                assertFalse(rollbackCipher.keyExists())
                assertTrue("Deleting the primary fixture key must preserve the second fixture key", secondFixture.keyExists())
                assertTrue("Deleting the primary fixture key must preserve the third fixture canary", thirdFixture.keyExists())
            } finally {
                source.delete()
                firstEncrypted.delete()
                secondEncrypted.delete()
                thirdEncrypted.delete()
                rollbackCipher.deleteKey()
                secondFixture.deleteKey()
                clearFixtureKeyAliasOwner(secondFixture.keyAlias, testRunId)
                thirdFixture.deleteKey()
                clearFixtureKeyAliasOwner(thirdFixture.keyAlias, testRunId)
            }
        }
    }

    @Test
    fun recoveryRejectsCommittedJournalSymlinkWithoutTouchingTargetArtifacts() = runBlocking {
        val runId = testRunId
        val journal = File(context.noBackupFilesDir, "glafira-restore-journal")
        val target = File(context.cacheDir, "committed-journal-target-$runId").apply { check(mkdir()) }
        val committed = File(target, "restore.committed").apply { writeText("GLAFIRA_RESTORE_COMMITTED_V1") }
        val database = File(target, "database.db").apply { writeText("must-survive") }
        val archive = File(target, "files.glb").apply { writeText("must-survive-too") }
        val passphrase = "GLAFIRA-committed-journal-link".toCharArray()
        try {
            Files.createSymbolicLink(journal.toPath(), target.toPath())
            val failure = assertThrows(IllegalStateException::class.java) {
                runBlocking { isolatedCoordinator().recover(passphrase) }
            }
            assertTrue("Recovery must stop at the explicit journal symlink guard",
                failure.message.orEmpty().contains("символическая ссылка"))
            assertTrue("Committed marker in the symlink target is preserved", committed.isFile)
            assertEquals("must-survive", database.readText())
            assertEquals("must-survive-too", archive.readText())
        } finally {
            if (Files.isSymbolicLink(journal.toPath())) Files.delete(journal.toPath())
            check(target.deleteRecursively())
            passphrase.fill(0.toChar())
        }
    }

    @Test
    fun committedCleanupFsyncsFilesDirectoryOnlyOnceAcrossRecoveryRetry() = runBlocking {
        val journal = File(context.noBackupFilesDir, "glafira-restore-journal").apply { check(mkdirs()) }
        val pending = File(journal, "restore.pending").apply { writeText("GLAFIRA_RESTORE_ROLLBACK_V3") }
        val committed = File(journal, "restore.committed").apply { writeText("GLAFIRA_RESTORE_COMMITTED_V1") }
        val database = File(journal, "database.db").apply { writeText("encrypted-fixture-placeholder") }
        val archive = File(journal, "files.glb").apply { writeText("encrypted-fixture-placeholder") }
        val passphrase = "committed-snapshot-parent-fsync".toCharArray()
        try {
            fixtureRuntimeHooks.plaintextSnapshotDirectorySyncCalls = 0
            fixtureRuntimeHooks.plaintextSnapshotDirectorySyncCompletedCalls = 0
            fixtureRuntimeHooks.failPlaintextSnapshotDirectorySyncOnce = true
            val failure = assertThrows(IOException::class.java) {
                runBlocking { isolatedCoordinator().recover(passphrase) }
            }
            assertEquals("Injected fsync failure must be the observed error",
                "injected plaintext snapshot parent-directory fsync failure", failure.message)
            assertEquals(1, fixtureRuntimeHooks.plaintextSnapshotDirectorySyncCalls)
            assertEquals(0, fixtureRuntimeHooks.plaintextSnapshotDirectorySyncCompletedCalls)
            assertTrue(committed.exists() && database.exists() && archive.exists())

            isolatedCoordinator().recover(passphrase)
            assertEquals("Retry must complete one filesDir fsync", 1,
                fixtureRuntimeHooks.plaintextSnapshotDirectorySyncCompletedCalls)
            assertEquals("Recovery must not repeat snapshot cleanup after journal cleanup", 2,
                fixtureRuntimeHooks.plaintextSnapshotDirectorySyncCalls)
            assertFalse(pending.exists())
            assertFalse(committed.exists())
            assertFalse(database.exists())
            assertFalse(archive.exists())
            assertFalse(journal.exists())
        } finally {
            fixtureRuntimeHooks.failPlaintextSnapshotDirectorySyncOnce = false
            passphrase.fill(0.toChar())
        }
    }

    @Test
    fun committedJournalCleanupCanBeReenteredWithoutRollbackKey() = runBlocking {
        val journal = File(context.noBackupFilesDir, "glafira-restore-journal").apply { check(mkdirs()) }
        val pending = File(journal, "restore.pending").apply { writeText("GLAFIRA_RESTORE_ROLLBACK_V3") }
        val committed = File(journal, "restore.committed").apply { writeText("GLAFIRA_RESTORE_COMMITTED_V1") }
        val database = File(journal, "database.db").apply { writeText("encrypted-fixture-placeholder") }
        val archive = File(journal, "files.glb").apply { writeText("encrypted-fixture-placeholder") }
        val plaintextSnapshot = File(context.filesDir, ".glafira-core-snapshot").apply { check(mkdirs()) }
        val plaintextExport = File(plaintextSnapshot, "core-portable-v2.json").apply {
            writeText("fixture-only plaintext task/result data")
        }
        val passphrase = "committed-cleanup-reentry".toCharArray()
        try {
            fixtureRuntimeHooks.failCommittedCleanupOnce = true
            assertThrows(IOException::class.java) {
                runBlocking { isolatedCoordinator().recover(passphrase) }
            }
            assertTrue("Committed marker is preserved after interrupted cleanup", committed.exists())
            assertTrue("Pending marker is retained until cleanup completes", pending.exists())
            assertTrue("Rollback database is retained while cleanup is interrupted", database.exists())
            assertTrue("Rollback archive is retained while cleanup is interrupted", archive.exists())
            assertTrue("Plaintext export remains until committed cleanup can run", plaintextExport.exists())

            isolatedCoordinator().recover(passphrase)
            assertFalse("Crash-left plaintext export is removed during committed recovery", plaintextExport.exists())
            assertFalse("Plaintext export directory is removed", plaintextSnapshot.exists())
            assertFalse(pending.exists())
            assertFalse(committed.exists())
            assertFalse(database.exists())
            assertFalse(archive.exists())
            assertFalse(journal.exists())
            assertFalse(rollbackCipher.keyExists())
        } finally {
            passphrase.fill(0.toChar())
            // If recovery did not remove the committed marker, retain the complete fixture evidence.
            if (!committed.exists() && journal.exists()) check(journal.deleteRecursively())
        }
    }

    companion object {
        private const val MAINTENANCE_TIMEOUT_MILLIS = 10_000L
        private val FIXTURE_LIFETIME_PROCESS_LOCK = java.util.concurrent.locks.ReentrantLock(true)
    }
}
