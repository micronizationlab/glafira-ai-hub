# GLAFIRA Core Dry Run

## Purpose

Dry Run creates a redacted, deterministic preview of a Core task without invoking its registered handler. It is a planning result, not evidence that the requested operation happened.

## Current implementation slice

- `GlafiraExecutionMode.DRY_RUN` and `LIVE` are explicit domain modes.
- The Core runtime owner currently selects `DRY_RUN` only. No owner-facing LIVE switch is exposed.
- Core authorization policy is inspected through a read-only path, and runtime-budget checks run before a preview is produced. Missing scopes and approval requirements are represented in the plan; Dry Run does not create approval requests.
- `GlafiraDryRunPlanner` reads the registered tool definition and creates a plan with risk, simulation availability, safe target-field names, expected changes, required scopes, approval points, limitations and a deterministic SHA-256 plan hash.
- Payload values, message bodies, credentials and provider responses are never copied into the preview.
- The executor does not call the task handler in Dry Run. It stores a compact `DRY_RUN_PREVIEW` record in the existing task status detail and moves the task to `WAITING_FOR_OWNER`.
- Desktop task claims are withheld while Core is in Dry Run; Desktop cannot bypass the Core mode gate.

## Simulation semantics

- Registered read-only and draft tools are marked `PREVIEW_ONLY`: the plan describes a proposed operation, but does not run a mock against a live service.
- External-write, financial, destructive and unknown tools are `NOT_SIMULATABLE` until a reviewed sandbox adapter exists.
- A NOT_SIMULATABLE plan is still useful as an action preview, but it must never be described as a successful simulation.
- A plan hash binds the exact task identity, project, tool, origin, payload and required scopes. Any change to the action produces a different hash.

## Desktop Bridge preview channel

- An authenticated Desktop claim poll is still admitted by the existing TLS/HMAC, paired-device scope, replay and rate-limit checks.
- While Core is in Dry Run, the claim method returns no lease. The Bridge then performs a separate read-only query over queued `desktop.execute` tasks and selects a task matching the paired node's advertised capabilities.
- Core returns only the redacted `GlafiraDryRunPlan` JSON, including task ID, project ID, tool ID, risk, simulation status, required permissions, approval points, limitations and plan hash.
- The raw task instruction, task payload values, secrets and executable handler arguments are not sent to Desktop in this path.
- The Desktop worker validates a strict preview schema and invokes its `onDryRunPreview` callback. It does not enqueue the preview in the Desktop task queue, invoke an agent executor, or send a task-result update.
- If no compatible queued task exists, the normal `available=false` response is returned. Malformed preview envelopes fail closed.

## Ten Desktop preview hardening checks

The Desktop preview codec has focused unit coverage for: valid redacted preview acceptance; rejection of LIVE mode; missing fields; unexpected fields; malformed plan hashes; unknown risk levels; unknown simulation states; unknown authorization states; unsafe target-field names; and malformed or oversized string arrays.

## Not yet complete

- The Desktop preview/result protocol is not implemented; claims are safely blocked rather than previewed.
- A durable typed preview table and owner-facing preview UI are not implemented. The current compact redacted preview is stored in the existing task status detail.
- The separate LIVE-run creation flow, including fresh action-bound owner approval against the reviewed plan hash, is not implemented. Core runtime therefore remains in DRY_RUN.
- No external provider sandbox adapters are wired.
- Automated tests and Android/Desktop CI for the current implementation slice must pass before this step can be accepted.

## Safety invariants

- Dry Run never invokes a registered task handler.
- No live Bridge operation, send, publish, CRM mutation, payment or delete is performed by preview.
- A preview cannot be silently converted into a live run.
- Unknown tools fail closed and are marked NOT_SIMULATABLE.
- Missing authorization, invalid policy or exhausted runtime budget prevents preview execution.
