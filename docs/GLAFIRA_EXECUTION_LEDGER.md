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


### 2026-11-25 — GLAFIRA-GOV-W05 (11-workstream live checkpoint)
- Verified current Main SHA `f3d947ef5bde088d32931c7a614e6258121a9e88` by comparing it with the PR #583 base; refs are identical at checkpoint creation.
- Core Outbox PR #583 is draft, 5 files, 192 additions / 7 deletions, head `915c74400e02405d5b18e9fc025dd1f09daef18e`. Exact-head CI run `45541927538`: Android JVM, shared contracts, Debug APK and managed emulator succeeded; iOS, Windows Desktop, Windows Agent and workflow lint were skipped by change-aware routing. Project Dispatcher `45541927597` succeeded. Skipped platform lanes are not passes and are not required for this Android-only schema slice unless dependency review identifies otherwise.
- Review blocker: migration test `versionElevenDatabaseMigratesOutboxWithoutLosingExistingCoreTables` drops `core_outbox` but leaves `core_outbox_sequences` before setting user_version=11. This does not test creation of the sequence table on v11→v12. Owner recorded the intended correction in PR comment `7152249586`; do not edit the same files concurrently. Require corrected exact-head CI and a fresh diff review before merge.
- PR #567 Backup: draft, 214 changed lines; security review blocker remains: decrypt path must fail on a missing Keystore alias without creating a replacement key. No merge.
- PR #560 Bridge: draft; client logical-operation identity only. Receiver-side durable idempotency/ACK remains unproven. No merge.
- PR #568 Android selector: draft and stacked on oversized #558; exact-head emulator acceptance must prove clickability and stale parent must be decomposed/ported cleanly. No merge.
- PR #545 provider context policy: draft; policy kernel exists, but production provider-dispatch enforcement is not proven. No merge.
- PR #569 helper repair-cycle documentation: draft; documentation alone is not evidence that helpers executed the autonomous defect-repair loop. Verify on a real bounded defect before counting helper integration.
- 11-stream disposition: (1) Core—Outbox migration test correction is the immediate owner-held action; (2) Backup—missing-key fail-closed correction; (3) Bridge—receiver-side idempotency and ACK; (4) Desktop—inspect current Hub/lease branch overlap before reserving files; (5) Android—selector acceptance and project-picker regression; (6) iOS—no iOS lane was required for this schema-only PR and no new iOS acceptance evidence recorded; (7) CI/security—exact-head jobs inspected; skipped lanes remain skipped; (8) Windows Agent—no new implementation/acceptance evidence; (9) Token—calculator/research only, no issuance; (10) Multi-model—provider policy kernel is not yet wired through all production dispatch paths; (11) Helper intake—review existing helper output before assigning overlapping implementation.
- No Program 150 step marked DONE. No PR merged in this checkpoint. #410 remains frozen and untouched. Next action: wait for owner’s PR #583 test correction, validate the new exact head, then merge only after required review and all applicable checks; record resulting Main SHA and post-merge validation in a follow-up ledger entry.


### 2026-11-25 — GLAFIRA-GOV-W06 (duplicate and stale-branch reconciliation)
- Reconfirmed Main remains `f3d947ef5bde088d32931c7a614e6258121a9e88`; latest repository commit search returned this as newest commit. PR #584 remains open Draft, one ledger file, head `fe7f495e9ddf81b9fc6aadd399de5802e6621c38`; no exact-head workflow runs, no reviews, and no merge. Do not report it as integrated.
- Found duplicate Outbox schema PR #581 on older base `bd70fb3f4a413f5bf7b3088f361600aa7a73f44c`. Compared all five implementation files against #583: four blobs identical; migration test differed only in formatting/blank lines and retained the same defect (drops `core_outbox` but not `core_outbox_sequences`). Main was one commit ahead of #581 base. Closed #581 as superseded with no unique implementation discarded; #583 remains the sole candidate.
- #583 remains on current base `f3d947ef5bde088d32931c7a614e6258121a9e88`, head `915c74400e02405d5b18e9fc025dd1f09daef18e`; exact-head CI `45541927538` and Dispatcher `45541927597` passed their reported lanes, but migration-test defect remains. Owner's stated correction has not appeared in the fetched head; do not merge.
- Fresh ancestry audit: #567 Backup is 12 commits behind Main and diverged (9 commits ahead); #560 Bridge is 12 behind (4 ahead); #545 provider policy is 18 behind (2 ahead); #569 helper cycle is 12 behind (4 ahead). These branches require clean-Main port/reconciliation before merge; do not merge stale ancestry or treat old CI as current evidence.
- Eleven workstreams: Core—#583 only candidate, blocked on corrected migration test; Backup—#567 missing-key strict lookup and scope limit remain open; Bridge—#560 client logical ID only, receiver idempotency/ACK absent; Desktop—rebase/overlap audit required before any branch integration; Android—#568 is stacked on oversized #558 and corrected head lacks acceptance proof; iOS—no fresh acceptance evidence; CI/security—#583 exact-head lanes inspected and skipped lanes remain non-passes; Windows Agent—no fresh implementation/acceptance evidence; Token—calculator/research only, no issuance; Multi-model—#545 policy primitives are not production dispatch enforcement; Helpers—#569 documentation is not a demonstrated repair run and is stale-base.
- No source PR merged in this checkpoint; no Program 150 step marked DONE. PR #410 remains frozen. Next: owner correction to #583, then exact-head validation; separately clean-port Backup strict missing-key behavior only after current Main/file ownership is reconciled. PR #584 itself remains unmerged pending its own review and validation.


### 2026-11-25 — GLAFIRA-ANDROID-W07 (project selector failure diagnosis and clean-Main extraction)
- Owner reprioritized Android project switching as the immediate top task. Diagnostic policy clarified: do not stop the whole validation matrix at the first independent failure; finish all safe independent jobs, collect full logs/JUnit/artifacts, then create a consolidated repair list. Abort only the unsafe/invalidated stage and continue independent checks. A test failure must never be converted to a pass or hidden by skipped assertions.
- PR #568 exact head `9622a75039eddf15248748096ac062adcef5fc62`; CI run `45541922674`, Dispatcher `45541922663`. Android JVM, shared contracts, Debug APK passed; managed emulator failed; iOS/Desktop/Agent/workflow lint skipped. Full emulator suite completed **133 tests: 132 passed, 1 failed, 0 skipped**. Previous clickability assertion passed; next failure is `Built-in VodorodAktiv project is missing from the picker` in `MainActivitySmokeTest.chatWorkspaceExposesProjectAgentModuleAndActionSelectors` line 77. Artifact `14990320760` (`android-emulator-diagnostics-45541922674`) contains JUnit XML/HTML and logcat. Dispatcher failure was a GitHub GraphQL API error (`C040:21496F:1F153D2:61DAE13:6B06595F`) during PR-list query, not an Android compile/test failure.
- Root-cause direction: #568 is stacked on oversized #558 and tests a mixed parent feature set. To isolate the requested user-facing behavior, created clean-Main branch `fix/android-project-selector-clean-main-20261125` from Main `f3d947ef5bde088d32931c7a614e6258121a9e88`. PR #585 adds only the chat project selector, refreshes projects from durable storage when opened, and adds a focused emulator test switching to VodorodAktiv and Token. Exact head `539a2d1497ba5eff861a919bf74a956bb4613114`; two files, 114 additions, zero deletions. At ledger update time no exact-head workflow runs had appeared; no pass or merge claimed.
- Added diagnostic/split comments to #568 and #558. #558 remains unmerged; do not merge its 505-line aggregate. #568 remains Draft until #585's clean-Main acceptance result is known. Remaining #558 scope to split into independent project-store/canonical-name (only if still needed after Main audit), agent/module selectors, chat actions/settings, and independent BackupSettings smoke-test slices. No changes to Program 150 completion count.
- Other workstreams retain prior blockers from GLAFIRA-GOV-W06; no unrelated merges attempted in this focused Android cycle. #410 remains frozen.


