# GLAFIRA task state model and cross-layer mapping

**Status:** current implementation inventory and normative mapping proposal. This document does not claim that Desktop Core↔Hub task assignment is already wired.

## 1. Authority boundaries

There is no single mutable state machine shared by every process. There is one canonical task state machine per owning Core, plus bounded transport/worker state machines:

- **Owning Core** is authoritative for task intent, project, dependencies, owner approval, retry policy, canonical result acceptance, cancellation and audit history.
- **Windows Desktop Hub** records delegated Agent-fleet delivery/lease/fencing state. For a Desktop-owned task, its store may be the local durable implementation of the Desktop Core queue; for a task owned by Android Core, Hub records only a delegated execution projection. It cannot transfer task ownership or approve effects.
- **Windows Agent** records a local active lease, progress outbox and result outbox. It is never the canonical task-state authority.
- **Task-plan status** is an aggregate projection of node task states, not another independently writable task state machine.
- **Task events/history** are append-only audit records, not a competing current-state field.

A Core task may be delegated to a Hub/Agent only with an exact owner, generation, task identity, capability subset, payload digest, risk and approval reference. Cross-Core handoff follows `docs/contracts/DUAL_CORE_OWNERSHIP_AND_FAILOVER.md`.

## 2. Current state inventories

### Owning Core (Android Core implementation)

`GlafiraTaskState` currently persists:

- `QUEUED`
- `RUNNING`
- `WAITING_FOR_NETWORK`
- `WAITING_FOR_OWNER`
- `RETRY_SCHEDULED`
- `SUCCEEDED`
- `FAILED`
- `CANCELLED`

Terminal states are immutable. `WAITING_FOR_OWNER` is intentionally overloaded today: approval-required, dry-run preview, restore review and uncertain external outcome are distinguished by `lastError`/approval/preview metadata. In particular, interrupted non-read-only work is currently persisted as `WAITING_FOR_OWNER` with `lastError=CORE_EXECUTION_OUTCOME_UNKNOWN`, not as a distinct Core enum value.

### Windows Desktop Hub

`HubTaskState` currently persists:

- `Queued`
- `Leased`
- `Succeeded`
- `Failed`
- `Cancelled`
- `OutcomeUnknown`

Progress (`RUNNING`, percentage and message) is recorded as lease/task events; it is not a separate terminal Hub state. Lease ID, fencing token, attempts, expiry and owner approval are stored separately.

### Windows Agent

The Agent has no authoritative task-state enum. Its durable journal contains:

- `ActiveLeases` — exact task/lease/fence identity and server-confirmed expiry;
- `PendingProgresses` — progress updates not yet acknowledged by Hub;
- `PendingResults` — terminal result envelopes not yet acknowledged by Hub.

A handler's `SUCCEEDED` / `FAILED` / `CANCELLED` outcome is an execution result, not permission to mutate the owning Core's canonical state. The Agent removes outbox records only after exact ACK validation.

### Task plans

`GlafiraCoreTaskPlanStatus` is derived from child task states. It must never be used to drive a child task transition independently. Plan status is refreshed in the same transaction as relevant node state changes.

## 3. Normative mapping

| Owning Core state | Windows Hub state / event | Meaning and rule |
|---|---|---|
| `QUEUED` | `Queued` | Task is eligible only after dependency, capability, risk and owner-policy checks |
| `RUNNING` | `Leased` + progress events | Hub has a fenced lease; Agent may work only under the exact lease and owner generation |
| `WAITING_FOR_NETWORK` | No active lease; Hub may retain a delegated queued record | No claim until the owning Core/transport resumes |
| `WAITING_FOR_OWNER` for approval/preview/restore review | No claim; Hub must not infer approval | Exact owner action required; a preview is not a grant |
| `WAITING_FOR_OWNER` + `CORE_EXECUTION_OUTCOME_UNKNOWN` | `OutcomeUnknown` | Side effect may have happened; no blind retry; reconcile provider/source or require explicit owner decision |
| `RETRY_SCHEDULED` | `Queued` with bounded retry metadata, if delegated again | Core remains retry-policy authority; preserve idempotency key and generation rules |
| `SUCCEEDED` | `Succeeded` | Core commits only after exact result identity/digest validation and durable ACK |
| `FAILED` | `Failed` | Failure is known, not merely an expired lease |
| `CANCELLED` | `Cancelled` | Owner cancellation of queued or leased ReadOnly work; the active lease is deleted/fenced and delayed Agent progress/results are rejected |

