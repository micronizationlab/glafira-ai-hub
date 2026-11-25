# GLAFIRA execution control — mandatory for coordinator and every helper

**Canonical plan:** `docs/GLAFIRA_150_STEP_COMPLETION_PLAN.md`  
**Canonical execution history:** `docs/GLAFIRA_EXECUTION_LEDGER.md`  
**Control issue:** #548  
**Owner:** Валерий Корчагин. **Coordinator:** GLAFIRA.  
**Rule:** the 150 numbered acceptance steps are the only completion denominator. Items 151–200 are a separate extension backlog.

## 1. Session start — do this before any work

1. Read this file, `docs/GLAFIRA_HIERARCHICAL_WBS_STANDARD.md`, the full 150-step plan, execution ledger, collaborator handoff, engineering dispatcher rule, root `AGENTS.md` and `.github/copilot-instructions.md`.
2. Read issue #548 and refresh GitHub facts live: `main` SHA, open/closed PRs, branches, changed-file lists, commit ancestry, reviews, workflow runs, jobs, logs and artifacts relevant to active work.
3. Reconcile the ledger with the actual repository. Never trust a remembered SHA, an earlier chat summary, an old PR base or an old green CI run.
4. Search the plan and ledger for the exact step/acceptance criterion. Search current `main`, all open PR diffs and helper branches for the same behavior and files.
5. If the behavior already exists, do not implement it again. Verify it against the criterion and either record evidence, record a precise gap, or mark the candidate SUPERSEDED.
6. Reserve one unique Work Item ID, one Program Step ID (or explicitly approved cross-step task), one accountable executor, exact files, dependencies, tests and intended PR. Record reservation in the ledger before editing.
7. If another active item touches the same behavior, API, schema, migration or files, stop and reconcile ownership before work begins.

## 2. Mandatory hierarchical WBS and strict sequence

- `1` is the GLAFIRA project; `1.1` is a subproject; `1.1.1` is a chapter; each deeper level appends one numeric component down to an individual executable step.
- The canonical rules, stable eleven-subproject map, legacy 150-step mapping and mandatory step-by-step procedure are defined in `docs/GLAFIRA_HIERARCHICAL_WBS_STANDARD.md`.
- Every issue, branch, PR, commit, test report and ledger entry must carry its hierarchical WBS ID(s). Existing Program 150 IDs remain compatibility references and must be mapped, not renumbered.
- No participant may start a dependent step before all predecessor steps are accepted and recorded DONE. A blocked predecessor blocks dependent work; skipping requires explicit owner approval and a recorded decision.
- Parallel execution is permitted only for independent sibling tasks with separate WBS IDs, executors, branches and exclusive file ownership. The coordinator must reserve ownership before implementation.
- Every participant must implement and report one executable leaf step at a time, including exact acceptance evidence and the next eligible step.

## 3. Work item identity and ownership

Every task has a stable ID: `GLAFIRA-S<step>-W<sequence>` (example: `GLAFIRA-S016-W01`). A task record must include:
- Step ID(s), short objective and acceptance criteria;
- one accountable executor (GLAFIRA or one named helper); reviewers are separate;
- allowed file paths and explicit out-of-scope paths;
- parent/base SHA, branch, PR/issue, changed-file fingerprint;
- dependencies and conflicts with other active work;
- tests, expected CI lanes and evidence links;
- status, last update, next action and blocker.

One logical behavior has one active implementation owner. Helpers may independently review or research, but must not implement overlapping changes. The eleventh workstream collects helper work first and reconciles it before new implementation.

## 4. The mandatory implementation cycle

1. **Inventory:** refresh main, PRs, branches, changed files and prior work.
2. **Define:** write hypothesis, source evidence, smallest change, risks, dependencies and tests.
3. **Reserve:** register Work Item ID and exclusive file/behavior ownership.
4. **Implement:** one logical change on one dedicated branch from fresh main (or documented dependency branch).
5. **Review diff:** verify only intended files changed; compare against all open PRs and main; reject duplicate hunks/behavior.
6. **Test:** run targeted tests first. Expand only to required project checks.
7. **Build/CI queue:** enqueue once per unique commit SHA + workflow/lane. Never run parallel builds. Reuse existing completed/in-progress run for the same SHA and workflow.
8. **Inspect evidence:** read job results and failure logs. Classify source defect, test defect, environment failure, cancellation, timeout or skipped. A rerun requires a written reason and must reuse the same commit SHA unless code changed.
9. **PR review:** one PR per independent change. Default target 50–150 changed lines, hard ceiling 200; larger work is split before implementation. No duplicate PR for an existing diff.
10. **Merge gate:** fresh base; no unresolved overlap/conflict; required review; exact-head checks all passed; required platform tests actually executed; no skipped/cancelled jobs counted as success; dependency PRs merged in order; owner approval where required.
11. **Merge and verify:** authorized maintainer merges; record merge commit; run/reuse post-merge checks for that exact main SHA; inspect logs.
12. **Close the loop:** only then mark acceptance criterion DONE, update plan checkbox if the whole numbered step is satisfied, append evidence to ledger, release file ownership and notify helpers.