### 2026-11-25 — GLAFIRA-ANDROID-W08 / GLAFIRA-CI-W09 (home selector and failure-continuation repair)
- Clarified the immediate Android defect from owner report: the main ContactList project dropdown rendered non-selected project labels with `Bg` (near-white) on a light popup, explaining the visible blank rows with red delete crosses. The prior #568 failure is separately recorded: exact head `9622a75039eddf15248748096ac062adcef5fc62`, run `45541922674`, full emulator suite 133 tests, 132 passed / 1 failed / 0 skipped; selector clickability passed, then the ChatScreen picker did not expose VodorodAktiv. Artifact `14990320760` preserves full XML/HTML/logcat.
- Reworked clean-Main PR #585 to target the existing **home-screen** selector: dark foreground for unselected project labels, merged accessibility description/click action, refresh durable project list when opening. Added focused emulator test to open home selector, verify VodorodAktiv and Token labels, and switch both active projects. Current head `a7782fec492576b448fd4af84aa8443e682fb7e2`, two files, 83 additions / 2 deletions, clean Main base `f3d947ef5bde088d32931c7a614e6258121a9e88`. Dispatcher `45544448246` SUCCESS; CI `45544448016`: JVM SUCCESS, shared contracts SUCCESS, APK and managed emulator IN_PROGRESS at checkpoint; other platforms skipped by change-aware selection. No acceptance or merge claimed yet.
- Inspected `.github/workflows/android.yml`: APK and emulator jobs previously depended on `android-tests.result == 'success'`; JVM/emulator JUnit verification steps used `if: success()`. Thus JVM failure could skip independent APK/emulator diagnostics and post-failure JUnit verification. Created clean-Main PR #586 to remove the JVM-success dependency from independent APK/emulator jobs, run JUnit verification with `if: always()`, preserve aggregate failure in exact-head summary, and add host regression tests for this orchestration policy. Head `4beaef9343547db42b5e407ab628fee45cd342c7`, two files, 62 additions / 6 deletions. Dispatcher `45544795334` SUCCESS; exact-head CI `45544795617` was running all selected jobs including Android JVM, Android emulator, APK, workflow lint, iOS, Windows Agent and Desktop at checkpoint; no results claimed yet.
- Failure handling policy: instrumentation test runner should execute the full suite and report all independent test failures; artifacts must upload with `always()`. Independent platform jobs must continue after a different job fails; final summary must remain failed if any required job fails. A test failure is not a reason to skip independent diagnostics. Abort only the unsafe/invalidated stage and record why.
- #558 remains an oversized aggregate and #568 remains stacked/failed; neither is mergeable evidence for the home selector. Keep them unmerged until their unique remaining features are decomposed or explicitly superseded. #585 is the clean Main implementation candidate. No Program 150 step marked DONE; no source changes merged in this cycle. Other workstreams retain W06 blockers; continue Core/Backup/Bridge/Desktop after the prioritized selector and CI repair are validated. #410 remains frozen.


### 2026-11-25 — GLAFIRA-GOV-W06 (11-workstream execution and merge checkpoint)
- Main before this checkpoint: `6b2992d57ce7b3b179fef36d01824bf0a749ad20`, created by squash-merging CI continuation PR #587. #587 exact-head run `45545605485` passed all selected jobs: Android JVM, Android emulator (132 tests, 0 failed/0 skipped), APK, workflow lint, iOS, Windows Agent, Windows Desktop, shared contracts, and exact-head summary. Main post-merge run `45546532800` is separately in progress at this checkpoint: all jobs except managed Android emulator have completed successfully; do not call post-merge run complete until emulator and summary finish.
- Workstream 1 Core: identified and fixed the v11→v12 migration test gap in the Outbox schema slice. The test now drops both `core_outbox` and `core_outbox_sequences` before setting `user_version=11`. Clean-current-Main PR #590, head `4a20fb16c8c81831d5c35f780ede6755a3e9685a`, 5 files / 193 additions / 7 deletions. Android JVM, shared contracts, APK passed; managed emulator still in progress (run `45546554698`). Old #583/#588 superseded and closed. No merge yet.
- Workstream 2 Backup & Recovery: isolated the missing-Keystore-key security defect from oversized #567 into bounded PR #591, head `d0731ea3efb05bf20922ecad7063e561175fee9b`, 2 files / 167 additions. Decrypt now requires the existing alias and throws `ROLLBACK_KEY_MISSING`; test asserts alias is not recreated and no plaintext destination survives. JVM and managed emulator still in progress (run `45546742952`). Remaining separate gates: coordinator cleanup race outside maintenance gate, atomic ciphertext publication, process-death/disk-full, WAL/SHM, legacy journal compatibility.
- Workstream 3 Bridge: #560 remains unmerged. Its Desktop client slice carries stable logical operation ID across new signed transport envelopes, but receiver-side durable claim/ACK integration is not proven. Search shows `GlafiraBridgeReplayGuard` is declared but no production usage reference was found; do not equate client identity with end-to-end idempotency.
- Workstream 4 Windows Desktop/Hub: post-merge run `45546532800` Windows Desktop tests/build completed successfully. Full Desktop transport/lifecycle/two-PC acceptance remains open.
- Workstream 5 Android: home-screen selector root cause was low-contrast `Bg` foreground plus inaccessible semantics node. The corrected prior head passed 133/133 emulator tests (0 failed, 0 errors, 0 skipped) in run `45545447335`. Clean-current-Main PR #589, head `066f16d6f26598f0a748cee82d1aa863a7485e3`, 2 files / 92 additions / 2 deletions; JVM/contracts/APK passed, exact-head emulator still running (run `45546429599`). Old #585 superseded and closed. #558/#568 remain unmerged; chat picker is a distinct control from the home selector.
- Workstream 6 iOS: #587 exact-head iOS Swift package tests passed; post-merge Main run `45546532800` iOS tests/build passed. Native peer/device acceptance remains separate.
- Workstream 7 CI/CD/security: #587 merged as `6b2992d57ce7b3b179fef36d01824bf0a749ad20`. APK and emulator jobs no longer depend on JVM success; JUnit report verification runs with `if: always()`; exact-head summary preserves failures. Post-merge run is still waiting for managed emulator completion.
- Workstream 8 Windows Agent: #587 exact-head and post-merge Main Windows Agent tests/build passed. Two-PC/fleet acceptance remains open.
- Workstream 9 Token/economics: no issuance/payment implementation; retain calculator/docs-only scope pending owner approval.
- Workstream 10 Multi-model/provider policy: extracted the isolated fail-closed provider context policy kernel and tests into clean-Main PR #592 (2 files / 198 additions). This kernel is not yet wired into production provider dispatch; no outbound-canary proof and no completion claim.
- Workstream 11 Helper intake/integration: #569 remains documentation-only and does not prove a helper completed a bounded autonomous defect-repair cycle. Require an actual isolated defect, helper patch, exact-head validation and review before claiming success.
- CI continuation principle: independent selected jobs continue after another job fails; JUnit validation and artifact upload must run after failure; a failed required job keeps the aggregate failed. Skipped is never success. Full emulator logs/JUnit/artifacts are retained before writing the repair list.
- Superseded PRs #583, #585, #588 were closed after clean-current-Main ports #590 and #589 were opened. PR #586 remains superseded by merged #587. #567 remains blocked for non-key lifecycle issues; #560, #545 and #569 remain open/draft pending their stated acceptance. #410 remains frozen and untouched.
- No numbered 150-step item is marked DONE in this checkpoint. All merge claims are tied to actual merge SHA; outstanding PRs above are not merged.

