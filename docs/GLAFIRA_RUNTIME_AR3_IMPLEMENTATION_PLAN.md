# GLAFIRA Autonomous Runtime — AR-3 Execution Foundation

## Priority and current baseline

GLAFIRA Core remains Priority 1. Backup & Recovery remains Priority 2. This AR-3 slice is the next Core runtime stage and must build on the shared executor and the Core maintenance gate introduced for consistent backups.

The current repository has:
- A persistent SQLite task queue with encrypted payloads and explicit lifecycle states.
- A single coroutine executor with authorization-before-dispatch, allow-listed handlers and bounded retry.
- A process-wide maintenance gate that serializes executor work against a Core snapshot/restore.
- No complete app-level runtime owner, foreground-service wiring, WorkManager recovery path, durable owner approval inbox, or registered production task handlers yet.

This document is an implementation plan. It does not claim that autonomous background execution is already available.

## Runtime topology

One application-scoped runtime owns one queue, one authorizer, one handler registry, and one executor instance.

Entry points must never create independent executors:
- Interactive UI submits tasks to the persistent queue and may request a bounded drain.
- The owner-started foreground service asks the shared executor to process active work while the Android foreground notification is visible.
- WorkManager schedules durable, deferred drain/recovery attempts and backup jobs.
- The Bridge may enqueue only validated typed requests; it must not call handlers directly.
- Backup/restore acquires the shared Core maintenance gate and blocks all execution entry points.

All entry points use the same queue, authorization policy, executor, idempotency ledger and maintenance gate.

## AR-3 implementation components

### 1. Runtime dependency owner

Introduce an application-scoped `GlafiraCoreRuntimeOwner` that constructs and exposes:
- `GlafiraTaskStore`
- trusted owner-scope/grant repository
- typed handler registry
- `RegistryBackedGlafiraTaskAuthorizer`
- one `GlafiraCoreTaskExecutor`
- runtime health and task-lease repository

Construction must fail closed if owner grant storage or handler registration is unavailable. No default allow-all authorizer is permitted.

### 2. Foreground service

Add a non-exported, owner-controlled foreground service for active, user-approved long-running work:
- Start only from an allowed user-visible action or a platform-permitted transition.
- Show a persistent notification with current runtime state and a stop/pause action.
- Drain bounded batches through the shared executor; do not implement a second execution path.
- Stop when the queue is idle, work is waiting for owner/network, the owner pauses runtime, or platform policy requires stopping.
- Handle `onTaskRemoved`, service recreation and cancellation without assuming the process will always survive.
- Declare the narrowest correct foreground-service type only after mapping actual operations to Android's current service-type rules. Do not label arbitrary AI processing as `dataSync` merely to bypass platform restrictions.

Android 14+ requires applicable foreground-service type declarations and permissions. Android 15 applies additional time limits to some service types. Foreground-service start restrictions still apply.

### 3. WorkManager recovery

Use unique work names and idempotent scheduling:
- One-time drain after enqueue when the app is backgrounded.
- Network-constrained retry for `WAITING_FOR_NETWORK`.
- Delayed retry for `RETRY_SCHEDULED`.
- Startup/reboot reconciliation where Android permits it.
- Periodic health and backup checks using WorkManager's best-effort minimum interval; never promise exact execution time.

WorkManager is a deferred recovery mechanism, not a substitute for a continuously running daemon.

### 4. Task leases and crash recovery

Before automatic recovery of a task left in `RUNNING`, add a reviewed schema migration for:
- execution lease ID / worker ID
- lease expiry and heartbeat
- last safe checkpoint
- external operation/idempotency reference
- recovery classification

Recovery rules:
- Read-only and local draft tasks may be retried only when their handler declares idempotent recovery.
- External writes, publishing, payments, destructive actions and Bridge side effects must not be replayed merely because a process died.
- If the outcome is ambiguous, move the task to an owner-review/reconciliation state.
- Do not turn `WAITING_FOR_OWNER` into `QUEUED` without a fresh owner grant.
- Do not reset a stale `RUNNING` task to `QUEUED` until its side-effect journal has been reconciled.

### 5. Owner control plane and health

Provide explicit owner controls:
- Runtime enabled/paused.
- Start/stop active session.
- Quiet hours and battery/network policies.
- Per-day call/token budget and maximum concurrent tasks.
- Pending approvals with exact tool, project, target, payload summary and expiry.
- Visible states: Active, Working, Waiting for network, Waiting for owner, Paused by owner, Restricted by Android, Recovering, Failed.
- Heartbeat and last-successful-run timestamps, with honest "deferred by Android" reporting.

External writes, financial operations and destructive actions remain blocked until durable approval storage and action-bound grants are connected to the executor.

## Required implementation order

- AR-3.1: application-scoped runtime owner and single shared executor instance.
- AR-3.2: task leases, operation journal and crash-recovery policy.
- AR-3.3: WorkManager one-time and deferred recovery scheduling.
- AR-3.4: owner-started foreground service with notification and platform-compliant service type.
- AR-3.5: owner control UI, health status and pause/stop controls.
- AR-3.6: Bridge enqueue integration through Core authorization; no direct handler invocation.
- AR-3.7: physical-device acceptance across reboot, process death, Doze, battery saver, offline mode, low storage and OS restrictions.

Do not begin external action handlers until AR-3.2 and durable owner approvals pass review.

## Acceptance criteria

- Every entry point uses the same executor and maintenance gate.
- Backup cannot capture a task while its handler is actively mutating external state.
- A process death does not silently lose a task or blindly replay an ambiguous external side effect.
- Owner approval survives process death and cannot be forged by model output.
- WorkManager retries are unique, bounded and idempotent.
- Foreground service starts only through permitted paths and has an accurate persistent notification.
- User pause/stop takes effect without corrupting task state.
- Runtime reports platform deferrals honestly.
- Device tests demonstrate recovery behavior; unit tests alone are insufficient.

## Platform truth

GLAFIRA can be designed to resume approved work when Android permits it, but no Android application can guarantee uninterrupted 24/7 execution while powered off, force-stopped, deprived of battery/network, or restricted by OS/vendor policy. A separately approved always-on host could improve availability later; no paid server or external infrastructure is assumed by this plan.
