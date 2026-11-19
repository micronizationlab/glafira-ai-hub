# GLAFIRA CORE — Foundation Technical Specification

**Status:** proposed implementation baseline; documentation only until code and acceptance tests are merged.  
**Priority:** 1 — GLAFIRA Core.  
**Depends on:** existing provider registry/coordinator and the persistent task queue contract.  
**Must precede:** autonomous external actions, event automation, skill execution, visual workflow execution and cross-device task synchronization.

## 1. Purpose and authority

GLAFIRA Core is the single authority for context assembly, memory access, agent delegation, review policy, tool authorization and task outcome reporting.

Authority order:
1. The owner defines goals, approves policies and retains final authority.
2. GLAFIRA Core coordinates tasks and enforces project boundaries and permissions.
3. Specialist agents reason or execute only within explicit role and task scopes.
4. Model providers are replaceable inference backends. A provider is not an authority and cannot grant permissions.
5. Skills, retrieved documents, messages, websites, files and demonstrations are untrusted input unless separately verified.

No model-generated text, memory item, reviewer verdict or skill may modify the authority model, approval policy, credential permissions or production configuration.

## 2. Core module boundaries

Keep these as separate interfaces with one application-scoped Core owner:

- **CoreCoordinator** — plans, routes, delegates and synthesizes.
- **MemoryService / MemoryRepository** — stores and retrieves owner-approved memory.
- **AgentRegistry** — resolves provider-independent role definitions and capability scopes.
- **AgentExecutor** — executes a typed task through the existing task queue and approved handlers.
- **ReviewService** — obtains an independent review and applies a deterministic review policy.
- **TraceService / AuditRepository** — appends structured lifecycle and security events.
- **PolicyEngine** — evaluates project, role, task, data-classification and action permissions.
- **ContextAssembler** — builds the minimum permitted context for a particular agent/provider.
- **ProviderRegistry** — existing provider adapters; Core remains provider-independent.

No module may write directly around PolicyEngine or bypass the shared task executor. Runtime workers, interactive UI and future Bridge adapters must call the same application-scoped Core interfaces.

## 3. Unified memory model

### 3.1 Memory classes and scopes

Memory is a governed store of durable facts and decisions, not a transcript dump and not a replacement for source documents.

Required scopes:
- **OWNER** — owner preferences, standing instructions and explicitly approved personal context.
- **PROJECT** — facts, goals, decisions and operating rules belonging to one project.
- **AGENT** — role-specific procedural learnings and validated agent configuration; never a private cross-project memory.
- **CONVERSATION** — durable facts explicitly promoted from a conversation, linked to its source.
- **WORKING** — short-lived task context; expires automatically and is not promoted by default.

Required data classes: PUBLIC, INTERNAL, CONFIDENTIAL and RESTRICTED. The project policy determines which classes may be sent to each provider or agent. RESTRICTED data is local-only unless the owner explicitly configures a narrowly scoped exception.

### 3.2 Memory record

Each record has:
- memoryId (UUID), schemaVersion;
- scopeType and scopeId (owner/project/agent/conversation/task);
- kind (FACT, PREFERENCE, DECISION, INSTRUCTION, PROCEDURE, SUMMARY);
- value or encrypted value reference;
- dataClassification;
- status (PROPOSED, CONFIRMED, DISPUTED, SUPERSEDED, EXPIRED, DELETED);
- confidence and confidenceBasis (owner-confirmed, source-derived, agent-inferred);
- provenance: source type, source ID, author/actor and source timestamp;
- createdAt, updatedAt, validFrom, expiresAt;
- createdBy, lastModifiedBy, optional supersedesMemoryId;
- optional tags and source citation/reference.

A memory inferred by an agent is PROPOSED by default. Explicit owner instructions can be marked CONFIRMED. Conflicting records are retained as a conflict set until resolved; the system must not silently overwrite a confirmed owner decision.

### 3.3 Storage and retrieval

- Use the existing Core persistence boundary and migration framework; do not create a second independent task/memory database without a reviewed migration decision.
- Memory tables are separate from queue tables, but share the Core database transaction boundary when the queue implementation permits it. This allows task completion, memory promotion and trace append to commit consistently.
- Encrypt confidential/restricted memory values at rest using an Android Keystore-protected data key and authenticated encryption. Never store keys in SQLite or export device-bound keys in backups.
- Search filters by scope, classification, status and expiry before content is returned.
- Initial implementation: deterministic metadata/tag search and bounded in-process text search after decrypting only permitted candidate records. Do not create a plaintext full-text index of confidential/restricted memory.
- Semantic/vector retrieval is a later phase. Any remote embedding provider must pass the same data-sharing policy as a model call; local embeddings are preferred for restricted data.
- ContextAssembler returns only the minimum relevant records, with provenance and memory IDs, for the requesting project/role/provider.
- Retrieved memory is context data, never executable instructions that override system or owner policy.

