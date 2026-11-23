# Core recovery stack reconciliation: PRs #71, #104, #119 and #122

**Audit baseline:** main `2fdd9002e5e9e114c43a0b2b7b3b68b28c41c85b` (2026-11-21).  
**Decision:** do not merge any historical branch as a stack. Preserve only verified, still-relevant deltas through small current-main changes.

## 1. Why the old stacks are unsafe to merge

| PR | State | Original base → head | Original scope | Current comparison vs main | Decision |
|---|---|---|---:|---:|---|
| #71 | Open draft | `85eca11ddbeda8972a682c5d3cfd9968137c17d0` → `e73d25f468614c3377930f4c31b45e91d91c2994` | 21 files, +1,418/−112 | 493 commits ahead, 84 behind | Do not merge; highly divergent and contains a broad old platform/Bridge/Agent stack |
| #104 | Open draft, stacked on #103 | `0b6969253b97a94541ec99506745fcc8af40ec12` → `e037d4e3e8e084207782d70b0d3fa2d8f52894d6` | 18 files, +1,717/−147 | 39 commits ahead, 73 behind | Do not merge; dependency/concurrency changes have newer mainline implementations |
| #119 | Open draft, stacked on #104 | `e037d4e3e8e084207782d70b0d3fa2d8f52894d6` → `47fe2441b6c4fcab57465f01e8952c80bce87097` | 2 files, +38/−2 | 45 commits ahead, 73 behind | Do not merge; fairness change is already in current Core store and tests |
| #122 | Closed, never merged; stacked on #119 | `47fe2441b6c4fcab57465f01e8952c80bce87097` → `60a819f1c476db48571e290de4fb0f8bd60bfcad` | 24 files, +1,529/−11 | 128 commits ahead, 73 behind | Do not merge as a stack; selectively retain the durable task-plan features already present on main and do not resurrect the obsolete Android-server path |

The head/base divergence is material: these are not clean feature branches against today's main. Their historical CI results do not validate a merge against current main.

## 2. Feature-by-feature reconciliation

### PR #71 — bounded parallel execution, maintenance and recovery

**Mainline equivalents present:**

- `GlafiraCoreExecutionLimiter.kt`: one owner-wide bounded execution limiter shared by Core workers and distributed project dispatch.
- `GlafiraCoreMaintenanceGate.kt`: shared execution leases and an exclusive maintenance/snapshot gate.
- `GlafiraCoreTaskExecutor.kt`: recovery epoch and bounded task execution.
- `GlafiraCoreDrainWorker.kt` / `GlafiraCoreWorkScheduler.kt`: scheduled queue drain.
- `GlafiraTaskStore.kt`: transactional claims, encrypted result persistence, history and dependency state.
- Current instrumentation/unit tests cover independent-connection claims, duplicate claim prevention, cancellation/completion race, result/history rollback, recovery and backup/restore behavior.

**Do not transplant:** the PR's broad Android Bridge listener, Kotlin desktop-bridge CLI and older Agent/Bridge transport implementation. The current product has a separate Windows Desktop Hub and Windows Agent transport. A historical Android-hosted server is not the chosen fleet architecture.

### PR #104 — durable dependency edges and claim gating

**Mainline equivalents present:**

- `GlafiraTaskDependencyPolicy.kt`: bounded dependency count and cycle validation.
- `GlafiraTaskStore.kt`: schema v6 dependency table, atomic task+edge enqueue, claim gating and failure propagation.
- `GlafiraTaskStoreDependencyInstrumentedTest.kt`: SQLite integration coverage.
- `GlafiraTaskDependencyPolicyTest.kt` and `GlafiraTaskRecoveryPolicyTest.kt`: pure policy/recovery coverage.

**Remaining acceptance gaps:** explicit missing/duplicate edge coverage is being added in PR #247; the full dependency matrix must pass managed emulator acceptance before step 10 is marked complete.

### PR #119 — project fairness

The unique change is small: choose the project with fewer currently running Core tasks, then rotate by oldest last dispatch, preserving priority/FIFO within the fairness tier. The current `GlafiraTaskStore.kt` claim path and `GlafiraTaskQueueRecoveryInstrumentedTest.kt` project-fairness regression are the mainline implementation. PR #119 is therefore not a merge candidate.

### PR #122 — durable model-planned DAG and Bridge admission

**Durable task-plan features already present on main:**

