# GLAFIRA execution ledger

This is the append-only execution journal required by `docs/GLAFIRA_EXECUTION_CONTROL.md`. It records reservations, code changes, builds, merges and evidence. The numbered acceptance plan remains in `docs/GLAFIRA_150_STEP_COMPLETION_PLAN.md`.

## Baseline checkpoint — 2026-11-24

- Repository: `micronizationlab/glafira-ai-hub`
- Main SHA observed before this governance change: `f2b5e4b087d4f2357e0354ec0cc56c228c0ed1ec`
- Control issue: #548
- Governance branch: `governance/program-150-single-source-of-truth`
- Prior plan file blob: `af9319fe6b0aa221fb33175407e40272aa16eb06`
- Prior plan header referenced `b34c831c024b9db6157f3dea7fdfa9d9b650d337`; this mismatch is an explicit reconciliation task.
- The plan contains 200 numbered entries: 1–150 are the original program; 151–200 are extensions. The last literal checkbox audit found 27 checked in 1–150 and 27/200 overall. This is historical until revalidated against current main; no automatic completion claims.
- PR #410: closed, not merged; frozen source-only. Never reopen or push to it.

## Work-item registry

| Work Item ID | Step | Owner | Scope | Status | Evidence / next action |
|---|---|---|---|---|---|
| GLAFIRA-GOV-W01 | Governance | GLAFIRA | Establish this control, update helper instructions and reconcile plan baseline | IN_PROGRESS | Branch `governance/program-150-single-source-of-truth`; PR to be opened after doc consistency check |
| GLAFIRA-S016-W01 | 16 | GLAFIRA | Core failure-injection matrix | BLOCKED | First refresh exact current-main tests/PRs; do not duplicate already merged failure-injection coverage |
| GLAFIRA-S017-W01 | 17 | GLAFIRA | Provider outcome classification and no blind replay | BLOCKED | Inspect current AiProviderClient/outbox and existing merged PRs before editing |
| GLAFIRA-S018-W01 | 18 | GLAFIRA | Durable result outbox + explicit ACK | BLOCKED | Compare Core, Desktop Bridge and Agent outboxes; one canonical contract per authority |
| GLAFIRA-S020-W01 | 20 | GLAFIRA | Persistent owner grants and approval binding | BLOCKED | Inspect current grants, owner session and approval stores |
| GLAFIRA-S028-W01 | 28 | GLAFIRA | Preserve TaskPlan instrumentation acceptance | BLOCKED | Inventory existing instrumented tests before adding any |
| GLAFIRA-S044-W01 | 44 | Owner + GLAFIRA | Private-LAN real-device pairing acceptance | OWNER_ACCEPTANCE | Requires actual owner devices; emulator is not equivalent |

## Active CI checkpoint — refresh before any retry

| PR / Work | Commit / Run | Last observed state | Rule |
|---|---|---|---|
| #41 Core encrypted memory | run `44347885661`, job `138442612684` | IN_PROGRESS | Wait and inspect logs; do not rerun while active |
| #98 HR | run `44647645046` | SUCCESS exact-head summary | Keep separate; green CI does not itself authorize merge |
| #510 iOS | run `45223882538`, job `138442753777` | QUEUED | Wait; do not enqueue duplicate |
| #511 iOS | run `45224291831`, job `138442773196` | QUEUED | Wait; dependency follows #510 |
| #512 iOS | run `45224734271`, job `138442773243` | QUEUED | Wait; dependency follows #511 |

These states are a checkpoint only. Always refresh from GitHub before acting.

## PR inventory snapshot

- #41: Core encrypted SQLite memory/Trace; Draft, mergeable false; run pending.
- #545: provider-context policy kernel; Draft; policy implementation is not yet proven wired into production provider dispatch.
- #547: Android GitHub read intents; Draft; verify parser/factory allowlist, owner approval, Dry Run, audit and result delivery.
- #500 and #520: divergent Bridge/Desktop histories; reconcile unique commits before any integration.
- #510 → #511 → #512: ordered iOS dependency chain; native checks queued at checkpoint.
- #183: non-issuing device economics calculator; separate from wallet/issuance.
- #470: token business documentation; not implementation authorization.
- #98: HR project; separate from Core foundation.

## Build registry rules

Record each build as `repository|commit SHA|workflow|job/lane|configuration`. Reuse an existing run for the same identity. No parallel release/build jobs. A retry must cite the failed run, log diagnosis and reason. If source changes, use the new SHA and record it as a new build identity. Record artifact digest/checksum where applicable.

## Append-only execution events