## Reconciled live checkpoint — 2026-11-25 05:06 UTC

This section supersedes earlier *current-state* statements above where they conflict with this checkpoint. Earlier entries remain unchanged as historical evidence.

- Verified current `main`: `a800d3553a51f4ef89763a67cedd1fbfb5f40081`.
- Verified PR #595 is merged (2026-11-25T04:38:51Z), merge commit equals current Main `a800d3553a51f4ef89763a67cedd1fbfb5f40081`. Outbox schema v12 and v11→v12 migration coverage are therefore in Main. This is schema groundwork only; no sender, receiver inbox, durable ACK/retry worker or cross-Core delivery is claimed.
- PR #594 is closed unmerged as superseded by clean-current-Main PR #601. Its change was re-ported, not discarded.
- PR #600 (CI exact-head report and required-check context names): head `b2f4b410db0110feed40f1a99423c581827b1a59`, base current Main. Latest observed CI run `45550520308`: Android JVM, APK, iOS, Windows Desktop, Windows Agent, workflow lint and shared contracts succeeded; managed Android emulator acceptance remained IN_PROGRESS. Exact-head summary is downstream of all lanes and had not yet completed. Do not merge until emulator and exact-head summary complete successfully.
- PR #601 (Android home project selector): clean base current Main `a800d3553a51f4ef89763a67cedd1fbfb5f40081`, head `55557e6b93871cda25f1d2d5e40ae9649b383cc3`, exactly two files changed. CI run `45550825942`: Android JVM, APK and managed-emulator lanes were IN_PROGRESS at last capture; unrelated iOS/Windows/workflow lanes were skipped by change-aware routing. It still needs fresh validation against the corrected required-check names after #600 is integrated. No merge claimed.
- Older-base PRs #596 (Backup), #597 (provider-context security), #598 (canonical project IDs) and #599 (Bridge logical operation identity) still target superseded base `72d063046faeb31778500d2fb595211608cf9587`; each remains open and blocked. Re-port one bounded change at a time onto current Main, compare actual diffs, and rerun exact-head CI; do not change base metadata alone and call it a rebase.
- The active ruleset `Protect main` requires these exact status contexts: `Android JVM tests`, `Shared contracts`, `SharedAndroid emulator acceptance contracts`, `Android debug APK contracts`, `Windows Desktop`, `Windows Agent`, `iOS`, `Workflow lint`, and `Exact-head CI summary`. A skipped or missing required context is not a pass. Repository auto-merge is disabled. No ruleset bypass was used.

### Eleven workstream status at this checkpoint

1. **Core / owner lifecycle:** continue runtime owner-generation fencing and safe external-effect boundaries; schema work alone is not runtime completion.
2. **Backup & recovery:** #596 is a small missing-rollback-key fail-closed slice, but its base is stale and exact-head validation/review is still required.
3. **Android project/workspace selector:** clean-current-Main PR #601 adds accessible selector semantics, refresh-on-open and emulator switching coverage for Personal, VodorodAktiv and Token. Not merged or yet validated at this checkpoint.
4. **Bridge:** #599 client logical-operation identity is only a client-side slice; durable server Inbox, authenticated receive, ACK and restart recovery remain open.
5. **Windows Desktop Hub:** CI lane passed on #600; no claim of full Core integration or end-to-end device handoff.
6. **Windows Agent:** CI lane passed on #600; background execution, durable handoff and restart recovery still need integration acceptance.
7. **iOS:** CI lane passed on #600; maintain shared contract compatibility, but keep below Core/Desktop priority.
8. **CI/CD:** #600 aligns seven job display names to the ruleset contexts and corrects the PR summary SHA to `pull_request.head.sha`; emulator and exact-head summary remain the merge gate.
9. **Provider security/context:** #597 adds a fail-closed policy kernel but production provider-dispatch wiring is not yet proven; stale base prevents integration.
10. **Token/GCU:** keep design and safety constraints recorded; no implementation priority ahead of Core, Bridge and Desktop.
11. **Helpers/agents:** validate useful real project work only after foundation; no agent work is allowed to displace Core/Backup/Bridge/Desktop.

### Journal discipline
- This checkpoint records observed repository evidence, not planned work as completed.
- A CI run is recorded against its exact commit SHA; cancelled, skipped, pending or missing required lanes are never reported as green.
- A PR is recorded as integrated only after GitHub reports `merged=true` and the resulting Main SHA is re-read.
- Next priority: complete #600's emulator and exact-head summary; then integrate it if every required context is successful. Next, validate #601 on the corrected ruleset contexts. In parallel, prepare a focused Core Outbox enqueue/sequence slice only after reviewing the current transaction and identity APIs.

## Follow-up reconciliation — 2026-11-25 05:08 UTC

- PR #600 was merged by squash after all nine exact required status contexts passed. Exact PR head: `b2f4b410db0110feed40f1a99423c581827b1a59`. Exact-head summary job `138992202050` in CI run `45550520308` succeeded and printed that same SHA with all nine component outcomes `success`. Merge commit / new Main: `5bfe84924981748dc3ee04874bd33621372523cb`.
- PR #601 is closed unmerged and superseded by #603. Its exact-head CI exposed a real compile error in both Android JVM and APK lanes: missing import `androidx.compose.ui.semantics.role` at `MainActivity.kt:393`. This was diagnosed from job logs, corrected, and re-ported on post-#600 Main. Do not count the earlier failed run as passing.
- PR #603 is the current Android selector candidate: clean base `5bfe84924981748dc3ee04874bd33621372523cb`, head `708b1026fc936fae40b79f1b1a46688e6fa84c2e`, with fresh exact-head CI runs `45551263461` and `45551263391` in progress at capture. Await all applicable Android JVM/APK/emulator/shared-contract/exact-head-summary contexts before merge.
- PR #602's docs-only branch diverged after #600 merged. This follow-up copies the append-only ledger update onto the new Main baseline; #602 is superseded by this clean-current-Main documentation PR.
- Main post-merge workflow run was still in progress at capture; the successful PR #600 exact-head validation remains the evidence for that merge. Recheck post-merge checks separately; do not conflate them with PR-head checks.

### Next queue after this checkpoint
1. Complete #603 CI, fix any compiler or emulator failures from logs, then merge only when required contexts are green.
2. Integrate this ledger reconciliation so the append-only journal remains synchronized.
3. Port #596 Backup and #597 provider policy individually onto current Main, then #599 Bridge and #598 project identity; each gets exact-head validation and its own bounded diff.
4. Resume Core Outbox with a reviewed atomic enqueue/sequence-allocation slice. Do not add a sender or cross-Core transport before identity binding, encryption, peer trust, signature verification, inbox idempotency, ACK binding and restart recovery have tests.

## Live checkpoint after selector and Core security-gate merges — 2026-11-25 05:14 UTC

