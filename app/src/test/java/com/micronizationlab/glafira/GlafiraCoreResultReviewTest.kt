package com.micronizationlab.glafira

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class GlafiraCoreResultReviewTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun independentEvaluatorAcceptsBoundedJsonAndTraceContainsNoCandidate() = runBlocking {
        val canary = "SECRET_REVIEW_CANDIDATE_CANARY"
        val candidate = "{\"status\":\"ok\",\"private\":\"$canary\"}"
        val candidates = GlafiraCoreReviewCandidateRegistry()
        val trace = GlafiraTraceStore(temporary.root)
        val reviewer = GlafiraIndependentCoreResultReviewer(
            candidates,
            service(GlafiraCoreResultContractEvaluator(candidates), trace)
        )

        val result = reviewer.review(task(), candidate)

        assertEquals(GlafiraCoreResultReviewStatus.ACCEPTED, result.status)
        assertEquals(ReviewIndependence.INDEPENDENT_EVALUATOR, result.independence)
        val events = trace.query(
            TraceQuery(projectId = ProjectIdentifier("project-1")),
            TraceAccessContext(CoreActor("owner-1", CoreActorType.OWNER), setOf(ProjectIdentifier("project-1")))
        ).events
        assertEquals(
            setOf(CoreTraceEventType.REVIEW_REQUESTED, CoreTraceEventType.REVIEW_ACCEPTED),
            events.map { it.eventType }.toSet()
        )
        assertFalse(temporary.root.resolve("core_trace_v1.json").readText().contains(canary))
    }

    @Test fun invalidCandidateIsRejectedAndEvaluatorFailureIsUnavailable() = runBlocking {
        val candidates = GlafiraCoreReviewCandidateRegistry()
        val trace = GlafiraTraceStore(temporary.root)
        val rejected = GlafiraIndependentCoreResultReviewer(
            candidates,
            service(GlafiraCoreResultContractEvaluator(candidates), trace)
        ).review(task(), "not-json")
        assertEquals(GlafiraCoreResultReviewStatus.REJECTED, rejected.status)

        val unavailableCandidates = GlafiraCoreReviewCandidateRegistry()
        val unavailable = GlafiraIndependentCoreResultReviewer(
            unavailableCandidates,
            service(
                object : IndependentReviewEvaluator {
                    override suspend fun evaluate(
                        request: ReviewRequest
                    ): IndependentReviewAssessment =
                        throw IllegalStateException("provider secret")
                },
                trace
            )
        ).review(task().copy(taskId = "task-2"), "{\"ok\":true}")
        assertEquals(GlafiraCoreResultReviewStatus.UNAVAILABLE, unavailable.status)

        val raw = temporary.root.resolve("core_trace_v1.json").readText()
        assertTrue(raw.contains("REVIEW_REJECTED"))
        assertTrue(raw.contains("REVIEW_UNAVAILABLE"))
        assertFalse(raw.contains("not-json"))
        assertFalse(raw.contains("provider secret"))
    }

    private fun service(evaluator: IndependentReviewEvaluator, repository: TraceRepository) =
        GlafiraIndependentReviewService(
            evaluator = evaluator,
            contextResolver = object : ReviewTraceContextResolver {
                override suspend fun resolve(taskId: TaskIdentifier) = ReviewTraceContext(
                    projectId = ProjectIdentifier("project-1"),
                    correlationId = CoreIdentifier(taskId.value),
                    executorRoleId = AgentRoleIdentifier("executor.project.read"),
                    reviewerActor = CoreActor("reviewer.core-result.v1", CoreActorType.REVIEWER),
                    dataClassification = CoreDataClassification.INTERNAL
                )
            },
            traceService = GlafiraTraceService(repository),
            now = { Instant.parse("2026-11-22T20:30:00Z") }
        )

    private fun task() = GlafiraTask(
        taskId = "task-1",
        projectId = "project-1",
        taskType = "project.read",
        payload = "{}",
        origin = "owner",
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        idempotencyKey = "idem-1"
    )
}
