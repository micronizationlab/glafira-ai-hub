package com.micronizationlab.glafira

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class GlafiraReviewTraceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-11-21T00:30:00Z")
    private var nextEvent = 0
    private val context = ReviewTraceContext(
        projectId = ProjectIdentifier("project-1"),
        correlationId = CoreIdentifier("correlation-1"),
        executorRoleId = AgentRoleIdentifier("executor-role"),
        reviewerActor = CoreActor("reviewer-agent", CoreActorType.AGENT),
        dataClassification = CoreDataClassification.CONFIDENTIAL
    )

    @Test fun independentReviewerWritesOnlyRedactedTraceMetadata() = runBlocking {
        val store = GlafiraTraceStore(temporary.root)
        val service = service(store) {
            IndependentReviewAssessment(
                verdict = ReviewVerdict.ACCEPT,
                findings = listOf(ReviewFinding("criterion-1", ReviewFindingSeverity.INFO, "Satisfied")),
                reviewerVersion = 3,
                providerId = "provider-b",
                independence = ReviewIndependence.INDEPENDENT_PROVIDER
            )
        }

        val result = service.review(request())

        assertEquals(ReviewVerdict.ACCEPT, result.verdict)
        assertEquals(ReviewIndependence.INDEPENDENT_PROVIDER, result.independence)
        val trace = store.query(TraceQuery(projectId = context.projectId), access())
        assertEquals(
            setOf(CoreTraceEventType.REVIEW_REQUESTED, CoreTraceEventType.REVIEW_ACCEPTED),
            trace.events.map { it.eventType }.toSet()
        )
        assertTrue(trace.events.all { it.redactionApplied && !it.secretValuesStored })
        val raw = temporary.root.resolve("core_trace_v1.json").readText()
        assertFalse(raw.contains("candidate-output-secret@example.com"))
        assertFalse(raw.contains("evidence-with-password=never-store"))
        assertFalse(raw.contains("Satisfied"))
        assertTrue(raw.contains("findingCount"))
    }

    @Test fun sameExecutorAndReviewerRoleFailsBeforeEvaluationAndIsTraced() = runBlocking {
        val store = GlafiraTraceStore(temporary.root)
        var evaluated = false
        val service = service(store) {
            evaluated = true
            validAssessment()
        }

        val failure = runCatching {
            service.review(request().copy(reviewerRoleId = context.executorRoleId))
        }.exceptionOrNull()

        assertTrue(failure is ReviewUnavailableException)
        assertFalse(evaluated)
        val events = store.query(TraceQuery(projectId = context.projectId), access()).events
        assertEquals(2, events.size)
        assertTrue(events.any {
            it.eventType == CoreTraceEventType.REVIEW_UNAVAILABLE &&
                it.resultCode == "REVIEWER_ROLE_NOT_INDEPENDENT"
        })
    }

    @Test fun traceQueriesAreProjectIsolated() = runBlocking {
        val store = GlafiraTraceStore(temporary.root)
        service(store) { validAssessment() }.review(request())

        val foreign = TraceAccessContext(
            CoreActor("owner-2", CoreActorType.OWNER), setOf(ProjectIdentifier("project-2"))
        )
        assertTrue(store.query(TraceQuery(), foreign).events.isEmpty())
        assertTrue(store.query(TraceQuery(projectId = context.projectId), foreign).events.isEmpty())
        assertEquals(2, store.query(TraceQuery(), access()).events.size)
    }

    @Test fun evaluatorFailureRecordsGenericUnavailableWithoutLeakingCause() = runBlocking {
        val store = GlafiraTraceStore(temporary.root)
        val service = service(store) {
            error("provider password=super-secret-value")
        }

        val failure = runCatching { service.review(request()) }.exceptionOrNull()

        assertTrue(failure is ReviewUnavailableException)
        assertEquals("Independent reviewer is unavailable", failure?.message)
        val raw = temporary.root.resolve("core_trace_v1.json").readText()
        assertFalse(raw.contains("super-secret-value"))
        assertTrue(raw.contains("REVIEW_EVALUATOR_UNAVAILABLE"))
    }

    @Test fun traceAppendIsIdempotentButRejectsConflictingEventIdentity() = runBlocking {
        val store = GlafiraTraceStore(temporary.root)
        val event = event("event-fixed", "STARTED")
        store.append(event)
        store.append(event)
        assertEquals(1, store.query(TraceQuery(), access()).events.size)

        val failure = runCatching {
            store.append(event.copy(resultCode = "DIFFERENT"))
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, store.query(TraceQuery(), access()).events.size)
    }

    private fun service(
        store: GlafiraTraceStore,
        evaluation: suspend (ReviewRequest) -> IndependentReviewAssessment
    ) = GlafiraIndependentReviewService(
        evaluator = object : IndependentReviewEvaluator {
            override suspend fun evaluate(request: ReviewRequest) = evaluation(request)
        },
        contextResolver = object : ReviewTraceContextResolver {
            override suspend fun resolve(taskId: TaskIdentifier) = context
        },
        traceService = GlafiraTraceService(store),
        now = { now },
        eventId = { TraceEventIdentifier("event-${++nextEvent}") }
    )

    private fun request() = ReviewRequest(
        taskId = TaskIdentifier("task-1"),
        taskContractRef = "contract-1",
        acceptanceCriteria = listOf("Output satisfies the contract"),
        candidateOutputRef = "candidate-output-secret@example.com",
        evidenceRefs = listOf("evidence-with-password=never-store"),
        safeTraceSummaryRef = "safe-summary-1",
        reviewerRoleId = AgentRoleIdentifier("reviewer-role"),
        reviewPolicyVersion = 2
    )

    private fun validAssessment() = IndependentReviewAssessment(
        ReviewVerdict.ACCEPT,
        emptyList(),
        1,
        null,
        ReviewIndependence.INDEPENDENT_EVALUATOR
    )

    private fun access() = TraceAccessContext(
        CoreActor("owner-1", CoreActorType.OWNER), setOf(context.projectId)
    )

    private fun event(id: String, result: String) = CoreTraceEvent(
        eventId = TraceEventIdentifier(id),
        occurredAt = now,
        recordedAt = now,
        eventType = CoreTraceEventType.TASK_STARTED,
        severity = CoreTraceSeverity.INFO,
        actor = CoreActor("agent-1", CoreActorType.AGENT),
        projectId = context.projectId,
        taskId = TaskIdentifier("task-1"),
        parentTaskId = null,
        correlationId = context.correlationId,
        causationId = null,
        component = "test",
        action = "task.start",
        targetType = "TASK",
        targetId = "task-1",
        resultCode = result,
        approvalRef = null,
        redactionApplied = true
    )
}
