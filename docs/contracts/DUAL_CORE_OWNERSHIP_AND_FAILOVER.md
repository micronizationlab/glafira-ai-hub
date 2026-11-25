# Dual-Core ownership and safe failover contract

**Status:** normative architecture contract; implementation and device acceptance remain separate release gates.  
**Scope:** Android Core and Windows Desktop Core, with iOS as an owner-facing peer and Windows Agent as a subordinate worker of Desktop Hub.  
**Security posture:** fail closed; no automatic external-side-effect replay.

## 1. Product and authority model

GLAFIRA has two independently useful Core installations:

- **Android Core** is a limited but independently useful owner node for chat, supported local tasks, approvals, durable queueing and result history.
- **Windows Desktop Core** is the primary high-capability owner node for complex tasks, local files, provider/model execution, connectors, long-running work and specialist agents.
- **iOS** is an owner-facing peer client. It does not become a third Core authority merely by pairing.
- **Windows Desktop Hub** is the authenticated transport/fleet host for Windows Agents. It is not a second policy authority inside Desktop Core.
- **Windows Agent** is a subordinate, typed worker. It cannot coordinate peers, grant permissions, change task ownership or approve external effects.

The two Cores must each retain useful independent capability. Neither is a permanent single point of failure. They do not share a mutable SQLite database and must not be treated as one globally writable queue.

**Authority is per task and per ownership domain.** Exactly one Core owns a task at a time and is the only Core allowed to mutate that task's canonical state, approval, retry policy, result acceptance and audit history. The owner Core is also the authority for the project data and grants it has not explicitly delegated. A peer receives a bounded replica or execution assignment, never implicit ownership of all data or permissions.

This resolves the phrase “Core is the only authority”: Core policy is authoritative within the owning Core; the architecture has two independent Core instances, not one global Core process.

## 2. Stable identities

Every installation creates a cryptographically random, persistent `coreId` and has:

- `accountId`: stable GLAFIRA account identity;
- `deviceId`: stable enrolled device identity;
- `coreId`: installation identity, distinct from device and account IDs;
- `coreKind`: `ANDROID` or `WINDOWS_DESKTOP`;
- `keyEpoch`: monotonic credential/key generation;
- `protocolVersion` and `schemaVersion`;
- owner-approved capabilities and revocation state.

### Signing and payload-encryption keys are separate

The current Android and Windows installation-identity key pairs are signing identities. The trusted-peer registry pins a peer's public signing key; it does not establish a payload-decryption key. The Android task-payload AES key is installation-local and must never be treated as a shared Core-to-Core encryption key.

Therefore, a signed outbox row or an app-local ciphertext is not yet a deliverable cross-Core message. Before any remote payload is sent, each installation must have a separately enrolled key-agreement public key bound to the same Core ID, key epoch and owner-confirmed peer record. The protocol must define authenticated envelope encryption (ephemeral key agreement, a reviewed KDF, AEAD, and canonical associated data binding event ID, source/recipient Core IDs, project/task IDs, owner generation, state version, sequence and payload digest). Do not reuse an ECDSA signing key for key agreement.

Acceptance must cover recipient/key-epoch mismatch, ciphertext tampering, replay, key rotation/revocation, missing local private key, crash during envelope creation and restore onto a new installation. A missing or mismatched decryption key is a hard failure; never regenerate a key and silently reinterpret old ciphertext. Cross-Core payload delivery remains disabled until this separate key lifecycle and envelope suite is implemented and reviewed.

A reinstall or restore onto a new installation creates a new `coreId`. It must not silently inherit live leases, credentials, approval grants or the previous installation's authority.

## 3. Canonical task ownership record

Each cross-Core task has one owner record:

