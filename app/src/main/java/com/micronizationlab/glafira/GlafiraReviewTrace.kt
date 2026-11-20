package com.micronizationlab.glafira

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

data class ReviewTraceContext(
    val projectId: ProjectIdentifier,
    val correlationId: CoreIdentifier,
    val executorRoleId: AgentRoleIdentifier,
    val reviewerActor: CoreActor,
    val dataClassification: CoreDataClassification
)

interface ReviewTraceContextResolver {
    suspend fun resolve(taskId: TaskIdentifier): ReviewTraceContext?
}

data class IndependentReviewAssessment(
    val verdict: ReviewVerdict,
    val findings: List<ReviewFinding>,
    val reviewerVersion: Int,
    val providerId: String?,
    val independence: ReviewIndependence
)

interface IndependentReviewEvaluator {
    suspend fun evaluate(request: ReviewRequest): IndependentReviewAssessment
}

class ReviewUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Runs one bounded independent review and records only allow-listed metadata.
 * Candidate output, evidence contents and provider responses never enter Trace.
 */
class GlafiraIndependentReviewService(
    private val evaluator: IndependentReviewEvaluator,
    private val contextResolver: ReviewTraceContextResolver,
    private val traceService: TraceService,
    private val now: () -> Instant = Instant::now,
    private val eventId: () -> TraceEventIdentifier = {
        TraceEventIdentifier(UUID.randomUUID().toString())
    },
    private val maxFindings: Int = 100
) : ReviewService {
    init { require(maxFindings in 1..100) }

    override suspend fun review(request: ReviewRequest): ReviewResult {
        val context = contextResolver.resolve(request.taskId)
            ?: throw ReviewUnavailableException("Review context is unavailable")
        val requestedEventId = eventId()
        try {
            record(
                context = context,
                request = request,
                id = requestedEventId,
                causationId = null,
                type = CoreTraceEventType.REVIEW_REQUESTED,
                severity = CoreTraceSeverity.INFO,
                action = "review.request",
                resultCode = "REQUESTED",
                attributes = mapOf("reviewPolicyVersion" to request.reviewPolicyVersion.toString())
            )
        } catch (failure: Exception) {
            throw ReviewUnavailableException("Review trace persistence failed", failure)
        }

        if (context.executorRoleId == request.reviewerRoleId) {
            recordUnavailable(context, request, requestedEventId, "REVIEWER_ROLE_NOT_INDEPENDENT")
            throw ReviewUnavailableException("Independent reviewer role is required")
        }

        val assessment = try {
            evaluator.evaluate(request)
        } catch (_: Exception) {
            recordUnavailable(context, request, requestedEventId, "REVIEW_EVALUATOR_UNAVAILABLE")
            throw ReviewUnavailableException("Independent reviewer is unavailable")
        }

        val validationFailure = validateAssessment(assessment)
        if (validationFailure != null) {
            recordUnavailable(context, request, requestedEventId, validationFailure)
            throw ReviewUnavailableException("Independent reviewer returned an invalid assessment")
        }

        val result = ReviewResult(
            taskId = request.taskId,
            verdict = assessment.verdict,
            findings = assessment.findings,
            reviewerRoleId = request.reviewerRoleId,
            reviewerVersion = assessment.reviewerVersion,
            providerId = assessment.providerId,
            independence = assessment.independence,
            reviewedAt = now()
        )
        val (type, severity, code) = outcome(result.verdict)
        try {
            record(
                context = context,
                request = request,
                id = eventId(),
                causationId = requestedEventId,
                type = type,
                severity = severity,
                action = "review.complete",
                resultCode = code,
                attributes = mapOf(
                    "verdict" to result.verdict.name,
                    "findingCount" to result.findings.size.toString(),
                    "reviewerVersion" to result.reviewerVersion.toString(),
                    "independence" to result.independence.name
                )
            )
        } catch (failure: Exception) {
            throw ReviewUnavailableException("Review trace persistence failed", failure)
        }
        return result
    }

    private fun validateAssessment(assessment: IndependentReviewAssessment): String? {
        if (assessment.reviewerVersion <= 0) return "REVIEWER_VERSION_INVALID"
        if (assessment.findings.size > maxFindings) return "REVIEW_FINDING_LIMIT_EXCEEDED"
        if (assessment.independence != ReviewIndependence.INDEPENDENT_PROVIDER &&
            assessment.independence != ReviewIndependence.INDEPENDENT_EVALUATOR
        ) return "REVIEW_INDEPENDENCE_REJECTED"
        if (assessment.providerId != null && assessment.providerId.isBlank())
            return "REVIEW_PROVIDER_INVALID"
        return null
    }

    private suspend fun recordUnavailable(
        context: ReviewTraceContext,
        request: ReviewRequest,
        requestedEventId: TraceEventIdentifier,
        reasonCode: String
    ) {
        try {
            record(
                context = context,
                request = request,
                id = eventId(),
                causationId = requestedEventId,
                type = CoreTraceEventType.REVIEW_UNAVAILABLE,
                severity = CoreTraceSeverity.ERROR,
                action = "review.unavailable",
                resultCode = reasonCode,
                attributes = emptyMap()
            )
        } catch (traceFailure: Exception) {
            throw ReviewUnavailableException("Review trace persistence failed", traceFailure)
        }
    }

    private suspend fun record(
        context: ReviewTraceContext,
        request: ReviewRequest,
        id: TraceEventIdentifier,
        causationId: TraceEventIdentifier?,
        type: CoreTraceEventType,
        severity: CoreTraceSeverity,
        action: String,
        resultCode: String,
        attributes: Map<String, String>
    ) {
        val timestamp = now()
        traceService.record(
            CoreTraceEvent(
                eventId = id,
                occurredAt = timestamp,
                recordedAt = timestamp,
                eventType = type,
                severity = severity,
                actor = context.reviewerActor,
                projectId = context.projectId,
                taskId = request.taskId,
                parentTaskId = null,
                correlationId = context.correlationId,
                causationId = causationId,
                component = "CoreReviewService",
                action = action,
                targetType = "TASK",
                targetId = request.taskId.value,
                resultCode = resultCode,
                approvalRef = null,
                evidenceRefs = emptyList(),
                dataClassification = context.dataClassification,
                redactionApplied = true,
                secretValuesStored = false,
                safeAttributes = attributes
            )
        )
    }

    private fun outcome(verdict: ReviewVerdict): Triple<CoreTraceEventType, CoreTraceSeverity, String> =
        when (verdict) {
            ReviewVerdict.ACCEPT -> Triple(
                CoreTraceEventType.REVIEW_ACCEPTED, CoreTraceSeverity.INFO, "ACCEPTED"
            )
            ReviewVerdict.REVISE -> Triple(
                CoreTraceEventType.REVIEW_REVISION_REQUESTED, CoreTraceSeverity.WARNING, "REVISION_REQUESTED"
            )
            ReviewVerdict.REJECT -> Triple(
                CoreTraceEventType.REVIEW_REJECTED, CoreTraceSeverity.ERROR, "REJECTED"
            )
            ReviewVerdict.ESCALATE -> Triple(
                CoreTraceEventType.REVIEW_ESCALATED, CoreTraceSeverity.WARNING, "ESCALATED"
            )
        }
}

