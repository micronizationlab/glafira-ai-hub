package com.micronizationlab.glafira

import java.util.UUID

enum class GlafiraCoreTraceEventType {
    TASK_CREATED, TASK_QUEUED, TASK_STARTED, TASK_PAUSED, TASK_RESUMED,
    TASK_SUCCEEDED, TASK_FAILED, TASK_CANCELLED,
    AGENT_SELECTED, AGENT_DELEGATED, AGENT_RESULT_RECEIVED,
    MEMORY_PROPOSED, MEMORY_CONFIRMED, MEMORY_UPDATED, MEMORY_DISPUTED, MEMORY_DELETED,
    REVIEW_REQUESTED, REVIEW_ACCEPTED, REVIEW_REVISION_REQUESTED, REVIEW_REJECTED,
    REVIEW_ESCALATED, REVIEW_UNAVAILABLE,
    POLICY_CHECKED, PERMISSION_DENIED, APPROVAL_REQUESTED, APPROVAL_GRANTED, APPROVAL_REVOKED,
    TOOL_REQUESTED, TOOL_STARTED, TOOL_SUCCEEDED, TOOL_FAILED,
    PROVIDER_SELECTED, PROVIDER_FALLBACK, PROVIDER_TIMEOUT,
    CONTEXT_REDACTED, SECRET_REDACTION_TRIGGERED,
    BACKUP_STARTED, BACKUP_VERIFIED, RESTORE_STARTED, RESTORE_SUCCEEDED, RESTORE_FAILED
}

enum class GlafiraCoreTraceActorType { OWNER, CORE, AGENT, PROVIDER, DEVICE, SYSTEM }
enum class GlafiraCoreTraceDataClassification { PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }

/**
 * Untrusted, in-memory event input. It deliberately has no payload or provider-response field.
 * Producers must pass it through GlafiraCoreTraceRedactor before persistence.
 */
data class GlafiraCoreTraceDraft(
    val eventId: String = "evt${UUID.randomUUID()}",
    val projectId: String,
    val taskId: String? = null,
    val parentEventId: String? = null,
    val correlationId: String,
    val occurredAtMillis: Long,
    val actorType: GlafiraCoreTraceActorType,
    val actorId: String,
    val component: String,
    val eventType: GlafiraCoreTraceEventType,
    val actionCode: String,
    val targetType: String? = null,
    val targetId: String? = null,
    val resultStatus: String? = null,
    val errorCode: String? = null,
    val approvalRef: String? = null,
    val evidenceRefs: List<String> = emptyList(),
    val dataClassification: GlafiraCoreTraceDataClassification = GlafiraCoreTraceDataClassification.INTERNAL,
    val durationMs: Long? = null
)

/** Safe metadata-only event. No task payload, prompt, provider response or credential field exists. */
data class GlafiraCoreTraceEvent internal constructor(
    val eventId: String,
    val projectId: String,
    val taskId: String?,
    val parentEventId: String?,
    val correlationId: String,
    val occurredAtMillis: Long,
    val actorType: GlafiraCoreTraceActorType,
    val actorId: String,
    val component: String,
    val eventType: GlafiraCoreTraceEventType,
    val actionCode: String,
    val targetType: String?,
    val targetId: String?,
    val resultStatus: String?,
    val errorCode: String?,
    val approvalRef: String?,
    val evidenceRefs: List<String>,
    val dataClassification: GlafiraCoreTraceDataClassification,
    val redactionApplied: Boolean,
    val secretValuesStored: Boolean,
    val durationMs: Long?
) {
    init {
        require(redactionApplied) { "TRACE_REDACTION_REQUIRED" }
        require(!secretValuesStored) { "TRACE_SECRET_STORAGE_FORBIDDEN" }
        require(occurredAtMillis > 0L)
        require(durationMs == null || durationMs >= 0L)
        require(evidenceRefs.size <= MAX_EVIDENCE_REFS)
        require(isSafeIdentifier(eventId) && isSafeIdentifier(projectId) &&
            isSafeIdentifier(correlationId) && isSafeIdentifier(actorId) &&
            isSafeIdentifier(component) && isSafeCode(actionCode))
        require(taskId == null || isSafeIdentifier(taskId))
        require(parentEventId == null || isSafeIdentifier(parentEventId))
        require(targetType == null || isSafeCode(targetType))
        require(targetId == null || isSafeIdentifier(targetId))
        require(resultStatus == null || isSafeCode(resultStatus))
        require(errorCode == null || isSafeCode(errorCode))
        require(approvalRef == null || isSafeIdentifier(approvalRef))
        require(evidenceRefs.all(::isSafeIdentifier))
    }

    companion object {
        const val MAX_EVIDENCE_REFS = 32
        private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val CODE = Regex("[A-Za-z][A-Za-z0-9._-]{0,95}")
        internal fun isSafeIdentifier(value: String) = IDENTIFIER.matches(value)
        internal fun isSafeCode(value: String) = CODE.matches(value)
    }
}

/**
 * Redacts before persistence. Trace fields are opaque identifiers/codes only; arbitrary text,
 * URLs, contact details and credential-bearing values collapse to a constant sentinel.
 */
