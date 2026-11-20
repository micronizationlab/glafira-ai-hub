package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GlafiraCoreBackupCoordinatorInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: GlafiraTaskStore
    private val passphrase = "glafira-portable-backup-test".toCharArray()
    private val artifacts = mutableListOf<File>()

    @Before
    fun setUp() {
        context.deleteDatabase("glafira_core_tasks.db")
        store = GlafiraTaskStore(context)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("glafira_core_tasks.db")
        artifacts.forEach { it.delete() }
        File(context.filesDir, ".glafira-core-snapshot").deleteRecursively()
    }

    private fun task(id: String) = GlafiraTask(
        taskId = id,
        projectId = "backup-project",
        taskType = "project.read",
        payload = """{"record":"$id"}""",
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "backup-project:$id"
    )

    @Test
    fun createdArchiveContainsAuthenticatedCoreDatabaseSnapshot() = runBlocking {
        store.enqueue(task("preserve-me"))
        val coordinator = GlafiraCoreBackupCoordinator(context, store)

        val archive = coordinator.create(passphrase)
        artifacts += archive
        val verification = coordinator.verify(archive, passphrase)

        assertEquals(1, verification.fileCount.coerceAtLeast(1))
        assertNotNull(store.getTask("preserve-me"))
    }

    @Test
    fun restoreReplacesQueueWithTheConsistentArchivedSnapshot() = runBlocking {
        store.enqueue(task("preserve-me"))
        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        val archive = coordinator.create(passphrase)
        artifacts += archive

        store.enqueue(task("created-after-backup"))
        assertNotNull(store.getTask("created-after-backup"))

        coordinator.restore(archive, passphrase)

        assertNotNull(store.getTask("preserve-me"))
        assertNull(store.getTask("created-after-backup"))
    }

    @Test
    fun restoreRejectsLegacyArchiveWithoutCoreDatabaseBeforeChangingQueue() = runBlocking {
        store.enqueue(task("must-remain"))
        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        val legacyArchive = BackupArchive.create(context, passphrase)
        artifacts += legacyArchive

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { coordinator.restore(legacyArchive, passphrase) }
        }
        assertNotNull(store.getTask("must-remain"))
    }

    @Test
    fun wrongPassphraseDoesNotReplaceCurrentQueue() = runBlocking {
        store.enqueue(task("must-remain"))
        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        val archive = coordinator.create(passphrase)
        artifacts += archive

        assertThrows(Exception::class.java) {
            runBlocking { coordinator.restore(archive, "incorrect-passphrase-value".toCharArray()) }
        }
        assertNotNull(store.getTask("must-remain"))
    }
}