- Current Main observed: `0727c8e2eebab9ece50067bd158635eb116c55ec`.
- PR #603 (home project selector) merged by squash as `0727c8e2eebab9ece50067bd158635eb116c55ec`. Exact PR head `708b1026fc936fae40b79f1b1a46688e6fa84c2e`. CI run `45551263461`: Android JVM, Debug APK, Shared contracts, managed emulator and exact-head summary all succeeded. Emulator JUnit: 135 test cases, 0 failures, 0 errors, 0 skipped. The new smoke test exercises opening the selector and switching Personal, VodorodAktiv and Token.
- PR #609 merged as `15c9a04f599e9763295f2843b892e3356eb50335`. The Dual-Core contract now explicitly separates signing identity from recipient payload encryption. Existing Android/Windows Core keys are signing keys; trusted-peer pins are signing-key metadata; local Android task AES is not a cross-Core encryption key. A separate owner-approved key-agreement key and authenticated envelope-encryption protocol are required before any remote payload delivery. No transport was enabled.
- PR #605 is the clean-current-Main port of #596's rollback snapshot cipher and missing-key fail-closed tests. Head `59cbd5233e0c067dacfe9df5280b9f9985c134f1`; Android JVM, APK and shared contracts succeeded; emulator acceptance was still IN_PROGRESS at capture. The slice is a primitive only; production Backup/Restore wiring remains open.
- PR #606 is the clean-current-Main port of #598's canonical built-in project ID repair. Head `a30e83b5132766c00d935a7ff023a66c9cec75d7`; Android JVM, APK and shared contracts succeeded; emulator acceptance was still IN_PROGRESS. The helper still needs to be wired into the actual project-create UI after selector integration; do not claim duplicate prevention in the UI until that is done.
- PR #607 is the clean-current-Main port of #597's fail-closed provider-context policy kernel. Head `65c174b2b09759f8b4520564f87dfb9b4d60db15`; Android JVM, APK and shared contracts succeeded; emulator acceptance was still IN_PROGRESS. It remains a pure kernel until production provider dispatch and durable owner grants/Trace are wired.
- PR #608 is the clean-current-Main port of #599's Bridge logical-operation identity. Head `b8c667a12d09f2818e08b2479aa443e6efed7733`; Android JVM, APK and shared contracts succeeded; emulator acceptance remained IN_PROGRESS at capture. It adds caller-controlled stable logical IDs, not automatic retries or server-side durable Inbox/ACK.
- Post-merge Main CI for #603, run `45552011537`, was still IN_PROGRESS at capture (Android JVM/APK/emulator); PR-head validation for #603 is complete and remains separately recorded above.
- PR #600 and #604 are merged as recorded in the prior checkpoint. PR #601 and #602 are closed as superseded. PR #594 is closed as superseded by #601/#603.
- Older PRs #596–#599 remain open until their clean-current-Main replacements (#605–#608) pass full applicable validation and are integrated. Do not merge stale-base duplicates.

### Eleven workstreams — live status

1. **Core identity/runtime:** Android/Windows signing identity exists, but no separate cross-Core payload-encryption key agreement is enrolled. Owner-generation and safe-effect fencing remain mandatory.
2. **Backup & recovery:** #605 has JVM/APK/shared-contract success; emulator and production backup/restore wiring remain open.
3. **Android project/workspaces:** selector UI merged in #603 with 135/135 emulator tests passing. Canonical project-ID repair is under #606; UI wiring is not yet complete.
4. **Bridge:** #608 client logical-operation identity under CI; server Inbox, durable receipt, authenticated ACK and restart recovery remain unimplemented.
5. **Windows Desktop:** #600's Windows Desktop CI lane passed; end-to-end Core/Bridge handoff remains open.
6. **Windows Agent:** #600's Windows Agent CI lane passed; production task-handler and durable Core reconciliation remain separate gates.
7. **iOS:** #600's iOS lane passed; shared contracts remain the compatibility gate.
8. **CI/CD:** #600 merged and aligns required check contexts; exact PR-head SHA summary is now validated.
9. **Provider security:** #607 policy kernel under CI; production dispatch enforcement and durable grants remain open.
10. **Token/GCU:** retained as a lower-priority design track; no implementation work ahead of Core/Backup/Bridge/Desktop.
11. **Helpers/agents:** retained below foundation work; use only after core platform and execution safety gates.

### Next execution order
1. Finish exact-head emulator and summary checks for #605–#608; inspect complete logs/JUnit before deciding.
2. Wire #606's `createOrSelectProject` into the production project-creation callback after checking it against #603's merged UI.
3. Integrate #605 into the actual rollback/restore path only after confirming maintenance-gate and staging-cleanup behavior.
4. Integrate #607 through the real provider dispatch path with durable owner grants and Trace-before-dispatch.
5. Implement Core Outbox in the correct order: separate peer key agreement and envelope encryption; atomic enqueue/sequence; receiver Inbox idempotency; signature/ACK verification; retry/recovery. No cross-Core send before these gates pass.
6. Reconcile stale PRs #596–#599 only after replacements are validated; close each duplicate with an explicit superseding link.

## Integration checkpoint — 2026-11-25 05:21 UTC

This checkpoint supersedes prior live-state descriptions above where they conflict; prior entries remain as historical snapshots.

- Current Main observed: `4ab42bd587719276f563fcc33d5b39b9a225b402`.
- PR #605 merged as `c9a27fc0bf9c0d9d98ebdc3caa2bafb3919682e7`. Exact-head Android JVM/APK/shared contracts/emulator and summary passed. Emulator JUnit: 136 test cases, 0 failures, 0 errors, 0 skipped. It adds an installation-local AES-GCM rollback snapshot primitive and missing-key fail-closed behavior; production Backup/Restore wiring is still not claimed.
- PR #607 merged as `ffd729f8febf3f299da2e6e09b5e622154f7a662`. Exact-head Android JVM/APK/shared contracts/emulator and summary passed. Emulator JUnit: 134 test cases, 0 failures, 0 errors, 0 skipped. It adds a pure provider-context policy kernel; production dispatch enforcement and durable grants/Trace remain open.
- PR #608 merged as `c44e73335e53e4dc0d8161fd5f9fd159364248db`. Exact-head Android JVM/APK/shared contracts/emulator and summary passed. Emulator JUnit: 134 test cases, 0 failures, 0 errors, 0 skipped. Android JVM reports included 54 `desktop-bridge` test cases and 257 app test cases (311 total, 0 failures/errors/skips). It adds client logical-operation identity only; no automatic retry or server Inbox/ACK is claimed.
- PR #606 merged as `4ab42bd587719276f563fcc33d5b39b9a225b402`. Exact-head Android JVM/APK/shared contracts/emulator and summary passed. Emulator JUnit: 139 test cases, 0 failures, 0 errors, 0 skipped. It repairs built-in project labels without changing stable IDs, prevents duplicate creation by canonical name, and wires the production project-create callback through `createOrSelectProject`.
- PR #596, #597, #598 and #599 are closed as superseded by merged #605, #607, #606 and #608 respectively. PR #594/#601/#602 are also closed as superseded. Do not reopen stale-base duplicates.
- PR #600 CI fix, #603 selector UI, #604/#610 ledger updates and #609 Core key-separation contract are merged as recorded in prior entries.
- The repository's post-merge push CI after #606 must still be checked independently; exact-head PR CI above is the evidence for the respective merges.

### Eleven workstreams — updated state

