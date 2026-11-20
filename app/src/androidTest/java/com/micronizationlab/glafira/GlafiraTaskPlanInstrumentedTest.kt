package com.micronizationlab.glafira

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
class GlafiraCoreTaskPlanInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var store: GlafiraTaskStore? = null

    @Before fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        store = GlafiraTaskStore(context)
    }

    @After fun tearDown() {
        store?.close()
        store = null
        context.deleteDatabase(DATABASE_NAME)
    }

    private fun task(id: String) = GlafiraTask(
        taskId = id,
        projectId = "hydrogen-active",
        taskType = "project.read",
        payload = "{}",
        origin = "task-plan-test",
        createdAtMillis = 10_000L,
        updatedAtMillis = 10_000L,
        idempotencyKey = "idem-$id"
    )

    private fun plan(nodes: List<GlafiraCoreTaskPlanNode>) = GlafiraCoreTaskPlan(
        planId = "plan-00000001",
        projectId = "hydrogen-active",
        idempotencyKey = "instruction-00000001",
        nodes = nodes,
        createdAtMillis = 10_000L
    )

    @Test fun planAndForwardDependencyEdgesAreCommittedAtomically() {
        val queue = requireNotNull(store)
        val submitted = plan(listOf(
            GlafiraCoreTaskPlanNode(task("child"), setOf("parent")),
            GlafiraCoreTaskPlanNode(task("parent"))
        ))

        val result = queue.enqueuePlan(submitted)
        assertTrue(result.inserted)
        assertEquals(listOf("child", "parent"), result.plan.nodes.map { it.taskId })
        assertEquals(GlafiraCoreTaskPlanStatus.QUEUED, result.plan.status)
        assertEquals(setOf("parent"), queue.taskDependencies("child"))

        // Despite appearing second in the plan, the predecessor is the only claimable node.
        assertEquals("parent", queue.claimNext(11_000L)?.taskId)
        assertNull(queue.claimNext(11_001L))
        assertTrue(queue.transition(
            "parent", setOf(GlafiraTaskState.RUNNING), GlafiraTaskState.SUCCEEDED,
            12_000L, null, null
        ))
        assertEquals("child", queue.claimNext(12_001L)?.taskId)

        val running = queue.taskPlan("plan-00000001", "hydrogen-active")
        assertNotNull(running)
        assertEquals(GlafiraCoreTaskPlanStatus.RUNNING, running?.status)
        assertTrue(requireNotNull(running).updatedAtMillis >= 12_000L)
    }

    @Test fun planSubmissionIsIdempotentAndProjectScoped() {
        val queue = requireNotNull(store)
        val submitted = plan(listOf(
            GlafiraCoreTaskPlanNode(task("first")),
            GlafiraCoreTaskPlanNode(task("second"), setOf("first"))
        ))
        assertTrue(queue.enqueuePlan(submitted).inserted)
        val replay = queue.enqueuePlan(submitted)
        assertFalse(replay.inserted)
        assertEquals(2, replay.plan.nodes.size)
        assertNull(queue.taskPlan("plan-00000001", "another-project"))
    }

    @Test fun cyclicOrCrossPlanEdgesAreRejectedBeforeAnyRowsAreWritten() {
        val queue = requireNotNull(store)
        val cyclic = plan(listOf(
            GlafiraCoreTaskPlanNode(task("cycle-a"), setOf("cycle-b")),
            GlafiraCoreTaskPlanNode(task("cycle-b"), setOf("cycle-a"))
        ))
        var rejected = false
        try {
            queue.enqueuePlan(cyclic)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("A cyclic plan must be rejected", rejected)
        assertNull(queue.getTask("cycle-a"))
        assertNull(queue.getTask("cycle-b"))
        assertNull(queue.taskPlan("plan-00000001", "hydrogen-active"))
    }

    @Test fun projectPlanJournalCombinesNodeHistoryAndPersistedResults() {
        val queue = requireNotNull(store)
        queue.enqueuePlan(plan(listOf(GlafiraCoreTaskPlanNode(task("journal-root")))))
        assertEquals("journal-root", queue.claimNext(11_000L)?.taskId)
        assertTrue(queue.persistCoreTaskResult(
            "journal-root", setOf(GlafiraTaskState.RUNNING), """{"summary":"ready"}""", 12_000L
        ))

        val journal = queue.taskPlanJournal("plan-00000001", "hydrogen-active")
        assertNotNull(journal)
        assertEquals(GlafiraCoreTaskPlanStatus.SUCCEEDED, journal?.plan?.status)
        val node = requireNotNull(journal).nodes.single()
        assertEquals("""{"summary":"ready"}""", node.result)
        assertTrue(node.history.any { it.toState == GlafiraTaskState.RUNNING })
        assertTrue(node.history.any { it.toState == GlafiraTaskState.SUCCEEDED })
        assertEquals(1, queue.taskPlans("hydrogen-active").size)
    }

    @Test fun desktopNodeInventoryTracksMultipleLiveWorkersAndTheirCapabilities() {
        val queue = requireNotNull(store)
        queue.recordDesktopNode("desktop-node-01", setOf("cpu", "files"), 2, 20_000L)
        queue.recordDesktopNode("desktop-node-02", setOf("gpu", "browser"), 4, 21_000L)

        val nodes = queue.desktopNodes(60_000L, 21_000L)
        assertEquals(listOf("desktop-node-02", "desktop-node-01"), nodes.map { it.nodeId })
        assertEquals(setOf("gpu", "browser"), nodes.first().capabilities)
        assertEquals(4, nodes.first().maxParallelTasks)
    }

    @Test fun failedNodeMakesPlanTerminalAndPreservesSharedTaskHistory() {
        val queue = requireNotNull(store)
        queue.enqueuePlan(plan(listOf(
            GlafiraCoreTaskPlanNode(task("root")),
            GlafiraCoreTaskPlanNode(task("dependent"), setOf("root"))
        )))
        assertEquals("root", queue.claimNext(11_000L)?.taskId)
        assertTrue(queue.transition(
            "root", setOf(GlafiraTaskState.RUNNING), GlafiraTaskState.FAILED,
            12_000L, null, "simulated failure"
        ))
        assertNull(queue.claimNext(12_001L))
        val record = queue.taskPlan("plan-00000001", "hydrogen-active")
        assertEquals(GlafiraCoreTaskPlanStatus.FAILED, record?.status)
        assertEquals(2, record?.completedCount)
        assertEquals(GlafiraTaskState.FAILED, queue.getTask("dependent")?.state)
        assertTrue(queue.taskHistory("root", "hydrogen-active").isNotEmpty())
    }

    companion object {
        private const val DATABASE_NAME = "glafira_core_tasks.db"
    }
}
