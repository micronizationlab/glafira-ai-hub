# GLAFIRA Engineering Session Journal

Entries are append-only. Statuses are snapshots; always refresh GitHub before relying on them.

## 2026-11-20 — Android regression, Bridge, economics and collaborator handoff

### Android feature-presence regression work
- PR #139 — https://github.com/micronizationlab/glafira-ai-hub/pull/139 — head 9ea6f8a4999b6f0a6fa221f5e764d211a5ad77cf. Run 44703093650: Android JVM 113 JUnit cases, 0 failures/errors/skips; emulator 4 instrumentation cases, 0 failures/errors/skips; APK built (artifact 14613947024). Includes task queue Add Task, council action and backup manual action/regression UI coverage.
- PR #140 — https://github.com/micronizationlab/glafira-ai-hub/pull/140 — head 4e1fee022acd02a21656cbb391189775e8a6c8a3, stacked on #139. Run 44718720093: Android JVM 179 JUnit cases, 0 failures/errors/skips; emulator 7 instrumentation cases, 0 failures/errors/skips; APK built (artifact 14620273569). Bridge Settings entry, owner-started TLS host, Android Keystore pairing/identity stores and foreground service registration are included. Both PRs remain draft; no physical-device/LAN acceptance has been claimed.
- APK run links: https://github.com/micronizationlab/glafira-ai-hub/actions/runs/44703093650 and https://github.com/micronizationlab/glafira-ai-hub/actions/runs/44718720093.

### Core / Bridge / Desktop cross-platform validation
- PR #170 head a8cb1035ec55cd16594c3e99498d93ab3683c29a. Runs: Windows Desktop Hub 39/39 passed, 0 warnings/errors; Windows Agent 15/15 passed, 0 warnings/errors; Windows Desktop UI job verified Hub store with 39/39 tests; Android JVM 134 JUnit cases, 0 failures/errors/skips; Android emulator 12 instrumentation cases, 0 failures/errors/skips; shared Kotlin/Swift/C# contract generation and compilation passed; iOS Swift package job passed. Workflow lint was skipped. Project Dispatcher run 44723514112 failed only because GitHub API returned HTTP 403 installation rate-limit exceeded while listing workflows; retry after rate limit reset. Do not misreport that as an application-code failure.
- #142 validation-only full Core stack branch and #151 clean dependency PR had no workflow runs at the time of this entry. They require a fresh exact-head CI trigger and actual JUnit/instrumentation inspection.

### Token / consumer product design
- PR #178 — https://github.com/micronizationlab/glafira-ai-hub/pull/178 — current-mainline draft, head 1153c911e3fcaaf08ec496fdfb228b775e6fcbdf. Documentation PR adds device reward/break-even formula, benchmark CSV, model free-quota registry, Telegram Stars payment constraints, consumer presentation, Mac Desktop, sequential account-bound downloads, signed coordinator pinning and mandatory collaborator audit. CI run 44726116899 was docs-only: Android/iOS/Windows tests and emulator checks were correctly skipped; exact-head summary passed. Project Dispatcher run 44726116938 passed. Review and merge are not yet complete.
- Issue #177 tracks Telegram storefront / Stars billing. Issue #152 updated for Mac Desktop and website onboarding; #154 updated for per-device economics and GCU/GLF; #107 updated for provider free-quota catalogue; #115 updated for project donation block under wallet transfer.
- Donation recipient ID is intentionally not fabricated. It must come from the verified Mother treasury account.

### Mandatory next-session actions for ChatGPT and Костя
1. Fetch live PRs/issues/branches and compare to this entry. Check #139/#140 current heads and artifacts; request physical-device tests for Google Drive backup and LAN Bridge pairing.
2. Refresh #142 and #151 workflows. Read logs and XML/TRX contents; verify executed test counts and skipped jobs. Fix failures before considering the Core stack ready.
3. Retry the #170 Project Dispatcher job after GitHub API rate limiting clears; inspect exact error and final job result.
4. Keep Core changes serial where they touch task store/dependency chain; continue independent Bridge and Windows Desktop work in parallel.
5. Review #178 and model-level quota evidence. Keep free quota separate from GLAFIRA included allowance; no silent paid fallback.
6. Do not merge drafts, issue GLF, activate paid billing or claim device earnings without owner authorization and acceptance evidence.
