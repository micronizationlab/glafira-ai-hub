package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraTaskCancellationInstrumentedTest {
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
        projectId = "cancel-project",
        taskType = "project.read",
        payload = "{}",
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "cancel-project:$id"
    )

    @Test
    fun ownerCancellationIsAtomicAndCancelledTaskIsNeverClaimed() {
        store.enqueue(task("queued-task"))

        assertTrue(store.cancelUnclaimedTask("queued-task", 2_000L))
        assertEquals(GlafiraTaskState.CANCELLED, store.listTasks().single().state)
        assertEquals("OWNER_CANCELLED", store.listTasks().single().lastError)
        assertFalse(store.cancelUnclaimedTask("queued-task", 3_000L))
        assertNull(store.claimNext(4_000L))
    }

    @Test
    fun runningTaskCannotBeCancelledByQueueAction() {
        store.enqueue(task("running-task"))
        assertEquals("running-task", store.claimNext(2_000L)?.taskId)

        assertFalse(store.cancelUnclaimedTask("running-task", 3_000L))
        assertEquals(GlafiraTaskState.RUNNING, store.listTasks().single().state)
    }

    @Test
    fun waitingStatesCanBeCancelledBeforeDispatch() {
        listOf(
            GlafiraTaskState.WAITING_FOR_NETWORK,
            GlafiraTaskState.WAITING_FOR_OWNER
        ).forEachIndexed { index, state ->
            val id = "waiting-task-$index"
            store.enqueue(task(id))
            assertTrue(store.transition(id, setOf(GlafiraTaskState.QUEUED), state, 2_000L))
            assertTrue(store.cancelUnclaimedTask(id, 3_000L))
            assertEquals(GlafiraTaskState.CANCELLED, store.listTasks().first { it.taskId == id }.state)
        }
    }
}