- `GlafiraTaskPlan.kt`, `GlafiraTaskPlanOrchestrator.kt`, `GlafiraCoreTaskPlanAdapter.kt`, `GlafiraJsonTaskPlanDecoder.kt`;
- `GlafiraAiProviderTaskPlanDecomposer.kt` and `GlafiraAiLocalTaskPort.kt`;
- `GlafiraDesktopDispatchPlanner.kt`;
- task-plan tables, atomic enqueue, project-scoped status/journal and tests in `GlafiraTaskStore.kt` and the task-plan test suites.

**Bridge delta not ported verbatim:**

PR #122 adds `GlafiraBridgeAdmissionService` and changes the Android `GlafiraBridgeOperationDispatcher` to handle `DESKTOP_TASK_CLAIM` / `DESKTOP_TASK_RESULT` by calling Android Core's `claimDesktopTask` and `updateDesktopTask`. Current main deliberately leaves those Android-dispatch operations unavailable. The old PR path assumes Desktop workers call an Android-hosted Core endpoint; it predates the accepted two-peer-Core/Windows-Hub ownership contract and is not the current architecture.

Current main does contain the security primitives (`GlafiraBridgeCoreGate`, `GlafiraBridgeReplayGuard`, `GlafiraBridgeAdmissionPorts`, Android Keystore credentials) and a separate Windows Hub authenticator/replay guard. What is still missing is the **Desktop Core → Windows Hub task assignment and canonical result reconciliation** under the dual-Core ownership contract. Implement that on the Windows Hub/ Desktop Core boundary with owner generation and exact ACK; do not revive an Android-hosted Agent server or bypass the Hub.

The PR #122 exact-head run on 2026-11-20 validated its own historical head, not a merge against current main. The task-plan feature is already represented by newer mainline files/tests; the obsolete Android-server route remains intentionally unported.

## 3. Current source-of-truth file map

| Concern | Current mainline source |
|---|---|
| Core task states, queue and transactional persistence | `GlafiraTask.kt`, `GlafiraTaskQueue.kt`, `GlafiraTaskStore.kt` |
| Bounded concurrency and maintenance | `GlafiraCoreExecutionLimiter.kt`, `GlafiraCoreMaintenanceGate.kt`, `GlafiraCoreTaskExecutor.kt`, `GlafiraCoreDrainWorker.kt` |
| Dependency DAG and fairness | `GlafiraTaskDependencyPolicy.kt`, `GlafiraTaskStore.kt`, `GlafiraTaskStoreDependencyInstrumentedTest.kt` |
| AI decomposition and durable plans | `GlafiraTaskPlanner.kt`, `GlafiraAiProviderTaskPlanDecomposer.kt`, `GlafiraCoreTaskPlanAdapter.kt`, `GlafiraTaskPlanOrchestrator.kt`, `GlafiraTaskPlan.kt` |
| Android Bridge policy primitives | `GlafiraBridgeCoreGate.kt`, `GlafiraBridgeAdmissionPorts.kt`, `GlafiraBridgeReplayGuard.kt`, `AndroidKeystoreBridgeCredentialStore.kt` |
| Windows Agent fleet admission and transport | `BridgeV1RequestAuthenticator`, `SqliteHubReplayGuard`, `BridgeV1TaskOperationDispatcher`, `BridgeV1AgentTaskClient` in `windows-desktop` / `windows-agent` |
| Dual-Core ownership/handoff | Windows installation identity primitive merged in `GLAFIRA.Desktop.Core` (PR #322); canonical task owner generation, signed handoff, per-Core inbox/outbox and cross-Core reconciliation remain pending under `docs/contracts/DUAL_CORE_OWNERSHIP_AND_FAILOVER.md` |

## 4. Next Core/Bridge gates

1. Finish all supported SQLite upgrade tests (v1/v2 PR #245) and missing/duplicate dependency tests (PR #247).
2. Complete bounded worker-pool/backpressure/configurable concurrency and stale-worker fencing acceptance.
3. Finish failure injection across claim, handler, progress, result, ACK and provider outcome boundaries.
4. Extend the merged Android/Windows installation identity primitives with canonical task owner generation, durable per-Core inbox/outbox and signed handoff; do not enable takeover until adversarial fencing acceptance passes.
5. Implement Desktop Core → Windows Hub task assignment/payload mapping and exact canonical result reconciliation.
6. Add a real, typed, bounded Windows Agent handler only after the task type, payload, capability, result and owner-generation contracts are aligned.
7. Keep physical two-PC and cross-Core partition/failover tests as release gates.

No historical PR is to be merged merely to make its status green. All new work branches from current main and is validated on its exact head.
