package com.micronizationlab.glafira

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraCoreTaskExecutorTest {
    private class FakeQueue(initial: GlafiraTask? = null) : GlafiraTaskQueue {
        var task: GlafiraTask? = initial
        var claimed: GlafiraTask? = null
        var lastRetryAt: Long? = null

        override fun enqueue(task: GlafiraTask) = GlafiraTaskEnqueueResult(task, true)
        override fun getTask(taskId: String): GlafiraTask? = task?.takeIf { it.taskId == taskId }

        override fun claimNext(nowMillis: Long): GlafiraTask? {
            val candidate = task ?: return null
            if (candidate.state != GlafiraTaskState.QUEUED &&
                candidate.state != GlafiraTaskState.RETRY_SCHEDULED
            ) return null
            val running = candidate.copy(
                state = GlafiraTaskState.RUNNING,
                attemptCount = candidate.attemptCount + 1,
                updatedAtMillis = nowMillis,
                retryAtMillis = null
            )
            task = running
            claimed = running
            return running
        }

        override fun transition(
            taskId: String,
            expectedStates: Set<GlafiraTaskState>,
            nextState: GlafiraTaskState,
            nowMillis: Long,
            retryAtMillis: Long?,
            lastError: String?
        ): Boolean {
            val current = task ?: return false
            if (current.taskId != taskId || current.state !in expectedStates) return false
            if (!expectedStates.all { GlafiraTaskPolicy.canTransition(it, nextState) }) return false
            lastRetryAt = retryAtMillis
            task = current.copy(
                state = nextState,
                updatedAtMillis = nowMillis,
                retryAtMillis = retryAtMillis,
                lastError = lastError
            )
            return true
        }
    }

    private fun task() = GlafiraTask(
        taskId = "task-1",
        projectId = "project-1",
        taskType = "project.read",
        payload = "{}",
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "project-1:task-1"
    )

    private fun executor(
        queue: FakeQueue,
        authorization: GlafiraTaskAuthorizationStatus = GlafiraTaskAuthorizationStatus.AUTHORIZED,
        outcome: GlafiraTaskExecutionOutcome = GlafiraTaskExecutionOutcome.Succeeded(),
        clock: () -> Long = { 10_000L }
    ) = GlafiraCoreTaskExecutor(
        queue = queue,
        authorizer = GlafiraTaskAuthorizer { _, _ ->
            GlafiraTaskAuthorizationResult(authorization, "test-policy")
        },
        handlers = listOf("project.read" to GlafiraTaskHandler { outcome }),
        clock = clock,
        baseRetryDelayMillis = 1_000L,
        maxRetryDelayMillis = 8_000L
    )

    @Test
    fun emptyQueueReturnsIdle() = runBlocking {
        val result = executor(FakeQueue(null)).executeNext()
        assertEquals(GlafiraTaskExecutionStatus.IDLE, result.status)
        assertNull(result.taskId)
    }

    @Test
    fun authorizedTaskRunsAndIsMarkedSucceeded() = runBlocking {
        val queue = FakeQueue(task())
        val result = executor(queue).executeNext()
        assertEquals(GlafiraTaskExecutionStatus.SUCCEEDED, result.status)
        assertEquals(GlafiraTaskState.SUCCEEDED, queue.task?.state)
    }

    @Test
    fun ownerApprovalIsRequiredBeforeHandlerRuns() = runBlocking {
        val queue = FakeQueue(task())
        var handlerCalled = false
        val worker = GlafiraCoreTaskExecutor(
            queue = queue,
            authorizer = GlafiraTaskAuthorizer { _, _ ->
                GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER)
            },
            handlers = listOf("project.read" to GlafiraTaskHandler {
                handlerCalled = true
                GlafiraTaskExecutionOutcome.Succeeded()
            })
        )
        val result = worker.executeNext()
        assertEquals(GlafiraTaskExecutionStatus.WAITING_FOR_OWNER, result.status)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, queue.task?.state)
        assertTrue(!handlerCalled)
    }

    @Test
    fun retryableFailureSchedulesBoundedRetry() = runBlocking {
        val queue = FakeQueue(task())
        val result = executor(
            queue,
            outcome = GlafiraTaskExecutionOutcome.RetryableFailure("temporary"),
            clock = { 10_000L }
        ).executeNext()
        assertEquals(GlafiraTaskExecutionStatus.RETRY_SCHEDULED, result.status)
        assertEquals(GlafiraTaskState.RETRY_SCHEDULED, queue.task?.state)
        assertEquals(11_000L, queue.lastRetryAt)
    }

    @Test
    fun permanentFailureDoesNotRetry() = runBlocking {
        val queue = FakeQueue(task())
        val result = executor(
            queue,
            outcome = GlafiraTaskExecutionOutcome.PermanentFailure("invalid")
        ).executeNext()
        assertEquals(GlafiraTaskExecutionStatus.FAILED, result.status)
        assertEquals(GlafiraTaskState.FAILED, queue.task?.state)
        assertNull(queue.lastRetryAt)
    }

    @Test
    fun handlerExceptionIsPersistedAsRetryScheduled() = runBlocking {
        val queue = FakeQueue(task())
        val worker = GlafiraCoreTaskExecutor(
            queue = queue,
            authorizer = GlafiraTaskAuthorizer { _, _ ->
                GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
            },
            handlers = listOf("project.read" to GlafiraTaskHandler {
                throw IllegalStateException("temporary failure")
            }),
            clock = { 20_000L },
            baseRetryDelayMillis = 1_000L,
            maxRetryDelayMillis = 8_000L
        )
        val result = worker.executeNext()
        assertEquals(GlafiraTaskExecutionStatus.RETRY_SCHEDULED, result.status)
        assertEquals(GlafiraTaskState.RETRY_SCHEDULED, queue.task?.state)
        assertEquals(21_000L, queue.lastRetryAt)
        assertEquals("HANDLER_ERROR:IllegalStateException", queue.task?.lastError)
    }

    @Test
    fun waitingForNetworkIsPersistedWithoutRetryTimestamp() = runBlocking {
        val queue = FakeQueue(task())
        val result = executor(queue, outcome = GlafiraTaskExecutionOutcome.WaitingForNetwork).executeNext()
        assertEquals(GlafiraTaskExecutionStatus.WAITING_FOR_NETWORK, result.status)
        assertEquals(GlafiraTaskState.WAITING_FOR_NETWORK, queue.task?.state)
        assertNull(queue.task?.retryAtMillis)
    }

    @Test
    fun retryLimitMovesTaskToFailed() = runBlocking {
        val exhausted = task().copy(attemptCount = 5)
        val queue = FakeQueue(exhausted)
        val result = executor(
            queue,
            outcome = GlafiraTaskExecutionOutcome.RetryableFailure("still failing")
        ).executeNext()
        assertEquals(GlafiraTaskExecutionStatus.FAILED, result.status)
        assertEquals(GlafiraTaskState.FAILED, queue.task?.state)
        assertEquals("RETRY_LIMIT_REACHED:still failing", queue.task?.lastError)
    }

    @Test
    fun ownerApprovalWaitIsDurableInQueueState() = runBlocking {
        val queue = FakeQueue(task())
        val result = executor(
            queue,
            authorization = GlafiraTaskAuthorizationStatus.WAITING_FOR_OWNER
        ).executeNext()
        assertEquals(GlafiraTaskExecutionStatus.WAITING_FOR_OWNER, result.status)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, queue.getTask("task-1")?.state)
        assertNull(queue.task?.retryAtMillis)
    }

    @Test
    fun onlyRegisteredToolsCanHaveHandlers() {
        val queue = FakeQueue(task())
        val error = runCatching {
            GlafiraCoreTaskExecutor(
                queue = queue,
                authorizer = GlafiraTaskAuthorizer { _, _ ->
                    GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
                },
                handlers = listOf("arbitrary.shell" to GlafiraTaskHandler {
                    GlafiraTaskExecutionOutcome.Succeeded()
                })
            )
        }.exceptionOrNull()
        assertNotNull(error)
    }
}
