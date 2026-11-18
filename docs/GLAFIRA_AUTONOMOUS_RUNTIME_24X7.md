# GLAFIRA Autonomous Runtime — 24/7 operating model

Owner requirement (2026-11-18): GLAFIRA AI HUB must support autonomous operation around the clock whenever the device, power, network and Android execution policy permit it. It must not depend on the owner keeping the chat screen open.

## Product behavior

GLAFIRA has two distinct modes:

- **Interactive mode:** responds to the owner in the app, voice or connected clients.
- **Autonomous mode:** continues approved background work, monitors task queues, schedules follow-ups, retries recoverable failures and asks the owner for decisions when a policy gate is reached.

Autonomous mode is owner-controlled, visible, pausable and resumable. It is not an unrestricted agent loop.

## Android runtime design

1. **Foreground service for active long-running work.** Use only for user-noticeable ongoing work, with a persistent notification and explicit start/stop controls. Declare the correct foreground-service type and permissions for the target Android versions; request notification permission where applicable. Do not disguise background work as a media/location service.
2. **WorkManager for durable deferred work.** Persist scheduled maintenance, queue recovery, bounded retries and network-constrained jobs. WorkManager is not a real-time scheduler and must not be represented as guaranteeing exact execution times.
3. **Boot and process recovery.** Reconcile the persisted queue after reboot/app update/process death where Android permits. Respect force-stop: Android does not allow an app to silently restart itself after the owner force-stops it.
4. **Persistent task queue.** Store task state transactionally (Room/SQLite), not only in memory or a rewrite-in-place JSON file. Required states: queued, running, waiting_for_network, waiting_for_owner, retry_scheduled, succeeded, failed, cancelled. Each task has project, origin, priority, created/updated timestamps, attempt count, deadline, idempotency key and required scopes.
5. **Single execution authority.** Foreground service and WorkManager workers call the same Core task executor. They must not create separate permission logic or run duplicate copies of a task.
6. **Heartbeat and health.** Record last heartbeat, active task, queue depth, connectivity, battery/thermal constraints, last failure and recovery reason. Show these in an owner-facing status screen and notification.
7. **Network and provider resilience.** Pause safely while offline; resume on validated connectivity. Apply bounded timeouts, exponential backoff with jitter and provider-specific rate limits. Never retry non-idempotent external actions unless the destination supports an idempotency key and Core verifies it.
8. **Resource policy.** Owner-configurable active hours, quiet hours, battery threshold, metered-network policy, maximum concurrent tasks, daily model-call/token budget and maximum task duration. Critical security and data-integrity alerts may notify during quiet hours.
9. **Owner approval inbox.** If a task requires sending, publishing, purchasing, payment, deleting or changing external business data, persist it as waiting_for_owner. Display exact target, recipient, payload, consequences and scopes. Resume only after an explicit, still-valid approval bound to that exact action.
10. **Safe shutdown and upgrade.** Persist checkpoints before stopping; handle cancellation cooperatively; migrate queue schema transactionally; retain rollback path. No task may be marked succeeded until its effect is verified.

## 24/7 limitations and truthful status

- No Android app can execute while the phone is powered off, has no usable battery, or has been force-stopped by the owner.
- Doze, vendor battery managers, permission revocation, OS updates and network loss can defer or stop background execution.
- Foreground services improve continuity but are subject to Android policy, user-visible notification requirements and OS restrictions.
- WorkManager provides durable eventual execution, not an exact 24-hour uptime guarantee.
- If continuous operation must continue while the phone is off or disconnected, a separately approved always-on host is required. The initial design remains local-first and does not silently introduce a rented server or paid backend.

The UI must report states honestly: **Active**, **Working**, **Waiting for network**, **Waiting for owner**, **Paused by owner**, **Restricted by Android**, **Recovering**, **Failed**. Never show “24/7 online” when the runtime cannot verify it.

## Implementation sequence (part of Core Priority 1)

- **AR-1 — Contracts and persistence:** task model, Room schema, state transitions, idempotency and migration tests.
- **AR-2 — Shared executor:** one Core task executor with scope checks, cancellation, checkpoints and typed outcomes.
- **AR-3 — Android service and scheduler:** foreground service for active work; WorkManager for deferred/recovery work; boot reconciliation where allowed; notification and runtime permissions.
- **AR-4 — Owner control plane:** start/pause/resume/stop, quiet hours, battery/network budgets, queue view and approval inbox.
- **AR-5 — Resilience acceptance:** test process kill, reboot, offline/online transitions, battery restrictions, permission revocation, duplicate dispatch, app upgrade and force-stop behavior on physical devices.

## Acceptance criteria

- A task survives process death and is not duplicated after recovery.
- The same authorization decision is applied whether work starts from UI, service, scheduler or Bridge.
- External side effects remain blocked until exact owner approval.
- Owner can inspect, pause, resume or cancel autonomous work.
- Runtime status distinguishes active execution from deferred or blocked execution.
- Physical-device tests document Android-version and manufacturer-specific behavior.
