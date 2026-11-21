# GLAFIRA Parallel Development Program

Status: owner-approved execution model; workstream baselines require live CI and branch reconciliation.
Date: 2026-11-19

## 1. Operating decision

Develop all four applications concurrently. Within each application, independent engineering tracks may also run concurrently. Shared contracts, security boundaries and integration gates prevent parallel work from becoming incompatible.

The four product streams are:
1. Android owner client.
2. iOS owner client.
3. Windows Desktop workspace and Agent-fleet host.
4. Windows Agent background worker.

Cross-cutting streams run in parallel with all four products:
- Core/runtime and task orchestration.
- Versioned Bridge and Agent protocol.
- Identity, enrollment, authorization, approvals and credential storage.
- CI, integration, test infrastructure and release packaging.
- Shared design system and visual mockup approval.

This is a parallel program, not four serial projects. A blocked stream must not stop unrelated ready streams.

## 2. Parallel workstream matrix

### Android
- A1 — Core/runtime integration: durable task queue, execution, recovery, dependency scheduling and result persistence.
- A2 — Bridge and device trust: TLS host/client compatibility, pairing, approval, revocation, reconnect and offline delivery.
- A3 — Mobile product workflows: chat/task inbox, project context, approvals, notifications, settings and diagnostics.
- A4 — Reliability: instrumentation, process death, network interruption, backup/restore, physical-device acceptance and installable APK.

### iOS
- I1 — Native app shell: establish a real Xcode app target and navigation lifecycle around the existing Swift package.
- I2 — Bridge compatibility: protocol golden vectors, TLS pinning, pairing, Keychain, revocation and reconnect.
- I3 — Owner workflows: chat, projects, task status, approvals, notifications and offline state.
- I4 — Delivery: simulator tests, signing configuration owned by the user, physical iPhone/iPad acceptance and TestFlight readiness.

A Swift package or SwiftUI module is not a shippable iOS app. Do not claim distribution readiness until a signed app target is built and tested.

### Windows Desktop
- D1 — Desktop workspace: native Windows app shell, chats, projects, task views, approvals and diagnostics.
- D2 — Host/control plane: authenticated Agent endpoint, enrollment, inventory, health, capabilities, revocation and fleet controls.
- D3 — Broker/scheduler: durable task leases, bounded global/per-node concurrency, dependency-aware dispatch, heartbeat, retries and result ACK.
- D4 — Host lifecycle: Windows service/tray operation, autostart, secure local storage, graceful shutdown/drain, upgrade and recovery.
- D5 — Packaging: signed installer, update/rollback and clean-install/upgrade acceptance.

The existing Desktop Bridge CLI is a useful protocol/worker foundation, not the final Desktop workspace or packaged host.

### Windows Agent
- W1 — Worker service: background process, startup, reconnect/backoff, heartbeat, pause/resume and resource reporting.
- W2 — Secure enrollment: first-time owner approval on Desktop, device-bound credential, capability/scope grants, rotation and revocation.
- W3 — Typed execution: validated task envelopes, bounded local handlers, dependency/cancellation semantics, structured results and artifact integrity.
- W4 — Fleet resilience: worker crash, host restart, network loss, lease expiry, duplicate delivery and late-result reconciliation.
- W5 — Packaging: minimal installer/tray/diagnostics, signed update, uninstall and resource-limit controls.

The initial acceptance fleet is ten heterogeneous Windows Agents. The protocol and storage must not encode a fixed fleet size; later scaling targets include 50 and 100 nodes.

## 3. Shared engineering tracks

### C1 — Canonical contracts
Own versioned schemas for task, result, heartbeat, capabilities, enrollment, approval, revocation, lease, artifact and error/status vocabulary. Kotlin, Swift and Windows implementations must pass shared golden vectors and compatibility tests.

### C2 — Core authority and policy
Keep GLAFIRA Core as the logical authority for task policy, project isolation, tool admission, permissions, approvals and audit. Windows Desktop coordinates its Agent fleet but cannot override Core policy or owner approval.

### C3 — Security and privacy
Define per-device identity and revocable credentials; local protected credential storage; TLS; replay protection; scoped capabilities; least privilege; audit redaction; safe update verification. Never put passwords, API keys, private keys or reusable browser credentials in task payloads or repository files.

### C4 — CI and integration
Maintain per-stream CI and a cross-workstream status report. Validate the exact current PR head SHA. A green result for an older SHA is stale. Run fast unit checks on each change and full cross-platform acceptance at integration milestones.

### C5 — Design system
Maintain shared identity, tokens, typography, spacing, status semantics and component behavior. Produce separate Android, iOS, Desktop and Agent mockups. The owner must approve the actual mockups before broad UI implementation. Existing functional UI must not be removed while the new design is introduced.

## 4. Dependency graph and parallelism rules

The following can proceed concurrently:
- Android queue/recovery and Android Bridge hardening, provided each has explicit file ownership.
- iOS app-target bootstrap and iOS Bridge compatibility.
- Desktop workspace shell foundation and Agent service foundation.
- Agent enrollment protocol and Desktop fleet inventory UI, once the schema is agreed.
- CI reliability, test fixtures, contract tests and mockup preparation.
- Independent test coverage and documentation for each platform.

