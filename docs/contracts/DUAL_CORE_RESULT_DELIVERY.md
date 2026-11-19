# Dual-Core operation ownership and result delivery contract

Status: result-delivery foundation; autonomous dual-Core synchronization is not implemented.
Live dispatch remains disabled by the existing DRY_RUN gate. Owner acknowledgement of a
preview must not implicitly enable LIVE mode.

## Identities and authority

- A logical operation is identified by `(projectId, idempotencyKey)`; the origin Core assigns
  a stable `taskId`, persisted before dispatch. Retransmission never generates a new operation.
- A result has immutable `resultId = result-<taskId>`. Repeating identical terminal data from
  the original device acknowledges the original receipt; conflicting terminal data is rejected.
- A network lease is a delivery/liveness mechanism, not proof that an external action did
  not happen. An expired lease must never authorize another device to repeat the action.
- The future distributed owner record must persist `ownerCoreId`, monotonically increasing
  `ownershipGeneration` and an operation identity. A target accepting effects must enforce
  the generation or support the stable operation idempotency key. Neither is implemented
  by the existing generic external tools; a partition cannot safely trigger automatic failover.
- Without proof of nonexecution, interrupted/expired work becomes outcome-unknown and
  requires reconciliation and an explicit owner decision. Device credentials and permissions
  are independently checked at each Core. Receiving an event grants no authority.

## Implemented durable boundaries

For PROJECT_READ, CONVERSATION_READ and CONVERSATION_APPEND, the committed Core task
row is also the durable acceptance receipt. These enqueue-only operations use the same
pairing/HMAC/scope/expiry/rate checks but do not first consume a separate replay-ledger row.
A crash before enqueue permits safe authenticated resubmission; a lost acceptance reply
returns the existing task ID through the SQL-unique `(projectId,idempotencyKey)` receipt.
The origin device, tool, scopes and semantic payload (excluding transport request ID) must
match. A different payload/device produces a conflict without returning the old task ID.
A terminal receipt is returned without changing state or requeueing execution. Only the
three enqueue-only operations use this path; control operations retain their replay guard.
Clients must retain the logical key across transport retries. A fresh key means a new action.

1. Android Core success writes its actual result and `SUCCEEDED` in one SQLite transaction.
   The result journal uses the task DB, so a committed state cannot outlive an absent journal
   entry for executions using this persistence adapter. SQLite state-history triggers participate
   in the same transaction after the queue migration is integrated.
2. Desktop records its terminal state and output atomically in its existing durable queue file.
   A terminal record with `deliveryAcknowledged=false` is the outbound event; it cannot be
   evicted by history pruning. Restart/poll retries every pending record independently.
3. Android receives Desktop terminal data, updates task/result storage and inserts its immutable
   receipt in one outer transaction. A lost terminal HTTP response is harmless: a fresh signed
   transport request with the same terminal payload receives acknowledgement without a lease.
   Different payload/device/project is rejected. An expired original lease can reconcile late
   terminal output after the queue's quarantine migration; it cannot renew execution authority.
4. Desktop persists local acknowledgement only after Android reports committed acceptance.
   If either process dies between receipt and local acknowledgement, it resends the stored result.
5. `TASK_STATUS` accepts `taskId,resultOffset` and returns task state, up to 100 most recent
   history events, immutable result ID, total UTF-16 length and an at-most-8000-character JSON
   result chunk. Advance the offset by the returned chunk UTF-16 length until reconstructed; the immutable ID binds all chunks.
   `TASK_RESULT_ACK(taskId,resultId)` records a per-device acknowledgement idempotently.
   Result rows are retained after acknowledgement. Status/ACK require active `project.read`,
   the original read scope, matching project and original requesting device (Desktop worker
   results are additionally guarded by `desktop.task.result`). Other-project tasks are hidden.
   Bridge request admission and each later runtime callback recheck active credentials; a
   fresh RUNNING preflight must succeed before the Desktop handler starts.
6. Protocol `resultEncoding=json` transports real Desktop output including the literal `none`;
   old clients retain their legacy sentinel behavior. Failed delivery retains its pending record.

## Storage and portable backup

`core_task_results` stores task_id, project_id, unique result_id, terminal_state,
source_device_id (nullable), result_ciphertext, completed_at. Ciphertext is a JSON string map
using Keystore AES-GCM associated data `taskId|core-result|projectId`.
`core_result_acks` stores `(result_id,device_id,acknowledged_at)` with a composite primary key.

Portable recovery must decrypt and reencrypt results under destination keys, preserve logical
identities and terminal outcomes, quarantine interrupted work and discard device-specific ACKs,
leases and grants. source_device_id is attribution, not authorization on a restored installation.
Desktop queue files currently use owner-only permissions and plaintext JSON; OS-protected
result encryption remains separate work and must not be described as already implemented.

## Remaining requirements and validation

The contract does not claim exactly-once execution for arbitrary external side effects. There
is an unavoidable ambiguous window between an external service accepting an action and local
result persistence unless that service participates in idempotency/reconciliation. Storage failure
at that boundary quarantines the task rather than automatically replaying its handler.

Desktop pending output requires an explicitly supplied persistent queue; the in-memory
constructor is intended for tests. Production CLI wiring must supply that queue and be
covered by a restart regression: library persistence tests do not prove the launch path. History capacity applies backpressure when all records are
unacknowledged. Large outputs beyond existing protocol limits need chunked upload/artifact
transfer; they remain pending rather than being truncated. Full UI rendering, independent Core
authority arbitration, ownership-generation enforcement, complete history pagination, cloud
hosting, live tool adapters and autonomous dual-Core sync remain future increments.

After portable restore, prior device leases/credentials are deliberately absent. An offline
Desktop keeps its old completed output pending, but automatic receipt reconciliation to the
restored Core needs a new owner-approved proof/binding API; it cannot recreate authority from
a task ID. Legacy completed Desktop results remain in core_desktop_results; the new status
endpoint currently exposes new journal entries only, so a safe legacy journal migration is
also needed before claiming complete historical-result retrieval.

Unit tests cover real result forwarding, commit failure quarantine, ambiguous draft-handler
failure without replay, result persistence through Desktop restart, sequence-matched ACK,
project filtering and non-eviction of unacknowledged output. Android instrumentation covers
atomic journal storage, duplicate terminal receipt and project/device conflict rejection; those
tests require an Android runtime and are not equivalent to an executed device test when only
an instrumentation APK is compiled.
