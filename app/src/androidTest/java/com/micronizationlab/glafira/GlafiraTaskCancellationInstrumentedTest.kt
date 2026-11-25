package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraTaskCancellationInstrumentedTest {
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
    private fun task(id: String) = GlafiraTask(
        taskId = id, projectId = "cancel-project", taskType = "project.read",
        payload = "{}", origin = "owner", createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L, idempotencyKey = "cancel-project:$id"
    )

    @Test fun cancellationRecordsHistoryFailsChildrenAndExcludesClaim() {
        store.enqueue(task("parent"))
        store.enqueueWithDependencies(task("child"), setOf("parent"), 1_500L)
        assertTrue(store.cancelUnclaimedTask("parent", "cancel-project", 2_000L))
        assertEquals(GlafiraTaskState.CANCELLED, store.getTask("parent")?.state)
        assertEquals(GlafiraTaskState.FAILED, store.getTask("child")?.state)
        store.writableDatabase.rawQuery(
            "SELECT 1 FROM core_task_history WHERE task_id=? AND to_state='cancelled' AND reason='OWNER_CANCELLED'",
            arrayOf("parent")
        ).use { assertTrue(it.moveToFirst()) }
        assertFalse(store.cancelUnclaimedTask("parent", "cancel-project", 3_000L))
        assertNull(store.claimNext(4_000L))
    }

    @Test fun runningOrWrongProjectTaskCannotBeCancelled() {
        store.enqueue(task("running"))
        assertEquals("running", store.claimNext(2_000L)?.taskId)
        assertFalse(store.cancelUnclaimedTask("running", "cancel-project", 3_000L))
        assertEquals(GlafiraTaskState.RUNNING, store.getTask("running")?.state)
        store.enqueue(task("wrong-project"))
        assertFalse(store.cancelUnclaimedTask("wrong-project", "another-project", 4_000L))
        assertEquals(GlafiraTaskState.QUEUED, store.getTask("wrong-project")?.state)
    }

    @Test fun waitingAndRetryScheduledTasksCanBeCancelled() {
        listOf(GlafiraTaskState.WAITING_FOR_NETWORK, GlafiraTaskState.WAITING_FOR_OWNER).forEachIndexed { i, state ->
            val id = "waiting-$i"
            store.enqueue(task(id))
            assertTrue(store.transition(id, setOf(GlafiraTaskState.QUEUED), state, 2_000L))
            assertTrue(store.cancelUnclaimedTask(id, "cancel-project", 3_000L))
        }
        store.enqueue(task("retry"))
        assertEquals("retry", store.claimNext(4_000L)?.taskId)
        assertTrue(store.transition("retry", setOf(GlafiraTaskState.RUNNING),
            GlafiraTaskState.RETRY_SCHEDULED, 4_500L, retryAtMillis = 6_000L))
        assertTrue(store.cancelUnclaimedTask("retry", "cancel-project", 5_000L))
        assertEquals(GlafiraTaskState.CANCELLED, store.getTask("retry")?.state)
    }

    @Test fun concurrentClaimAndCancelCannotOverwriteEachOther() {
        val secondStore = GlafiraTaskStore(context)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            store.enqueue(task("race"))
            val cancel = pool.submit<Boolean> {
                gate.await()
                store.cancelUnclaimedTask("race", "cancel-project", 2_000L)
            }
            val claim = pool.submit<GlafiraTask?> { gate.await(); secondStore.claimNext(2_000L) }
            gate.countDown()
            val cancelled = cancel.get(10, TimeUnit.SECONDS)
            val claimed = claim.get(10, TimeUnit.SECONDS)
            val finalState = store.getTask("race")?.state
            assertTrue((cancelled && claimed == null && finalState == GlafiraTaskState.CANCELLED) ||
                (!cancelled && claimed?.taskId == "race" && finalState == GlafiraTaskState.RUNNING))
        } finally {
            pool.shutdownNow()
            secondStore.close()
        }
    }
}
