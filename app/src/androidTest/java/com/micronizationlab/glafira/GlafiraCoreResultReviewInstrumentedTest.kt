package com.micronizationlab.glafira

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
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
class GlafiraCoreResultReviewInstrumentedTest {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().targetContext }
    private lateinit var taskStore: GlafiraTaskStore
    private lateinit var traceStore: GlafiraCoreTraceStore

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        taskStore = GlafiraTaskStore(context)
        traceStore = GlafiraCoreTraceStore(taskStore)
    }

    @After
    fun tearDown() {
        taskStore.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun acceptedReviewPersistsResultAndRedactedSqliteTrace() = runBlocking {
        val canary = "RESULT_REVIEW_PRIVATE_CANARY"
        val task = task("review-accepted")
        assertTrue(taskStore.enqueue(task).inserted)

        val result = executor("{\"ok\":true,\"private\":\"$canary\"}").executeNext()

        assertEquals(GlafiraTaskExecutionStatus.SUCCEEDED, result.status)
        assertEquals(GlafiraTaskState.SUCCEEDED, taskStore.getTask(task.taskId)?.state)
        assertNotNull(taskStore.coreTaskResult(task.taskId, PROJECT_ID))
        val reviewEvents = reviewEvents(task.taskId)
        assertEquals(
            setOf(
                GlafiraCoreTraceEventType.REVIEW_REQUESTED,
                GlafiraCoreTraceEventType.REVIEW_ACCEPTED
            ),
            reviewEvents.map { it.eventType }.toSet()
        )
        assertTrue(reviewEvents.all { it.secretValuesStored.not() && it.redactionApplied })
        assertFalse(reviewEvents.any { event ->
            listOfNotNull(event.actionCode, event.resultStatus, event.targetId)
                .any { it.contains(canary) }
        })
    }

    @Test
    fun rejectedReviewQuarantinesTaskWithoutPersistingResult() = runBlocking {
        val task = task("review-rejected")
        assertTrue(taskStore.enqueue(task).inserted)

        val result = executor("not-json").executeNext()

        assertEquals(GlafiraTaskExecutionStatus.WAITING_FOR_OWNER, result.status)
        assertEquals(GlafiraTaskState.WAITING_FOR_OWNER, taskStore.getTask(task.taskId)?.state)
        assertEquals("CORE_RESULT_REVIEW_REJECTED", taskStore.getTask(task.taskId)?.lastError)
        assertNull(taskStore.coreTaskResult(task.taskId, PROJECT_ID))
        assertEquals(
            setOf(
                GlafiraCoreTraceEventType.REVIEW_REQUESTED,
                GlafiraCoreTraceEventType.REVIEW_REJECTED
            ),
            reviewEvents(task.taskId).map { it.eventType }.toSet()
        )
    }

    private fun executor(summary: String) = GlafiraCoreTaskExecutor(
        queue = taskStore,
        authorizer = GlafiraTaskAuthorizer { _, _ ->
            GlafiraTaskAuthorizationResult(GlafiraTaskAuthorizationStatus.AUTHORIZED)
        },
        handlers = listOf("project.read" to GlafiraTaskHandler {
            GlafiraTaskExecutionOutcome.Succeeded(summary)
        }),
        resultReviewer = GlafiraCoreResultReviewFactory.create(taskStore, traceStore),
        clock = { 2_000L }
    )

    private fun reviewEvents(taskId: String) = traceStore.query(
        GlafiraCoreTraceQuery(
            projectId = PROJECT_ID,
            taskId = taskId,
            eventTypes = setOf(
                GlafiraCoreTraceEventType.REVIEW_REQUESTED,
                GlafiraCoreTraceEventType.REVIEW_ACCEPTED,
                GlafiraCoreTraceEventType.REVIEW_REJECTED
            )
        ),
        GlafiraCoreTraceAccessContext(PROJECT_ID, setOf(taskId))
    ).events

    private fun task(taskId: String) = GlafiraTask(
        taskId = taskId,
        projectId = PROJECT_ID,
        taskType = "project.read",
        payload = "{}",
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "idem-$taskId"
    )

    private companion object {
        const val DATABASE_NAME = "glafira_core_tasks.db"
        const val PROJECT_ID = "review-project"
    }
}
