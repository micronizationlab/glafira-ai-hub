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