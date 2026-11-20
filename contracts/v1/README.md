# GLAFIRA Task and Agent Contracts v1

Status: initial cross-platform contract draft; requires review before platform adoption.

## Scope
This schema is the shared language-neutral source for task dispatch and Windows Agent lifecycle. Android and iOS remain owner-facing clients. Windows Desktop is the Agent host/coordinator. GLAFIRA Core remains the policy and approval authority.

## Message families
- `TaskEnvelope`: immutable task intent, project scope, idempotency key, typed task kind, payload reference and hash, dependencies, risk/approval metadata and resource budget.
- `TaskLease`: time-bounded assignment with monotonically increasing fencing token.
- `TaskResultEnvelope`: durable outcome bound to the lease and fencing token.
- `TaskStatusEvent`: ordered task lifecycle event; distinct from Bridge transport response.
- `AgentHeartbeat`: liveness, capabilities, active leases and resource usage.
- `AgentEnrollmentRequest` / `AgentEnrollmentDecision`: owner-reviewed registration and explicit granted capability set.

## Security rules
1. Never place passwords, API keys, private keys, cookies, bearer tokens or reusable credentials in these payloads.
2. Payloads are references to separately authorized, integrity-checked data, not arbitrary executable commands.
3. Desktop may coordinate leases but cannot grant Core permissions or bypass owner approval.
4. Agent capabilities are allowlisted typed handlers; no generic shell, arbitrary code execution or unrestricted filesystem access.
5. External side effects require Core policy admission and owner approval where applicable.
6. Lease expiry does not prove that a side effect did not occur. Use `OUTCOME_UNKNOWN` and reconciliation; do not blindly retry.
7. Results are accepted only for the current lease/fencing token. Late results cannot overwrite a newer lease owner.
8. Replayed request IDs and idempotency keys must be deduplicated durably.

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
- Duplicate task delivery is deduplicated by stable task ID/idempotency key.
- Two Agents cannot hold a valid lease for the same task/fencing generation.
- A stale lease result cannot overwrite a newer result.
- Dependency children are not dispatched before required dependencies succeed.
- Revoked Agents cannot claim tasks after reconnect.
- Crash/restart/network-loss recovery never blindly repeats an uncertain external side effect.

## Current limitations
This is a schema and contract draft, not a running broker, transport, enrollment service or signed application. Platform implementations must be integrated and tested before interoperability is claimed.

## Generated cross-platform DTOs

`generate-models.mjs` derives each message family separately from the canonical schema. The CI job is pinned to quicktype 26.0.0 and publishes a SHA-scoped artifact containing:

- Kotlin data classes using kotlinx.serialization (Kotlin serialization plugin/runtime must be enabled by the consuming module).
- Swift Codable structs.
- C# models using System.Text.Json.

The generator keeps the seven message families separate instead of flattening the top-level `oneOf` into one permissive object. Generated DTOs provide typed transport shapes; the canonical JSON Schema validator remains authoritative for conditional and cross-field rules such as mandatory owner approval for external side effects and reconciliation requirements for `OUTCOME_UNKNOWN`. Do not treat deserialization alone as policy validation.

Generated artifacts are build outputs at this stage. Platform modules must adopt the DTOs deliberately and run the shared lifecycle fixtures before interoperability is claimed.


## Windows Agent heartbeat and owner profile

The Windows Agent uses the signed `DESKTOP_AGENT_HEARTBEAT` Bridge operation with the `desktop.task.claim` scope and an empty payload. The Hub responds only after the credential, device identity and owner approval are validated. An accepted response contains the server-owned Agent ID, allowlisted capabilities and maximum parallelism. A pending or revoked Agent receives a failure response with no capability profile and does not refresh liveness.

Heartbeat is transport/liveness only: it does not claim a task, renew a task lease or grant any permission. The Agent must use the returned owner profile when it later submits a task claim. The Hub still compares claim capabilities and parallelism against its own owner-approved record.

## Desktop task lease fencing

For the Bridge `DESKTOP_TASK_CLAIM` response, a successful lease must include opaque `leaseId` and positive `fencingToken` alongside the task fields. Every `DESKTOP_TASK_RESULT` update (including progress) must echo both values. The Desktop worker persists the lease identity with its local task record and must not submit a result without it. The Desktop Hub accepts updates only when the device, task, lease ID and fencing token all match the currently active lease; stale workers must be rejected.

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