### 3.4 Owner controls and lifecycle

Owner controls must support inspect, confirm, correct, dispute, supersede, expire, export and delete. Deletion must cover the active record and searchable/indexed derivatives; backups follow the documented backup retention and restore policy. A delete tombstone may remain in the audit trail without retaining the deleted content.

Do not automatically train/fine-tune models on memory or raw conversation history. Promotion from conversation to durable memory must be explicit, policy-governed and traceable.

### 3.5 Proposed repository contract

Kotlin-level interface shape (names are normative; exact package and suspend/Flow details may follow the existing code style):

```kotlin
interface MemoryRepository {
    suspend fun put(record: MemoryRecord, actor: CoreActor): MemoryWriteResult
    suspend fun get(id: MemoryId, access: MemoryAccessContext): MemoryRecord?
    suspend fun search(query: MemoryQuery, access: MemoryAccessContext): List<MemoryRecord>
    suspend fun update(id: MemoryId, change: MemoryChange, actor: CoreActor): MemoryWriteResult
    suspend fun delete(id: MemoryId, actor: CoreActor): MemoryDeleteResult
    suspend fun export(query: MemoryExportQuery, actor: CoreActor): MemoryExport
}
```

Every method receives an access context or actor. There is no unscoped get-all method exposed to agents.

## 4. Agent contracts and orchestration

### 4.1 Role definition

A role is provider-independent and includes:
- roleId, version, display name and purpose;
- system instruction reference (versioned and controlled);
- allowed project scopes and data classifications;
- allowed tool IDs and action scopes;
- required input/output schema versions;
- review policy and escalation conditions;
- maximum context, time, step and cost budgets (enforced centrally in later Runtime work);
- allowed model capabilities and provider routing constraints.

Installing or assigning a role does not grant new tool or account permissions. The effective permission set is the intersection of owner policy, project policy, role scope and task grant.

### 4.2 Typed task envelope

Every agent invocation uses a typed envelope:
- taskId, parentTaskId, correlationId;
- projectId and requestingActor;
- roleId and roleVersion;
- input payload reference plus schema version;
- permitted memory IDs/context bundle;
- permitted tool/action scopes;
- deadline and resource budget;
- idempotency key;
- review requirement;
- trace/correlation metadata.

The result is typed:
- taskId, status, output payload and schema version;
- evidence/source references;
- tools requested/used (IDs and outcomes, not secrets);
- uncertainty and limitations;
- proposed memory promotions (never auto-confirmed);
- error/retry classification.

Use existing persistent task lifecycle and executor contracts. Do not introduce a parallel task queue or allow a model to invent executable handler names. The executor resolves only registered handler IDs.

### 4.3 Core interfaces

```kotlin
interface AgentRegistry {
    fun resolve(roleId: RoleId, version: RoleVersion? = null): AgentRole?
}

interface AgentExecutor {
    suspend fun execute(request: AgentTaskRequest): AgentTaskResult
}

interface CoreCoordinator {
    suspend fun handle(request: CoreTaskRequest): CoreTaskResult
}
```

All implementations are application-scoped and share the same PolicyEngine, MemoryService and TraceService. Provider adapters receive a purpose-built context, not direct database access.

## 5. Independent Reviewer

### 5.1 Purpose and limits

Reviewer is a separate evaluation step, not a second authority. It assesses whether a result satisfies the task contract and policy. It cannot grant permissions, execute tools, approve external side effects or rewrite the owner's instructions.

### 5.2 Review request and verdict

Review request includes task specification, acceptance criteria, candidate output, schema, evidence references, relevant non-secret trace summary and permitted source context. It excludes credentials and unrelated project memory.

Verdicts:
- **ACCEPT** — criteria satisfied with sufficient evidence.
- **REVISE** — specific correctable gaps; return structured findings to the executor.
- **REJECT** — material correctness, safety or policy failure.
- **ESCALATE** — ambiguity, insufficient evidence, conflicting instructions or high-impact decision requiring owner input.

Each finding contains criterionId, severity, explanation, evidence reference and remediation. Review records include reviewer role/version, provider/model identifier, review policy version, timestamp and verdict.

### 5.3 Independence policy

