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
- Earlier #372 head run [44934756011](https://github.com/micronizationlab/glafira-ai-hub/actions/runs/44934756011) failed the backup-button clickable-node assertion (1/117). The branch now has a newer head `30dd5ab5d11630c5509d612c0aedba124ce1bfa0`; run [44964979933](https://github.com/micronizationlab/glafira-ai-hub/actions/runs/44964979933) has Android JVM, shared contracts and APK passed, while managed-emulator acceptance is still running. The latest implementation uses one Box clickable semantics node with `Role.Button` and a stable content description. Do not call this fixed until the emulator result and exact-head summary pass.
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
- Issue [#137](https://github.com/micronizationlab/glafira-ai-hub/issues/137) is partly stale: current `MainActivity.kt` has clickable Council and task-queue entry points; `CouncilScreen.kt` has an enabled-when-idle run action and one-provider guidance; `TaskQueueScreen.kt` has an Add Task button/dialog. `SettingsScreen.kt` still has no Bridge entry point, but Android Bridge classes are only typed authorization/dispatch helpers, explicitly not a network listener or pairing implementation. Do not add an Android server UI without an architecture decision; the intended screen may instead be a client/pairing surface for Windows Desktop Hub. Backup accessibility remains under #372 and provider/restore acceptance under #387. The issue has been annotated to narrow scope and avoid duplicate UI.
- Issue [#99](https://github.com/micronizationlab/glafira-ai-hub/issues/99) is partly stale: current `MainActivity.kt` already has an explicit `RequestPermission()` launcher for `RECORD_AUDIO`, called after the microphone tap. Denial currently shows only a Toast; rationale/permanent-denial recovery and app-settings route are not present in the inspected flow. Input remains one-shot `RecognizerIntent`, not a full controllable voice session; sound/voice settings and physical-device audio acceptance remain open. Do not recreate the permission launcher.
- Main branch backup action still needs the end-to-end accessibility fix described above.

**Next:** repair the high-priority visible controls first; then runtime permission/rationale/permanent-denial flow and independent STT/TTS lifecycle tests. Physical-device acceptance remains required.

### 5. Bridge & Shared Protocol — P1

**Existing implementation:** Android `GlafiraBridgeProtocol.kt`, `GlafiraBridgeReplayGuard.kt`, `GlafiraBridgeOperationDispatcher.kt`; JVM `:desktop-bridge` module; Windows Hub Bridge v1; shared JSON schemas/fixtures; iOS BridgeKit.

**Current evidence / blockers:** shared HMAC golden vector and Kotlin/Swift/C# conformance workflow exist. Windows Hub supports HTTPS/TLS, pairing and typed task claim/result operations. The contracts README explicitly says generated DTOs are build outputs and platform adoption must be deliberate; the schema remains the policy authority.

**Next:** build a single operation-by-operation compatibility matrix (request fields, signature, replay, authorization, response, task lifecycle and ACK); run shared fixtures against all consumers. Keep transport ACK distinct from durable task-result ACK.

### 6. Windows Desktop Hub — P2

**Existing implementation:** `BridgeV1HttpsHost.cs`, TLS certificate store, pairing ticket store, pairing endpoint, request authenticator, task operation dispatcher, durable Hub store and 16 test paths.

**Current evidence / blockers:** source already implements an explicitly started HTTPS host, private-LAN-only binding, certificate fingerprint validation, bounded request sizes, pairing and authenticated task dispatch. `DesktopHubHostController` is wired into WPF `MainWindow`, with visible start/stop, pairing-ticket, Agent approval and revocation flows. `DesktopHubStore` persists Agents, capabilities, leases/fencing, progress and results. Issue [#123](https://github.com/micronizationlab/glafira-ai-hub/issues/123) is stale as a missing-host task and has been annotated; the remaining gap is Desktop Core plan/DAG and local-worker integration with multi-Agent queue dispatch, restart recovery and physical two-Agent acceptance. Desktop source presence does not prove installer/two-PC acceptance.

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

**Current evidence / blockers:** typed provider outcome handling is already present on main: `NOT_SENT` / `REJECTED` / `UNKNOWN`, bounded Retry-After, pre/post-dispatch classification and composite-consultation UNKNOWN with partial-result preservation. Issue #262 is stale as a port task and has been annotated; remaining work is durable per-stage checkpoint/reconciliation across process death and Core retry scheduling. Provider IDs include GigaChat, OpenAI, DeepSeek, Grok, Gemini and OpenRouter; Yandex is explicitly disabled in `ProviderRegistry.kt`, and `openrouter/free` is not a guarantee of quota or perpetual free access. This audit has not established a complete multi-provider live acceptance matrix.

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


## Pull-request topology snapshot

At the time of this inventory, the repository had 76 open pull requests. Several are stacked on other feature branches rather than targeting `main`; treat their base branch as a dependency, not a cosmetic setting.

Examples found in the open-PR list:
- Android backup/accessibility: #372 → #369 → #352 → #347. All four are one dependent delivery chain; the current exact-head emulator blocker is at the top of that chain.
- Core project fairness: #119 is based on #104 (task dependency/parallel-claim work).
- Delivery and portable recovery: #58 is based on #57.
- Desktop queue reliability: #64 is based on #62, which is based on #61.
- Older runtime stack: #37 is based on #35; #35 is based on #32.
- Bridge owner APK validation: #69 is based on the Android Keystore signer fix branch (#63).
- Older Android Bridge integration and runtime PRs (#50, #47, #48, #37, #35) form another stack and must be reconciled with the newer mainline implementation before reuse.
- Earlier iOS/Bridge/voice PRs (#17, #18, #21, #23, #27, #28) are based on feature branches, not all on current main.

**Action:** before any merge, compare the PR head with current `main`, identify the complete dependency chain, inspect every changed file, and check whether the change has already landed through a newer implementation. Rebase/retarget only after that analysis; never merge an intermediate PR to make its descendants appear ready.


## Source-audit refresh — 2026-11-22

This update supersedes only the moving CI/source observations above; historical run references remain useful for the earlier commits.

- **Current baseline remains** `main` = `7dda2bc6f7ca7ba506c0b59ddf6d97ca2b927193`. The 150-step plan now records this baseline and the agreed priority order: Core → Backup & Recovery → Runtime/Bridge/Desktop → Android UI/Voice, Agent handler expansion, iOS and specialist agents; CI is cross-cutting.
- **#372 latest head** is `30dd5ab5d11630c5509d612c0aedba124ce1bfa0`, still stacked #372 → #369 → #352 → #347. Latest run `44964979933`: Android JVM, Shared Contracts and APK passed; emulator acceptance was still running at the time of this refresh. Windows/iOS/workflow lint were skipped, not passed. Branch compare against main is 33 commits ahead / 4 behind; review the whole dependency stack and rebase only after the dependency chain is resolved.
- **#380 and #382** are each based directly on current main (not behind). Their exact-head Android matrices passed: #380 run `44940462816` (238 JVM cases, 118 emulator cases) and #382 run `44941044671` (238 JVM cases, 119 emulator cases). Both remain draft and need independent code review; skipped Windows/iOS jobs are not passes. #380 does not implement the production atomic ownership-transfer CAS required by #366. #382 persists Runtime config in a separate SQLite database; portable snapshot scope must remain explicit and restore must stay fail-closed/paused.
- **Windows Agent** source verification: Program.cs registers no concrete `IAgentTaskHandler`; AgentWorker polls only when status is Ready and the registry contains at least one handler. Coordinator and Hub task claim/result operations exist, including durable journal and exact ACK checks. Issue #384 remains the activation gate for bounded result artifacts and the first read-only handler.
- **Protected branch policy** was tested by attempting to merge a documentation-only PR after its exact-head summary passed: GitHub rejected it because at least one approving review is required and 7 of 9 required status checks were still expected (platform jobs were skipped). Do not bypass this rule. Documentation changes remain open for owner review.
- **No source/runtime fixes are represented as merged by this inventory.** A docs PR or green partial workflow is not a product acceptance result.

- **Voice source recheck (2026-11-22):** confirmed the runtime microphone permission request exists on main; issue #99 has been annotated with the remaining denial/permanent-denial and voice-session gaps. This is a scope correction, not completion of voice acceptance.

- **Windows Desktop recheck (2026-11-22):** confirmed the HTTPS host is lifecycle-wired into WPF, and task claim/result dispatch is present. Issue #123 now distinguishes implemented Hub transport/store from the still-unproven Desktop Core plan/DAG/multi-Agent integration and physical acceptance.

- **Android UI recheck (2026-11-22):** confirmed Council and Add Task controls exist in source; Bridge entry point is still absent from SettingsScreen. Issue #137 now distinguishes source-present controls from installed-APK/physical acceptance and focuses remaining work on the Bridge entry point plus reproduced regressions.

- **Voice/settings recheck (2026-11-22):** confirmed the existing «ЗВУК И ГОЛОС» card, TTS/auto-speak toggles, mic permission status/request and sound-volume slider. Issue #99 was annotated to narrow remaining work to missing controls/session behavior.

- **Provider source recheck (2026-11-22):** confirmed typed outcome classification and composite UNKNOWN behavior on main; annotated #262 to focus on durable checkpoint/reconciliation rather than re-porting existing code. Confirmed Yandex remains unsupported and OpenRouter free availability is not guaranteed.

- **Bridge UI architecture recheck (2026-11-22):** confirmed Android Bridge protocol/core gate do not implement a network listener or pairing; annotated #137 so the missing entry point is not mistaken for permission to recreate the legacy Android-hosted server. UI direction must match the Android peer ↔ Windows Desktop Hub authority model.