Cancellation rule: queued work of any risk is safe to cancel before dispatch; leased `ReadOnly` work is cancelled and its lease is removed; leased `LocalWrite` or `ExternalSideEffect` work becomes `OutcomeUnknown`; already terminal or unknown tasks are not silently rewritten. The Desktop UI requires explicit owner confirmation and explains the unknown-outcome case.

Hub `OutcomeUnknown` must map to the owning Core's `WAITING_FOR_OWNER` plus the exact `CORE_EXECUTION_OUTCOME_UNKNOWN` reason until the Core model introduces a first-class state. This avoids treating every owner wait as an unknown external effect.

## 4. Transition and retry invariants

1. Only the owning Core can authorize a task state transition, approval, retry or cancellation.
2. Hub lease transitions are fenced by exact `leaseId` and monotonic `fencingToken`; cross-Core assignment also carries `ownerCoreId` and `ownerGeneration`.
3. Agent progress is non-terminal. It cannot extend a lease unless Hub returns a valid, future expiry in the exact ACK.
4. Agent progress/result outboxes are write-ahead and at-least-once. Duplicate delivery is idempotent; exact ACK identity and digest are mandatory before deleting local outbox records.
5. Read-only work may be replayed after lease expiry only after fencing/reconciliation and with the same idempotency key.
6. Draft/local-write work may require owner review; external-side-effect/financial/destructive work with uncertain outcome is never blindly replayed.
7. `OUTCOME_UNKNOWN` is not the same as `FAILED`, `CANCELLED` or `WAITING_FOR_NETWORK`.
8. A task cancellation racing result commit has one durable winner; result and state are committed atomically.
9. Plan status is a projection. Child task state and plan aggregate status are updated transactionally.
10. Restore imports no active leases, approvals, grants or ownership authority.

## 5. Required implementation gaps

- Make `WAITING_FOR_OWNER` reason codes a closed, validated enum so approval-required, restore-review, dry-run and unknown-outcome cases cannot be conflated.
- Preserve exact owner Core ID and ownership generation in Hub task, lease, event and result records.
- Add a canonical mapping layer between owning Core state and Hub delegated-execution state; reject illegal mappings and stale generations.
- Complete owner-generation-aware cancellation propagation across Core→Hub→Agent; the current local Desktop owner path is implemented, but cross-Core cancellation handoff is not.
- Reconcile Hub terminal results into the owning Core's durable inbox and commit canonical result + state + trace atomically.
- Keep Agent local journal states separate from Core/Hub business task states.
- Add cross-layer conformance fixtures covering every state mapping, legal transition, illegal transition, retryability and unknown-outcome rule.

## 6. Acceptance tests

1. Hub `OutcomeUnknown` becomes Core `WAITING_FOR_OWNER` with exact reason; it never becomes Core `FAILED` or `RETRY_SCHEDULED` automatically.
2. Approval-required `WAITING_FOR_OWNER` cannot be mistaken for `OUTCOME_UNKNOWN`.
3. User cancellation propagates from owner Core to Hub as a distinct cancellation event and fences Agent work.
4. Agent progress ACK renews only the current lease and does not change canonical Core task state.
5. A terminal Agent result remains in the durable outbox until exact Hub ACK; Hub result remains pending canonical Core ACK until Core commits it.
6. A stale owner generation or fencing token is rejected at every boundary.
7. Plan aggregate state matches the transactional child-state projection.
8. Restore cannot resurrect an active lease or a terminal task's previous runnable state.

## 7. Source files

- Android Core: `GlafiraTask.kt`, `GlafiraTaskStore.kt`, `GlafiraCoreTaskExecutor.kt`, `GlafiraTaskPlan.kt`, `GlafiraCoreOwnerApprovalStore.kt`.
- Windows Hub: `HubContracts.cs`, `DesktopHubStore.cs`, `BridgeV1TaskOperationDispatcher.cs`.
- Windows Agent: `AgentContracts.cs`, `AgentExecutionJournal.cs`, `AgentTaskPollingCoordinator.cs`, `AgentWorker.cs`.
- Normative ownership: `docs/contracts/DUAL_CORE_OWNERSHIP_AND_FAILOVER.md`.
