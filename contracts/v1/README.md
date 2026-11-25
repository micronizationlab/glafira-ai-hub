# GLAFIRA Task and Agent Contracts v1

Status: cross-platform message-shape draft; project-coordinator election/claims are INACTIVE per ADR-0001 and must not be used for runtime coordination.

## Scope
This schema is the shared language-neutral source for task dispatch and participating-node lifecycle. Android and Windows Desktop Core installations retain independent task authority; Windows Hub may coordinate its local Agent fleet. A Windows Agent remains a subordinate worker unless explicitly provisioned as a full GLAFIRA node. The legacy project-coordinator message family is not active for runtime election, task claims or heartbeats. See ADR-0001.

## Message families
- `TaskEnvelope`: immutable task intent, project scope, idempotency key, typed task kind, payload reference and hash, dependencies, risk/approval metadata and resource budget.
- `TaskLease`: time-bounded assignment with monotonically increasing fencing token.
- `TaskResultEnvelope`: durable outcome bound to the lease and fencing token.
- `TaskStatusEvent`: ordered task lifecycle event; distinct from Bridge transport response.
- `AgentHeartbeat`: liveness, capabilities, active leases and resource usage.
- `AgentEnrollmentRequest` / `AgentEnrollmentDecision`: owner-reviewed registration and explicit granted capability set.
- `AgentRevocationRequest` / `AgentRevocationDecision`: owner-requested revocation bound to the exact enrollment generation, including the last accepted fencing token and explicit active-lease disposition.

## Security rules
1. Never place passwords, API keys, private keys, cookies, bearer tokens or reusable credentials in these payloads.
2. Payloads are references to separately authorized, integrity-checked data, not arbitrary executable commands.
3. No global project coordinator is active. The Windows Desktop Hub may coordinate its local Agent transport fleet but cannot grant Core permissions or bypass owner approval.
4. Agent capabilities are allowlisted typed handlers; no generic shell, arbitrary code execution or unrestricted filesystem access.
5. External side effects require Core policy admission and owner approval where applicable.
6. Lease expiry does not prove that a side effect did not occur. Use `OUTCOME_UNKNOWN` and reconciliation; do not blindly retry.
7. Results are accepted only for the current lease/fencing token. Late results cannot overwrite a newer lease owner.
8. Replayed request IDs and idempotency keys must be deduplicated durably.
9. Revocation targets an exact enrollment ID. A revoked or replaced Agent cannot reuse an older enrollment, and an uncertain active lease is reported as `OUTCOME_UNKNOWN` for owner reconciliation.

## Compatibility
- Schema version 1 is explicit. Unknown major versions and unknown task types are rejected.
- Additive optional fields require a documented compatibility review. Do not silently reinterpret unknown fields.
- Bridge response status is transport-level. Task lifecycle is represented only by `TaskStatusEvent`.
- A successful task result requires a result reference and SHA-256 digest.
- A result with `OUTCOME_UNKNOWN` must set `requiresReconciliation=true` and `retryable=false`.

## Signing golden vector
The Bridge HMAC canonicalization fixture matches the existing Kotlin and Swift v1 envelope implementations. Fields are concatenated in this exact order: protocolVersion, requestId, idempotencyKey, deviceId, projectId, operation, issuedAtEpochMs, expiresAtEpochMs, then payload entries sorted by key. Each field is encoded as UTF-16-code-unit-length, colon, value; the concatenated string is UTF-8 encoded before HMAC-SHA256.

Fixture:
- Secret bytes: `00 01 02 ... 1f` (test only; never a production credential).
- Payload: `conversationId=chat-0001`, `messageText=Привет 🌍`.
- Canonical UTF-8 string:

```text
1:112:request-00019:idem-000111:device-000112:project-000119:CONVERSATION_APPEND13:173000000000013:173000030000014:conversationId9:chat-000111:messageText9:Привет 🌍
```

- Expected HMAC-SHA256: `bb9e7f042e94f5910e877b4f660f20807a33c83cabe9afa47c3820413465cf91`.

## Required conformance tests
- Kotlin and Swift produce identical canonical bytes and HMAC for the fixture above.
- Payload key insertion order does not change the signature.
- Unicode, malformed identifiers, unsupported versions, expired/future timestamps and oversized payloads are handled consistently.
- Unknown operation/task type is rejected; it is never mapped to a generic executor.
- Revocation is never encoded as an enrollment decision. The pre-adoption draft value `AgentEnrollmentDecision.decision=REVOKED` is rejected; callers must send the exact-generation `AgentRevocationRequest` and consume its decision.
- Duplicate task delivery is deduplicated by stable task ID/idempotency key.
- Two Agents cannot hold a valid lease for the same task/fencing generation.
- A stale lease result cannot overwrite a newer result.
- Dependency children are not dispatched before required dependencies succeed.
- Revoked Agents cannot claim tasks after reconnect.
- Crash/restart/network-loss recovery never blindly repeats an uncertain external side effect.

