# GLAFIRA Core Dry Run

## Purpose

Dry Run creates a redacted, deterministic preview of a Core task without invoking its registered handler. It is a planning result, not evidence that the requested operation happened.

## Current implementation slice

- `GlafiraExecutionMode.DRY_RUN` and `LIVE` are explicit domain modes.
- The Core runtime owner currently selects `DRY_RUN` only. No owner-facing LIVE switch is exposed.
- Core authorization policy is inspected through a read-only path, and runtime-budget checks run before a preview is produced. Missing scopes and approval requirements are represented in the plan; Dry Run does not create approval requests.
- `GlafiraDryRunPlanner` reads the registered tool definition and creates a plan with risk, simulation availability, safe target-field names, expected changes, required scopes, approval points, limitations and a deterministic SHA-256 plan hash.
- Payload values, message bodies, credentials and provider responses are never copied into the preview.
- The executor does not call the task handler in Dry Run. It atomically stores the redacted plan in the typed `core_dry_run_previews` table, encrypted with the app's Android Keystore key, and moves the task to `WAITING_FOR_OWNER`. Task metadata contains only a fixed marker, not the plan body.
- Desktop task claims are withheld while Core is in Dry Run; Desktop cannot bypass the Core mode gate.

## Simulation semantics

- Registered read-only and draft tools are marked `PREVIEW_ONLY`: the plan describes a proposed operation, but does not run a mock against a live service.
- External-write, financial, destructive and unknown tools are `NOT_SIMULATABLE` until a reviewed sandbox adapter exists.
- A NOT_SIMULATABLE plan is still useful as an action preview, but it must never be described as a successful simulation.
- A plan hash binds the exact task identity, project, tool, origin, payload and required scopes. Any change to the action produces a different hash.

## Desktop Bridge preview channel

- An authenticated Desktop claim poll is still admitted by the existing TLS/HMAC, paired-device scope, replay and rate-limit checks.
- While Core is in Dry Run, the claim method returns no lease. The Bridge asks Core to select a queued `desktop.execute` task matching the paired node's advertised capabilities; Core persists the redacted preview on that task and moves it to `WAITING_FOR_OWNER`.
- Core returns only the redacted `GlafiraDryRunPlan` JSON, including task ID, project ID, tool ID, risk, simulation status, required permissions, approval points, limitations and plan hash.
- The raw task instruction, task payload values, secrets and executable handler arguments are not sent to Desktop in this path. The Core orchestrator can retrieve the same stored preview and stop waiting for a live Desktop result.
- The Desktop worker validates a strict preview schema and invokes its `onDryRunPreview` callback. It does not enqueue the preview in the Desktop task queue, invoke an agent executor, or send a task-result update.
- The current Desktop CLI renders the callback as a clearly labelled `PREVIEW ONLY; NOT EXECUTED` report, including the plan hash and redacted JSON.
- If no compatible queued task exists, the normal `available=false` response is returned. Malformed preview envelopes fail closed.

## Ten Desktop preview hardening checks

The Desktop preview codec has focused unit coverage for: valid redacted preview acceptance; rejection of LIVE mode; missing fields; unexpected fields; malformed plan hashes; unknown risk levels; unknown simulation states; unknown authorization states; unsafe target-field names; and malformed or oversized string arrays.

## Twenty small storage, review and safety additions

1. Bumped the Core SQLite schema to version 3.
2. Added a dedicated `core_dry_run_previews` table.
3. Linked each preview to its Core task with a foreign key.
4. Stored project identity separately for scoped retrieval.
5. Stored the registered tool ID as typed metadata.
6. Constrained risk values with a SQLite CHECK constraint.
7. Constrained simulation states with a SQLite CHECK constraint.
8. Constrained review states to PENDING, ACKNOWLEDGED or REJECTED.
9. Enforced a 64-character SHA-256 hash in the database.
10. Encrypted the complete preview JSON at rest with Android Keystore AES-GCM.
11. Bound preview ciphertext to task and project identifiers as associated data.
12. Added an index for review status and newest-first retrieval.
13. Added an index for project-scoped preview retrieval.
14. Persisted task-state transition and preview row in one SQLite transaction.
15. Used compare-and-set expected task states to prevent stale writes.
16. Made repeated persistence with the same plan hash idempotent.
17. Rejected a different plan hash for an already-persisted task.
18. Bounded preview JSON size and result-list limits.
19. Bound owner review to the exact plan hash and PENDING state.
20. Recorded review decision and timestamp once; review confirmation is not execution approval.

## Not yet complete

- Desktop claim polling returns a redacted preview without a lease; the Desktop worker validates it and routes it to a preview-only callback/CLI display, never to the executable task queue.
- The typed preview table and owner-facing Compose review screen are implemented. The owner can acknowledge or reject a plan after fresh biometric/device authentication; this records review only and never starts execution.
- The separate LIVE-run creation flow, including fresh action-bound owner approval against the reviewed plan hash, is not implemented. Acknowledge/reject only records review; Core runtime remains in DRY_RUN.
- No external provider sandbox adapters are wired.
- Automated tests and Android/Desktop CI for the current implementation slice must pass before this step can be accepted.

## Safety invariants

- Dry Run never invokes a registered task handler.
- No live Bridge operation, send, publish, CRM mutation, payment or delete is performed by preview.
- A preview cannot be silently converted into a live run.
- Unknown tools fail closed and are marked NOT_SIMULATABLE.
- Missing authorization, invalid policy or exhausted runtime budget prevents preview execution.
