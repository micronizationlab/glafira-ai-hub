package com.micronizationlab.glafira

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraCoreConcurrencyTest {
    private class ConcurrentQueue(initial: List<GlafiraTask>) :
        GlafiraTaskQueue, GlafiraInterruptedTaskRecovery, GlafiraCoreTaskResultPersistence {
        private val tasks = initial.associateBy { it.taskId }.toMutableMap()
        private val results = mutableMapOf<String, String>()
        var recoveryCalls = 0
            private set
        var failResultPersistence = false

        @Synchronized
        override fun enqueue(task: GlafiraTask): GlafiraTaskEnqueueResult {
            val existing = tasks[task.taskId]
            if (existing != null) return GlafiraTaskEnqueueResult(existing, false)
            tasks[task.taskId] = task
            return GlafiraTaskEnqueueResult(task, true)
        }

        @Synchronized
        override fun getTask(taskId: String): GlafiraTask? = tasks[taskId]

        @Synchronized
        override fun claimNext(nowMillis: Long): GlafiraTask? {
            val candidate = tasks.values.firstOrNull {
                it.state == GlafiraTaskState.QUEUED ||
                    (it.state == GlafiraTaskState.RETRY_SCHEDULED &&
                        (it.retryAtMillis == null || it.retryAtMillis <= nowMillis))
            } ?: return null
            val claimed = candidate.copy(
                state = GlafiraTaskState.RUNNING,
                attemptCount = candidate.attemptCount + 1,
                updatedAtMillis = nowMillis,
                retryAtMillis = null
            )
            tasks[candidate.taskId] = claimed
            return claimed
        }

        @Synchronized
        override fun transition(
            taskId: String,
            expectedStates: Set<GlafiraTaskState>,
            nextState: GlafiraTaskState,
            nowMillis: Long,
            retryAtMillis: Long?,
            lastError: String?
        ): Boolean {
            val current = tasks[taskId] ?: return false
            if (current.state !in expectedStates ||
                !expectedStates.all { GlafiraTaskPolicy.canTransition(it, nextState) }
            ) return false
            tasks[taskId] = current.copy(
                state = nextState,
                updatedAtMillis = nowMillis,
                retryAtMillis = retryAtMillis,
                lastError = lastError
            )
            return true
        }

        @Synchronized
        override fun recoverInterruptedCoreTasks(nowMillis: Long, maxRetries: Int): Int {
            recoveryCalls += 1
            return 0
        }

        @Synchronized
        override fun persistCoreTaskResult(
            taskId: String,
            expectedStates: Set<GlafiraTaskState>,
            result: String,
            nowMillis: Long
        ): Boolean {
            if (failResultPersistence) throw IllegalStateException("simulated result-store failure")
            val existing = results[taskId]
            if (existing != null) return existing == result &&
                tasks[taskId]?.state == GlafiraTaskState.SUCCEEDED
            if (!transition(taskId, expectedStates, GlafiraTaskState.SUCCEEDED, nowMillis)) return false
            results[taskId] = result
            return true
        }

        @Synchronized
        fun result(taskId: String): String? = results[taskId]

        @Synchronized
        fun completedCount(): Int = tasks.values.count { it.state == GlafiraTaskState.SUCCEEDED }
    }

    private fun task(id: String) = GlafiraTask(
        taskId = id,
        projectId = "project-1",
        taskType = "project.read",
        payload = "{}",
        origin = "test",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "idem-$id"
    )

    @Test
    fun independentTasksRunConcurrentlyAndAreClaimedOnlyOnce() = runBlocking {
        val queue = ConcurrentQueue((1..5).map { task("task-$it") })
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val claimedIds = ConcurrentHashMap.newKeySet<String>()
        val twoHandlersStarted = CompletableDeferred<Unit>()
        val releaseHandlers = CompletableDeferred<Unit>()

        val executor = GlafiraCoreTaskExecutor(
            queue = queue,
            authorizer = GlafiraTaskAuthorizer { _, _ ->
                GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
            },
            handlers = listOf("project.read" to GlafiraTaskHandler { item ->
                assertTrue("Task claimed more than once: ${item.taskId}", claimedIds.add(item.taskId))
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                if (current == 2) twoHandlersStarted.complete(Unit)
                try {
                    releaseHandlers.await()
                    GlafiraTaskExecutionOutcome.Succeeded(item.taskId)
                } finally {
                    active.decrementAndGet()
                }
            }),
            dispatcher = Dispatchers.Default,
            clock = { 10_000L },
            runtimeBudget = { GlafiraRuntimeBudget(maxConcurrentTasks = 2) }
        )

        val executions = (1..4).map { async { executor.executeNext() } }
        withTimeout(5_000L) { twoHandlersStarted.await() }
        assertEquals(2, maximumActive.get())
        // A new caller arriving while handlers are live must reuse the completed recovery epoch.
        val fifthExecution = async { executor.executeNext() }
        yield()
        releaseHandlers.complete(Unit)
        val results = withTimeout(5_000L) { (executions + fifthExecution).awaitAll() }

        assertEquals(5, results.count { it.status == GlafiraTaskExecutionStatus.SUCCEEDED })
        assertEquals(5, claimedIds.size)
        assertEquals(5, queue.completedCount())
        (1..5).forEach { index -> assertEquals("task-$index", queue.result("task-$index")) }
        assertEquals(1, queue.recoveryCalls)
        assertEquals(0, active.get())
    }

    @Test
    fun maintenanceWaitsForHandlersAndBlocksNewExecutionLeases(): Unit = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val maintenanceStarted = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val first = async {
            GlafiraCoreMaintenanceGate.withExecution {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()

        val maintenance = async {
            GlafiraCoreMaintenanceGate.withExclusive {
                maintenanceStarted.complete(Unit)
                releaseMaintenance.await()
            }
        }
        yield()
        assertFalse(maintenanceStarted.isCompleted)

        val second = async {
            GlafiraCoreMaintenanceGate.withExecution {
                secondStarted.complete(Unit)
            }
        }
        yield()
        assertFalse(secondStarted.isCompleted)

        releaseFirst.complete(Unit)
        withTimeout(5_000L) { maintenanceStarted.await() }
        assertFalse(secondStarted.isCompleted)
        releaseMaintenance.complete(Unit)
        maintenance.await()
        withTimeout(5_000L) { secondStarted.await() }
        first.await()
        second.await()
    }
    @Test
    fun oneHandlerFailureDoesNotCancelAnotherParallelHandler() = runBlocking {
        val queue = ConcurrentQueue(listOf(task("bad"), task("good")))
        val active = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val executor = GlafiraCoreTaskExecutor(
            queue = queue,
            authorizer = GlafiraTaskAuthorizer { _, _ ->
                GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
            },
            handlers = listOf("project.read" to GlafiraTaskHandler { item ->
                val current = active.incrementAndGet()
                if (current == 2) bothStarted.complete(Unit)
                try {
                    bothStarted.await()
                    if (item.taskId == "bad") throw IllegalStateException("simulated worker failure")
                    GlafiraTaskExecutionOutcome.Succeeded("independent task completed")
                } finally {
                    active.decrementAndGet()
                }
            }),
            dispatcher = Dispatchers.Default,
            clock = { 10_000L },
            runtimeBudget = { GlafiraRuntimeBudget(maxConcurrentTasks = 2) }
        )

        val results = withTimeout(5_000L) {
            (1..2).map { async { executor.executeNext() } }.awaitAll()
        }

        assertEquals(1, results.count { it.status == GlafiraTaskExecutionStatus.RETRY_SCHEDULED })
        assertEquals(1, results.count { it.status == GlafiraTaskExecutionStatus.SUCCEEDED })
        assertEquals(GlafiraTaskState.RETRY_SCHEDULED, queue.getTask("bad")?.state)
        assertEquals(GlafiraTaskState.SUCCEEDED, queue.getTask("good")?.state)
        assertEquals("independent task completed", queue.result("good"))
        assertNull(queue.result("bad"))
        assertEquals(0, active.get())
    }

    @Test
    fun failedResultCommitQuarantinesPossibleWriteInsteadOfReplayingIt() = runBlocking {
        val writeTask = task("write-task").copy(taskType = "message.send")
        val queue = ConcurrentQueue(listOf(writeTask)).apply { failResultPersistence = true }
        val executor = GlafiraCoreTaskExecutor(
            queue = queue,
            authorizer = GlafiraTaskAuthorizer { _, _ ->
                GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
            },
            handlers = listOf("message.send" to GlafiraTaskHandler {
                GlafiraTaskExecutionOutcome.Succeeded("write completed before result-store failure")
            }),
            dispatcher = Dispatchers.Default,
            clock = { 10_000L },
            runtimeBudget = { GlafiraRuntimeBudget(maxConcurrentTasks = 1) }
        )

        val result = executor.executeNext()

        assertEquals(GlafiraTaskExecutionStatus.WAITING_FOR_OWNER, result.status)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, queue.getTask("write-task")?.state)
        assertNull(queue.result("write-task"))
    }

    @Test
    fun externalWriteHandlerExceptionIsQuarantinedInsteadOfRetried() = runBlocking {
        val queue = ConcurrentQueue(listOf(task("external-write").copy(taskType = "message.send")))
        val executor = GlafiraCoreTaskExecutor(
            queue = queue,
            authorizer = GlafiraTaskAuthorizer { _, _ ->
                GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
            },
            handlers = listOf("message.send" to GlafiraTaskHandler {
                throw IllegalStateException("simulated failure after possible external send")
            }),
            dispatcher = Dispatchers.Default,
            clock = { 10_000L },
            runtimeBudget = { GlafiraRuntimeBudget(maxConcurrentTasks = 1) }
        )

        val result = executor.executeNext()

        assertEquals(GlafiraTaskExecutionStatus.WAITING_FOR_OWNER, result.status)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, queue.getTask("external-write")?.state)
        assertNull(queue.result("external-write"))
    }

    @Test
    fun cancellationDuringExternalWriteQuarantinesTaskForOwner() = runBlocking {
        val queue = ConcurrentQueue(listOf(task("cancelled-write").copy(taskType = "message.send")))
        val handlerStarted = CompletableDeferred<Unit>()
        val executor = GlafiraCoreTaskExecutor(
            queue = queue,
            authorizer = GlafiraTaskAuthorizer { _, _ ->
                GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
            },
            handlers = listOf("message.send" to GlafiraTaskHandler {
                handlerStarted.complete(Unit)
                awaitCancellation()
            }),
            dispatcher = Dispatchers.Default,
            clock = { 10_000L },
            runtimeBudget = { GlafiraRuntimeBudget(maxConcurrentTasks = 1) }
        )

        val worker = launch { executor.executeNext() }
        withTimeout(5_000L) { handlerStarted.await() }
        worker.cancelAndJoin()

        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, queue.getTask("cancelled-write")?.state)
        assertNull(queue.result("cancelled-write"))
    }

}