## Current limitations
This is a schema and contract draft, not a running broker, transport, enrollment service or signed application. Platform implementations must be integrated and tested before interoperability is claimed.

## Generated cross-platform DTOs

`generate-models.mjs` derives each of the nine message families separately from the canonical schema. The CI job is pinned to quicktype 26.0.0 and publishes a SHA-scoped artifact containing:

- Kotlin data classes using kotlinx.serialization (Kotlin serialization plugin/runtime must be enabled by the consuming module).
- Swift Codable structs.
- C# models using System.Text.Json.

The generator keeps the seven message families separate instead of flattening the top-level `oneOf` into one permissive object. Generated DTOs provide typed transport shapes; the canonical JSON Schema validator remains authoritative for conditional and cross-field rules such as mandatory owner approval for external side effects and reconciliation requirements for `OUTCOME_UNKNOWN`. Do not treat deserialization alone as policy validation.

Generated artifacts are build outputs at this stage. Platform modules must adopt the DTOs deliberately and run the shared lifecycle fixtures before interoperability is claimed.


## Windows Agent heartbeat and owner profile

The Windows Agent uses the signed `DESKTOP_AGENT_HEARTBEAT` Bridge operation with the `desktop.task.claim` scope and an empty payload. The Hub responds only after the credential, device identity and owner approval are validated. An accepted response contains the server-owned Agent ID, allowlisted capabilities and maximum parallelism. A pending or revoked Agent receives a failure response with no capability profile and does not refresh liveness.

Heartbeat is transport/liveness only: it does not claim a task, renew a task lease or grant any permission. The Agent must use the returned owner profile when it later submits a task claim. The Hub still compares claim capabilities and parallelism against its own owner-approved record.

## Desktop task lease fencing

For the Bridge `DESKTOP_TASK_CLAIM` response, a successful lease must include the canonical `ownerCoreId`, positive `ownerGeneration`, opaque `leaseId`, and positive `fencingToken` alongside the task fields. The Agent validates and preserves the owner identity and generation in its typed task envelope. Every `DESKTOP_TASK_RESULT` update (including progress) must echo all four values. The Desktop worker persists that identity with its local task record and must not submit a result without it. The Desktop Hub accepts updates only when the device, task, owner Core ID, owner generation, lease ID and fencing token match the durable task/lease; stale workers must be rejected.

### Durable result acknowledgement

Bridge v1 task updates carry the server-issued `leaseId` and positive `fencingToken`.
A terminal result may add `resultEncoding=json`: `result` is then a JSON string or
JSON `null`, preserving literal values such as `none` without a sentinel collision.
Other encodings and non-string/non-null JSON values are rejected.

After committing a result (or recognizing the exact duplicate), Windows Hub returns
`accepted=true`, `taskId`, `leaseId`, `fencingToken`, `state`, and `resultSha256`.
The digest is lowercase SHA-256 of the exact UTF-8 `result` field in the signed request.
The Desktop outbox validates that acknowledgement before marking the terminal queue
record delivered. Unacknowledged terminal records cannot be evicted. They survive a
restart and are retried without running the handler again. Backpressure stops new
claims when protected local history has no room.

### Bounded diagnostics artifact acknowledgement

`DESKTOP_TASK_ARTIFACT` uses the existing `desktop.task.result` scope and uploads one
typed, already-redacted diagnostics snapshot for an active task lease. Its signed payload
contains `contractVersion`, `artifactId`, `taskId`, `ownerCoreId`, `ownerGeneration`,
`leaseId`, `fencingToken`, `schemaId`, `schemaVersion`, `contentType`, `byteLength`,
`sha256`, `redactionStatus`, `createdAtEpochMs`, and canonical `payloadBase64`.

Version 1 accepts only schema `glafira.agent.diagnostics.snapshot` version 1,
`application/json`, `REDACTED`, a JSON object, and 1–32768 decoded bytes. The Hub verifies
the decoded length and SHA-256 in constant time, binds the artifact to the authenticated
Agent, project, owner generation, active lease and fencing token, then encrypts the payload
at rest. A stale binding is rejected and an existing artifact ID cannot be overwritten.

After the durable commit the Hub returns `accepted=true`, a `duplicate` flag, and the exact
artifact/task/owner/lease/fence/schema/length/digest binding. An identical retry returns a
duplicate acknowledgement even after the terminal result removed the lease, which closes
the lost-ACK window without accepting a different payload. This defines Hub acceptance
only; it does not enable any Windows Agent task handler or cause diagnostics collection.


