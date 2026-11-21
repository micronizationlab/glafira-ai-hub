# GLAFIRA Core task-state transition contract

**Status:** normative transition matrix for the Android Core task state machine.
**Implementation:** `GlafiraTaskState` and `GlafiraTaskPolicy.canTransition` in `GlafiraTask.kt`.
**Verification:** `GlafiraTaskStateTransitionMatrixTest` exhaustively checks every persisted from/to pair.

## Authority and scope

The owning Core is the sole authority for canonical task intent, authorization, retry policy, cancellation, result acceptance and terminal state. Windows Desktop Hub and Windows Agent maintain separate delegated-execution/transport states; their state machines must not be treated as alternate writers of Android Core state. Task-plan status is a derived projection of child task states.

This matrix describes legal Core task-state transitions only. It does not grant permission to execute an operation. Authorization, owner approvals, lease fencing, idempotency and result acceptance remain mandatory independent gates.

## Complete transition matrix

A transition is allowed only when the destination appears in the corresponding row.

| From | Allowed destinations |
|---|---|
| `QUEUED` | `RUNNING`, `WAITING_FOR_NETWORK`, `WAITING_FOR_OWNER`, `CANCELLED`, `FAILED` |
| `RUNNING` | `WAITING_FOR_NETWORK`, `WAITING_FOR_OWNER`, `RETRY_SCHEDULED`, `SUCCEEDED`, `FAILED`, `CANCELLED` |
| `WAITING_FOR_NETWORK` | `QUEUED`, `RUNNING`, `FAILED`, `CANCELLED` |
| `WAITING_FOR_OWNER` | `QUEUED`, `FAILED`, `CANCELLED` |
| `RETRY_SCHEDULED` | `RUNNING`, `FAILED`, `CANCELLED` |
| `SUCCEEDED` | none (terminal) |
| `FAILED` | none (terminal) |
| `CANCELLED` | none (terminal) |

Self-transitions are not allowed. A new task is created directly in `QUEUED`; it does not transition into that state from a terminal state.

## Invariants

1. Terminal states `SUCCEEDED`, `FAILED` and `CANCELLED` are immutable.
2. `RETRY_SCHEDULED` must carry a retry timestamp; all other states must not.
3. A transition must provide an expected-current-state set and update conditionally in the same database transaction as its history/derived-plan effects.
4. `WAITING_FOR_OWNER` is not a single reason. The reason code and approval/preview metadata must distinguish approval required, dry-run review, restore review and `CORE_EXECUTION_OUTCOME_UNKNOWN`.
5. An interrupted write or external side effect with an uncertain outcome must not be moved to `RETRY_SCHEDULED` automatically.
6. Cancellation racing with result persistence has one durable winner; result and terminal state must be committed atomically.
7. Task-plan status is derived from child task states and is never an independent transition authority.
8. Restore must not revive terminal tasks or import active leases, approvals, grants or ownership authority.

## Cross-layer boundary

- Core owns canonical state and final result acceptance.
- Desktop Hub owns only the Desktop fleet lease/dispatch journal or a delegated projection, fenced by exact lease identity and owner generation.
- Windows Agent owns only its local active-lease/progress/result outboxes; it cannot declare canonical task completion.
- Cross-Core transfer requires the signed ownership handoff and monotonically increasing generation defined in `DUAL_CORE_OWNERSHIP_AND_FAILOVER.md`.

The Core transition matrix is not a replacement for the separate Hub and Agent contracts. Cross-layer mappings and ACK conformance remain tracked by issue #91.
