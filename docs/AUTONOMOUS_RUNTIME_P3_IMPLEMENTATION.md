# GLAFIRA Autonomous Runtime — Priority 3 implementation baseline

**Status:** implementation baseline; no runtime capability is considered complete until code, automated tests and an end-to-end acceptance check pass.
**Priority:** P3 — Autonomous Runtime & Bridge.
**Base:** Core/Gateway/Desktop integration baseline. This work must not bypass or destabilize P1 Core or P2 Backup & Recovery.
**Branch:** runtime/p3-autonomous-runtime-foundation

## Non-negotiable architecture

- GLAFIRA Core remains the single authority for task creation, policy, authorization, approvals, dispatch and final status.
- All work uses the existing persistent Core SQLite task queue and shared executor. No parallel durable queue.
- Runtime workers are replaceable executors, not authorities. Android, Desktop and iOS are peer clients.
- Deny by default. Missing, stale, contradictory or unavailable policy means no execution.
- External send, publish, purchase, payment, delete, or business-record mutation requires explicit action-bound owner approval unless a narrow, revocable owner policy already authorizes that exact class of action.
- Demonstrations, files, websites, messages, skills and model output are untrusted data; they cannot change policy or grant permissions.
- Secrets stay in protected credential storage and never enter model context, task traces, skill packages or logs.
- No paid backend, public endpoint, always-on microphone, automatic account login or production release is introduced by this work.

## Five delivery steps

### 1. Bounded autonomy: time, steps, concurrency and cost

Add a typed RuntimeBudget to each task envelope:
- max wall-clock duration and deadline;
- max agent/tool steps;
- max retries and total concurrent child tasks;
- optional provider/token/cost ceiling where usage can be measured;
- per-project and per-agent concurrency limits;
- cancellation and pause checkpoints.

Core enforces budgets centrally before dispatch and before every tool/agent step. Budget exhaustion transitions the task to a stable PAUSED_BUDGET or FAILED_BUDGET state, records a redacted trace event, releases leases and prevents silent retries. Unknown cost is reported as unknown, never represented as zero. Defaults are conservative and owner-configurable; no unlimited mode.

Acceptance: boundary tests for zero/negative/overflow values, deadline expiry, cancellation, child-task fanout, retries, concurrent claims, unknown provider cost and process death. Verify limits cannot be increased by an agent, provider response or imported skill.

### 2. Dry Run and action preview

Add an explicit execution mode: DRY_RUN or LIVE. DRY_RUN is the default for newly created workflows and for all unreviewed skills.
- Resolve the same Core policy and scopes as live execution.
- Use read-only inspection and mock/sandbox adapters; do not call live write endpoints.
- Produce a structured plan of proposed actions, targets, data classes, expected changes, required permissions and approval points.
- Mark unavailable simulation honestly; never claim a simulated action succeeded in the real service.
- A live run must be a new, separately authorized execution bound to the reviewed plan hash; do not silently convert a dry run into live mode.

Acceptance: test that send/publish/delete/payment/business-write handlers are never invoked in DRY_RUN; plan hash changes invalidate approval; unsupported tools return NOT_SIMULATABLE; preview contains no credentials.

### 3. Event-triggered task launch

Add an allowlisted event registry for schedule, webhook, message, file and connected-app events.
- Each trigger has owner/project scope, enabled state, source identity, event schema version, rate limit, deduplication/idempotency key, expiry and action policy.
- Trigger delivery only enqueues a Core task; it never executes a tool directly.
- Validate source authenticity and payload bounds before enqueue.
- Add cooldown, burst limits, loop prevention, pause/revoke and an owner-visible trigger history.
- Schedule execution is best-effort; do not promise exact-time delivery on Android background execution.

Acceptance: forged-source, replay, duplicate, out-of-order, oversized payload, burst, recursive-trigger, revoked-trigger and process-restart tests. Confirm every accepted event creates at most one logical Core task.

### 4. Teach-by-demonstration skill capture

Allow the owner to describe or record a repeatable procedure and turn it into a proposed, versioned skill.
- Capture only explicitly selected steps; no hidden recording or passive monitoring.
- Redact secrets and personal data before persistence.
- Convert demonstrations into declarative, typed steps referencing registered tools; never store arbitrary shell/script execution as a skill.
- New or changed skills start PROPOSED/disabled and require validation, scope review and owner activation.
- Store provenance, author, source references, version, permissions, tests, compatibility and rollback metadata.
- A skill cannot grant itself permissions or override Core/owner policy.

Acceptance: tests for prompt injection inside demonstrations, secret redaction, missing tool IDs, schema drift, permission escalation, version rollback and owner revocation. No automatic learning from raw chat history.

### 5. Visual agent and workflow builder

Build a visual editor for a typed workflow graph:
- nodes: task, agent role, registered tool, condition, wait/event, approval, review and output;
- edges: success, failure, condition and timeout;
- each node declares input/output schema, project scope, permissions, budget and retry policy;
- static validation before save; graph versions are immutable once executed;
- run inspector shows node status, evidence, trace, approvals, retries and resource use;
- replay is allowed only for safe/read-only nodes or explicit DRY_RUN; side effects require fresh approval.

Acceptance: graph schema validation, cycle detection, unreachable node detection, scope propagation, invalid-edge rejection, immutable versioning, run trace inspection and safe replay tests.

## Shared implementation sequence and release gates

1. Add domain contracts and deterministic policy validators without enabling new side effects.
2. Implement budget enforcement and Dry Run against the Core queue/executor.
3. Add event ingress that only creates Core tasks.
4. Add skill capture, review, versioning and activation gates.
5. Add workflow graph schema, validator, editor and run inspector.
6. Run Android/Desktop unit and integration suites, migration/backup compatibility checks, process-death tests and authenticated Bridge end-to-end tests.

Each step must be independently testable and may be paused without leaving the queue or policy in an inconsistent state. Do not mark a step complete based on documentation or compilation alone. Before enabling autonomous live execution, require passing P1 Core authorization and P2 backup/restore acceptance for all affected Core tables.

## Step 2 implementation progress (partial)

A first Core Dry Run slice is now present:
- Core has explicit DRY_RUN/LIVE mode contracts; the application runtime owner selects DRY_RUN only.
- The redacted planner binds a deterministic plan hash to task identity, project, tool, origin, payload and required scopes.
- The planner reports tool risk, simulation availability, safe target-field names, expected changes, permissions, approval points and limitations.
- The Core executor returns a preview and does not invoke the registered handler in DRY_RUN.
- The preview is stored in the existing task status detail; tasks wait for owner review.
- Desktop claims are blocked while the Core owner is in DRY_RUN, pending a Desktop preview protocol.

This is not Step 2 acceptance yet. A typed durable preview store, owner-facing review UI, Desktop preview protocol, sandbox adapters and a separate action-bound LIVE run flow remain outstanding. See [DRY_RUN.md](DRY_RUN.md).

## Status checklist

- [ ] Step 1 — Runtime budgets enforced by Core
- [ ] Step 2 — Dry Run with live-side-effect isolation
- [ ] Step 3 — Authenticated, deduplicated event triggers
- [ ] Step 4 — Owner-taught, reviewed and versioned skills
- [ ] Step 5 — Typed visual workflow builder and run inspector
- [ ] End-to-end Android/Desktop acceptance
- [ ] Backup/restore compatibility and rollback evidence

## Known dependency

The current integration work must first be verified against its exact PR head and CI results. This branch is based on the recorded Core/Gateway/Desktop integration commit; it does not merge that integration or claim device-level acceptance. P1/P2 work takes precedence whenever it is actively ready for implementation or verification.