### 2026-11-24 — GLAFIRA-GOV-W01
- Confirmed main SHA `f2b5e4b087d4f2357e0354ec0cc56c228c0ed1ec`.
- Confirmed stale plan baseline `b34c831c024b9db6157f3dea7fdfa9d9b650d337`.
- Confirmed plan has 200 numbered checklist entries; 27 checked at last literal audit. The original 150 denominator remains separate from extension items 151–200.
- Created control issue #548 and governance branch from the observed main SHA.
- No application source code, runtime behavior, APK or installer changed in this governance task.

### 2026-11-25 — GLAFIRA-S016-W02
- Scope: one bounded Core recovery regression for the uncertain external-write/result-commit boundary; no production behavior changed.
- Integrated PR #550 (squash merge) as main commit `1aa7a1adab9296c1845d3fcc9268d5a886e59e9e`; source PR head `c749880303232ca7f48f0c6e9cf237fd246ea2b6`.
- Exact-head validation: CI run `45398172987` success; Project Dispatcher run `45399931427` success. Managed emulator report: 131 tests, 0 failures, 0 errors, 0 skipped; the injected result-commit failure test passed.
- Verified behavior: after simulated external write and failed encrypted result persistence, Core moves the task to WAITING_FOR_OWNER; reopening the database does not requeue or replay the operation.
- This is one Step 16 acceptance slice only. It does not complete Step 16, does not implement Step 18 durable outbox/ACK, and does not claim post-merge validation. No further build was started because no workflow run was returned for the merge SHA.
- Next: continue with a separate bounded Core work item; do not repeat this failure-injection case. Preserve #550 as merged evidence.
### 2026-11-25 — GLAFIRA-S017-W02
- Scope: clean-Main adapter slice preserving typed provider dispatch outcomes through local distributed-task and planner adapters.
- Integrated PR #572 as Main commit `f0ecbccddd3f79de53c30ff4d3b96504dff88c62`; exact PR head `6bec35084fb544cc6db8634c962b2954936194e9`.
- Exact-head CI run `45534865139`: Android JVM, shared contracts, Debug APK and managed emulator passed; Windows Agent/Desktop, iOS and workflow lint were skipped by change-aware routing. Project Dispatcher run `45534865152` passed.
- Provider `NOT_SENT`, `REJECTED` and `UNKNOWN` outcomes, error category and Retry-After now survive the two task adapters. No automatic retry or fallback was introduced.
- This is one Step 17 adapter slice only. Provider coordinator checkpoints, durable reconciliation and full Step 17 acceptance remain open. Older PR #552 remains unmerged and is not the continuation branch.
### 2026-11-25 — GLAFIRA-S018-W01
- Scope: Core durable outbox schema foundation; no event sender, receiver inbox, retry worker or cross-Core replay is enabled.
- Draft implementation PR #575 on `core/step18-outbox-schema-v12-current-main`; exact latest code commit `ece7f198e6bc3325e6f32473fc9c03f298067993`.
- Schema v12 stores source/recipient/project, monotonic source sequence, correlation/causation, owner generation/state version, idempotency key, payload digest/ciphertext, source key epoch, protocol version and exact ACK binding fields.
- Project deletion removes project-scoped outbox rows in the existing transaction; portable Core snapshots exclude the outbox as non-portable authority.
- Added fresh-database schema assertions and a v11→v12 migration test that simulates a v11 database by removing the new table and lowering `user_version` while preserving the existing Core tables.
- Exact-head CI for commit `ece7f198e6bc3325e6f32473fc9c03f298067993`: Project Dispatcher run `45536961095` succeeded; shared-contract conformance succeeded; Android JVM run `45536961073` was still running at this checkpoint. Android emulator acceptance and APK packaging were not reported for this latest head yet.
- Merge gate: wait for all applicable exact-head jobs, review the migration and portability tests, then integrate only if the head remains unchanged. No completion or merge is claimed here.