- Reviewer receives the task contract and candidate result, not the executor's hidden reasoning.
- Prefer a different model/provider or a separately configured evaluation path for high-risk reviews.
- If the same model/provider is used, mark independence as LIMITED; do not claim statistically or institutionally independent verification.
- Deterministic validators (schema, tests, checksums, permission checks) run alongside model review.
- High-impact or external actions still require the owner's approval under PolicyEngine, even after ACCEPT.
- Bound review attempts (default proposal: at most two revise cycles); then ESCALATE rather than looping indefinitely.
- A reviewer failure is not success: mark REVIEW_UNAVAILABLE and apply the task's fail-closed policy.

### 5.4 Review interface

```kotlin
interface ReviewService {
    suspend fun review(request: ReviewRequest): ReviewResult
}
```

The Core policy decides which task classes require review. Review policy and verdict are persisted and trace-linked to the originating task.

## 6. Trace and audit event format

### 6.1 Event envelope

Trace is append-only at the application API level. Events are structured and versioned:

```json
{
  "eventId": "uuid",
  "schemaVersion": 1,
  "occurredAt": "UTC ISO-8601",
  "recordedAt": "UTC ISO-8601",
  "eventType": "TASK_STARTED",
  "severity": "INFO",
  "actor": { "type": "AGENT", "id": "agent-id", "roleVersion": "1" },
  "projectId": "project-id",
  "taskId": "task-id",
  "parentTaskId": null,
  "correlationId": "correlation-id",
  "causationId": "previous-event-id",
  "component": "CoreCoordinator",
  "action": "agent.execute",
  "target": { "type": "ROLE", "id": "researcher" },
  "result": { "status": "STARTED", "errorCode": null },
  "approvalRef": null,
  "evidenceRefs": [],
  "dataClassification": "INTERNAL",
  "redaction": { "applied": true, "secretValuesStored": false },
  "durationMs": null
}
```

Payloads and provider responses are not copied wholesale into the trace. Store references to separately protected task artifacts where necessary. Never log passwords, API keys, OAuth tokens, session cookies, raw credential-bearing URLs or unredacted personal data.

### 6.2 Event taxonomy

Minimum event types:
- TASK_CREATED, TASK_QUEUED, TASK_STARTED, TASK_PAUSED, TASK_RESUMED, TASK_SUCCEEDED, TASK_FAILED, TASK_CANCELLED;
- AGENT_SELECTED, AGENT_DELEGATED, AGENT_RESULT_RECEIVED;
- MEMORY_PROPOSED, MEMORY_CONFIRMED, MEMORY_UPDATED, MEMORY_DISPUTED, MEMORY_DELETED;
- REVIEW_REQUESTED, REVIEW_ACCEPTED, REVIEW_REVISION_REQUESTED, REVIEW_REJECTED, REVIEW_ESCALATED, REVIEW_UNAVAILABLE;
- POLICY_CHECKED, PERMISSION_DENIED, APPROVAL_REQUESTED, APPROVAL_GRANTED, APPROVAL_REVOKED;
- TOOL_REQUESTED, TOOL_STARTED, TOOL_SUCCEEDED, TOOL_FAILED;
- PROVIDER_SELECTED, PROVIDER_FALLBACK, PROVIDER_TIMEOUT;
- CONTEXT_REDACTED, SECRET_REDACTION_TRIGGERED;
- BACKUP_STARTED, BACKUP_VERIFIED, RESTORE_STARTED, RESTORE_SUCCEEDED, RESTORE_FAILED.

### 6.3 Integrity, retention and queries

- TraceRepository exposes append and filtered read; agents receive read-only, task-scoped views.
- Enforce bounded pagination and project-level access filtering.
- Redact before persistence; test redaction at every event producer.
- Keep operational payloads minimal; configurable retention applies to event details and referenced artifacts.
- Later hardening: hash-chain events and authenticate checkpoints with a Keystore-protected key. A local hash chain detects accidental/tamper changes but is not proof against a fully compromised/rooted device.
- Backup must include trace data consistently with Core state; restore must preserve event IDs and verify integrity.

### 6.4 Trace interface

```kotlin
interface TraceService {
    suspend fun append(event: CoreTraceEvent)
    suspend fun query(query: TraceQuery, access: TraceAccessContext): TracePage
}
```

## 7. Policy and data-flow invariants