The following require an agreed upstream contract first:
- Android/iOS/Windows protocol implementations depend on C1 schema version and golden vectors.
- Agent task execution depends on Desktop enrollment, scopes and lease contract.
- Desktop dispatch depends on Core admission and owner approval contract.
- Cross-device result reconciliation depends on stable task IDs, idempotency keys and outcome taxonomy.
- UI integration depends on approved mockups and stable view models, but UI-independent backend work does not wait for mockups.

## 5. Branch, ownership and integration rules

1. Every active workstream has one named file/path ownership boundary and one branch/PR. Avoid simultaneous edits to the same source file.
2. Use small branches based on the latest validated dependency branch, not automatically on main when main lacks required foundations.
3. A PR must state its exact base branch, upstream dependencies, files owned, tests run, tests not run and known acceptance gaps.
4. Keep dependent PRs stacked when required. Do not merge a child branch before its parent dependency is accepted.
5. Never force-push, reset, retarget or merge another workstream without explicit coordination.
6. Shared protocol changes are proposed first in C1; platform adapters follow in parallel after the schema is reviewed.
7. Integration branches are disposable validation compositions, not substitutes for independently reviewable PRs.
8. A failure in one stream blocks only dependent work. Record the blocker and continue unrelated ready work.
9. Keep all consequential external actions in DRY_RUN until owner-approved live-action gates pass.
10. UI redesign remains gated on visual mockup approval; backend, contracts, tests and packaging preparation continue meanwhile.

## 6. Work intake and scheduling

Use the existing GLAFIRA Project Dispatcher as the read-only cross-PR status source; do not create a competing dispatcher. Extend its workstream manifest/status only after validating the existing PR #66 workflow and its dependency baseline.

For each workstream track:
- owner/maintainer and branch;
- priority and dependency IDs;
- exclusive file/path ownership;
- current head SHA and PR;
- CI state per workflow and job;
- local/remote tests and device acceptance;
- blockers, risks and next independently executable task.

Schedule work in this order:
1. Restore a validated shared integration baseline and remove CI infrastructure blockers.
2. Freeze the first canonical cross-platform task/Agent contracts.
3. Start independent Android, iOS, Desktop and Agent implementation branches in parallel.
4. Add contract tests and platform-specific CI while implementation proceeds.
5. Compose integration branches frequently; do not wait until all four apps are complete.
6. Validate end-to-end flows across two nodes, then ten Agents, then scale/fault scenarios.
7. Obtain owner visual approval and perform incremental UI integration.
8. Release each app only after its own install/upgrade/security acceptance and ecosystem interoperability checks.

## 7. Current repository facts and immediate blockers

As of 2026-11-19:
- PR #72 contains the four-app architecture and design-system baseline.
- PR #71 contains substantial Core/SQLite concurrency work and remains Draft. Its latest head SHA is `6dd1bfc81bf4d46876c2cbcc3bbc561007232e12`; a new CI run was queued at audit time. An earlier run failed dependency resolution for `org.json:json:20240303`, `com.google.code.gson:gson:2.11.0` and `org.bouncycastle:bcpkix-jdk18on:1.80`. Repository declarations include Google Maven and Maven Central, so inspect the exact CI/network/repository response before changing versions or source.
- PR #66 provides a read-only Project Dispatcher. Its dispatcher-specific workflow previously passed, but its Android build failed on unresolved Runtime Foundation symbols in `GlafiraCoreRuntimeOwner.kt`. It must be validated against an integration-ready dependency baseline before being treated as operational.
- PR #62 provides a durable Desktop CLI queue foundation; it is not the full Desktop app or Agent service.
- PR #44 provides an iOS Swift BridgeKit and UI module; it is not a signed/installable iOS app.
- The repository contains numerous stacked PRs and feature branches. Their presence is not proof that they are integrated or compatible.
- No full Windows Agent product, installer or ten-node acceptance suite is established by this audit.
- No UI mockup approval has been completed. Do not start broad visual UI replacement.

## 8. Definition of done for a workstream

A workstream is not complete merely because code exists or a PR is open. Completion requires:
- agreed contract and security review;
- build and focused unit tests on the exact PR head;
- integration tests for its declared dependencies;
- crash/restart/network failure tests where applicable;
- no skipped required tests hidden as success;
- artifact/package verification where applicable;
- owner acceptance for physical-device behavior, credentials, permissions and release;
- updated status with exact SHA, links and remaining limitations.

## 9. First execution batch

1. Reconcile and validate the current Core/queue dependency stack without merging it into main.
2. Validate the Project Dispatcher against that integration-ready stack.
3. Publish C1 task/result/Agent protocol schema and shared golden test vectors.
4. Start Android A1/A2, iOS I1/I2, Desktop D2/D3 and Agent W1/W2 as independent branches with non-overlapping ownership.
5. Add contract CI and failure-injection tests in parallel.
6. Continue mockup preparation separately; wait for owner visual approval before broad UI implementation.

This plan authorizes parallel work organization. It does not authorize automatic merging, live external actions, credential transfer, or release.
