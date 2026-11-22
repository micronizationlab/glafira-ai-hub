package com.micronizationlab.glafira

import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class GlafiraCoreResultReviewStatus {
    ACCEPTED, REVISION_REQUESTED, REJECTED, ESCALATED, UNAVAILABLE
}

data class GlafiraCoreTaskResultReviewOutcome(
    val status: GlafiraCoreResultReviewStatus,
    val reviewerVersion: Int? = null,
    val independence: ReviewIndependence? = null
)

fun interface GlafiraCoreTaskResultReviewer {
    suspend fun review(task: GlafiraTask, result: String): GlafiraCoreTaskResultReviewOutcome
}

/** Candidate output exists only for the bounded review call and is never copied into Trace. */
internal class GlafiraCoreReviewCandidateRegistry {
    private val candidates = ConcurrentHashMap<String, String>()

    suspend fun <T> withCandidate(taskId: TaskIdentifier, candidate: String, block: suspend () -> T): T {
        check(candidates.putIfAbsent(taskId.value, candidate) == null) {
            "CORE_RESULT_REVIEW_ALREADY_RUNNING"
        }
        return try {
            block()
        } finally {
            candidates.remove(taskId.value, candidate)
        }
    }

    fun resolve(taskId: TaskIdentifier): String? = candidates[taskId.value]
}

/**
 * Independent deterministic contract evaluator. It does not execute the task, use its prompt,
 * call tools, or receive mutable executor state; it only validates the bounded candidate result.
 */
internal class GlafiraCoreResultContractEvaluator(
    private val candidates: GlafiraCoreReviewCandidateRegistry
) : IndependentReviewEvaluator {
    override suspend fun evaluate(request: ReviewRequest): IndependentReviewAssessment {
        val candidate = candidates.resolve(request.taskId)
            ?: throw ReviewUnavailableException("Review candidate is unavailable")
        val valid = candidate.isNotBlank() &&
            candidate.length <= GlafiraCoreTaskResultPersistence.MAX_RESULT_CHARS &&
            candidate.firstOrNull { !it.isWhitespace() } == '{' &&
            runCatching { JSONObject(candidate) }.isSuccess
        return IndependentReviewAssessment(
            verdict = if (valid) ReviewVerdict.ACCEPT else ReviewVerdict.REJECT,
            findings = if (valid) emptyList() else listOf(
                ReviewFinding(
                    criterionId = "core-result-json-object-v1",
                    severity = ReviewFindingSeverity.ERROR,
                    explanation = "Candidate result does not satisfy the bounded JSON object contract.",
                    remediation = "Return one bounded JSON object matching the registered Core tool contract."
                )
            ),
            reviewerVersion = REVIEWER_VERSION,
            providerId = null,
            independence = ReviewIndependence.INDEPENDENT_EVALUATOR
        )
    }

    private companion object { const val REVIEWER_VERSION = 1 }
}

internal class GlafiraCoreReviewContextResolver(
    private val taskStore: GlafiraTaskStore
) : ReviewTraceContextResolver {
    override suspend fun resolve(taskId: TaskIdentifier): ReviewTraceContext? {
        val task = taskStore.getTask(taskId.value) ?: return null
        return ReviewTraceContext(
            projectId = ProjectIdentifier(task.projectId),
            correlationId = CoreIdentifier(task.taskId),
            executorRoleId = AgentRoleIdentifier("executor.${task.taskType}"),
            reviewerActor = CoreActor(REVIEWER_ROLE.value, CoreActorType.REVIEWER),
            dataClassification = CoreDataClassification.INTERNAL
        )
    }

    companion object {
        val REVIEWER_ROLE = AgentRoleIdentifier("reviewer.core-result.v1")
    }
}

