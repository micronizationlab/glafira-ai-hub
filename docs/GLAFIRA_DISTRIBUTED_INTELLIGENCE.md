# GLAFIRA Distributed Intelligence

Status: accepted architecture; implementation is incremental and must remain compatible with the owner-controlled Android/Desktop Bridge.

## 1. Product model

GLAFIRA is one coordinated intelligence system with multiple execution nodes:
- **Android Mobile Node**: owner-facing mobile interface, voice/microphone/camera and notifications, lightweight local actions, secure local vault ownership, connectivity and approval controls.
- **Desktop Compute Node**: preferred coordinator/runtime for heavy work when paired and available; agent orchestration, parallel task execution, large document processing, local model inference, indexing/RAG, coding/builds and long-running jobs.
- **Future Compute Nodes**: optional additional trusted computers/servers, each explicitly enrolled, scoped and revocable.

The mobile and desktop applications are not separate assistants and must not create divergent memories or competing coordinators. GLAFIRA Core owns task identity, plans, state transitions, delegation policy and result reconciliation.

## 2. Task routing policy

A routing decision is made by Core using task requirements and node capability/availability, not by blindly sending every request to Desktop.
- Route to Desktop for sustained CPU/GPU/RAM work, local model inference, parallel agents, large files/indexing/RAG, coding/builds, and approved desktop-only automation.
- Keep work on Android when it is latency-sensitive and lightweight, tied to mobile sensors/voice/notifications, required for local owner authentication/approval/vault interaction, or useful offline.
- If Desktop is unavailable, Core may execute a safe lightweight fallback on Android, or persist a queued task for Desktop. It must not silently downgrade a task whose security, capability or quality requirements cannot be met.

## 3. Task lifecycle and protocol

Every delegated task has a stable taskId, projectId, optional parentTaskId, createdAt, deadline/expiry, priority, requiredCapabilities, requiredScopes, payload schema/version, idempotency key and state.

States: CREATED -> QUEUED -> DISPATCHED -> ACCEPTED -> RUNNING -> SUCCEEDED | FAILED | CANCELLED | EXPIRED.

A task may emit bounded progress events and child-task summaries. Retries must be idempotent and bounded. A disconnected node must not cause a task to be lost or executed twice. Core reconciles task state after reconnect using taskId and sequence/version numbers.

Task payloads contain instructions and minimum necessary context. They never contain website passwords, master keys, API secrets or raw vault contents. A node receives only owner-approved scopes.

## 4. Agent orchestration

Core plans work into dependency-aware subtasks. Independent subtasks may run concurrently subject to node capacity and configured limits. Each subtask declares executor/agent role, required capabilities, dependencies, resource estimate/priority, input references and expected output schema.

Agents return structured results, evidence/source references where applicable, and explicit failure reasons. Core validates results, resolves dependencies, retries eligible failures and synthesizes the final answer. An agent cannot grant itself tools, scopes or owner approvals.

## 5. Capability and resource registry

Each enrolled node advertises an authenticated capability snapshot: node ID, platform, app version, available execution adapters, model/runtime inventory, memory/CPU/GPU class where available, free capacity, battery/power state for mobile, network state and supported protocol versions.

Capability snapshots are hints, not authorization. Core must separately validate scopes and operation policy. Avoid collecting unnecessary hardware telemetry.

## 6. Security boundaries

- Pairing remains owner-controlled, TLS authenticated and certificate-pinned.
- LAN Bridge is transport, not a general-purpose shell or arbitrary remote execution endpoint.
- Every operation is typed, bounded, authenticated, replay-protected and scoped.
- External writes, financial operations, destructive actions and credential use require their own explicit owner policy/approval.
- Password Vault secrets stay on the node that owns the vault and are delivered only to a trusted destination adapter; never to LLM prompts, model providers, ordinary task payloads, logs, screenshots, telemetry or chat history.
- Desktop credentials for the Bridge are transport credentials, not website passwords.
- Revocation, expiry, audit metadata and fail-closed behavior apply to every node and task.
- Untrusted model output, remote node output and file content are data, never executable policy.

## 7. Offline and resilience behavior

Android remains useful when Desktop is asleep/offline. Tasks requiring Desktop are durably queued with expiry and clear status. When connectivity returns, Core reconciles and dispatches them. Desktop must support owner-configured sleep/wake behavior; automatic wake-on-LAN is optional and must not be assumed.

A Desktop crash or network loss must preserve accepted task state or mark it recoverable. No automatic replay of non-idempotent external actions.

## 8. Implementation sequence

1. **P0 — Restore green build**: fix current compile regression and require Android + Desktop tests.
2. **P1 — Shared task contract**: versioned task envelope, task state enum, capability requirements, idempotency and progress/result envelopes.
3. **P2 — Desktop task runtime**: bounded durable queue, worker capacity, cancellation, expiry and status query; no arbitrary shell.
4. **P3 — Android routing client**: Core routing policy, Bridge dispatch/accept/progress/result, reconnect reconciliation and local fallback.
5. **P4 — Agent execution**: connect planner batches to actual desktop agent/model/tool adapters with concurrency limits and output validation.
6. **P5 — Local model and knowledge services**: desktop model inventory, inference adapters, RAG/indexing and resource-aware routing.
7. **P6 — UX and acceptance**: desktop UI/installer, mobile task status, physical Android↔Desktop end-to-end tests, offline/reconnect and security tests.

## 9. Acceptance criteria

- A mobile request can be routed to Desktop and returns one correlated result.
- Desktop can run independent approved subtasks concurrently within configured limits.
- Progress, cancellation, expiry, retry and reconnect are deterministic.
- Desktop unavailable behavior is visible and safe.
- No secrets appear in task envelopes, logs, prompts or returned results.
- Unapproved operations fail closed.
- Android and Desktop unit/integration tests pass; physical LAN acceptance is recorded before release claims.

## 10. Current implementation boundary

The existing Android Bridge and Desktop JVM CLI provide pairing and a bounded project/conversation transport. They do **not** yet implement this distributed task runtime, general desktop control, model execution, or remote browser automation. This document is the accepted target architecture, not a claim that those features are already complete.