class GlafiraTraceService(private val repository: TraceRepository) : TraceService {
    override suspend fun record(event: CoreTraceEvent) = repository.append(event)
    override suspend fun query(query: TraceQuery, access: TraceAccessContext): TracePage =
        repository.query(query, access)
}

/** Durable redacted Trace. Project-less events are retained but never returned by this API. */
class GlafiraTraceStore(directory: File) : TraceRepository {
    private val store = DurableJsonFile(
        storageFile(directory, FILE_NAME),
        decode = ::decode,
        encode = ::encode
    )

    override suspend fun append(event: CoreTraceEvent) {
        require(event.redactionApplied) { "Trace event must be redacted before persistence" }
        require(redactedMetadata(event)) { "Trace event contains unsafe metadata" }
        store.update({ emptyList() }) { events ->
            val existing = events.firstOrNull { it.eventId == event.eventId }
            when {
                existing == null -> events.plus(event)
                existing == event -> events
                else -> throw IllegalStateException("Conflicting trace event identifier")
            }
        }
    }

    override suspend fun query(query: TraceQuery, access: TraceAccessContext): TracePage {
        if (query.projectId != null && query.projectId !in access.allowedProjects)
            return TracePage(emptyList())
        val events = store.read { emptyList() }.asSequence()
            .filter { it.projectId != null && it.projectId in access.allowedProjects }
            .filter { query.projectId == null || it.projectId == query.projectId }
            .filter { query.taskId == null || it.taskId == query.taskId }
            .filter { query.eventTypes.isEmpty() || it.eventType in query.eventTypes }
            .filter { query.after == null || !it.occurredAt.isBefore(query.after) }
            .filter { query.before == null || !it.occurredAt.isAfter(query.before) }
            .sortedWith(compareByDescending<CoreTraceEvent> { it.occurredAt }.thenBy { it.eventId.value })
            .take(query.limit)
            .toList()
        return TracePage(events)
    }

    private fun encode(events: List<CoreTraceEvent>): String = JSONArray().apply {
        events.sortedBy { it.eventId.value }.forEach { put(encodeEvent(it)) }
    }.toString()

