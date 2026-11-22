# GLAFIRA — live inventory of ten workstreams

**Audit baseline:** `main` at `7dda2bc6f7ca7ba506c0b59ddf6d97ca2b927193` (2026-11-22).  
**Purpose:** source-and-test inventory for the ten coordinated workstreams. This is not a release acceptance report.  
**Rule:** a source file or green partial workflow is not proof of an end-to-end feature. Skipped jobs are not passes; physical-device and two-PC checks remain separate gates.

## Baseline inventory

The recursive Git tree at the baseline contains 335 tracked files. Major module counts:

| Area | Files | Test / test-infrastructure paths |
|---|---:|---:|
| Android app (`app/`) | 141 | 47 |
| Windows Desktop (`windows-desktop/`) | 47 | 16 |
| Windows Agent (`windows-agent/`) | 30 | 9 |
| iOS (`ios/`) | 18 | 4 |
| Shared contracts (`contracts/`) | 16 | 1 |
| Project docs (`docs/`) | 33 | — |
| GitHub Actions workflows | 8 | — |

The 150-step ledger currently names baseline `0649b3a24592e2ccd73b6997db04e2664f055be4`; that is stale relative to this audit baseline. Reconcile its checkboxes and dependency references before using it as the live completion authority.

## Ten workstreams

### 1. GLAFIRA Core — P0

**Existing implementation:** `GlafiraTaskStore.kt`, `GlafiraCoreTaskExecutor.kt`, `GlafiraTaskQueue.kt`, `GlafiraTaskOwnershipRecord.kt`, `GlafiraCoreRuntimeOwner.kt`, task-plan orchestration, state-transition matrix, recovery and concurrency tests.

