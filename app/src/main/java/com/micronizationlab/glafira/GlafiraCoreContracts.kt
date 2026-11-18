package com.micronizationlab.glafira

import java.time.Instant

/**
 * Provider-independent domain contracts for GLAFIRA Core.
 *
 * These are domain models only. Persistence, encryption, Android services and
 * provider adapters are deliberately implemented behind the interfaces below.
 * Never serialize secret values or raw provider prompts into CoreTraceEvent.
 */

@JvmInline
value class CoreIdentifier(val value: String) {
    init { require(value.isNotBlank()) { "Core identifier must not be blank" } }
}

@JvmInline
value class MemoryIdentifier(val value: String) {
    init { require(value.isNotBlank()) { "Memory identifier must not be blank" } }
}

@JvmInline
value class TaskIdentifier(val value: String) {
    init { require(value.isNotBlank()) { "Task identifier must not be blank" } }
}

@JvmInline
value class ProjectIdentifier(val value: String) {
    init { require(value.isNotBlank()) { "Project identifier must not be blank" } }
}

@JvmInline
value class AgentRoleIdentifier(val value: String) {
    init { require(value.isNotBlank()) { "Agent role identifier must not be blank" } }
}

@JvmInline
value class TraceEventIdentifier(val value: String) {
    init { require(value.isNotBlank()) { "Trace event identifier must not be blank" } }
}

enum class CoreDataClassification { PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }

enum class MemoryScopeType { OWNER, PROJECT, AGENT, CONVERSATION, WORKING }

enum class MemoryKind { FACT, PREFERENCE, DECISION, INSTRUCTION, PROCEDURE, SUMMARY }

enum class MemoryStatus { PROPOSED, CONFIRMED, DISPUTED, SUPERSEDED, EXPIRED, DELETED }

enum class MemoryConfidenceBasis { OWNER_CONFIRMED, SOURCE_DERIVED, AGENT_INFERRED }

data class MemoryProvenance(
    val sourceType: String,
    val sourceId: String?,
    val actorId: String,
    val occurredAt: Instant
) {
    init { require(sourceType.isNotBlank() && actorId.isNotBlank()) }
}

/**
 * content is a domain value supplied only after repository authorization/decryption.
 * Durable storage must persist an encrypted payload/reference, never plaintext for
 * CONFIDENTIAL or RESTRICTED records.
 */
data class CoreMemoryRecord(
    val id: MemoryIdentifier,
    val schemaVersion: Int = 1,
    val scopeType: MemoryScopeType,
    val scopeId: String,
    val kind: MemoryKind,
    val content: String,
    val classification: CoreDataClassification,
    val status: MemoryStatus = MemoryStatus.PROPOSED,
    val confidence: Double,
    val confidenceBasis: MemoryConfidenceBasis,
    val provenance: MemoryProvenance,
    val createdAt: Instant,
    val updatedAt: Instant,
    val validFrom: Instant? = null,
    val expiresAt: Instant? = null,
    val createdBy: String,
    val lastModifiedBy: String,
    val supersedes: MemoryIdentifier? = null,
    val tags: Set<String> = emptySet()
) {
    init {
        require(schemaVersion > 0)
        require(scopeId.isNotBlank() && content.isNotBlank())
        require(confidence in 0.0..1.0)
        require(createdBy.isNotBlank() && lastModifiedBy.isNotBlank())
        require(!updatedAt.isBefore(createdAt))
        require(expiresAt == null || !expiresAt.isBefore(createdAt))
        require(status != MemoryStatus.CONFIRMED ||
            confidenceBasis == MemoryConfidenceBasis.OWNER_CONFIRMED) {
            "Only owner-confirmed provenance may create confirmed memory"
        }
    }
}

data class MemoryAccessContext(
    val actorId: String,
    val projectId: ProjectIdentifier?,
    val agentRoleId: AgentRoleIdentifier?,
    val allowedScopes: Set<MemoryScopeType>,
    val maximumClassification: CoreDataClassification
) {
    init { require(actorId.isNotBlank()) }
}

data class MemoryQuery(
    val text: String,
    val scopes: Set<MemoryScopeType> = emptySet(),
    val statuses: Set<MemoryStatus> = setOf(MemoryStatus.CONFIRMED),
    val kinds: Set<MemoryKind> = emptySet(),
    val limit: Int = 20,
    val includeExpired: Boolean = false
) {
    init {
        require(limit in 1..100) { "Memory query limit must be between 1 and 100" }
    }
}

sealed interface MemoryWriteResult {
    data class Stored(val id: MemoryIdentifier) : MemoryWriteResult
    data class Rejected(val reasonCode: String) : MemoryWriteResult
}

sealed interface MemoryDeleteResult {
    data object Deleted : MemoryDeleteResult
    data object NotFound : MemoryDeleteResult
    data class Rejected(val reasonCode: String) : MemoryDeleteResult
}

