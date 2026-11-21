# Bridge and Agent Contract Compatibility Audit

Date: 2026-11-19
Status: initial source comparison; no protocol changes made.

## Scope inspected

- Android Bridge protocol and Core task domain on `glafira/core-parallel-execution-foundation`.
- Desktop queue/runtime on `codex/p0-desktop-queue-persistence`.
- iOS BridgeKit on `feature/glafira-ios-bridgekit-foundation`.
- Earlier Android Bridge protocol specification on `feature/glafira-bridge-protocol`.

These branches are separate snapshots. This audit does not imply they are merged or tested together.

## Confirmed compatibility observations

### 1. Android and iOS signing envelope

The Kotlin and Swift implementations both define the first Bridge envelope with:
- protocol version;
- request ID and idempotency key;
- device and project IDs;
- operation;
- issue/expiry timestamps;
- string-map payload;
- HMAC-SHA256 signature.

Both canonicalize fields in the same order and sort payload keys. Both prefix each field with its UTF-16 code-unit length and then encode the canonical string as UTF-8. Swift explicitly documents that it matches Kotlin. The two implementations should continue to be protected by shared, checked-in golden vectors, including non-ASCII payload values and payload key ordering.

### 2. Operation-set drift

The Android Kotlin source on the inspected parallel-execution branch includes:
- PROJECT_READ
- CONVERSATION_READ
- CONVERSATION_APPEND
- APPROVAL_REQUEST
- DESKTOP_TASK_CLAIM
- DESKTOP_TASK_RESULT

The inspected iOS Swift BridgeKit source includes only the first four operations. The Android operation allowlist also defines task claim/result payload fields that the iOS enum cannot currently represent.

This is a real source-level compatibility gap for Desktop task polling/result delivery. It must be resolved through an explicit versioned contract and compatibility tests, not by silently assuming that all v1 peers understand every operation.

### 3. Status and result model gap

The initial Bridge response status vocabulary is currently aligned around ACCEPTED, PENDING_APPROVAL, SUCCEEDED, FAILED and CANCELLED. The wider Core task state machine includes QUEUED, RUNNING, WAITING_FOR_NETWORK, WAITING_FOR_OWNER and RETRY_SCHEDULED. The Desktop runtime has its own durable task state transitions.

The transport response status must not be mistaken for the durable task lifecycle. Define a separate task-status/result event model and explicit mapping to each platform's local state. Preserve OUTCOME_UNKNOWN/reconciliation semantics for uncertain external effects.

### 4. Multiple task domain models

The inspected tree contains:
- Android `GlafiraTask` and `GlafiraTaskState`;
- Core `AgentTaskRequest` / `AgentTaskResult`;
- Desktop `DesktopTaskEnvelope` / `DesktopTaskState`;
- Bridge `GlafiraBridgeRequest` / `GlafiraBridgeResponse`;
- iOS `BridgeRequest` / `BridgeResponse`.

These models serve different layers and need not become one giant class. They do need a canonical mapping for task identity, project scope, parent/dependency IDs, idempotency key, issuer/target device, capability scope, risk/approval reference, deadline/resource budget, attempt/lease, terminal outcome, structured result and artifact integrity metadata.

### 5. Desktop Agent protocol is not yet represented in the iOS BridgeKit

The current iOS package implements Android-host pairing and a limited Bridge client. It is not yet an Agent fleet client and should not be extended with Desktop-only worker privileges. Android/iOS remain owner clients; Windows Desktop owns Agent enrollment and dispatch.

## Required canonical contract decisions (C1)

1. Keep transport request identity separate from logical task identity.
2. Define stable TaskEnvelope, TaskResultEnvelope, TaskStatusEvent, AgentHeartbeat, AgentCapabilities, EnrollmentRequest/Approval, Lease and Revocation schemas.
3. Specify protocol version negotiation and additive-operation compatibility. A v1 peer must reject an unknown operation safely; it must not interpret it as a generic action.
4. Specify task state vs transport response state separately.
5. Define task lease/fencing semantics and how late results are reconciled after lease expiry.
6. Define dependency readiness and parent/child failure semantics.
7. Define retry classification: only explicitly registered safe/idempotent work can auto-retry; uncertain writes wait for owner reconciliation.
8. Define payload/artifact size limits, chunking, integrity hashes and ACK behavior.
9. Define per-device identity, capability scopes, owner approval references and revocation checks at admission and execution.
10. Generate language-specific models and golden vectors from the reviewed schema, or maintain equivalent conformance tests that prove byte-for-byte compatibility.

## Workstream ownership after contract review

- C1 schema and golden vectors: shared contract owner; no platform UI files.
- Android adapter: Android Bridge/Core owners.
- iOS adapter: iOS BridgeKit owners.
- Desktop broker/client adapter: Windows Desktop owners.
- Windows Agent worker adapter: Agent owners.
- Cross-platform conformance harness: CI owner.

Avoid simultaneous edits to the same Kotlin/Swift protocol files. Each adapter change must name its exact base SHA and contract version.

## Acceptance tests

- Kotlin ↔ Swift HMAC golden vectors pass byte-for-byte for every supported operation and Unicode payload fixture.
- Unknown version/operation, malformed ID, expired request, future timestamp, oversized payload and unknown fields are rejected.
- Replayed transport request is rejected/deduplicated without creating a second logical task.
- Duplicate task delivery to two Agents cannot create two live leases.
- Wrong project, device, scope, capability or approval reference is rejected.
- Dependency child is not dispatched before all required dependencies succeed.
- Agent crash and host restart recover leases without blindly replaying uncertain writes.
- Late result from an expired lease is reconciled and cannot overwrite a newer fenced owner.
- Result ACK loss causes result redelivery only, not handler re-execution.
- Revoked Agent cannot claim new work after reconnect.

## Next action

Review and approve the canonical schemas and versioning rules first. Then implement the Android, iOS, Desktop and Windows Agent adapters concurrently in isolated branches. Do not claim end-to-end compatibility until the cross-platform conformance and failure-injection tests run on the exact integrated commit.
