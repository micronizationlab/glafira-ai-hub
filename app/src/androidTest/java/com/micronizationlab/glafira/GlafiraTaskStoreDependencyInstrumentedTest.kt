package com.micronizationlab.glafira

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraTaskStoreDependencyInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val stores = mutableListOf<GlafiraTaskStore>()

    @Before fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @After fun tearDown() {
        stores.forEach { runCatching { it.close() } }
        stores.clear()
        context.deleteDatabase(DATABASE_NAME)
    }

    private fun newStore(): GlafiraTaskStore =
        GlafiraTaskStore(context).also(stores::add)

    private fun task(id: String, projectId: String = "project-1") = GlafiraTask(
        taskId = id,
        projectId = projectId,
        taskType = "project.read",
        payload = "{}",
        origin = "instrumentation-test",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "idem-$id"
    )

    private fun enqueue(store: GlafiraTaskStore, item: GlafiraTask) {
        assertTrue(store.enqueue(item).inserted)
    }

    @Test fun dependentTaskCannotBeClaimedUntilEveryPredecessorSucceeds() {
        val store = newStore()
        enqueue(store, task("parent"))
        enqueue(store, task("child"))
        assertTrue(store.addTaskDependencies("child", setOf("parent"), 2_000L))

        val claimedParent = store.claimNext(3_000L)
        assertEquals("parent", claimedParent?.taskId)
        assertNull("Child must remain queued while parent is running", store.claimNext(3_001L))

        assertTrue(store.transition(
            "parent", setOf(GlafiraTaskState.RUNNING), GlafiraTaskState.SUCCEEDED,
            4_000L, null, null
        ))
        assertEquals("child", store.claimNext(4_001L)?.taskId)
    }

    @Test fun failedPredecessorFailsDependentInsteadOfLeavingItQueuedForever() {
        val store = newStore()
        enqueue(store, task("parent"))
        enqueue(store, task("child"))
        assertTrue(store.addTaskDependencies("child", setOf("parent"), 2_000L))
        assertEquals("parent", store.claimNext(3_000L)?.taskId)
        assertTrue(store.transition(
            "parent", setOf(GlafiraTaskState.RUNNING), GlafiraTaskState.FAILED,
            4_000L, null, "test failure"
        ))

        assertNull(store.claimNext(4_001L))
        assertEquals(GlafiraTaskState.FAILED, store.getTask("child")?.state)
        assertEquals("DEPENDENCY_FAILED", store.getTask("child")?.lastError)
    }

    @Test fun dependencyEdgesRejectCyclesAndCrossProjectReferences() {
        val store = newStore()
        enqueue(store, task("a"))
        enqueue(store, task("b"))
        enqueue(store, task("foreign", "project-2"))
        assertTrue(store.addTaskDependencies("a", setOf("b"), 2_000L))

        var cycleRejected = false
        try {
            store.addTaskDependencies("b", setOf("a"), 2_001L)
        } catch (_: IllegalArgumentException) {
            cycleRejected = true
        }
        assertTrue("Transitive cycle must be rejected", cycleRejected)

        var projectRejected = false
        try {
            store.addTaskDependencies("a", setOf("foreign"), 2_002L)
        } catch (_: IllegalArgumentException) {
            projectRejected = true
        }
        assertTrue("Cross-project dependency must be rejected", projectRejected)
        assertEquals(setOf("b"), store.taskDependencies("a"))
    }

    @Test fun independentSqliteConnectionsNeverClaimTheSameTask() {
        val writer = newStore()
        repeat(32) { enqueue(writer, task("parallel-$it")) }
        val workers = (1..8).map { newStore() }
        val pool = Executors.newFixedThreadPool(workers.size)
        try {
            val futures = workers.map { worker ->
                pool.submit<Set<String>> {
                    buildSet {
                        while (true) {
                            val claimed = worker.claimNext(10_000L) ?: break
                            add(claimed.taskId)
                        }
                    }
                }
            }
            val claimedIds = futures.flatMap { it.get(30, TimeUnit.SECONDS) }
            assertEquals(32, claimedIds.size)
            assertEquals(32, claimedIds.toSet().size)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test fun versionFiveDatabaseMigratesDependencyTableWithoutDroppingTasks() {
        val original = newStore()
        enqueue(original, task("preserved"))
        original.writableDatabase.execSQL("DROP TABLE IF EXISTS core_task_dependencies")
        original.writableDatabase.execSQL("PRAGMA user_version = 5")
        original.close()

        val migrated = newStore()
        val tableExists = migrated.readableDatabase.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'core_task_dependencies'",
            null
        ).use { it.moveToFirst() }

        assertTrue(tableExists)
        assertNotNull(migrated.getTask("preserved"))
    }

    companion object {
        private const val DATABASE_NAME = "glafira_core_tasks.db"
    }
}
