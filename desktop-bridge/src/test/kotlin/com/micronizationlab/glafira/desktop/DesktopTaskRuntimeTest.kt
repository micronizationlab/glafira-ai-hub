package com.micronizationlab.glafira.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DesktopTaskRuntimeTest {
    @get:Rule val temp = TemporaryFolder()
    private var now = 1_800_000_000_000L

    private fun task(id: String, type: String = "analysis.document") = DesktopTaskEnvelope(
        taskId = id, projectId = "project-a", taskType = type,
        instruction = "Perform a bounded test task.", contextRefs = emptyList(),
        requiredCapabilities = emptySet(), requiredScopes = setOf("project.read"),
        priority = 5, createdAt = now, expiresAt = now + 60_000,
        idempotencyKey = "idempotency-$id-unique"
    )

    @Test fun dispatchesIndependentTasksInParallelAndPublishesResults() {
        val queue = DesktopTaskQueue(temp.root.toPath().resolve("tasks.json")) { now }
        queue.enqueue(task("task_one"))
        queue.enqueue(task("task_two"))
        val started = CountDownLatch(2)
        val finish = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val results = java.util.Collections.synchronizedList(mutableListOf<DesktopAgentResult>())
        val handler = DesktopAgentExecutor {
            val count = active.incrementAndGet()
            peak.updateAndGet { old -> maxOf(old, count) }
            started.countDown()
            check(finish.await(3, TimeUnit.SECONDS))
            active.decrementAndGet()
            "done:${it.taskId}"
        }
        DesktopTaskRuntime(queue, mapOf("analysis.document" to handler), maxParallelTasks = 2, onResult = results::add)
            .use { runtime ->
                assertEquals(2, runtime.dispatchAvailable())
                assertTrue(started.await(3, TimeUnit.SECONDS))
                assertTrue(peak.get() >= 2)
                finish.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (results.size < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            }
        assertEquals(2, results.size)
        assertTrue(results.all { it.state == DesktopTaskState.SUCCEEDED })
    }

    @Test fun runtimeMarksOrphanedRunningTasksFailedBeforeDispatch() {
        val queue = DesktopTaskQueue(temp.root.toPath().resolve("tasks-recovery.json")) { now }
        queue.enqueue(task("task_interrupted"))
        queue.claimNext()
        queue.transition("task_interrupted", DesktopTaskState.ACCEPTED)
        queue.transition("task_interrupted", DesktopTaskState.RUNNING)
        val executions = AtomicInteger()

        DesktopTaskRuntime(
            queue,
            mapOf("analysis.document" to DesktopAgentExecutor {
                executions.incrementAndGet()
                "should-not-run"
            })
        ).use { runtime ->
            assertEquals(0, runtime.dispatchAvailable())
        }

        val recovered = requireNotNull(queue.get("task_interrupted"))
        assertEquals(DesktopTaskState.FAILED, recovered.state)
        assertEquals("WORKER_INTERRUPTED", recovered.errorCode)
        assertEquals(0, executions.get())
    }

    @Test fun missingAgentHandlerFailsClosed() {
        val queue = DesktopTaskQueue(temp.root.toPath().resolve("tasks.json")) { now }
        queue.enqueue(task("task_missing"))
        val results = mutableListOf<DesktopAgentResult>()
        DesktopTaskRuntime(queue, emptyMap(), onResult = results::add).use { runtime ->
            // The task is consumed and failed immediately; no worker was started.
            assertEquals(0, runtime.dispatchAvailable())
        }
        assertEquals(DesktopTaskState.FAILED, queue.get("task_missing")?.state)
        assertEquals("NO_AGENT_HANDLER", results.single().errorCode)
    }
    @Test fun closeDrainsAnAlreadyRunningTaskBeforeReturning() {
        val queue = DesktopTaskQueue(temp.root.toPath().resolve("tasks-close.json")) { now }
        queue.enqueue(task("task_close"))
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val runtime = DesktopTaskRuntime(
            queue,
            mapOf("analysis.document" to DesktopAgentExecutor {
                started.countDown()
                check(finish.await(3, TimeUnit.SECONDS))
                "finished:${it.taskId}"
            })
        )
        assertEquals(1, runtime.dispatchAvailable())
        assertTrue(started.await(3, TimeUnit.SECONDS))

        val closeEntered = CountDownLatch(1)
        val closer = Thread {
            closeEntered.countDown()
            runtime.close()
        }
        closer.start()
        assertTrue(closeEntered.await(1, TimeUnit.SECONDS))
        finish.countDown()
        closer.join(3_000)

        assertFalse("close() should return after the worker drains.", closer.isAlive)
        assertEquals(DesktopTaskState.SUCCEEDED, queue.get("task_close")?.state)
        assertEquals("finished:task_close", queue.get("task_close")?.result)
    }

}
