package com.micronizationlab.glafira

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    companion object {
        private const val DATABASE_NAME = "glafira_core_tasks.db"
    }
}