### 2026-11-25 — GLAFIRA-GOV-W02
- Audited open workstream PR ancestry against Main `f6aa2ce498cf5a05c7bd8d03eaff271cbdb6435d`.
- Trusted-peer chain #563→#564→#565, backup #559/#567, Bridge #560, CI #555, Android selector #568, provider policy #545, GitHub read #547, Token #183 and Core-1B #41 are still open; several are based on older Main SHAs and remain draft/stacked. Do not treat `mergeable=true` as proof of current-head CI or safe integration.
- #568 exact-head run `45506983690` failed Android emulator acceptance; failure remains the project-selector accessibility clickability assertion. Do not merge until the semantics node exposes and proves the click action and the full emulator suite passes.
- #550 and #572 are confirmed merged as `1aa7a1adab9296c1845d3fcc9268d5a886e59e9e` and `f0ecbccddd3f79de53c30ff4d3b96504dff88c62`, respectively. #410 remains frozen and untouched.
- Work rule: before each integration, compare the exact head with current Main, run applicable checks on that exact SHA, review the actual diff and record the resulting Main SHA plus the validation evidence. A passing Dispatcher alone is not a merge gate.
### 2026-11-25 — GLAFIRA-GOV-W03
- Main before this checkpoint: 119094c3368c2fb9cc8db7c5ef33b8462b5672fd.
- Integrated PR #578 (trusted-peer identity types and no-backup SQLite registry) as squash commit 52e4c8a608961da6fbeda0784b434bb3f39cd2d9. Exact-head CI run 45538629550 passed Android JVM, shared contracts, Debug APK and managed emulator. Project Dispatcher run 45538629555 failed only in the report-building job with a GitHub GraphQL service error; this was not a source-code test failure.
- Trusted-peer scope remains schema-only: no biometric enrollment, owner-confirmed out-of-band fingerprint ceremony, rotation/revocation operations or cross-Core dispatch are implemented.
- Closed stale PR #552 after comparing all four changed source/test files against Main: each file's blob SHA was identical to Main following PR #572. No unique code was discarded; broader Step 17 acceptance remains open.
- PR #577 Outbox schema: exact head 092a9990ef10e78b1b0f623debebe0c469e7ace9 passed JVM, APK and shared contracts but failed four existing migration assertions because tests still expected user_version 11 after schema v12. Corrective test-only commit d31868629780782d4fd11b5b616108814779b106 updates the four assertions to v12 without removing coverage. Exact-head CI 45540706220 is running; Dispatcher 45540706272 passed. PR remains unmerged pending emulator and clean-current-Main validation.
- PR #568 project selector: exact head 7f9c69e5ee19d267d99d7ac65e62f24fe254ff18 failed the real UiAutomator assertion 'Project selector is not clickable'. Corrective commit 9152a5999cfd054aa36cf6bce5acfe241c99e9a7 adds an explicit Compose semantics onClick action while retaining touch handling. Exact-head CI 45540786290 is running; Dispatcher 45540786287 passed. PR remains stacked on oversized/stale #558 and is not eligible for merge; port only the minimal selector slice to a clean Main branch after acceptance passes.
- Eleven-workstream checkpoint: (1) Identity — trusted-peer schema now in Main; owner confirmation/enrollment still open. (2) Core Runtime — continue runtime-owner and lifecycle hardening. (3) SQLite Storage — outbox v12 schema awaiting exact-head emulator validation. (4) Task Queue — typed provider outcomes adapter slice merged in #572; durable provider reconciliation remains open. (5) Dispatcher — exact-head routing works on current slices; stale CI fallback PRs #555/#556 still require reconciliation. (6) Outbox/ACK — schema only; atomic enqueue, sender, receiver inbox and signature/ACK verification remain unimplemented. (7) Recovery — #550 failure-injection slice merged; broader recovery matrix remains open. (8) Backup/Restore — #559 production-identity test and #567 key-loss/maintenance-gate issues remain blockers. (9) Memory — #41 remains open; prior CI failure requires a fresh clean-Main review. (10) Trace/Audit — #545 policy kernel tests pass but production provider-dispatch enforcement is not proven. (11) Reviewer/Owner Approval/Dry Run/API Gateway — #547 read-only GitHub path still lacks end-to-end proof that unapproved or expired approvals cannot dispatch.
- #410 remains frozen and untouched. No pending PR is counted as complete merely because its Dispatcher or partial CI lanes passed.

