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
class GlafiraTaskPlanInstrumentedTest {
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

    private fun plan(nodes: List<GlafiraTaskPlanNode>) = GlafiraTaskPlan(
        planId = "plan-00000001",
        projectId = "hydrogen-active",
        idempotencyKey = "instruction-00000001",
        nodes = nodes,
        createdAtMillis = 10_000L
    )

    @Test fun planAndForwardDependencyEdgesAreCommittedAtomically() {
        val queue = requireNotNull(store)
        val submitted = plan(listOf(
            GlafiraTaskPlanNode(task("child"), setOf("parent")),
            GlafiraTaskPlanNode(task("parent"))
        ))

        val result = queue.enqueuePlan(submitted)
        assertTrue(result.inserted)
        assertEquals(listOf("child", "parent"), result.plan.nodes.map { it.taskId })
        assertEquals(GlafiraTaskPlanStatus.QUEUED, result.plan.status)
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
        assertEquals(GlafiraTaskPlanStatus.RUNNING, running?.status)
        assertTrue(requireNotNull(running).updatedAtMillis >= 12_000L)
    }

    @Test fun planSubmissionIsIdempotentAndProjectScoped() {
        val queue = requireNotNull(store)
        val submitted = plan(listOf(
            GlafiraTaskPlanNode(task("first")),
            GlafiraTaskPlanNode(task("second"), setOf("first"))
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
            GlafiraTaskPlanNode(task("cycle-a"), setOf("cycle-b")),
            GlafiraTaskPlanNode(task("cycle-b"), setOf("cycle-a"))
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

    @Test fun failedNodeMakesPlanTerminalAndPreservesSharedTaskHistory() {
        val queue = requireNotNull(store)
        queue.enqueuePlan(plan(listOf(
            GlafiraTaskPlanNode(task("root")),
            GlafiraTaskPlanNode(task("dependent"), setOf("root"))
        )))
        assertEquals("root", queue.claimNext(11_000L)?.taskId)
        assertTrue(queue.transition(
            "root", setOf(GlafiraTaskState.RUNNING), GlafiraTaskState.FAILED,
            12_000L, null, "simulated failure"
        ))
        assertNull(queue.claimNext(12_001L))
        val record = queue.taskPlan("plan-00000001", "hydrogen-active")
        assertEquals(GlafiraTaskPlanStatus.FAILED, record?.status)
        assertEquals(2, record?.completedCount)
        assertEquals(GlafiraTaskState.FAILED, queue.getTask("dependent")?.state)
        assertTrue(queue.taskHistory("root", "hydrogen-active").isNotEmpty())
    }

    companion object {
        private const val DATABASE_NAME = "glafira_core_tasks.db"
    }
}