| Field | Meaning |
|---|---|
| `taskId` | Globally stable task identifier |
| `projectId` | Project boundary |
| `ownerCoreId` | Current canonical state owner |
| `ownerGeneration` | Monotonically increasing fencing generation |
| `assignedExecutorCoreId` | Core/worker currently permitted to execute |
| `leaseId` / `fencingToken` | Time-bounded execution lease identity |
| `idempotencyKey` | Stable effect/task deduplication key |
| `payloadSha256` | Immutable task payload digest |
| `effectRisk` | `READ_ONLY`, `DRAFT`, `EXTERNAL_SIDE_EFFECT`, `FINANCIAL`, or `DESTRUCTIVE` |
| `approvalRef` | Exact owner approval binding, if required |
| `leaseExpiresAt` | Lease expiry; not proof that an external effect did not occur |
| `stateVersion` | Monotonic canonical task state version |

Ownership generation is monotonic and never reset on retry, restore or device reinstall. Every lease/result/ownership mutation carries the current generation and exact lease identity. A stale generation is rejected even if its request is correctly signed.

## 4. Assignment, execution and result authority

1. The owning Core validates the task, project, policy, risk, owner grant, deadline and idempotency key.
2. It persists the canonical task and a durable outbound event before sending an assignment.
3. The receiving Core/Hub verifies the signed envelope, owner identity, generation, payload digest, capability subset and expiry. It records an idempotent inbox receipt.
4. The receiver may execute only the exact delegated task scope. It cannot broaden capabilities or change the canonical approval.
5. The worker journals the lease and result before transmission. It returns a result bound to task ID, lease ID, fencing token, owner generation and result digest.
6. The owning Core validates the exact ACK and atomically commits the result, terminal state and trace/reconciliation event. A transport ACK alone does not prove that a task result was accepted by the owning Core.
7. The receiver retains its outbox record until the owning Core's exact durable ACK is received.

Desktop Hub may maintain a local lease/fleet journal to protect Windows Agent dispatch. That journal is a transport execution record. It must reconcile to the owning Desktop Core's canonical task state; it must not independently grant a task, approval or cross-Core ownership.

## 5. Safe handoff and failover

A planned handoff uses an explicit signed ownership-transfer record containing:

- task ID and project ID;
- old owner Core ID and generation;
- new owner Core ID and next generation;
- immutable task/payload digest and idempotency key;
- last canonical state version and checkpoint/result digest;
- allowed executor capabilities and effect risk;
- exact approval reference and its remaining validity, if any;
- transfer issue/expiry timestamps and transfer nonce.

The old owner first stops new execution and claims for that task, checkpoints durable state, and records the transfer as pending. The new owner accepts only the exact transfer and generation increment. The old owner is fenced from all subsequent state/result mutations.

A disconnected peer cannot declare itself the new owner merely because the other device is unreachable. If no reachable ownership coordinator or previously signed transfer grant can establish a new generation, the task remains waiting for reconciliation. Availability is deliberately sacrificed over split-brain execution.

Lease expiry only permits reconciliation. It does not prove the previous executor stopped or that an external effect did not happen.

## 6. Crash, timeout and uncertain outcomes

- **READ_ONLY:** replay may be permitted after lease expiry and exact generation/fence reconciliation, using the same idempotency key.
- **DRAFT:** preserve the draft and require reconciliation before re-execution if completion is uncertain.
- **EXTERNAL_SIDE_EFFECT / FINANCIAL / DESTRUCTIVE:** never blindly replay after timeout, crash, lost ACK or owner handoff. Set `OUTCOME_UNKNOWN`, `retryable=false`, and `requiresReconciliation=true`. Require source/provider reconciliation or an explicit owner decision.
- **Known rejection before dispatch:** mark rejected; no execution occurred.
- **Known unsent request:** retry may be permitted under the original task/effect idempotency key and valid owner grant.
- **Unknown transport outcome:** preserve the exact request/receipt in the outbox and reconcile. Do not create a new idempotency key to “try again”.

Task cancellation is a canonical state transition, not merely a local coroutine cancellation. A completion and cancellation race must have exactly one durable winner under compare-and-set/transaction semantics.

## 7. Per-Core inbox/outbox replication

Each Core maintains its own durable inbox and outbox. Events include:

- event ID, source Core ID, per-Core sequence, account/project/task IDs;
- owner generation and state version;
- event type, timestamp, idempotency key;
- immutable payload/result digest and bounded encrypted payload reference;
- causation/correlation IDs and signature/key epoch.

Delivery is at-least-once; processing is idempotent. Duplicate event IDs are acknowledged without reapplying mutations. Sequence gaps are detected and repaired by bounded snapshot/replay. Conflicting canonical writes are not resolved by last-write-wins. The task owner and ownership generation determine the valid writer.

Replication is data-minimized and project-scoped. Pairing or account membership does not grant access to every project, conversation, memory record, provider credential or connector.

## 8. Bridge and Hub boundaries

Bridge is authenticated transport, not authority. It must enforce:

- signed, versioned envelopes and strict schema validation;
- TLS identity pinning and owner-approved endpoint rotation;
- HMAC/device identity, replay protection and durable idempotency;
- capability subset and exact project/task scope;
- lease ID, fencing token, owner generation and result digest;
- revocation, expiry, reconnect and bounded request/response sizes.

The Windows Desktop Hub is the preferred coordinator for its Windows Agent fleet. Agent traffic is outbound from Agent to the pinned Hub. Do not expose arbitrary inbound ports or compile mutable LAN IP addresses into binaries. A phone-only account remains valid; Desktop is optional. Any Mother rendezvous/relay path is a separate, explicit, owner-enabled transport and must not silently become an authority.

## 9. Backup, restore and revocation

A portable backup may transfer validated project/task data and encrypted artifacts, but it never transfers:

- device-bound private keys, HMAC secrets or OS vault material;
- live leases, active ownership authority or pending one-time enrollment tickets;
- unexpired owner grants or approvals;
- replay caches or credentials that would allow a restored installation to impersonate the source.

Restore creates a new installation identity, imports data as a non-authoritative replica, marks interrupted work for owner review/reconciliation and requires fresh pairing/approval. Revocation invalidates the device credential and all outstanding leases for that device; delayed results from the revoked generation are rejected.

## 10. Minimum acceptance matrix

Before autonomous failover is enabled, test at least:

1. Android Core operates supported local tasks while Desktop is offline.
2. Desktop Core operates supported local tasks while Android is offline.
3. Both peers reconnect and reconcile inbox/outbox without duplicate task execution.
4. Planned ownership transfer increments generation and fences the old owner.
5. Old owner submits a delayed result after transfer; the new owner rejects it.
6. Crash after handler completion but before result ACK; the same result is recovered without re-executing the handler.
7. Crash/timeout during an external side effect; task becomes `OUTCOME_UNKNOWN` and is not replayed.
8. Network partition and split-brain attempt; no second owner is elected without a valid transfer/lease.
9. Revoked device reconnects and submits a stale lease/result; it is rejected.
10. Restore onto a new installation does not restore live grants, credentials or ownership.
11. Project-scoped replication does not disclose other projects or secrets.
12. Duplicate inbox/outbox delivery is idempotent and sequence gaps are repaired.

Physical acceptance must record device IDs (non-secret), Core IDs, exact build SHAs, task IDs, owner generations, state histories, ACK digests and sanitized logs.

## 11. Implementation sequence

1. Persist stable per-installation Core identity and key epoch.
2. Add ownership record/state model and migration without changing current local queue behavior.
3. Add durable per-Core inbox/outbox and idempotent receipt processing.
4. Add signed assignment/result envelopes with owner generation and exact ACK.
5. Implement planned handoff and stale-owner fencing.
6. Implement crash reconciliation and `OUTCOME_UNKNOWN` policy.
7. Add revocation, restore and key-rotation integration.
8. Add Android↔Desktop reconnect, duplicate-delivery and partition tests.
9. Enable failover only after the full acceptance matrix and owner review pass.

Until then, each Core continues to execute only tasks it owns locally or has explicitly received as a bounded delegated assignment. No autonomous cross-Core takeover is implied by pairing, heartbeat or lease expiry.