1. **Core / Outbox:** schema v12 is in Main; the signing-vs-encryption prerequisite is documented. No cross-Core payload sender/receiver is enabled. Next: separate owner-approved key-agreement identity and authenticated envelope encryption, then atomic enqueue/sequence, Inbox idempotency, signature/ACK verification and crash recovery.
2. **Backup & Recovery:** local rollback snapshot cipher now fails closed if its key is missing and removes partial decrypted output. Next: wire it into the actual restore staging/maintenance-gate path and test crash recovery; primitive alone is not full Backup completion.
3. **Android projects:** selector UI is merged and tested; canonical ID/name repair and duplicate prevention are merged and wired into project creation.
4. **Bridge:** client logical operation ID is merged and tested. Next: durable server Inbox keyed by sender+operation, authenticated receipt, idempotent append, signed ACK bound to event and owner generation, and restart recovery.
5. **Windows Desktop:** #600 Windows Desktop CI lane passed. End-to-end shared Core/Bridge handoff remains unverified.
6. **Windows Agent:** #600 Windows Agent CI lane passed. Production task execution, durable reconciliation and restart recovery remain open.
7. **iOS:** #600 iOS CI lane passed. Keep shared contracts aligned; no iOS-first expansion.
8. **CI/CD:** #600 corrected required status-context names and exact PR-head SHA summary; #600 merged after all nine required contexts passed. #603/#605/#606/#607/#608 exact-head summaries also verified their own PR head SHA.
9. **Provider security:** pure fail-closed context policy kernel merged. Next: durable owner grant storage, owner authentication/revocation, classification of actual context sources, Trace-before-dispatch, and enforcement in the real `AiProviderClient` send path.
10. **Token/GCU:** remain parked below Core/Backup/Bridge/Desktop.
11. **Helpers/agents:** remain below foundation work; no role expansion until safe task dispatch and recovery are implemented.

### Open integration risks (not marked DONE)
- Current Android/Windows installation identities expose signing keys; they do not provide a separate recipient encryption key. The Android task AES key is local-only. Cross-Core payload delivery must remain disabled until key agreement/envelope encryption is implemented.
- Outbox schema has no receiver Inbox implementation or runtime ACK verifier. A database row marked ACKNOWLEDGED must not be treated as cryptographic proof until sender/recipient identity, key epoch, event digest, owner generation and signature are verified in code.
- Provider policy is a pure kernel, not yet a dispatch guard. Never pass project/private context to providers based on a test-only grant or hardcoded approval.
- Backup cipher is a primitive; ensure actual rollback/restore path uses it and does not regenerate missing keys.

### Next work order
1. Verify the post-merge CI run for Main `4ab42bd587719276f563fcc33d5b39b9a225b402` to completion and retain its logs/artifacts.
2. Wire and test rollback cipher in production Backup/Restore.
3. Implement receiver Inbox + ACK verifier and distinct peer key agreement before Outbox sender/retry.
4. Wire provider-context policy into actual dispatch with durable owner grants and Trace.
5. Keep Windows Desktop/Agent/iOS on shared contracts and avoid expanding Token/agents until the foundation is reliable.


## Integration checkpoint — 2026-11-25 05:42 UTC (post-#612)

This entry supersedes earlier live-state descriptions above where they conflict. Earlier entries remain historical records and are not retroactively rewritten.

- Main before this checkpoint: `4396a7719dbe07e48f14db0c8ce6eb5d264309f8`.
- PR #612 merged by squash as `4396a7719dbe07e48f14db0c8ce6eb5d264309f8`. Scope: encrypt the local Core rollback journal with an installation-local Android Keystore AES-GCM key; validate the decrypted snapshot before replacing live database files; preserve legacy V2 recovery; fail closed on missing key/tampering and clean temporary plaintext.
- Exact PR-head CI run `45554381081` completed SUCCESS on head `42c9937f87ff86b9fc81beb90f5c96c731672884`. Android JVM: 316 tests, 0 failures, 0 errors, 0 skipped. Managed Android emulator: 143 tests, 0 failures, 0 errors, 0 skipped; JUnit verifier found one report with 143 test cases. Shared contracts and Debug APK passed. Windows Desktop, Windows Agent, iOS and workflow lint were skipped by change-aware routing, not tested.
- Closed PR #567 as superseded by #612 after diff comparison; the missing-key fail-closed and rollback-journal encryption scope is represented by #612. Do not reopen #567.
- Post-merge Main CI run `45555332895` for `4396a7719dbe07e48f14db0c8ce6eb5d264309f8` was IN_PROGRESS at this checkpoint. Shared contracts passed; Android JVM, managed emulator and Debug APK were still running. This is post-merge validation and must be checked separately; PR-head CI success is not a substitute for it.
- Current Outbox status: schema v12 only. There is still no atomic runtime enqueue/sequence allocator, sender, receiver Inbox, authenticated ACK verifier, retry worker or cross-Core delivery. Keep remote delivery disabled. Signing identity and payload-encryption key agreement remain separate prerequisites.
- Backup status: #612 supplies an encrypted rollback-journal primitive. Do not claim full production Backup/Restore acceptance until the actual restore staging path, maintenance gate, missing-key behavior and crash recovery are validated end-to-end.
- Android project selector: #603/#606 remain merged; #568 is a separate accessibility/workspace change stacked on large stale #558. #558 is not eligible for bulk merge; split and port only independently validated slices to current Main.
- Bridge #608 logical operation identity is merged; durable receiver Inbox/ACK/restart recovery remain open. Windows Desktop and Agent require their own applicable CI and end-to-end handoff validation. iOS remains lower priority than Core/Desktop. Token/GCU and helper expansion remain parked.
- The earlier live checkpoint in this file is stale on merge SHAs and open-PR status; use this entry plus live GitHub state for the next action. Do not mark plan steps DONE from PR existence or partial CI.
- Next execution: finish post-merge run `45555332895`; then implement one bounded Core Outbox primitive (atomic sequence allocation + idempotent local enqueue) with transaction/rollback tests, while preserving the no-remote-send gate. In parallel, split #558 and review the stale dependent PRs against current Main.

## Integration checkpoint — 2026-11-25 06:02 UTC (Outbox, clean-restore identity, Bridge CLI and chat selector)

This checkpoint supersedes earlier live-state descriptions above where they conflict. Earlier entries remain historical records.