## 5. Build and duplicate-work rules

- A build is identified by `(repository, commit SHA, workflow, job/lane, configuration)`, not by its display name or APK filename.
- Same identity + completed success: reuse the evidence; do not rebuild without a reason.
- Same identity + queued/in-progress: wait; do not create another run.
- Same identity + failure: inspect logs first; rerun only for a documented transient/infrastructure cause or after an identified correction.
- New commit SHA: new validation is required because the artifact changed.
- Different workflow/lane or materially different configuration: separate check is valid, but must be explicitly registered.
- Never create a second APK/installer for the same source SHA/configuration just to have another artifact. Record artifact SHA-256, version, source SHA and workflow run.
- CI concurrency is serialized globally for release/build lanes; work on non-overlapping code may proceed independently, but builds do not run in parallel.
- Never call a cancelled, skipped, queued, in-progress or partial workflow a pass.

## 6. PR and branch rules

- Before creating a branch, search existing branches and PRs by feature, changed paths and patch/commit equivalence.
- Before creating a PR, search open and recently closed PRs. Reuse/update the existing work item if it is the same logical change.
- One PR = one independently testable logical slice; default 50–150 changed lines, max 200. Do not aggregate unrelated features.
- No direct push to main, no force-push, no blind cherry-pick/rebase, no merge of an oversized aggregate.
- A merged PR is not automatically a completed Program step; the step may require other slices and acceptance tests.
- Closed/superseded PRs remain historical evidence. Do not reopen merely to continue work; extract only missing unique changes into a new bounded PR.

## 7. Definition of DONE

A numbered step is DONE only when:
1. its complete acceptance criterion is satisfied in code/configuration;
2. regression tests cover success and relevant failure/recovery/security paths;
3. exact-head CI for all required lanes passed (not skipped/cancelled);
4. review and dependencies are resolved;
5. change is merged to main;
6. post-merge checks for the resulting main SHA passed;
7. evidence and final SHA/PR/run/artifact are recorded in the ledger.

A physical-device, two-PC, cloud-provider or owner-approval requirement cannot be replaced by unit tests or an emulator. Record it as BLOCKED/OWNER_ACCEPTANCE until actually performed.

## 8. Eleven workstreams (parallel analysis, exclusive implementation ownership)

1. **Core:** queue, owner identity/generation, grants, authorization, Trace, dispatcher, result outbox/ACK, recovery.
2. **Backup & Recovery:** encrypted archive, migrations, interruption/corruption recovery, cloud/SAF, clean restore.
3. **Bridge:** pairing, TLS pinning, protocol, replay/idempotency, durable ingress/outbox, ACK, reconnect.
4. **Windows Desktop/Hub:** independent Desktop Core, Hub transport boundary, lifecycle, leases, WPF UX, diagnostics, packaging.
5. **Android:** owner UX, chat→intent→Core→preview→approval→execution→result, settings, voice, physical acceptance.
6. **iOS:** native peer, shared contracts, Keychain/biometrics, simulator and device acceptance.
7. **CI/CD & security:** serialized workflows, exact-head checks, log diagnosis, permissions, secrets, supply chain.
8. **Windows Agent:** allowlisted handlers, sandbox/resources, durable results/ACK, fleet/two-PC acceptance.
9. **Token/economics:** non-issuing calculator and docs only until owner separately approves issuance/payment implementation.
10. **Multi-model & specialist architecture:** provider grants/data classification, cost attribution, role projects, no bypass of Core.
11. **Helper intake/integration:** inspect helper output first, deduplicate, reserve files, review exact diff, split bounded slices, validate, merge in dependency order, notify helper.

## 9. Priority and sequencing

Respect current owner priorities: P1 Core; P2 Backup & Recovery; P3 Bridge and Windows Desktop. Android and Desktop are the main runtime surfaces. iOS and specialist-agent feature work follow the foundation. All eleven streams remain active for non-conflicting analysis and documentation, but no stream may bypass dependency order or claim shared file ownership.

## 10. Current baseline and reconciliation

At creation of this control branch, `main` was `f2b5e4b087d4f2357e0354ec0cc56c228c0ed1ec`. The plan file still carried an older baseline SHA `b34c831c024b9db6157f3dea7fdfa9d9b650d337`; therefore the first task is a live reconciliation audit. The plan contains 200 numbered entries: original steps 1–150 and extension items 151–200. The prior literal checkbox count was 27/200, with 27 checked in the first 150, but that count must be revalidated against current main before it is used as a progress claim.

PR #410 is closed without merge and frozen as source-only. Do not reopen, push to it, or merge its aggregate branch. Any useful missing change must be uniquely mapped to a numbered step and extracted as a small PR.

## 11. Required reporting format

At the end of each work cycle report:
- Current main SHA;
- Work Item ID and Program Step;
- owner/executor and files;
- what was already present (so it was not repeated);
- changes made and branch/PR;
- exact tests and run IDs/results/log diagnosis;
- merge SHA and post-merge CI (if merged);
- ledger update and next action;
- active blockers and who owns them.

No vague “done”, no hidden progress, no repeated work, no second build without a reason.
