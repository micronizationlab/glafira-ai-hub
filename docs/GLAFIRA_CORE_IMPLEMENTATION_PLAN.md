# GLAFIRA Core — First-priority implementation plan

Owner decision (2026-11-18): GLAFIRA Core becomes Priority 1. Backup & Recovery becomes Priority 2 and remains maintained; only critical data-loss or security defects interrupt Core work.

## Verified starting point

- `GlafiraCoordinator` currently selects a primary provider, optionally asks up to two explicitly enabled peers, redacts common secrets in the consultation history, and asks the primary provider to synthesize the result.
- `GlafiraCorePolicy` currently limits peer selection to configured and owner-approved providers; peer consultation is disabled by default.
- Draft PR #12 adds a typed tool registry, risk levels, scopes, and project/tool/action-bound expiring approval grants. It is policy infrastructure, not a persistent approval inbox or an execution broker.
- Draft PR #17 routes Bridge requests through Core authorization but explicitly does not yet provide durable owner approval, a network listener, or execution.
- Current provider calls use blocking HTTPS connections. The compatible-provider client has a 20-second connect timeout, a 90-second read timeout for most providers, and a 900-second Grok read timeout. A unified bounded timeout, cancellation, retry and error policy is not yet established.
- Current context assembly is limited: the coordinator receives a prompt and message history; there is no complete project-scoped context assembler with field-level sharing classifications and provenance.

## Execution order

### Core-1 — Establish the authority and execution boundary
- Keep the owner as final authority; models and agents cannot mint permissions or approval grants.
- Define one typed operation envelope with project, actor, tool, risk, requested scopes, exact action summary, idempotency key and provenance.
- Fail closed for unknown tools, missing scopes, stale approvals and policy/storage errors.
- Add tests proving LLM-produced content cannot become trusted approval state.

### Core-2 — Provider runtime reliability
- Centralize provider capabilities, model IDs and availability.
- Introduce bounded connect/read/overall deadlines, cancellation propagation, bounded retries for transient failures, and consistent typed errors.
- Do not retry non-idempotent external actions.
- Add fallback only where the owner has configured and permitted it; report which provider actually answered.

### Core-3 — Project-isolated context assembly
- Build context from the active project, conversation, role and approved skills.
- Add field-level classifications and per-provider disclosure policy.
- Keep secrets out of prompts and logs; attach source/provenance metadata to retrieved context.
- Add cross-project and restricted-field leakage tests.

### Core-4 — Durable approval inbox and tool broker
- Persist pending requests and state transitions transactionally.
- Owner review must show the exact recipient, payload/action, target project, tool, scopes and consequences.
- Approval grants are short-lived and bound to one exact operation; reject edits after approval.
- Re-check authorization immediately before dispatch; record result, failure and idempotency state.
- No send/publish/payment/delete/CRM mutation until this path is implemented and tested.

### Core-5 — Audit, diagnostics and acceptance
- Append-only audit events for proposal, approval/rejection, dispatch, completion, failure and revocation.
- Owner-visible health/status: selected provider, fallback reason, timeout, policy decision and operation outcome.
- Unit, integration and Android device tests for provider failures, cancellation, permissions, context isolation, approvals and replay.
- No release APK or merge claim until CI and required acceptance checks pass.

## Acceptance rule

A feature is complete only when code, automated tests and a documented end-to-end acceptance check exist. Draft PRs, design documents and green unit tests alone are not proof of device-level completion.