### 2026-11-25 — GLAFIRA-GOV-W04 (live 11-direction reconciliation)
- Main baseline: `bd70fb3f4a413f5bf7b3088f361600aa7a73f44c`. This is a status/evidence checkpoint only; no Program 150 item is marked DONE by this entry. PR #578 trusted-peer schema is already merged in Main as `52e4c8a608961da6fbeda0784b434bb3f39cd2d9`; exact-head CI `45538629550` passed Android JVM, shared contracts, Debug APK and emulator. Follow-up Project Dispatcher `45540543711` succeeded; earlier GraphQL-only dispatcher failure `45538629555` is not treated as a code failure.
- 1 Identity: trusted-peer registry/schema is in Main; owner-visible fingerprint ceremony, biometric-gated enrollment, session-to-peer/key-epoch binding and operational revoke/rotate remain open in stale stacked #564/#565. #564 CI `45457700420` passed, but its base is the superseded schema branch; its earlier dispatcher was cancelled. #565 CI/dispatcher `45457711073`/`45457711072` passed on its own stacked head; neither PR is merge-ready.
- 2 Core Runtime: continue Core lifecycle/owner-generation and safe external-effect fencing. Do not infer completion from schema PRs; no new runtime implementation merged in this checkpoint.
- 3 SQLite Storage: Outbox v12 PR #577 head `d31868629780782d4fd11b5b616108814779b106` adds the durable sequence-watermark table and schema migration; JVM, shared contracts and APK passed in CI `45540706220`; emulator job `138958984534` remained IN_PROGRESS at capture. Dispatcher `45540706272` passed. The PR is based on pre-#578 Main and has 203 changed lines (196 additions + 7 deletions), above the normal 200-line ceiling; port to current Main and split/reduce before merge. No Outbox schema merge yet.
- 4 Task Queue: PR #572 adapter slice is already in Main (commit `f0ecbccddd3f79de53c30ff4d3b96504dff88c62`); typed provider outcomes are preserved, but durable provider reconciliation and end-to-end idempotency remain open. #552 was closed as duplicate after its source files matched Main.
- 5 Dispatcher: #555 exact-head CI `45461891873` failed only at the summary because iOS was cancelled and normalized as `abandoned`; Android JVM, APK, emulator, contracts, workflow lint, Windows Agent and Desktop jobs passed. Do not rerun until the cancellation/reporting cause is addressed; no duplicate build was launched.
- 6 Outbox/ACK: only schema groundwork exists in #577. Atomic idempotent enqueue/sequence allocation, sender, receiver inbox, signature verification, durable ACK/retry and restart recovery are not implemented. No cross-Core delivery is enabled.
- 7 Recovery: #550 failure-injection slice is in Main as `1aa7a1adab9296c1845d3fcc9268d5a886e59e9e`; broader crash/recovery matrix remains open. #41's latest recorded Android build run `44347885661` failed because emulator `emulator-5554` was not found and boot timed out; this is an environment failure, not evidence of a source regression. #41 is also stale and oversized (11 files, 1,467 additions), so it must be decomposed/reconciled, not merged as-is.
- 8 Backup/Restore: #559 and #567 exact-head CI/Dispatcher both passed (`45446494649`/`45446494484`; `45470963928`/`45470964043`), but review blockers remain: #559 must test the actual production Core identity accessor/Keystore loss path; #567 must fail closed when rollback key is missing and coordinate staging cleanup under the maintenance gate. Both branches are based on old Main; no merge authorized.
- 9 Memory: encrypted SQLite memory/Trace PR #41 remains open; prior CI failure was emulator boot timeout, but size/base and required production integration/security review still block it. No step completion claimed.
- 10 Trace/Audit: provider policy PR #545 exact-head CI/Dispatcher `45288245566`/`45288245554` passed, but the policy function is not yet proven on the production provider-dispatch path; require a restricted canary dispatch test proving no sensitive context reaches outbound requests or logs. GitHub read PR #547 CI/Dispatcher `45288911815`/`45288911775` passed, but owner approval/Dry Run expiry/revocation gating is not yet proven end-to-end.
- 11 Reviewer/Owner Approval/Dry Run/API Gateway + helper intake: inspect every helper PR before new implementation; reuse the evidence above and do not duplicate existing comments. PR #560 CI/Dispatcher `45446833956`/`45446833768` passed, but receiver-side durable idempotency for a repeated logical operation under a new RequestId remains unproven. Android selector #568 head `9152a5999cfd054aa36cf6bce5acfe241c99e9a7` passed JVM/contracts/APK in run `45540786290`; emulator job `138959207931` remained IN_PROGRESS. Parent #558 has 505 additions/84 deletions and its prior emulator run `45461014026` failed exactly: `Project selector is not clickable`; keep the parent out of Main and port only the minimal selector slice after the child emulator passes. Token #183 is calculator-only, 286 additions (above policy) and based on stale Main; no issuance/billing.
- CI and branch policy: no skipped/cancelled lane is counted as a pass. PR #580 was superseded by the live Main ledger checkpoint and must not be merged as a duplicate; use this entry as the current append-only status snapshot. #410 remains frozen and untouched.
- Next actions: wait for #577/#568 emulator jobs and inspect their logs; then port Outbox schema to fresh Main and keep the diff within policy; retarget trusted-peer enrollment only after adding an actual owner-visible fingerprint confirmation/session-binding contract; continue backup and Bridge blockers without reopening old branches. Keep Core/Backup/Bridge ahead of Token and specialist-agent implementation.