object GlafiraCoreTraceRedactor {
    private val secretPatterns = listOf(
        Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}"),
        Regex("(?i)\\bsk-[A-Za-z0-9_-]{12,}\\b"),
        Regex("(?i)\\bya29\\.[A-Za-z0-9._-]{12,}\\b"),
        Regex("(?i)(password|passphrase|secret|token|api[_ -]?key|authorization|cookie|credential|private[_ -]?key)\\s*[:=]\\s*[^\\s,;]{4,}"),
        Regex("(?i)\\b(?:ghp_|gho_|github_pat_)[A-Za-z0-9_]{16,}\\b"),
        Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b")
    )
    private val email = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val url = Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s]+")
    private val phone = Regex("(?<![A-Za-z0-9])\\+?[0-9][0-9 ()-]{8,}[0-9](?![A-Za-z0-9])")
    // UUIDs are opaque stable identifiers, not telephone numbers. Strip them before phone scanning.
    private val uuid = Regex("(?i)(?:evt)?[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    fun redact(draft: GlafiraCoreTraceDraft): GlafiraCoreTraceEvent {
        require(draft.occurredAtMillis > 0L) { "TRACE_TIMESTAMP_INVALID" }
        require(draft.durationMs == null || draft.durationMs >= 0L) { "TRACE_DURATION_INVALID" }
        require(draft.evidenceRefs.size <= GlafiraCoreTraceEvent.MAX_EVIDENCE_REFS) {
            "TRACE_TOO_MANY_EVIDENCE_REFS"
        }
        return GlafiraCoreTraceEvent(
            eventId = requiredIdentifier(draft.eventId),
            projectId = requiredIdentifier(draft.projectId),
            taskId = draft.taskId?.let(::requiredIdentifier),
            parentEventId = draft.parentEventId?.let(::requiredIdentifier),
            correlationId = requiredIdentifier(draft.correlationId),
            occurredAtMillis = draft.occurredAtMillis,
            actorType = draft.actorType,
            actorId = safeIdentifier(draft.actorId),
            component = safeCode(draft.component),
            eventType = draft.eventType,
            actionCode = safeCode(draft.actionCode),
            targetType = draft.targetType?.let(::safeCode),
            targetId = draft.targetId?.let(::safeIdentifier),
            resultStatus = draft.resultStatus?.let(::safeCode),
            errorCode = draft.errorCode?.let(::safeCode),
            approvalRef = draft.approvalRef?.let(::safeIdentifier),
            evidenceRefs = draft.evidenceRefs.map(::safeIdentifier),
            dataClassification = draft.dataClassification,
            redactionApplied = true,
            secretValuesStored = false,
            durationMs = draft.durationMs
        )
    }

    private fun requiredIdentifier(raw: String): String {
        val value = raw.trim()
        require(!containsSensitiveValue(value) && GlafiraCoreTraceEvent.isSafeIdentifier(value)) {
            "TRACE_IDENTIFIER_INVALID"
        }
        return value
    }

    private fun containsSensitiveValue(value: String): Boolean {
        val withoutUuids = uuid.replace(value, "")
        return secretPatterns.any { it.containsMatchIn(value) } ||
            email.containsMatchIn(value) || url.containsMatchIn(value) || phone.containsMatchIn(withoutUuids)
    }

    private fun safeIdentifier(raw: String): String =
        sanitize(raw, GlafiraCoreTraceEvent::isSafeIdentifier)

    private fun safeCode(raw: String): String =
        sanitize(raw, GlafiraCoreTraceEvent::isSafeCode)

    private fun sanitize(raw: String, valid: (String) -> Boolean): String {
        val value = raw.trim()
        if (containsSensitiveValue(value) || !valid(value)) return REDACTED
        return value
    }

    private const val REDACTED = "redacted"
}

data class GlafiraCoreTraceQuery(
    val projectId: String,
    val taskId: String? = null,
    val eventTypes: Set<GlafiraCoreTraceEventType> = emptySet(),
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val afterEventId: String? = null,
    val limit: Int = DEFAULT_LIMIT
) {
    init {
        require(GlafiraCoreTraceEvent.isSafeIdentifier(projectId))
        require(taskId == null || GlafiraCoreTraceEvent.isSafeIdentifier(taskId))
        require(fromMillis == null || fromMillis > 0L)
        require(toMillis == null || toMillis > 0L)
        require(fromMillis == null || toMillis == null || fromMillis <= toMillis)
        require(afterEventId == null || GlafiraCoreTraceEvent.isSafeIdentifier(afterEventId))
        require(limit in 1..MAX_LIMIT) { "TRACE_PAGE_LIMIT_INVALID" }
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 100
    }
}

/** Access is always project-bound; task-scoped callers cannot widen to sibling tasks. */
data class GlafiraCoreTraceAccessContext(
    val projectId: String,
    val taskIds: Set<String>? = null,
    val allowProjectEvents: Boolean = false
) {
    init {
        require(GlafiraCoreTraceEvent.isSafeIdentifier(projectId))
        require(taskIds == null || taskIds.all(GlafiraCoreTraceEvent::isSafeIdentifier))
    }

    fun canRead(event: GlafiraCoreTraceEvent): Boolean =
        event.projectId == projectId && when {
            taskIds == null -> true
            event.taskId == null -> allowProjectEvents
            else -> event.taskId in taskIds
        }
}

data class GlafiraCoreTracePage(
    val events: List<GlafiraCoreTraceEvent>,
    val nextCursor: String? = null
)

interface GlafiraCoreTraceRepository {
    fun append(event: GlafiraCoreTraceDraft): String
    fun query(query: GlafiraCoreTraceQuery, access: GlafiraCoreTraceAccessContext): GlafiraCoreTracePage
}