data class MemoryChange(
    val content: String? = null,
    val status: MemoryStatus? = null,
    val classification: CoreDataClassification? = null,
    val expiresAt: Instant? = null,
    val tags: Set<String>? = null
)

data class MemoryExport(val schemaVersion: Int, val records: List<CoreMemoryRecord>)

interface MemoryRepository {
    suspend fun put(record: CoreMemoryRecord, access: MemoryAccessContext): MemoryWriteResult
    suspend fun get(id: MemoryIdentifier, access: MemoryAccessContext): CoreMemoryRecord?
    suspend fun search(query: MemoryQuery, access: MemoryAccessContext): List<CoreMemoryRecord>
    suspend fun update(
        id: MemoryIdentifier,
        change: MemoryChange,
        access: MemoryAccessContext
    ): MemoryWriteResult
    suspend fun delete(id: MemoryIdentifier, access: MemoryAccessContext): MemoryDeleteResult
    suspend fun export(query: MemoryQuery, access: MemoryAccessContext): MemoryExport
}

enum class CoreActorType { OWNER, SYSTEM, AGENT, REVIEWER, INTEGRATION }

data class CoreActor(val id: String, val type: CoreActorType) {
    init { require(id.isNotBlank()) }
}

data class AgentRole(
    val id: AgentRoleIdentifier,
    val version: Int,
    val displayName: String,
    val purpose: String,
    val allowedProjects: Set<ProjectIdentifier>,
    val allowedClassifications: Set<CoreDataClassification>,
    val allowedToolIds: Set<String>,
    val requiredInputSchema: String,
    val requiredOutputSchema: String,
    val reviewRequired: Boolean,
    val instructionRef: String
) {
    init {
        require(version > 0)
        require(displayName.isNotBlank() && purpose.isNotBlank())
        require(requiredInputSchema.isNotBlank() && requiredOutputSchema.isNotBlank())
        require(instructionRef.isNotBlank())
        require(allowedToolIds.none { it.isBlank() })
    }
}

interface AgentRegistry {
    fun resolve(roleId: AgentRoleIdentifier, version: Int? = null): AgentRole?
}

data class AgentTaskRequest(
    val taskId: TaskIdentifier,
    val parentTaskId: TaskIdentifier? = null,
    val correlationId: CoreIdentifier,
    val projectId: ProjectIdentifier,
    val requestingActor: CoreActor,
    val roleId: AgentRoleIdentifier,
    val roleVersion: Int,
    val inputPayloadRef: String,
    val inputSchema: String,
    val permittedMemoryIds: Set<MemoryIdentifier>,
    val permittedToolIds: Set<String>,
    val deadline: Instant?,
    val idempotencyKey: String,
    val reviewRequired: Boolean
) {
    init {
        require(roleVersion > 0)
        require(inputPayloadRef.isNotBlank() && inputSchema.isNotBlank())
        require(idempotencyKey.isNotBlank())
        require(permittedToolIds.none { it.isBlank() })
    }
}

enum class AgentTaskStatus {
    SUCCEEDED, FAILED, CANCELLED, WAITING_FOR_OWNER, REVIEW_REQUIRED, REJECTED
}

data class AgentTaskResult(
    val taskId: TaskIdentifier,
    val status: AgentTaskStatus,
    val outputPayloadRef: String? = null,
    val outputSchema: String? = null,
    val evidenceRefs: List<String> = emptyList(),
    val usedToolIds: List<String> = emptyList(),
    val uncertainty: List<String> = emptyList(),
    val proposedMemoryIds: List<MemoryIdentifier> = emptyList(),
    val errorCode: String? = null
)

interface AgentExecutor {
    suspend fun execute(request: AgentTaskRequest): AgentTaskResult
}

data class CoreTaskRequest(
    val taskId: TaskIdentifier,
    val projectId: ProjectIdentifier,
    val actor: CoreActor,
    val goal: String,
    val preferredRole: AgentRoleIdentifier? = null,
    val inputPayloadRef: String? = null,
    val correlationId: CoreIdentifier
) {
    init { require(goal.isNotBlank()) }
}

data class CoreTaskResult(
    val taskId: TaskIdentifier,
    val status: AgentTaskStatus,
    val result: AgentTaskResult?,
    val review: ReviewResult? = null
)

interface CoreCoordinator {
    suspend fun handle(request: CoreTaskRequest): CoreTaskResult
}

enum class ReviewVerdict { ACCEPT, REVISE, REJECT, ESCALATE }

enum class ReviewFindingSeverity { INFO, WARNING, ERROR, CRITICAL }

