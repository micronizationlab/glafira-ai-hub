# Dual-Core ownership, fencing and failover contract

Status: architecture contract for implementation; no claim that the current runtime implements this protocol.

This document extends `docs/contracts/DUAL_CORE_RESULT_DELIVERY.md`. It records the owner's decision that Android Core and Desktop Core are independently useful execution nodes and that neither is a permanent single point of failure.

## 1. Non-negotiable safety properties

1. **One logical operation, one authorized owner at a time.** Android and Desktop may both be available, but must not independently execute the same side-effecting task.
2. **A lease is not proof of non-execution.** Lease expiry proves only that an owner has not renewed within the expected window. It does not prove that a remote API, payment, file write, message send or other effect did not happen.
3. **No automatic takeover without fencing or idempotency.** A new owner may execute only when the effect target rejects stale generations, supports the same stable idempotency key, or reconciliation proves the prior owner did not perform the effect.
4. **Partition safety beats availability for side effects.** If the two Cores cannot agree on ownership and the effect target cannot enforce fencing, both may continue read-only/local work, but neither may assume ownership of the disputed operation.
5. **Local autonomy is bounded by local capability.** Android may execute tasks supported by Android-local capabilities; Desktop may execute tasks supported by Desktop-local capabilities. A node must not pretend to complete work requiring an unavailable peer's files, GPU, shell, browser, or credentials.
6. **Authority is per installation.** Pairing one device does not copy its grants, keys, leases, or approval state to another device.

## 2. Stable identities and owner record

Each installation creates a cryptographically random immutable `coreId` at enrollment. The private identity key is non-exportable where the platform supports it. A restored installation that cannot prove continuity creates a new `coreId`; it does not inherit the old device's authority merely because files were restored.

For each logical operation, persist an owner record:

```text
taskId
projectId
idempotencyKey
ownerCoreId
ownershipGeneration       // monotonically increasing integer
ownerRecordRevision       // monotonically increasing record revision
ownershipState            // UNASSIGNED | OWNED | HANDOFF_PENDING | QUARANTINED | TERMINAL
leaseIssuedAt
leaseExpiresAt
leaseIssuerCoreId
lastOwnerEventSequence
requiredCapabilities
effectClass               // READ_ONLY | LOCAL_REVERSIBLE | EXTERNAL_SIDE_EFFECT
```

The logical operation identity is `(projectId, idempotencyKey)`. The origin assigns and durably stores `taskId` before dispatch. Retries reuse the same identity; they never create a new task to escape an uncertain outcome.

Generation is monotonically increasing per logical operation. It must never be reset by restore, import, queue compaction, or clock changes. Owner-record revision is separate from ownership generation: progress updates may increase revision without transferring ownership.

## 3. Ownership acquisition and renewal

An owner may acquire an unassigned task only after a durable compare-and-set of the owner record. The acquisition must atomically persist ownerCoreId, generation, lease, and the corresponding event before execution begins.

A lease renewal:
- is authenticated by the current owner installation;
- is accepted only for the current ownerCoreId and generation;
- cannot reduce the generation or extend a lease after a higher generation has been committed;
- records a durable owner event before the runtime continues side effects.

A clock timeout alone does not transfer ownership. Wall clocks can jump and disconnected peers cannot know whether another Core is alive. Local monotonic clocks may drive local expiry timers, but cross-device ordering must rely on signed owner records, revisions, and explicit fencing rather than trusting wall-clock timestamps.

## 4. Fencing and effect execution

Every side-effecting invocation carries:

```text
taskId
projectId
idempotencyKey
ownerCoreId
ownershipGeneration
effectId
```

Before performing an effect, the executor must validate the current owner generation. The effect target or a trusted adapter must reject a generation lower than the highest generation already observed for that task. The adapter must also deduplicate the stable effectId/idempotencyKey.

If a tool cannot enforce a generation or stable idempotency key, the operation is classified as **unfenced**. Unfenced operations cannot be automatically taken over after an ambiguous interruption. They enter `OUTCOME_UNKNOWN` and require reconciliation or explicit owner decision.

A local queue transition to RUNNING is not a fence. A network lease, successful poll, or credential possession is not a fence.

## 5. Handoff and failover state machine

Recommended task execution states:

- `QUEUED`: accepted durably; no owner has started the effect.
- `OWNED`: a Core has durably acquired the current generation; no effect has started.
- `RUNNING`: an effect may have started.
- `SUCCEEDED` / `FAILED`: terminal outcome is supported by a durable result or a definitive rejection.
- `OUTCOME_UNKNOWN`: process/connection failed after effect start and before durable outcome was committed.
- `RECONCILIATION_REQUIRED`: a later Core must query the effect target or request owner review.
- `CANCELLED`: cancellation was confirmed before an effect, or the effect target confirmed cancellation.

Safe handoff:
1. Current owner stops accepting new work for the task.
2. It reaches a checkpoint and durably records whether any external effect has started.
3. If no effect started, it commits `HANDOFF_PENDING` with a signed handoff record.
4. The receiving Core verifies the handoff, increments generation by compare-and-set, and becomes the sole owner.
5. If an effect started, the task is not transferable until the effect target confirms its outcome or fencing makes stale execution impossible.

