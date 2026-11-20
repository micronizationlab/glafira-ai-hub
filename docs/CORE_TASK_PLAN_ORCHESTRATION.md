# Durable GLAFIRA Core Task Plans

## Purpose

A high-level owner instruction can be decomposed into a bounded, typed directed acyclic graph (DAG), persisted in the existing Core SQLite queue, and inspected as a project-scoped plan journal. The model proposes structure; Core validates and owns the durable graph. The model does not execute tools, grant permissions, select credentials or change the execution mode.

## Submission and validation

- `GlafiraCoreRuntimeOwner.submitInstruction` accepts an instruction, project ID, idempotency key, creation timestamp and node budget.
- `GlafiraAiProviderTaskPlanDecomposer` uses only the configured primary provider. There is no automatic paid fallback or silent provider substitution.
- The response is parsed through the existing strict `GlafiraTaskPlanner` JSON schema; malformed JSON, unknown executors, invalid dependencies, cycles and node-budget overruns are rejected before persistence.
- The first version allows up to 16 model-generated nodes, at most two ready nodes per planning batch, and up to 64 nodes in the durable plan model.
- `GlafiraCoreTaskPlanAdapter` converts the validated plan to deterministic task IDs, bounded idempotency keys, task payloads and dependency edges. Each node is currently placed in the Desktop execution queue (`desktop.execute`) with capability requirements derived from its trusted executor role.
- The original high-level instruction is not copied into every task payload. Each node contains only its own bounded title/instruction, role, capability set and plan metadata.

## Atomic persistence and replay safety

SQLite schema version 7 adds `core_task_plans` and `core_task_plan_nodes`. A single transaction writes the plan header, all task rows, plan membership and dependency edges. Workers cannot observe a partially persisted plan.

Plan replay is idempotent only when the plan ID, creation time, node ordering, immutable node content, capability scopes and dependency edges match the original. Reusing a project/idempotency key with different node content is rejected. Node idempotency keys are fixed-length hashes, so long request keys and subtask names cannot overflow downstream limits.

Plan timestamps are advanced by a database trigger whenever a member task changes state. Plan status is derived from durable node states rather than stored as a second competing state machine.

## Dependency and dispatch semantics

- Independent branches can become eligible concurrently.
- A join node remains blocked until every predecessor succeeds.
- Failed or cancelled predecessors propagate through dependent descendants using the existing Core dependency policy.
- The plan journal includes each node's transition history and any persisted Core/Desktop result.
- `GlafiraDesktopDispatchPlanner` produces an advisory capability-aware, normalized-load placement proposal across recently heartbeating Desktop nodes. It does not reserve a task or issue a lease. The actual claim path remains authoritative and must revalidate dependencies, leases, owner approval and capability grants.

## Security and execution boundary

- Model output is untrusted structured data and passes strict parsing and policy validation.
- Task nodes cannot add permissions: required scopes come from the registered Desktop execution tool, while required capabilities are derived from the validated executor role.
- No passwords, API keys, Bridge secrets or owner grants are passed to the planner model.
- The configured AI provider is used only when an owner submits a plan; this feature does not run a background model call merely because the app is open.
- Core execution remains `DRY_RUN`. Persisting a plan does not enable LIVE execution or automatically authorize external side effects.
- Every plan node is currently targeted at the Desktop queue. The included Android local AI port is not yet connected to durable plan execution; do not claim Android executes these plan nodes.
- The advisory Desktop placement planner is not a network transport. The Windows HTTPS listener, production payload resolver, pairing UI and end-to-end Desktop↔Agent task/result loop remain separate work in issue #123 / PR #189 and must pass their own acceptance tests.

## Validation

The implementation adds:
- JVM tests for plan validation, strict JSON decoding, planner limits, decomposition, idempotency scope and Desktop placement.
- Android instrumentation tests for atomic plan/dependency persistence, forward references, dependency gating, idempotent replay, content-conflict rejection, project scoping, plan journal/history, node inventory, failure propagation and schema v6→v7 migration preservation.

No production LIVE execution, cross-platform plan synchronization or end-to-end Desktop network delivery is claimed by this change.
