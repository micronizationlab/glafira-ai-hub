# Durable GLAFIRA Core Task Plans

## Implemented contract

A high-level instruction is normalized into `GlafiraTaskPlanRequest` and passed to a trusted `GlafiraTaskPlanDecomposer`. The planner returns typed `GlafiraTaskPlanNode` values; it does not execute tools or receive credentials. `GlafiraTaskPlanOrchestrator` validates project scope, idempotency, timestamps, node limits and the complete acyclic dependency graph before persistence.

The Core owner exposes `submitInstruction`, `enqueuePlan` and project-scoped `taskPlan` status. Plan header, node membership and dependency edges are persisted in one SQLite transaction. A crash cannot expose a partially inserted plan. Repeated submissions are idempotent and conflicting reuse is rejected.

## Parallel branches and dependency semantics

Independent nodes become eligible concurrently after their predecessors succeed. A join node remains blocked until every predecessor succeeds. Failure/cancellation propagates through descendants using the existing dependency failure pass. The existing bounded Core workers claim through the same transactional queue; the project fairness dispatcher prevents a busy project from monopolizing every worker.

## Desktop worker coordination

The shared database maintains a live Desktop-node registry with heartbeat timestamps, capabilities and per-node concurrency limits. The Core owner now exposes the full fresh-node inventory, not only the most recently seen node. Desktop claims still validate task capability requirements, dependency readiness, active leases and owner authorization. Expired/unknown external execution outcomes remain quarantined for owner reconciliation.

## Recovery and journal

Task state transitions remain recorded in `core_task_history`. Plan status is derived from durable node states, and plan activity timestamps are advanced by a database trigger. Existing interrupted-Core recovery, Desktop lease expiry/quarantine and terminal dependency propagation remain the recovery authorities.

## Safety boundary and remaining integration

The current runtime remains `DRY_RUN`; Desktop claim/lease issuance and external LIVE actions are not enabled by this change. A model provider must implement `GlafiraTaskPlanDecomposer` and return only registered, typed task operations. The provider adapter, owner-facing plan approval UI, end-to-end authenticated multi-device Bridge execution and cross-platform plan-status views require separate integration and validation. No generated plan can grant itself permissions or switch execution mode.

## Limits

- Maximum 64 nodes per plan.
- Maximum 64 predecessors per node.
- Maximum 512 KiB per task payload.
- Planner input maximum 32,000 characters.
- Desktop inventory freshness defaults to 45 seconds; stale nodes are not considered available.
