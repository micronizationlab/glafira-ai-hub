package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GlafiraTaskQueueRecoveryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: GlafiraTaskStore

    @Before fun setUp() {
        context.deleteDatabase("glafira_core_tasks.db")
        store = GlafiraTaskStore(context)
    }
    @After fun tearDown() {
        store.close()
        context.deleteDatabase("glafira_core_tasks.db")
    }
    private fun task(id: String, type: String = "project.read", project: String = "project") = GlafiraTask(
        taskId = id, projectId = project, taskType = type, payload = "{}", origin = "owner",
        createdAtMillis = 1_000, updatedAtMillis = 1_000, idempotencyKey = id
    )

    @Test fun disconnectedTaskResumesOnlyAfterExplicitNetworkWakeAcrossReopen() {
        store.enqueue(task("network"))
        store.claimNext(2_000)
        assertTrue(store.transition("network", setOf(GlafiraTaskState.RUNNING),
            GlafiraTaskState.WAITING_FOR_NETWORK, 3_000))
        store.close()
        store = GlafiraTaskStore(context)
        assertNull(store.claimNext(4_000))
        assertTrue(store.hasWaitingForNetworkTasks())
        assertEquals(1, store.resumeWaitingForNetwork(5_000))
        assertEquals(0, store.resumeWaitingForNetwork(5_000))
        assertEquals("network", store.claimNext(6_000)?.taskId)
        assertEquals(2, store.getTask("network")?.attemptCount)
    }

    @Test fun retryWakeUsesPersistedEarliestTimestampAndDoesNotRunEarly() {
        store.enqueue(task("retry"))
        store.claimNext(2_000)
        store.transition("retry", setOf(GlafiraTaskState.RUNNING), GlafiraTaskState.RETRY_SCHEDULED,
            3_000, retryAtMillis = 15_000)
        store.enqueue(task("ready"))
        assertEquals(15_000L, store.nextRetryAtMillis())
        assertEquals("ready", store.claimNext(4_000)?.taskId)
        assertNull(store.claimNext(14_999))
        assertEquals("retry", store.claimNext(15_000)?.taskId)
    }

    @Test fun completedOperationCannotBeReclaimedOrReopenedAndHistorySurvivesReopen() {
        store.enqueue(task("completed"))
        store.claimNext(2_000)
        assertTrue(store.transition("completed", setOf(GlafiraTaskState.RUNNING), GlafiraTaskState.SUCCEEDED, 3_000))
        assertFalse(store.transition("completed", setOf(GlafiraTaskState.RUNNING), GlafiraTaskState.SUCCEEDED, 4_000))
        assertEquals(0, store.recoverInterruptedCoreTasks(5_000, 5))
        assertNull(store.claimNext(5_000))
        store.close()
        store = GlafiraTaskStore(context)
        assertEquals(listOf(GlafiraTaskState.SUCCEEDED, GlafiraTaskState.RUNNING, GlafiraTaskState.QUEUED),
            store.taskHistory("completed", "project").map { it.toState })
        assertTrue(store.taskHistory("completed", "other-project").isEmpty())
        assertThrows(Exception::class.java) {
            store.writableDatabase.update("core_tasks", ContentValues().apply { put("state", "queued") },
                "task_id = ?", arrayOf("completed"))
        }
    }

    @Test fun uncertainWriteIsQuarantinedWhileIndependentReadKeepsRunning() {
        store.enqueue(task("write", "message.send"))
        store.claimNext(2_000)
        store.enqueue(task("read"))
        assertEquals(1, store.recoverInterruptedCoreTasks(3_000, 5))
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("write")?.state)
        assertEquals("CORE_EXECUTION_OUTCOME_UNKNOWN", store.getTask("write")?.lastError)
        assertEquals("read", store.claimNext(4_000)?.taskId)
    }

    @Test fun blockedOwnerAndExpiredTasksDoNotHideReadyTasks() {
        store.enqueue(task("owner").copy(priority = 100))
        store.transition("owner", setOf(GlafiraTaskState.QUEUED), GlafiraTaskState.WAITING_FOR_OWNER, 2_000)
        store.enqueue(task("expired").copy(priority = 99, deadlineAtMillis = 3_000))
        store.enqueue(task("ready"))
        assertEquals("ready", store.claimNext(4_000)?.taskId)
        assertEquals(GlafiraTaskState.FAILED, store.getTask("expired")?.state)
        assertEquals("TASK_DEADLINE_EXPIRED", store.getTask("expired")?.lastError)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("owner")?.state)
    }

    @Test fun scopedStatusAndQueueListingDoNotExposeOtherProjects() {
        store.enqueue(task("first", project = "first-project"))
        store.enqueue(task("second", project = "second-project"))
        assertNull(store.getTask("first", "second-project"))
        assertEquals(listOf("second"), store.listTasks(projectId = "second-project").map { it.taskId })
    }

    @Test fun historyIsRolledBackWithFailedResultTransaction() {
        store.enqueue(task("rollback"))
        store.claimNext(2_000)
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            db.update("core_tasks", ContentValues().apply { put("state", "succeeded"); put("updated_at", 3_000) },
                "task_id = ? AND state = ?", arrayOf("rollback", "running"))
            // Simulates an exception before the result insert commits.
        } finally { db.endTransaction() }
        assertEquals(GlafiraTaskState.RUNNING, store.getTask("rollback")?.state)
        assertEquals(2, store.taskHistory("rollback", "project").size)
    }
    @Test fun expiredDesktopLeaseDoesNotReplayOperationAndAcceptsLateBoundResult() {
        val now = System.currentTimeMillis()
        store.enqueue(task("desktop", "desktop.execute").copy(payload = """{"requiredCapabilities":["code.build"]}"""))
        assertNotNull(store.claimDesktopTask("desktop", "desktop-node-1", setOf("code.build"), 1, now - 700_000))
        assertTrue(store.queuedDesktopTasks().isEmpty())
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("desktop")?.state)
        assertNotNull(store.desktopTaskLeaseExpiry("desktop"))
        assertNull(store.claimDesktopTask("desktop", "desktop-node-2", setOf("code.build"), 1, now))
        assertFalse(store.updateDesktopTask("desktop", "desktop-node-1", "RUNNING", 50, null, null, null, now))
        assertFalse(store.updateDesktopTask("desktop", "desktop-node-2", "SUCCEEDED", 100, null, "wrong", null, now))
        assertTrue(store.updateDesktopTask("desktop", "desktop-node-1", "SUCCEEDED", 100, null, "actual result", null, now))
        assertEquals("actual result", store.desktopTaskResult("desktop"))
        assertEquals(GlafiraTaskState.SUCCEEDED, store.getTask("desktop")?.state)
    }

    @Test fun versionThreeUpgradePreservesTasksAndStartsHonestHistoryBaseline() {
        store.enqueue(task("legacy"))
        val db = store.writableDatabase
        db.execSQL("DROP TRIGGER core_task_history_insert")
        db.execSQL("DROP TRIGGER core_task_history_transition")
        db.execSQL("DROP TRIGGER core_task_terminal_guard")
        db.execSQL("DROP TABLE core_task_history")
        db.version = 3
        store.close()
        store = GlafiraTaskStore(context)
        assertEquals(4, store.writableDatabase.version)
        assertEquals(GlafiraTaskState.QUEUED, store.getTask("legacy")?.state)
        assertEquals("HISTORY_BASELINE", store.taskHistory("legacy", "project").single().reason)
        store.claimNext(2_000)
        assertEquals(2, store.taskHistory("legacy", "project").size)
    }

    @Test fun desktopProjectFilterRunsBeforeLimitAndCannotStarvePermittedProject() {
        repeat(101) { index ->
            store.enqueue(task("other-$index", "desktop.execute", "other-project").copy(priority = 100))
        }
        store.enqueue(task("permitted", "desktop.execute", "permitted-project"))
        assertTrue(store.queuedDesktopTasks().none { it.taskId == "permitted" })
        assertEquals(listOf("permitted"),
            store.queuedDesktopTasks(limit = 1, projectId = "permitted-project").map { it.taskId })
        assertTrue(store.queuedDesktopTasks(projectId = "missing-project").isEmpty())
    }

    @Test fun concurrentSqliteClaimsNeverReturnTheSameTaskToTwoWorkers() {
        val taskCount = 80
        repeat(taskCount) { index -> store.enqueue(task("parallel-$index")) }
        val start = CountDownLatch(1)
        val claimedIds = Collections.synchronizedList(mutableListOf<String>())
        val secondStore = GlafiraTaskStore(context)
        val stores = listOf(store, secondStore)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val workers = (0 until 8).map { workerIndex ->
                val workerStore = stores[workerIndex % stores.size]
                pool.submit {
                    start.await()
                    while (true) {
                        val claimed = workerStore.claimNext(2_000) ?: break
                        claimedIds += claimed.taskId
                        check(workerStore.transition(
                            claimed.taskId,
                            setOf(GlafiraTaskState.RUNNING),
                            GlafiraTaskState.SUCCEEDED,
                            3_000
                        ))
                    }
                }
            }
            start.countDown()
            workers.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            secondStore.close()
        }

        assertEquals(taskCount, claimedIds.size)
        assertEquals(taskCount, claimedIds.toSet().size)
        assertEquals(taskCount, store.listTasks(setOf(GlafiraTaskState.SUCCEEDED)).size)
        repeat(taskCount) { index ->
            assertEquals(
                listOf(GlafiraTaskState.QUEUED, GlafiraTaskState.RUNNING, GlafiraTaskState.SUCCEEDED),
                store.taskHistory("parallel-$index", "project").map { it.toState }.reversed()
            )
        }
    }

    @Test fun processRestartRecoveryRequeuesSafeReadsButQuarantinesUnknownWrites() {
        store.enqueue(task("restart-read", "project.read"))
        store.enqueue(task("restart-write", "message.send"))
        repeat(2) { assertNotNull(store.claimNext(2_000)) }

        store.close()
        store = GlafiraTaskStore(context)
        assertEquals(2, store.recoverInterruptedCoreTasks(3_000, 5))

        assertEquals(GlafiraTaskState.RETRY_SCHEDULED, store.getTask("restart-read")?.state)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, store.getTask("restart-write")?.state)
        assertEquals("restart-read", store.claimNext(4_000)?.taskId)
        assertNull(store.claimNext(4_000))
    }

}
