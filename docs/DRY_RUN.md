# GLAFIRA Core Dry Run

## Purpose

Dry Run creates a redacted, deterministic preview of a Core task without invoking its registered handler. It is a planning result, not evidence that the requested operation happened.

## Current implementation slice

- `GlafiraExecutionMode.DRY_RUN` and `LIVE` are explicit domain modes.
- The Core runtime owner currently selects `DRY_RUN` only. No owner-facing LIVE switch is exposed.
- Core authorization and runtime-budget checks run before a preview is produced.
- `GlafiraDryRunPlanner` reads the registered tool definition and creates a plan with risk, simulation availability, safe target-field names, expected changes, required scopes, approval points, limitations and a deterministic SHA-256 plan hash.
- Payload values, message bodies, credentials and provider responses are never copied into the preview.
- The executor does not call the task handler in Dry Run. It stores a compact `DRY_RUN_PREVIEW` record in the existing task status detail and moves the task to `WAITING_FOR_OWNER`.
- Desktop task claims are withheld while Core is in Dry Run; Desktop cannot bypass the Core mode gate.

## Simulation semantics

- Registered read-only and draft tools are marked `SIMULATABLE`: the plan describes a proposed operation, but does not run a mock against a live service.
- External-write, financial, destructive and unknown tools are `NOT_SIMULATABLE` until a reviewed sandbox adapter exists.
- A NOT_SIMULATABLE plan is still useful as an action preview, but it must never be described as a successful simulation.
- A plan hash binds the exact task identity, project, tool, origin, payload and required scopes. Any change to the action produces a different hash.

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