/** Bridges the provider-independent review service to the canonical SQLite Core Trace. */
internal class GlafiraCoreReviewTraceService(
    private val repository: GlafiraCoreTraceRepository
) : TraceService {
    override suspend fun record(event: CoreTraceEvent) {
        val projectId = requireNotNull(event.projectId) { "REVIEW_TRACE_PROJECT_REQUIRED" }
        repository.append(
            GlafiraCoreTraceDraft(
                eventId = event.eventId.value,
                projectId = projectId.value,
                taskId = event.taskId?.value,
                parentEventId = event.causationId?.value,
                correlationId = event.correlationId.value,
                occurredAtMillis = event.occurredAt.toEpochMilli(),
                actorType = event.actor.type.toTraceActor(),
                actorId = event.actor.id,
                component = event.component,
                eventType = GlafiraCoreTraceEventType.valueOf(event.eventType.name),
                actionCode = event.action,
                targetType = event.targetType,
                targetId = event.targetId,
                resultStatus = event.resultCode,
                approvalRef = event.approvalRef,
                evidenceRefs = event.evidenceRefs,
                dataClassification = GlafiraCoreTraceDataClassification.valueOf(
                    event.dataClassification.name
                ),
                durationMs = event.durationMs
            )
        )
    }

    override suspend fun query(query: TraceQuery, access: TraceAccessContext): TracePage {
        val requestedTypes = if (query.eventTypes.isEmpty()) REVIEW_EVENT_TYPES else query.eventTypes
        if (requestedTypes.any { it !in REVIEW_EVENT_TYPES }) return TracePage(emptyList())
        val mappedTypes = requestedTypes.mapTo(mutableSetOf()) {
            GlafiraCoreTraceEventType.valueOf(it.name)
        }
        val projects = query.projectId?.let(::setOf)
            ?: access.allowedProjects
        val events = projects.asSequence()
            .filter { it in access.allowedProjects }
            .flatMap { project ->
                repository.query(
                    GlafiraCoreTraceQuery(
                        projectId = project.value,
                        taskId = query.taskId?.value,
                        eventTypes = mappedTypes,
                        fromMillis = query.after?.toEpochMilli(),
                        toMillis = query.before?.toEpochMilli(),
                        limit = query.limit.coerceAtMost(GlafiraCoreTraceQuery.MAX_LIMIT)
                    ),
                    GlafiraCoreTraceAccessContext(project.value)
                ).events.asSequence()
            }
            .sortedWith(compareByDescending<GlafiraCoreTraceEvent> { it.occurredAtMillis }
                .thenByDescending { it.eventId })
            .take(query.limit)
            .map { it.toDomainEvent() }
            .toList()
        return TracePage(events)
    }

    private fun CoreActorType.toTraceActor(): GlafiraCoreTraceActorType = when (this) {
        CoreActorType.OWNER -> GlafiraCoreTraceActorType.OWNER
        CoreActorType.AGENT, CoreActorType.REVIEWER -> GlafiraCoreTraceActorType.AGENT
        CoreActorType.INTEGRATION -> GlafiraCoreTraceActorType.DEVICE
        CoreActorType.SYSTEM -> GlafiraCoreTraceActorType.SYSTEM
    }

    private fun GlafiraCoreTraceEvent.toDomainEvent() = CoreTraceEvent(
        eventId = TraceEventIdentifier(eventId),
        occurredAt = Instant.ofEpochMilli(occurredAtMillis),
        recordedAt = Instant.ofEpochMilli(occurredAtMillis),
        eventType = CoreTraceEventType.valueOf(eventType.name),
        severity = when (eventType) {
            GlafiraCoreTraceEventType.REVIEW_UNAVAILABLE,
            GlafiraCoreTraceEventType.REVIEW_REJECTED -> CoreTraceSeverity.ERROR
            GlafiraCoreTraceEventType.REVIEW_REVISION_REQUESTED,
            GlafiraCoreTraceEventType.REVIEW_ESCALATED -> CoreTraceSeverity.WARNING
            else -> CoreTraceSeverity.INFO
        },
        actor = CoreActor(actorId, actorType.toDomainActor()),
        projectId = ProjectIdentifier(projectId),
        taskId = taskId?.let(::TaskIdentifier),
        parentTaskId = null,
        correlationId = CoreIdentifier(correlationId),
        causationId = parentEventId?.let(::TraceEventIdentifier),
        component = component,
        action = actionCode,
        targetType = targetType,
        targetId = targetId,
        resultCode = resultStatus,
        approvalRef = approvalRef,
        evidenceRefs = evidenceRefs,
        dataClassification = CoreDataClassification.valueOf(dataClassification.name),
        redactionApplied = redactionApplied,
        secretValuesStored = secretValuesStored,
        durationMs = durationMs
    )

    private fun GlafiraCoreTraceActorType.toDomainActor(): CoreActorType = when (this) {
        GlafiraCoreTraceActorType.OWNER -> CoreActorType.OWNER
        GlafiraCoreTraceActorType.AGENT -> CoreActorType.AGENT
        GlafiraCoreTraceActorType.DEVICE, GlafiraCoreTraceActorType.PROVIDER ->
            CoreActorType.INTEGRATION
        GlafiraCoreTraceActorType.CORE, GlafiraCoreTraceActorType.SYSTEM -> CoreActorType.SYSTEM
    }

    private companion object {
        val REVIEW_EVENT_TYPES = setOf(
            CoreTraceEventType.REVIEW_REQUESTED,
            CoreTraceEventType.REVIEW_ACCEPTED,
            CoreTraceEventType.REVIEW_REVISION_REQUESTED,
            CoreTraceEventType.REVIEW_REJECTED,
            CoreTraceEventType.REVIEW_ESCALATED,
            CoreTraceEventType.REVIEW_UNAVAILABLE
        )
    }
}