- **Current Main observed:** `488235923b26794fabf1bc257ea889c7a1b59b44`.
- **PR #614 merged:** `a081d5391313c570be8ba396601a5259dd5600da`. Adds local transactional Outbox append with per-Core monotonic sequence allocation, exact idempotent replay validation (including signed-envelope and metadata equality), and rollback of sequence allocation on insert failure. Exact-head run `45555949176` completed SUCCESS: Android JVM 316 tests, managed emulator 145 tests (JUnit 1 report / 145 cases / 0 failures / 0 errors / 0 skipped), Shared contracts and Debug APK passed. Windows Desktop, Windows Agent, iOS and workflow lint were skipped by change routing. This is a local persistence primitive only: no sender, receiver Inbox, cryptographic peer key agreement, authenticated ACK verifier, retry worker or cross-Core send was enabled.
- **PR #615 merged:** `488235923b26794fabf1bc257ea889c7a1b59b44`. Adds an emulator regression test for portable restore onto a clean installation after deleting source noBackup identity metadata and the source Android Keystore signing key; verifies a fresh Core ID/public key and a new key epoch. Exact-head run `45556188150` completed SUCCESS: Android JVM 316 tests, managed emulator 144 tests (JUnit 1 report / 144 cases / 0 failures / 0 errors / 0 skipped), Shared contracts and Debug APK passed. Windows Desktop, Windows Agent, iOS and workflow lint were skipped. This is regression coverage, not physical-device restore acceptance.
- Post-merge CI for intermediate Main SHA `a081d5391313c570be8ba396601a5259dd5600da`, run `45557104799`, was cancelled after the subsequent Main update; its cancelled lanes and failed exact-head summary are not passes. The latest post-merge Main run at capture is `45557146141` for SHA `488235923b26794fabf1bc257ea889c7a1b59b44`: Shared contracts, Android JVM and Debug APK passed; managed emulator remained IN_PROGRESS. Other platform lanes were skipped. Complete the emulator and exact-head summary before reporting post-merge CI as passed.
- **Bridge PR #560** was closed as superseded after source comparison confirmed its shared HTTP client and enrollment facade files are already identical in current Main. Its only remaining CLI/test delta was ported to current-Main PR **#616**, head `711cf64d8f2daf3899b715a60605b89ae76d1ea7`, exact-head run `45557241418` IN_PROGRESS at capture. This adds explicit `--operation-id` parsing to the Desktop CLI and tests; receiver Inbox, authenticated ACK and end-to-end exactly-once delivery remain unimplemented.
- **Android PR #617** is a current-Main extraction from oversized #558, head `4db717bf93e77c379f6c2d0c326401411f3d0ec5`, exact-head run `45557622742` IN_PROGRESS at capture. It adds project switching in the chat header, accessible Compose click semantics, add/delete confirmation and project+agent draft keying; the existing home screen remains the launch screen. It includes the narrow click-semantics correction from #568; #568 was closed as superseded. It does not include agent/module selectors, chat action menu or direct-launch-to-chat changes. Oversized #558 remains open for further extraction; its DataManager and project-migration test files were verified identical to current Main and are not to be reimplemented.
- **Eleven workstreams — refreshed:** (1) Core Outbox: atomic local append is in Main; no peer delivery. Next protocol gate remains separately enrolled key-agreement keys and reviewed authenticated envelope encryption before any sender. (2) Backup: clean-install identity regeneration test is in Main; physical-device and interrupted-restore acceptance remain open. (3) Android: home selector and canonical project IDs are in Main; chat selector is #617 pending CI. (4) Bridge: stable logical client identity is in Main; CLI argument UX is #616 pending CI; durable receiver Inbox/ACK/restart recovery remain open. (5) Windows Desktop: no end-to-end Core/Bridge handoff claimed. (6) Windows Agent: no new acceptance; bounded diagnostics handler only. (7) iOS: no new validation; lower priority. (8) CI/CD: exact-head PR runs for #614/#615 passed; post-merge latest Main emulator is still pending. (9) Security: signing and payload-encryption keys remain distinct; no remote delivery. (10) Token/GCU: parked. (11) Helper intake: #560/#568 duplicates closed with replacement links; #558 still requires bounded extraction.
- **Next execution:** finish `45557146141`, `45557241418` and #617's exact-head run; merge eligible PRs only after all applicable required lanes and exact-head summaries pass; verify new Main SHA and its post-merge CI. Then extract the remaining agent/module/action-menu slice from #558 without changing the existing home-first launch behavior.
- No numbered 150-step acceptance item is marked DONE by these implementation slices alone.


### 2026-11-25 — GLAFIRA-GOV-W04
- Ported the mandatory autonomous defect-repair cycle to current Main without copying the stale ledger snapshot from #569; the existing GOV-W02 audit record was already present in Main.
- Added `docs/engineering/AUTONOMOUS_REPAIR_CYCLE.md` and a concise mandatory reference in `.github/copilot-instructions.md`.
- Rule: evidence → ownership/deduplication → in-scope correction → regression test → log inspection → repeat until pass or concrete blocker → evidence-based handoff.
- This is an operating instruction only; it grants no additional write, approval, merge, secret or external-side-effect permissions and does not alter the eleven workstreams or Program 150 completion count.
- Branch `governance/autonomous-repair-cycle-current-main-20261125`, based on Main `fd3dd39f5d6bbe0c4de4aa03d5fbea5060365371`; exact-head docs validation and Dispatcher are required before merge.


## Integration checkpoint — 2026-11-25 06:18 UTC (Bridge CLI, trusted peers, Android selector, dispatcher)

This checkpoint updates the live integration state after the previous Outbox/restore checkpoint.