    private fun encodeEvent(event: CoreTraceEvent): JSONObject = JSONObject().apply {
        put("eventId", event.eventId.value)
        put("schemaVersion", event.schemaVersion)
        put("occurredAt", event.occurredAt.toEpochMilli())
        put("recordedAt", event.recordedAt.toEpochMilli())
        put("eventType", event.eventType.name)
        put("severity", event.severity.name)
        put("actorId", event.actor.id)
        put("actorType", event.actor.type.name)
        put("projectId", event.projectId?.value ?: JSONObject.NULL)
        put("taskId", event.taskId?.value ?: JSONObject.NULL)
        put("parentTaskId", event.parentTaskId?.value ?: JSONObject.NULL)
        put("correlationId", event.correlationId.value)
        put("causationId", event.causationId?.value ?: JSONObject.NULL)
        put("component", event.component)
        put("action", event.action)
        put("targetType", event.targetType ?: JSONObject.NULL)
        put("targetId", event.targetId ?: JSONObject.NULL)
        put("resultCode", event.resultCode ?: JSONObject.NULL)
        put("approvalRef", event.approvalRef ?: JSONObject.NULL)
        put("evidenceRefs", JSONArray(event.evidenceRefs))
        put("dataClassification", event.dataClassification.name)
        put("redactionApplied", event.redactionApplied)
        put("secretValuesStored", false)
        put("durationMs", event.durationMs ?: JSONObject.NULL)
        put("safeAttributes", JSONObject().apply {
            event.safeAttributes.toSortedMap().forEach { (key, value) -> put(key, value) }
        })
    }

    private fun decode(raw: String): List<CoreTraceEvent> {
        val array = JSONArray(raw)
        return List(array.length()) { decodeEvent(array.getJSONObject(it)) }
    }

    private fun decodeEvent(json: JSONObject): CoreTraceEvent = CoreTraceEvent(
        eventId = TraceEventIdentifier(json.getString("eventId")),
        schemaVersion = json.getInt("schemaVersion"),
        occurredAt = Instant.ofEpochMilli(json.getLong("occurredAt")),
        recordedAt = Instant.ofEpochMilli(json.getLong("recordedAt")),
        eventType = CoreTraceEventType.valueOf(json.getString("eventType")),
        severity = CoreTraceSeverity.valueOf(json.getString("severity")),
        actor = CoreActor(
            json.getString("actorId"), CoreActorType.valueOf(json.getString("actorType"))
        ),
        projectId = json.nullableString("projectId")?.let(::ProjectIdentifier),
        taskId = json.nullableString("taskId")?.let(::TaskIdentifier),
        parentTaskId = json.nullableString("parentTaskId")?.let(::TaskIdentifier),
        correlationId = CoreIdentifier(json.getString("correlationId")),
        causationId = json.nullableString("causationId")?.let(::TraceEventIdentifier),
        component = json.getString("component"),
        action = json.getString("action"),
        targetType = json.nullableString("targetType"),
        targetId = json.nullableString("targetId"),
        resultCode = json.nullableString("resultCode"),
        approvalRef = json.nullableString("approvalRef"),
        evidenceRefs = json.getJSONArray("evidenceRefs").let { values ->
            List(values.length()) { values.getString(it) }
        },
        dataClassification = CoreDataClassification.valueOf(json.getString("dataClassification")),
        redactionApplied = json.getBoolean("redactionApplied"),
        secretValuesStored = json.getBoolean("secretValuesStored"),
        durationMs = if (json.isNull("durationMs")) null else json.getLong("durationMs"),
        safeAttributes = json.getJSONObject("safeAttributes").let { values ->
            values.keys().asSequence().associateWith { values.getString(it) }
        }
    ).also { event ->
        require(event.redactionApplied && redactedMetadata(event)) {
            "Stored trace event contains unsafe metadata"
        }
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun redactedMetadata(event: CoreTraceEvent): Boolean {
        val values = listOfNotNull(
            event.eventId.value,
            event.actor.id,
            event.projectId?.value,
            event.taskId?.value,
            event.parentTaskId?.value,
            event.correlationId.value,
            event.causationId?.value,
            event.component,
            event.action,
            event.targetType,
            event.targetId,
            event.resultCode,
            event.approvalRef
        ) + event.evidenceRefs + event.safeAttributes.keys + event.safeAttributes.values
        return values.all { value ->
            value.length <= MAX_METADATA_LENGTH &&
                value.none { it.isISOControl() } &&
                GlafiraSecretRedactor.redact(value) == value
        }
    }

    private companion object {
        const val FILE_NAME = "core_trace_v1.json"
        const val MAX_METADATA_LENGTH = 512
    }
}