**Current evidence / blockers:**
- PR [#347](https://github.com/micronizationlab/glafira-ai-hub/pull/347) adds claim/execution/result fencing by immutable owner-generation token. Exact-head Android JVM, contracts and APK passed; emulator acceptance failed in the backup UI test. Do not merge the PR solely on partial green checks.
- Issue [#381](https://github.com/micronizationlab/glafira-ai-hub/issues/381) remains the authoritative execution/result fencing acceptance gate, including failure injection and stale-generation result rejection.
- PR [#380](https://github.com/micronizationlab/glafira-ai-hub/pull/380) adds owner-generation binding to approvals. Exact-head run [44940462816](https://github.com/micronizationlab/glafira-ai-hub/actions/runs/44940462816): Android JVM 238 cases, emulator 118 cases, contracts and APK passed. It remains draft and does not close task execution fencing or full ownership handoff.
- PR [#382](https://github.com/micronizationlab/glafira-ai-hub/pull/382) persists Runtime enablement/budgets in SQLite. Exact-head run [44941044671](https://github.com/micronizationlab/glafira-ai-hub/actions/runs/44941044671): Android JVM 238 cases, emulator 119 cases, contracts and APK passed. It remains draft; this does not close restore/provider acceptance.
- Issue [#366](https://github.com/micronizationlab/glafira-ai-hub/issues/366) is the ownership-transfer prerequisite. Issue [#266](https://github.com/micronizationlab/glafira-ai-hub/issues/266) is the durable result outbox/exact ACK gate.

**Next:** review #380/#382 independently for integration; finish #381 and #366 transaction/failure tests; then #266. Do not reimplement existing queue/trace/runtime components.

### 2. Backup & Recovery — P0/P1

**Existing implementation:** `BackupSettings.kt`, `GlafiraBackupWorker.kt`, `BackupArchive.kt`, `BackupScheduler.kt`, `GlafiraCoreBackupCoordinator.kt`, portable snapshot and recovery instrumentation tests.

**Current evidence / blockers:**
- Android backup accessibility work is split across dependent PRs: [#347](https://github.com/micronizationlab/glafira-ai-hub/pull/347) → [#352](https://github.com/micronizationlab/glafira-ai-hub/pull/352) → [#369](https://github.com/micronizationlab/glafira-ai-hub/pull/369) → [#372](https://github.com/micronizationlab/glafira-ai-hub/pull/372). Do not merge an intermediate branch independently.
- PR #372 exact-head run [44934756011](https://github.com/micronizationlab/glafira-ai-hub/actions/runs/44934756011): Android JVM, contracts and APK passed; emulator acceptance failed the backup-button clickable-node assertion (1 failure / 117 cases). Logs were inspected; this is a real UI accessibility/action failure, not a reason to weaken the test.
- Issue [#350](https://github.com/micronizationlab/glafira-ai-hub/issues/350) tracks the click/guidance regression.
- Issue [#387](https://github.com/micronizationlab/glafira-ai-hub/issues/387) separately requires provider upload → remote read-back → authenticated verification and clean-device restore. A clickable button is not backup acceptance.

**Next:** solve the Compose/UiAutomator node and callback path; retain both clickability and visible guidance assertions; then execute provider failure injection and clean-profile restore acceptance. Physical Google Drive/Yandex provider behavior requires device testing.

### 3. Trace, Security & Owner Approval — P0/P1

**Existing implementation:** `GlafiraCoreTrace.kt`, `GlafiraCoreTraceStore.kt`, secret redaction tests, approval store, HMAC/replay protection, Android Keystore and Windows DPAPI stores.

**Current evidence / blockers:** trace persistence and instrumentation tests exist. PR #380 adds generation binding to approval records and fails closed for legacy rows without canonical owner metadata. Need cross-check direct SQL task-state writes outside the executor and prove trace/task/result atomicity under injected failures.

**Next:** inventory every mutation path for task state and grants; add failure-boundary tests; keep secrets and pairing credentials out of Trace/logs.

### 4. Android App, UI & Voice — P1/P4

**Existing implementation:** Compose app, Settings, project/task planning UI, Bridge settings/client components, voice policy/plugin contracts, speech delivery tests and 47 Android test paths.

**Current evidence / blockers:**
- Issue [#137](https://github.com/micronizationlab/glafira-ai-hub/issues/137) tracks Bridge entry-point visibility, backup controls, Council action and user-facing task queue.
- Issue [#99](https://github.com/micronizationlab/glafira-ai-hub/issues/99) tracks runtime microphone permission, voice-session UX and sound settings. Existing voice policy/contracts do not constitute a complete voice chat.
- Main branch backup action still needs the end-to-end accessibility fix described above.

**Next:** repair the high-priority visible controls first; then runtime permission/rationale/permanent-denial flow and independent STT/TTS lifecycle tests. Physical-device acceptance remains required.

### 5. Bridge & Shared Protocol — P1

**Existing implementation:** Android `GlafiraBridgeProtocol.kt`, `GlafiraBridgeReplayGuard.kt`, `GlafiraBridgeOperationDispatcher.kt`; JVM `:desktop-bridge` module; Windows Hub Bridge v1; shared JSON schemas/fixtures; iOS BridgeKit.

**Current evidence / blockers:** shared HMAC golden vector and Kotlin/Swift/C# conformance workflow exist. Windows Hub supports HTTPS/TLS, pairing and typed task claim/result operations. The contracts README explicitly says generated DTOs are build outputs and platform adoption must be deliberate; the schema remains the policy authority.

**Next:** build a single operation-by-operation compatibility matrix (request fields, signature, replay, authorization, response, task lifecycle and ACK); run shared fixtures against all consumers. Keep transport ACK distinct from durable task-result ACK.

### 6. Windows Desktop Hub — P2

**Existing implementation:** `BridgeV1HttpsHost.cs`, TLS certificate store, pairing ticket store, pairing endpoint, request authenticator, task operation dispatcher, durable Hub store and 16 test paths.

**Current evidence / blockers:** source already implements an explicitly started HTTPS host, private-LAN-only binding, certificate fingerprint validation, bounded request sizes, pairing and authenticated task dispatch. Issue [#123](https://github.com/micronizationlab/glafira-ai-hub/issues/123) must be reconciled against existing source rather than used to create a duplicate Hub. Desktop source presence does not prove installer/UI/two-PC acceptance.

**Next:** trace DI and UI wiring from Desktop Hub controls to host lifecycle; validate generated install package and two-PC pairing/approval/revocation. Then test claim → lease → result → ACK end-to-end.

### 7. Windows Agent — P2

**Existing implementation:** Windows service host, DPAPI credential store, pairing wizard, heartbeat, task polling coordinator, Bridge task client, execution journal, typed handler registry and 9 test paths.

**Current evidence / blockers:**
- `Program.cs` registers the polling coordinator and task handler registry, but the DI container currently has no `IAgentTaskHandler` implementation registered. The registry is therefore empty and the worker intentionally does not claim tasks.
- README correctly states that task execution is not enabled, although polling/lease/outbox infrastructure now exists in source.
- Issue [#384](https://github.com/micronizationlab/glafira-ai-hub/issues/384) defines the bounded result-artifact contract required before the first production handler.

**Next:** finish artifact metadata/storage/outbox/ACK contract and tests; implement only one bounded read-only diagnostics handler after that. No shell, arbitrary paths or generic command handler. Physical two-PC test required.

### 8. iOS — P3

**Existing implementation:** Swift BridgeKit, pinned HTTPS client, pairing, credential import, Keychain, owner biometrics, native SwiftUI shell and four test paths.

**Current evidence / blockers:** protocol and credential tests exist. README explicitly excludes App Store/TestFlight signing and requires physical-device validation for Keychain biometric prompts and enrollment-change invalidation. Project UI still needs authenticated Core data and a local outbox/cache.

**Next:** preserve protocol parity while binding project/conversation/task data after the shared Core contract stabilizes. Do not prioritize ahead of Android/Desktop Core delivery.

### 9. AI Providers, Planning & Memory — P3/P4

**Existing implementation:** `AiProviderClient.kt`, `AiProviderResult.kt`, `ProviderRegistry.kt`, task-plan decomposer/orchestrator, planner policy, voice plugin contracts and provider outcome/transport tests.

**Current evidence / blockers:** provider outcome classification and task planning have tests. Need to verify actual provider adapters, quota/fallback behavior, model availability, persistence/provenance of memory and prevention of blind retries for unknown provider outcomes. This audit has not yet established a complete multi-provider live integration.

**Next:** audit each configured provider adapter and classify outcomes as not-sent/rejected/unknown; preserve free-tier constraints and do not introduce paid dependencies without explicit approval.

### 10. CI, Release & Installation — cross-cutting

**Existing implementation:** eight GitHub Actions workflows covering Android, contracts, project dispatcher, iOS, Windows Agent, Windows deployment package, Windows Desktop Hub and Desktop UI.

**Current evidence / blockers:**
- Change-aware CI means platform jobs can be skipped. Skipped is not passed.
- PR #380 and #382 exact-head Android-focused matrices passed, but Windows/iOS/workflow-lint were skipped.
- PR #347 and dependent backup UI PRs fail Android emulator acceptance; JVM tests and APK packaging being green do not erase that failure.
- Latest main dispatcher success is not equivalent to a platform build/test matrix.

**Next:** maintain an exact-head matrix per PR; inspect test counts, failure reports and artifacts; build installable APK/Windows packages; record physical-device and two-PC acceptance separately.

## Active integration order

1. Core ownership and task/result fencing (#366, #381), with #380 approval fencing and #382 runtime persistence reviewed as separate slices.
2. Backup accessibility regression (#350 / #372 dependency chain), then provider read-back and clean-device restore (#387).
3. Shared Bridge request/result/ACK compatibility across Android, Windows Desktop, Agent and iOS.
4. Windows Desktop ↔ Agent installation/pairing/heartbeat, then bounded result artifact and first read-only handler (#384).
5. Android visible UI/voice defects (#137, #99).
6. iOS data integration and provider/model expansion.
7. Exact-head release matrix and physical acceptance.

## Audit rules

- No duplicate implementation when an equivalent module already exists.
- No merging a PR from the middle of a dependency chain.
- No weakening a failing test to obtain green CI.
- No treating a workflow dispatcher as platform validation.
- No claiming Windows task execution until a handler is registered and end-to-end result ACK is proven.
- Every fix gets a focused test, exact-head CI inspection and a recorded acceptance result.