## Dual-Core task ownership transfer v1

`dual-core-ownership.schema.json` defines the first language-neutral wire shape for a canonical task ownership record and an explicit signed transfer between two Core installations. The transfer binds the exact task/project, old and new Core IDs, current and next owner generation, canonical state version, immutable payload digest, idempotency key, effect-risk class, approval reference, nonce, validity window, signing-key epoch and public-key fingerprint.

The owner-facing read path is the signed Bridge v1 `OWNERSHIP_TRANSFER_READ` operation.
It requires the dedicated `ownership.read` device scope and an exact `transferId` payload.
The Windows Hub returns either `{available:false}` or the exact persisted flat transfer
envelope. Every successful response includes the original `protocolVersion` and `requestId`;
clients must reject a mismatched response, unexpected result fields, a different project or
transfer ID, and any envelope whose old-owner key is not independently trusted. The response
never supplies or upgrades that trust anchor. Android and iOS remain read-side projections and
cannot issue transfers or leases.

### Mandatory semantic checks

JSON Schema validates field types and bounds. The shared fixture verifier additionally enforces invariants that JSON Schema cannot express portably:

- old and new Core IDs must differ;
- the new owner generation must equal the old generation plus exactly one;
- transfer expiry must be later than issue time;
- EXTERNAL_SIDE_EFFECT, FINANCIAL and DESTRUCTIVE transfers require a non-null approval reference;
- unknown fields are rejected.

The signature covers every transfer field except `signature`, in the exact field order in `verify-fixtures.mjs`. Each value is encoded as UTF-16-code-unit-length, colon, value; null is the literal `null`; the concatenated string is UTF-8 encoded before ECDSA P-256/SHA-256 signing. Signatures are Base64-encoded IEEE P1363 fixed-width 64-byte `r || s` values (`signatureFormat=IEEE_P1363_FIXED_64`), not ASN.1 DER. Android/JCA implementations must convert DER output to this exact representation before transport; .NET implementations must request `DSASignatureFormat.IeeeP1363FixedFieldConcatenation` explicitly. The canonicalization fixture pins the bytes and field order across implementations.

**Important limitation:** the current fixture's signature is deliberately a placeholder and is not cryptographically valid. The schema and canonicalization checks do not verify a signature, establish a trusted old-Core public key, enforce key revocation, or persist a transfer inbox/outbox. Production adoption requires platform-level ECDSA verification against the authenticated old-Core identity, durable nonce replay protection, atomic owner-generation compare-and-set, and adversarial two-Core acceptance. No failover or task ownership mutation is enabled by this schema-only change.

Android and iOS owner clients may maintain a non-authoritative ownership projection. They must resolve the old Core's public key by the authenticated Core ID, key epoch and SHA-256 fingerprint; verify the fixed-width P-256 signature; require the current time to be inside the signed validity window; and advance only from the exact recorded old owner/generation and state binding to the signed next generation. An exact transfer retry is idempotent, while a reused transfer ID, stale generation, divergent state or untrusted key fails closed without replacing the projection. This projection does not issue leases, authorize execution or make a mobile client a Hub; Windows Hub remains authoritative.


## Project coordinator and multi-node participation v1 (LEGACY / INACTIVE)

Historical note: the following schema remains for compatibility and design traceability only. It is not a production runtime contract and must not be used for GitHub-backed leader election or task claims. The separate `project-coordinator.schema.json` defines four versioned messages:
- `ProjectNodeRecord`: enrolled Android, Windows Desktop, iOS or Windows Agent installation, with platform, capabilities, enrollment/credential epochs, liveness sequence and explicit coordinator eligibility.
- `CoordinatorLeaseRecord`: project coordinator identity, monotonic generation, lease, heartbeat sequence and node signature.
- `CoordinatorClaimRequest`: signed compare-and-swap claim against the generation and lease observed by the candidate.
- `ProjectTaskClaimRecord`: task claim bound to coordinator generation, task state version, idempotency key and fencing token.

The fixtures and validator preserve the historical message shape only. The prior proposal's rule that a subordinate `WORKER_AGENT` could not elect itself is not an active runtime feature; no Android, Windows, iOS or Agent installation participates in GitHub-backed global election. Per-task Core ownership and the Windows Hub's local Agent-fleet role remain separate.

The schema describes historical message shape only. The prior GitHub ref compare-and-swap algorithm is superseded by ADR-0001. The model generator's DTOs may remain for compatibility but must not be wired to runtime coordination without a new owner-approved ADR.