data class ReviewFinding(
    val criterionId: String,
    val severity: ReviewFindingSeverity,
    val explanation: String,
    val evidenceRef: String? = null,
    val remediation: String? = null
) {
    init { require(criterionId.isNotBlank() && explanation.isNotBlank()) }
}

data class ReviewRequest(
    val taskId: TaskIdentifier,
    val taskContractRef: String,
    val acceptanceCriteria: List<String>,
    val candidateOutputRef: String,
    val evidenceRefs: List<String>,
    val safeTraceSummaryRef: String?,
    val reviewerRoleId: AgentRoleIdentifier,
    val reviewPolicyVersion: Int
) {
    init {
        require(taskContractRef.isNotBlank() && candidateOutputRef.isNotBlank())
        require(acceptanceCriteria.isNotEmpty() && acceptanceCriteria.none { it.isBlank() })
        require(reviewPolicyVersion > 0)
    }
}

data class ReviewResult(
    val taskId: TaskIdentifier,
    val verdict: ReviewVerdict,
    val findings: List<ReviewFinding>,
    val reviewerRoleId: AgentRoleIdentifier,
    val reviewerVersion: Int,
    val providerId: String?,
    val independence: ReviewIndependence,
    val reviewedAt: Instant
) {
    init { require(reviewerVersion > 0) }
}

enum class ReviewIndependence { INDEPENDENT_PROVIDER, INDEPENDENT_EVALUATOR, LIMITED, DETERMINISTIC_ONLY }

interface ReviewService {
    suspend fun review(request: ReviewRequest): ReviewResult
}

enum class CoreTraceSeverity { DEBUG, INFO, WARNING, ERROR, SECURITY }

enum class CoreTraceEventType {
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

/**
 * attributes must contain allow-listed, redacted metadata only. Payloads, prompts,
 * credentials and raw provider responses belong in separately protected artifacts.
 */
data class CoreTraceEvent(
    val eventId: TraceEventIdentifier,
    val schemaVersion: Int = 1,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val eventType: CoreTraceEventType,
    val severity: CoreTraceSeverity,
    val actor: CoreActor,
    val projectId: ProjectIdentifier?,
    val taskId: TaskIdentifier?,
    val parentTaskId: TaskIdentifier?,
    val correlationId: CoreIdentifier,
    val causationId: TraceEventIdentifier?,
    val component: String,
    val action: String,
    val targetType: String?,
    val targetId: String?,
    val resultCode: String?,
    val approvalRef: String?,
    val evidenceRefs: List<String> = emptyList(),
    val dataClassification: CoreDataClassification = CoreDataClassification.INTERNAL,
    val redactionApplied: Boolean,
    val secretValuesStored: Boolean = false,
    val durationMs: Long? = null,
    val safeAttributes: Map<String, String> = emptyMap()
) {
    init {
        require(schemaVersion > 0)
        require(component.isNotBlank() && action.isNotBlank())
        require(durationMs == null || durationMs >= 0)
        require(!secretValuesStored) { "Trace events must never contain secret values" }
        require(safeAttributes.keys.none { it.isBlank() })
    }
}

data class TraceQuery(
    val projectId: ProjectIdentifier? = null,
    val taskId: TaskIdentifier? = null,
    val eventTypes: Set<CoreTraceEventType> = emptySet(),
    val after: Instant? = null,
    val before: Instant? = null,
    val limit: Int = 100
) {
    init {
        require(limit in 1..500)
        require(after == null || before == null || !after.isAfter(before))
    }
}

data class TraceAccessContext(val actor: CoreActor, val allowedProjects: Set<ProjectIdentifier>)

data class TracePage(
    val events: List<CoreTraceEvent>,
    val nextCursor: String? = null
)

interface TraceRepository {
    suspend fun append(event: CoreTraceEvent)
    suspend fun query(query: TraceQuery, access: TraceAccessContext): TracePage
}

interface TraceService {
    suspend fun record(event: CoreTraceEvent)
    suspend fun query(query: TraceQuery, access: TraceAccessContext): TracePage
}

enum class CoreAction {
    READ_MEMORY, WRITE_MEMORY, DELETE_MEMORY, DELEGATE_AGENT,
    EXECUTE_TOOL, EXTERNAL_SIDE_EFFECT, EXPORT_DATA
}

data class CorePolicyRequest(
    val actor: CoreActor,
    val projectId: ProjectIdentifier,
    val action: CoreAction,
    val classification: CoreDataClassification,
    val resourceId: String? = null
)

data class CorePolicyDecision(
    val allowed: Boolean,
    val reasonCode: String,
    val requiresOwnerApproval: Boolean = false
) {
    init { require(reasonCode.isNotBlank()) }
}

interface CorePolicyEngine {
    /** Must fail closed when policy is missing, stale, or contradictory. */
    suspend fun evaluate(request: CorePolicyRequest): CorePolicyDecision
}