1. Owner > Core policy > project policy > role scope > task grant. A narrower rule wins.
2. Deny by default when policy is missing, stale or contradictory.
3. Agents never read SQLite directly and never receive vault secrets.
4. Provider calls receive only ContextAssembler output permitted for that provider and data class.
5. Reviewer is read-only and cannot approve actions.
6. External send/publish/purchase/delete/business-record mutation/payment actions require explicit owner approval or a narrowly scoped, revocable pre-authorization.
7. Trace records metadata and safe references, not secret-bearing content.
8. Memory write, task transition and trace event should be transactionally consistent where possible; if not, use an outbox/reconciliation mechanism and surface incomplete audit state.
9. Every retry is bounded and idempotent; every delegated task retains parent/correlation IDs.
10. Untrusted instructions from files, sites, messages, imported skills and demonstrations cannot change Core policy.

## 8. Delivery plan within Priority 1

### Core-1A — contracts and schema
- Add memory, agent, review and trace domain models.
- Add migration plan against the existing Core task database.
- Add policy/access context types and typed serialization.
- Add repository interfaces and fake/in-memory test implementations.
- No new autonomous handlers or external actions.

### Core-1B — durable memory and trace
- Implement SQLite repositories and migrations.
- Implement Keystore-backed field encryption and safe redaction.
- Implement owner inspection/correction/deletion APIs (UI can follow).
- Add scope isolation, expiry and export tests.
- Integrate task lifecycle events with TraceRepository.

### Core-1C — Reviewer and coordinator integration
- Implement ReviewService with deterministic validators plus provider adapter.
- Add review policy to CoreCoordinator.
- Add structured revise/escalate behavior and bounded retries.
- Keep all external side-effect handlers disabled pending explicit policy and approval tests.

### Core-1D — acceptance hardening
- Backup/restore consistency tests for memory and trace.
- Process death, database migration, corrupted record and key-loss tests.
- Cross-project and cross-agent privacy tests.
- Provider data-sharing and secret-redaction tests.
- End-to-end task → memory proposal → review → owner confirmation → trace verification.

## 9. Acceptance criteria

Core foundation is not complete until all applicable checks pass:

**Memory**
- Owner/project/agent/conversation scopes cannot read each other's restricted records.
- Proposed memory is not treated as confirmed.
- Owner correction, supersession, export and deletion behave deterministically.
- Expired memory is excluded from retrieval.
- Confidential values are encrypted at rest; no plaintext secret or restricted value appears in logs or indexes.
- Provider context contains only policy-permitted memory with provenance.

**Agents and coordinator**
- Unknown roles, handlers, schema versions and scopes fail closed.
- All invocations use typed contracts and the shared executor.
- Duplicate idempotency keys do not execute a task twice.
- Parent/correlation IDs survive delegation and retries.
- Provider timeout/fallback does not change permission boundaries.

**Reviewer**
- ACCEPT, REVISE, REJECT and ESCALATE are persisted and trace-linked.
- Reviewer cannot call tools, mutate memory or grant approvals.
- Same-provider review is labelled LIMITED.
- Review failure never becomes implicit acceptance.
- Revision loops are bounded.

**Trace**
- Every task state transition and policy/approval outcome produces an event.
- Secret-redaction tests cover event fields and referenced artifacts.
- Project-scoped reads enforce access boundaries.
- Pagination is bounded and event ordering is stable.
- Backup/restore preserves trace continuity and verifies integrity.

**Release gate**
- Unit and Android emulator integration tests pass.
- Backup & Recovery acceptance for Core tables passes before production autonomous Runtime is enabled.
- Bridge pairing/security acceptance passes before any APK release containing enabled device automation.
- No APK is built or published by this specification change.

## 10. Explicit non-goals for this specification

- No implementation of the visual workflow editor, event engine, demonstration recorder, cross-device sync or public skill marketplace.
- No automatic model training from conversations or memory.
- No autonomous external side effects.
- No new paid server/backend requirement.
- No claim that these contracts are implemented until code, CI and end-to-end evidence exist.

## 11. Relationship to existing project documents

- `GLAFIRA_CORE_SECURITY_AND_TOOLS.md`: authority, provider and tool security baseline.
- `GLAFIRA_INTELLIGENCE_CENTER.md`: role/skill/knowledge/model lifecycle; memory remains distinct from knowledge collections and model training.
- `GLAFIRA_BACKUP_AND_RECOVERY.md`: archive, restore and key recovery constraints.
- `IMPLEMENTATION_ROADMAP.md`: Core first, Backup second, Runtime third.

This document narrows those existing principles into implementable Core contracts. Where older roadmap text places unified memory after Runtime, this specification and the updated roadmap move operational unified memory into Priority 1 Core.