internal class GlafiraIndependentCoreResultReviewer(
    private val candidates: GlafiraCoreReviewCandidateRegistry,
    private val service: ReviewService
) : GlafiraCoreTaskResultReviewer {
    override suspend fun review(task: GlafiraTask, result: String): GlafiraCoreTaskResultReviewOutcome {
        val taskId = TaskIdentifier(task.taskId)
        return candidates.withCandidate(taskId, result) {
            try {
                service.review(
                    ReviewRequest(
                        taskId = taskId,
                        taskContractRef = "core-task-result-v1.${task.taskType}",
                        acceptanceCriteria = listOf(
                            "Result is a bounded JSON object.",
                            "Review metadata is redacted before Trace persistence."
                        ),
                        candidateOutputRef = "core-result-candidate:${task.taskId}",
                        evidenceRefs = emptyList(),
                        safeTraceSummaryRef = null,
                        reviewerRoleId = GlafiraCoreReviewContextResolver.REVIEWER_ROLE,
                        reviewPolicyVersion = 1
                    )
                ).toOutcome()
            } catch (_: ReviewUnavailableException) {
                GlafiraCoreTaskResultReviewOutcome(GlafiraCoreResultReviewStatus.UNAVAILABLE)
            }
        }
    }

    private fun ReviewResult.toOutcome() = GlafiraCoreTaskResultReviewOutcome(
        status = when (verdict) {
            ReviewVerdict.ACCEPT -> GlafiraCoreResultReviewStatus.ACCEPTED
            ReviewVerdict.REVISE -> GlafiraCoreResultReviewStatus.REVISION_REQUESTED
            ReviewVerdict.REJECT -> GlafiraCoreResultReviewStatus.REJECTED
            ReviewVerdict.ESCALATE -> GlafiraCoreResultReviewStatus.ESCALATED
        },
        reviewerVersion = reviewerVersion,
        independence = independence
    )
}

internal object GlafiraCoreResultReviewFactory {
    fun create(
        taskStore: GlafiraTaskStore,
        traceRepository: GlafiraCoreTraceRepository
    ): GlafiraCoreTaskResultReviewer {
        val candidates = GlafiraCoreReviewCandidateRegistry()
        val service = GlafiraIndependentReviewService(
            evaluator = GlafiraCoreResultContractEvaluator(candidates),
            contextResolver = GlafiraCoreReviewContextResolver(taskStore),
            traceService = GlafiraCoreReviewTraceService(traceRepository)
        )
        return GlafiraIndependentCoreResultReviewer(candidates, service)
    }
}

internal fun GlafiraCoreTraceEventType.toReviewStatus(): GlafiraCoreResultReviewStatus? =
    when (this) {
        GlafiraCoreTraceEventType.REVIEW_ACCEPTED -> GlafiraCoreResultReviewStatus.ACCEPTED
        GlafiraCoreTraceEventType.REVIEW_REVISION_REQUESTED ->
            GlafiraCoreResultReviewStatus.REVISION_REQUESTED
        GlafiraCoreTraceEventType.REVIEW_REJECTED -> GlafiraCoreResultReviewStatus.REJECTED
        GlafiraCoreTraceEventType.REVIEW_ESCALATED -> GlafiraCoreResultReviewStatus.ESCALATED
        GlafiraCoreTraceEventType.REVIEW_UNAVAILABLE -> GlafiraCoreResultReviewStatus.UNAVAILABLE
        else -> null
    }
