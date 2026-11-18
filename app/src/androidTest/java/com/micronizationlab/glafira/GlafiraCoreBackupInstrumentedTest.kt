package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GlafiraCoreBackupInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: GlafiraTaskStore

    @Before
    fun setUp() {
        context.deleteDatabase("glafira_core_tasks.db")
        store = GlafiraTaskStore(context)
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("glafira_core_tasks.db")
    }

    private fun task(id: String = "backup-task") = GlafiraTask(
        taskId = id,
        projectId = "backup-project",
        taskType = "project.read",
        payload = "{\"record\":\"persist-me\"}",
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "backup-project:$id"
    )

    @Test
    fun queueStateAndEncryptedPayloadSurviveStoreReopen() {
        store.enqueue(task())
        assertTrue(
            store.transition(
                "backup-task",
                setOf(GlafiraTaskState.QUEUED),
                GlafiraTaskState.WAITING_FOR_OWNER,
                nowMillis = 2_000L
            )
        )
        store.close()

        store = GlafiraTaskStore(context)
        val restored = requireNotNull(store.getTask("backup-task"))
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, restored.state)
        assertEquals("{\"record\":\"persist-me\"}", restored.payload)
        assertEquals(0, restored.attemptCount)
    }


    @Test
    fun retryScheduleAttemptCountAndIdempotencySurviveStoreReopen() {
        store.enqueue(task())
        val claimed = requireNotNull(store.claimNext(nowMillis = 2_000L))
        assertEquals(1, claimed.attemptCount)
        assertTrue(
            store.transition(
                "backup-task",
                setOf(GlafiraTaskState.RUNNING),
                GlafiraTaskState.RETRY_SCHEDULED,
                nowMillis = 3_000L,
                retryAtMillis = 9_000L,
                lastError = "temporary"
            )
        )
        val duplicate = store.enqueue(task())
        assertTrue(!duplicate.inserted)
        store.close()

        store = GlafiraTaskStore(context)
        val persisted = requireNotNull(store.getTask("backup-task"))
        assertEquals(GlafiraTaskState.RETRY_SCHEDULED, persisted.state)
        assertEquals(1, persisted.attemptCount)
        assertEquals(9_000L, persisted.retryAtMillis)
        assertEquals("temporary", persisted.lastError)
        assertTrue(!store.enqueue(task()).inserted)
    }

    @Test
    fun backupRestoreRecoversCoreQueueStateAndPayload() = runBlocking {
        store.enqueue(task())
        assertTrue(
            store.transition(
                "backup-task",
                setOf(GlafiraTaskState.QUEUED),
                GlafiraTaskState.WAITING_FOR_OWNER,
                nowMillis = 2_000L
            )
        )

        val coordinator = GlafiraCoreBackupCoordinator(context, store)
        val passphrase = "GLAFIRA-test-passphrase-2026".toCharArray()
        val archive = coordinator.create(passphrase)
        try {
            assertTrue(
                store.transition(
                    "backup-task",
                    setOf(GlafiraTaskState.WAITING_FOR_OWNER),
                    GlafiraTaskState.QUEUED,
                    nowMillis = 3_000L
                )
            )
            assertTrue(
                store.transition(
                    "backup-task",
                    setOf(GlafiraTaskState.QUEUED),
                    GlafiraTaskState.RUNNING,
                    nowMillis = 4_000L
                )
            )
            assertTrue(
                store.transition(
                    "backup-task",
                    setOf(GlafiraTaskState.RUNNING),
                    GlafiraTaskState.FAILED,
                    nowMillis = 5_000L,
                    lastError = "changed-after-backup"
                )
            )

            coordinator.restore(archive, passphrase)
            val recovered = requireNotNull(store.getTask("backup-task"))
            assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, recovered.state)
            assertEquals("{\"record\":\"persist-me\"}", recovered.payload)
            assertEquals(0, recovered.attemptCount)
        } finally {
            archive.delete()
            passphrase.fill('\u0000')
            File(context.filesDir, ".glafira-core-snapshot").deleteRecursively()
        }
    }
}