Crash recovery:
- crash before `RUNNING` and with a durable proof that no effect started: may return to `QUEUED` after reconciliation;
- crash during/after `RUNNING`: `OUTCOME_UNKNOWN`, never ordinary `FAILED` merely because a process disappeared;
- expired lease with no proof of non-execution: `RECONCILIATION_REQUIRED`, not automatic replay;
- confirmed terminal effect: persist immutable result and terminal receipt before acknowledging delivery.

## 6. Split-brain and offline behavior

Android and Desktop may each continue independent tasks while disconnected, provided each task has a single locally durable owner and its capabilities are local. They may not both acquire the same unassigned or previously owned side-effecting task during a partition.

A safe deployment needs one of:
- an online authoritative compare-and-set service reachable by both Cores;
- a user-approved signed transfer token issued by the current owner and accepted by the receiver;
- a consensus/quorum mechanism with a clearly defined failure model;
- a target-side fencing service that rejects stale generations.

If none is available, the correct behavior is to quarantine disputed work, preserve both local logs, continue non-conflicting tasks, and ask the owner to reconcile. Do not claim autonomous failover under a network partition without one of these mechanisms.

## 7. Durable sync protocol

Each Core maintains an append-only event log and independent inbox/outbox.

Every event contains:
- `eventId` (globally unique);
- `coreId`;
- `coreSequence` (monotonic per Core);
- `taskId`, `projectId`, `idempotencyKey`;
- `ownerCoreId`, `ownershipGeneration`;
- `eventType`, schema version, creation timestamp;
- payload hash and authenticated signature/MAC.

Delivery is at-least-once. Receivers deduplicate by eventId and validate sequence gaps. ACKs identify the exact eventId and committed receiver revision. An ACK is sent only after the receiver transaction commits. Lost ACKs cause resend, not duplicate execution.

Inbox/outbox rules:
- persist event before network send;
- never delete an unacknowledged event;
- never infer authorization from receiving an event;
- verify device status, project scope, generation and payload binding at every acceptance;
- retain conflict evidence and rejected events for audit;
- reconcile duplicate terminal receipts by immutable resultId and payload hash.

## 8. Restore, revocation and credentials

Portable backup may preserve logical task IDs, immutable results, owner-event history and last known generation. It must not restore device-specific active leases, credentials, private keys, or pairing grants as if the restored device were the original.

A restore onto a new installation:
1. generates a new coreId and identity key;
2. imports task/result data as untrusted recovery data;
3. marks formerly active leases and RUNNING tasks for reconciliation;
4. requires owner approval to bind the new Core to projects and capabilities;
5. obtains a new owner generation before any task takeover;
6. keeps source device attribution for audit but never treats it as authority.

Revoking a device immediately blocks new claims, renewals, result writes and effect preflights for that device. Revocation does not erase the fact that an external effect may already have happened; affected tasks still require reconciliation.

## 9. Minimum acceptance matrix

| Scenario | Required result |
|---|---|
| Desktop disconnected; Android local task | Android continues if capability is local |
| Android disconnected; Desktop local task | Desktop continues if capability is local |
| Both online, distinct tasks | Both may execute concurrently |
| Both request same unassigned task | Exactly one durable acquisition succeeds |
| Owner crashes before effect start | Safe requeue only after durable proof and reconciliation |
| Owner crashes after effect start | OUTCOME_UNKNOWN; no automatic duplicate |
| Old owner wakes after generation increment | Effect target rejects stale generation |
| Lease expires during partition | No takeover without fencing or proof |
| Lost event ACK | Same event resends; receiver deduplicates |
| Duplicate identical terminal result | Idempotent acknowledgement |
| Conflicting terminal result | Reject and retain conflict evidence |
| Restore to new installation | New coreId; no inherited credentials/leases |
| Device revoked while offline | Rejected on reconnect before any new effect |
| Large result transfer interrupted | Resume by immutable resultId/offset; never truncate silently |

## 10. Implementation sequence

1. Add typed Core identity, owner record and event schemas without enabling LIVE execution.
2. Add durable transactional owner-record store and monotonic generation compare-and-set.
3. Add task state `OUTCOME_UNKNOWN` and reconciliation APIs/UI; migrate existing interrupted records conservatively.
4. Add effect adapter contract for idempotency and fencing; classify existing tools as fenced or unfenced.
5. Add signed handoff and owner-approved generation transfer.
6. Add independent per-Core inbox/outbox and reconciliation.
7. Integrate portable backup/restore and revocation semantics.
8. Add two-physical-device fault-injection tests for partition, process death, stale owner wakeup, duplicate delivery, restore and revocation.
9. Keep LIVE side effects disabled until the acceptance matrix passes.

This is a safety contract, not a claim that the current PR stack has completed these capabilities. The existing result-delivery foundation remains useful but is not yet an autonomous dual-Core system.