- **Current Main observed:** `685ebfa7b60cc906275bec528fc34b984ba8133e`.
- **PR #616 merged:** `fd3dd39f5d6bbe0c4de4aa03d5fbea5060365371`. Desktop Bridge CLI now requires a stable explicit `--operation-id` for `CONVERSATION_APPEND`, supports both split and equals flag forms, and does not automatically retry uncertain writes. Exact-head CI run `45557241418` completed SUCCESS across applicable Android JVM, emulator, Shared contracts and Debug APK checks; Windows Desktop/Agent and iOS were skipped by change routing. This is the sender-side logical operation ID only; it does not prove receiver Inbox/ACK or exactly-once end-to-end behavior.
- **PR #619 merged:** `685ebfa7b60cc906275bec528fc34b984ba8133e`. Adds owner-gated trusted-peer signing-key enrollment, exact fingerprint matching, P-256 key validation, one active key per peer, monotonic key epochs, revocation and exact active-key lookup in noBackup storage. Exact-head CI run `45558047467` completed SUCCESS: JVM 316 tests; emulator 146 tests, 0 failures/errors/skips; Shared contracts and Debug APK passed. Windows Desktop/Agent and iOS skipped. This stores signing trust only; it does not create a payload-encryption key or enable peer transport.
- **PR #621 merged:** `f40bb2f723a1f5281248d3530d05d3775f5fe9d5`. Added `docs/engineering/AUTONOMOUS_REPAIR_CYCLE.md`, mandatory Copilot helper instructions and GOV-W04 ledger entry. Exact-head Dispatcher and docs-only CI completed SUCCESS; platform build lanes were correctly skipped. Stale #569 was closed as superseded.
- **Trusted-peer acceptance tests:** stacked #620 completed SUCCESS on the #619 dependency head: 316 JVM tests and 153 emulator tests, 0 failures/errors/skips. Because squash-merge of #619 changed ancestry, the tests were copied to clean current-Main test-only PR **#623** (head `906ff4180fe6eb6ba4d62320cc551eba2b6f1cfb`). Its exact-head run `45559238671` has JVM, Shared contracts and Debug APK passed; managed emulator is still IN_PROGRESS. #620 is closed as superseded by #623. Old #564/#565 are closed as superseded by #619/#623.
- **Android chat project selector:** #617 remains open, current head `0c5be1232ba477ce4363ad013eeb51e76ddf4d24`. Its first exact-head run `45557816635` completed with 147 test cases and one failure: `StaleObjectException` at `MainActivitySmokeTest.kt:53` while clicking the chat project selector. The source compiled and the rest of the suite completed; this was a test accessibility-node race, not an app compilation failure. Added an eight-attempt fresh-node click helper and explicitly retained the chat back arrow; new exact-head run `45558797691` has JVM 316, Shared contracts and Debug APK passed; emulator is still IN_PROGRESS. #568 is closed as superseded by #617. Oversized #558 remains Draft; #617 is only the project-selector slice, while agent selector and module/action-menu remain to be extracted separately.
- **Dispatcher reliability:** stale #555 was closed and its unique workflow patch ported to current-Main PR **#622**, head `1992dcd2d034346a4c3cc0b206870c0590788be3`. The exact-head run `45559149158` has Workflow lint, Android JVM, Debug APK, Shared contracts, iOS, Windows Desktop and Windows Agent successful; Windows Desktop reports 200 tests passed and app build succeeded (with existing CA1416 analyzer warnings in platform-specific tests); Windows Agent reports 116 tests passed and build succeeded with 0 warnings. Managed emulator remains IN_PROGRESS, so exact-head summary and merge are pending. PR #622 is the cross-platform dispatcher fallback validation; it is not merged.
- **Post-merge Main validation:** run `45558854191` for Main SHA `685ebfa7b60cc906275bec528fc34b984ba8133e` has JVM 316, Shared contracts and Debug APK successful; managed emulator remains IN_PROGRESS. Do not report post-merge CI as fully passed until the emulator and exact-head summary finish.
- **Eleven workstreams — refreshed:** (1) Core local Outbox append (#614) and trusted signing-peer registry (#619) are in Main; Inbox/ACK, authenticated envelope and key agreement remain absent. (2) Backup rollback encryption (#612) and clean-install identity regeneration test (#615) are in Main; interrupted/physical-device restore acceptance remains open. (3) Android home selector and canonical identities are in Main; chat project selector #617 awaits emulator. (4) Bridge stable CLI operation ID #616 is in Main; receiver Inbox/ACK remains open. (5) Windows Desktop lane passed 200 tests/build in #622's current source, but no end-to-end Core handoff is claimed. (6) Windows Agent lane passed 116 tests/build in #622's current source, but no end-to-end task acceptance is claimed. (7) iOS compile lane passed in #622; feature parity remains deferred. (8) Dispatcher exact-head fallback #622 is awaiting emulator and exact-head summary. (9) Signing trust is now stored; peer payload encryption/key agreement is still a security gate. (10) Token/GCU remains parked. (11) Helper repair-cycle policy #621 is in Main; duplicate stale PRs #555/#560/#564/#565/#568/#569/#620 were closed with current-Main replacements.
- **Next execution:** finish exact-head runs `45558797691`, `45559238671`, `45559149158` and post-merge Main run `45558854191`; inspect JUnit/Windows/iOS logs, fix any source/test failure, merge #617/#623/#622 only after their applicable gates pass, and verify the resulting Main SHA plus post-merge CI. Then extract the remaining agent selector and module/action-menu pieces from #558.
- No Program 150 item is marked DONE by these integration slices alone.


### Addendum — 2026-11-25 06:19 UTC
- **PR #617 merged:** `058fc1bb0f442a24af7316393783598b6141d84e`. Current-head run `45558797691` completed SUCCESS: Android JVM 316 tests, managed emulator 147 tests (0 failures/errors/skips), Shared contracts and Debug APK passed. Windows Desktop/Agent/iOS/workflow lint skipped by Android-only change routing. The earlier StaleObjectException was resolved with fresh accessibility-node retries; the back arrow was preserved and tested.
- **Post-merge Main run:** `45559713041` for Main SHA `058fc1bb0f442a24af7316393783598b6141d84e` is IN_PROGRESS at capture; Android JVM, Debug APK and Shared contracts passed; managed emulator remains in progress. Do not mark post-merge CI complete yet.
- **PR #622:** exact-head run `45559149158` has Workflow lint, Android JVM, Debug APK, Shared contracts, Windows Desktop, Windows Agent and iOS all successful; Windows Desktop reports 200/200 tests and app build success (existing CA1416 warnings in platform-specific tests), Windows Agent reports 116/116 tests and build success. Managed emulator is still IN_PROGRESS; exact-head summary and merge remain pending.
- **PR #623:** clean-current-Main test-only trusted-peer acceptance PR; exact-head run `45559238671` has Android JVM, Shared contracts and Debug APK successful; managed emulator remains IN_PROGRESS. The earlier stacked #620 run passed 153 emulator cases, but #623 must complete its own exact-head emulator and summary.
- The previous #624 checkpoint head `2a7b4eeca2a8b30117b439262f3f3408d18d2261` passed Dispatcher and exact-head documentation validation. This addendum changes the ledger head, so the updated PR #624 must complete fresh exact-head validation before merge.


## Integration checkpoint — 2026-11-25 06:26 UTC (dispatcher and trusted-peer tests merged)

- **Current Main observed:** `8a3e9ec375e5ae50dc6970798963906ae94ec553`.
- **PR #622 merged:** `660cc765c328d21ad82cef26eb00a6beec7c9b86`. Exact-head run `45559149158` completed SUCCESS across all lanes: Android JVM 317, managed emulator 146 (0 failures/errors/skips), Debug APK, Shared contracts, Workflow lint, iOS, Windows Desktop and Windows Agent. Windows Desktop: 200/200 tests and build succeeded; existing CA1416 platform analyzer warnings in Windows-specific test call sites. Windows Agent: 116/116 tests and build succeeded with 0 warnings. This PR restores exact-head Actions/REST fallback in the dispatcher and is now in Main.
- **PR #623 merged:** `8a3e9ec375e5ae50dc6970798963906ae94ec553`. Adds only the current-Main trusted-peer instrumentation file. Exact-head run `45559238671` completed SUCCESS: JVM 316, emulator 153 (0 failures/errors/skips), Shared contracts, Debug APK and exact-head summary. The earlier stacked #620 run also passed 153 emulator cases but was closed after copying the tests to the clean-main #623 branch.
- **Post-merge run after #622:** `45560054972` for SHA `660cc765c328d21ad82cef26eb00a6beec7c9b86` completed all non-emulator lanes successfully: Android JVM 317, Debug APK, Shared contracts, Workflow lint, iOS, Windows Desktop and Windows Agent. Its emulator lane was cancelled when #623 advanced Main; its exact-head summary consequently failed. Do not call this a fully successful post-merge run.
- **Latest post-merge run:** `45560473700` for Main SHA `8a3e9ec375e5ae50dc6970798963906ae94ec553` is IN_PROGRESS at capture. Shared contracts and Debug APK passed; Android JVM and managed emulator remain IN_PROGRESS. Windows Desktop, Windows Agent, iOS and workflow lint are skipped by test-only change routing. Wait for the exact-head summary before reporting final Main validation.
- **Current split parent #558:** remains Draft and must not merge. #617's chat project selector is now in Main. Remaining independent slices are the agent selector and module/chat-actions menu. The home-first launch behavior is preserved; no direct-launch-to-chat change is authorized.
- **Stale PR reconciliation:** #555 closed in favor of #622; #560 in favor of #616; #568 in favor of #617; #569 in favor of #621; #564 in favor of #619; #565 and #620 in favor of #623. #556/#559 were closed after their unique content was already in Main or ported to clean replacements. #558 remains the tracking parent.
- **Eleven workstreams — refreshed:** (1) Core local Outbox append (#614), trusted signing-peer registry (#619) and trusted-peer tests (#623) are in Main; separate ECDH key agreement, authenticated encrypted envelope, durable Inbox/ACK and sender/receiver workers remain open. (2) Backup rollback encryption (#612) and clean-install identity regeneration acceptance (#615) are in Main; interrupted/physical-device restore acceptance remains open. (3) Android home selector, canonical project identity and chat project selector (#603/#606/#617) are in Main; agent/module/action-menu slices remain. (4) Bridge stable CLI operation ID (#616) is in Main; receiver Inbox/ACK remains open. (5) Windows Desktop lane passed 200 tests/build in #622; no end-to-end Core handoff claimed. (6) Windows Agent lane passed 116 tests/build in #622; no end-to-end task acceptance claimed. (7) iOS build lane passed in #622; feature parity remains deferred. (8) Dispatcher fallback (#622) is in Main; current post-merge Android CI remains in progress. (9) Signing trust is in Main; separate payload key agreement remains a hard security gate. (10) Token/GCU remains parked. (11) Helper repair-cycle governance (#621) and append-only execution evidence (#624) are in Main.
- **Next execution:** finish `45560473700` and inspect JUnit reports; if successful, continue with the next Core security gate (separate key-agreement identity lifecycle) before any Inbox transport. Then split the remaining #558 agent selector and module/action menu into bounded PRs, each with its own acceptance test and merge.
- No Program 150 acceptance item is marked DONE solely from these merged slices.


## Post-merge validation addendum — 2026-11-25 06:34 UTC

- **Main SHA:** `8a3e9ec375e5ae50dc6970798963906ae94ec553`.
- **Latest Main run:** `45560473700` completed SUCCESS on that exact SHA. Android JVM: **317 test cases**, 0 failures/errors/skips. Managed emulator: **154 test cases**, 0 failures/errors/skips. Debug APK and Shared contracts passed; Exact-head CI summary passed. Windows Desktop, Windows Agent, iOS and Workflow lint were skipped by the change-aware selector for this Android-only test change; they are not counted as passes.
- **Emulator configuration audit:** current `.github/workflows/android.yml` already uses KVM, API 35 `google_apis`, Pixel 2 x86_64, 2 GB RAM, a 900-second emulator boot timeout, disabled snapshots/animations and a JUnit verifier that rejects empty or skipped test reports. The earlier `Timeout waiting for emulator to boot` log belongs to an older workflow run/revision; it is not evidence that the current Main configuration still fails. An explicit `adb wait-for-device` / `sys.boot_completed` probe is not currently present. Add it only with a bounded timeout and after confirming it improves the current runner rather than duplicating the emulator action's own boot wait. Do not switch to `aosp_atd` without checking Google APIs dependencies in the instrumentation suite.
- **Acceptance boundary:** this confirms managed-emulator execution only. It does not satisfy physical-device acceptance on the owner's Android phone or a real Android↔Windows task handoff.
- **Next P0 gate:** resolve the transport/security design for separately authenticated Core-to-Core task delivery. Main already contains the local Outbox append, trusted signing-peer registry and tests; no Inbox/ACK sender/receiver path is enabled. Keep payload delivery disabled until the receiver identity, encryption channel, durable Inbox receipt and exact ACK are implemented and tested.

## Work item reservation — 2026-11-25 07:20 UTC

- **Work Item:** `BRIDGE-VAULT-01`; parent Program 150 Step 68; Streams 3 (Bridge) and 7 (CI/CD/security).
- **Base:** `a5369a8ef6e1a4ee4a2c088f805cf749e92b9cc2`; branch `security/desktop-vault-scoped-use-20261125`.
- **Deliverable:** synchronous `loadAndUseVault` for one-shot Bridge operations; clear owned credential/passphrase buffers on success and exception; migrate `postFromVault`; add lifecycle tests.
- **Allowed paths:** `DesktopBridgeCredentialVault.kt`, `DesktopBridgeEnrollmentFlow.kt`, `DesktopBridgeCredentialVaultTest.kt`, this ledger.
- **Explicit exclusion:** the long-running CLI task worker keeps its existing shutdown-bound secret lifetime because its executor must retain credentials until all in-flight handlers stop. It is not safe to force a callback-finally wipe while that worker is still active; this exception is documented and remains a separate scoped-lease follow-up.
- **Acceptance:** Desktop Bridge JVM tests pass on exact PR head; success/failure callbacks wipe credentials and consumed passphrase; no change to vault file format or pairing protocol.
- **Status:** RESERVED; implementation not yet accepted.

## Work item execution update — 2026-11-25 07:18 UTC

- First exact-head run `45566241596` completed. `desktop-bridge:test` executed 58 tests; two new scoped-callback tests failed because the test fixtures used different import and use passphrases. This is a test-fixture mismatch; production vault validation was not weakened. Shared contracts and Android debug APK contracts passed; emulator acceptance completed successfully. Exact-head summary correctly failed because Android JVM tests failed.
- Corrected both fixtures to use the same passphrase. Next step is one exact-head CI run on the corrected commit; no merge until JVM tests and exact-head summary pass.


## Integration checkpoint — 2026-11-25 07:53 UTC (Vault scoped-use and hierarchical WBS governance)

- **Main SHA at checkpoint:** `a1f9cc40b209616f4a3ef5a90d721d2226579090`.
- **PR #626 merged:** `0f8c4358c53bf74451d5e406a482be58421cfdc4`. `DesktopBridgeCredentialVault.loadAndUseVault` is now in Main for one-shot credential operations; `postFromVault` uses the scoped API. Exact-head CI run `45567161531` completed SUCCESS: Android JVM, emulator, Shared contracts, Debug APK and exact-head summary passed. Windows Desktop, Windows Agent, iOS and Workflow lint were skipped by change routing. Dispatcher run `45567161506` completed SUCCESS. Long-running worker credentials remain alive until worker shutdown; do not wipe them at a callback boundary while in-flight handlers still need them.
- **PR #628 merged:** `a1f9cc40b209616f4a3ef5a90d721d2226579090`. Added mandatory hierarchical dotted-decimal WBS standard, root `AGENTS.md`, and execution-control enforcement. Exact-head change-aware CI `45571539129` completed SUCCESS; Android JVM, emulator, Shared contracts, Windows Desktop/Agent, iOS, Workflow lint and Debug APK lanes were skipped because this was a documentation-only change. Dispatcher reconciliation `45571539130` completed SUCCESS. No platform test pass is claimed from skipped lanes.
- **New mandatory hierarchy:** `1 GLAFIRA`; `1.1`–`1.11` permanent subprojects corresponding to the eleven workstreams; `1.1.1` chapters; deeper dotted-decimal levels down to executable steps. Existing Program 150 step IDs remain historical acceptance references and must be mapped to WBS IDs, not renumbered. Every participant must reserve one leaf step, obey predecessors, own exclusive files and report evidence before starting the next dependent step.
- **Auditor follow-up:** issue [#629](https://github.com/micronizationlab/glafira-ai-hub/issues/629) records 31 evidence-based questions and requires a KEEP/SIMPLIFY/ADOPT/REWRITE/REMOVE/NEEDS-MORE-EVIDENCE decision matrix. No architecture reset is authorized by the audit request itself.
- **Post-merge CI limitation:** the available commit-workflow lookup exposes PR-triggered runs only; it returned no post-merge run for merge SHA `a1f9cc40b209616f4a3ef5a90d721d2226579090`. Therefore PR exact-head CI and dispatcher success are recorded, but post-merge Main CI is not claimed as passed.
- **Next eligible work:** reconcile the live Program-150 ledger and map active P1/P2/P3 acceptance steps to hierarchical WBS IDs; then continue Core Inbox/ACK and secure peer key agreement in dependency order. Keep payload delivery disabled until authenticated encryption, durable Inbox receipt and exact ACK are implemented and tested.
- **No Program-150 acceptance item is marked DONE solely because PR #626 or #628 merged.**
