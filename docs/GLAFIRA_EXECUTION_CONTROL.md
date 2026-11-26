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

Every task has a stable ID. Program-step work uses `GLAFIRA-S<step>-W<sequence>` (example: `GLAFIRA-S016-W01`). An explicitly owner-approved cross-step governance/design task may use `GLAFIRA-GOV-<domain>-W<sequence>` (for example, `GLAFIRA-GOV-D01`) only when its approval, WBS parent, tracker and cross-step scope are recorded in the ledger. The exception does not permit an unmapped or unapproved task. Every executable leaf still has a unique WBS ID and unique Work Item ID. A task record must include:
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
3. **Reserve:** register Work Item ID, exact WBS leaf, dependencies and exclusive implementation file/behavior ownership in the canonical ledger before editing. A reservation is not active and must not be dispatched until its ledger commit is merged to `main`.
4. **Implement:** one logical change on one dedicated branch from fresh main (or documented dependency branch).
5. **Review diff:** verify only intended files changed; compare against all open PRs and main; reject duplicate hunks/behavior.
6. **Test:** run targeted tests first. Expand only to required project checks.
7. **Build/CI queue:** enqueue once per unique commit SHA + workflow/lane. Never run parallel builds. Reuse existing completed/in-progress run for the same SHA and workflow.
8. **Inspect evidence:** read job results and failure logs. Classify source defect, test defect, environment failure, cancellation, timeout or skipped. A rerun requires a written reason and must reuse the same commit SHA unless code changed.
9. **PR review:** one PR per independent change. Default target 50–150 changed lines, up to 5 files. Any line-count exception (201–300 lines) and any file-count exception (6–10 files) independently require written owner/integrator approval before implementation begins. More than 300 lines or more than 10 files must be split. No duplicate PR for an existing diff.
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

Respect the current owner-approved priority order: P1 Core; P2 Backup & Recovery; P3 Bridge and Windows Desktop. Android and Desktop remain the main runtime surfaces, but do not displace Core or Backup. iOS and specialist-agent feature work follow the foundation. All eleven streams remain active for non-conflicting work. Workstream 11 integration intake/review/merge/post-merge work is checked before assigning implementation fallback. The latest explicit owner decision supersedes historical priority tables and dispatcher rankings; no helper or automated dispatcher may override it.

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


## 12. Mandatory fallback project — GLAFIRA DESIGN

Every one of the eleven workstreams must receive one bounded assignment in each coordinator cycle; a lane must not be silently left idle. Before using the design fallback, the coordinator/dispatcher checks the full ready-work queue and assigns the highest-ranked eligible, dependency-ready item available to that lane: implementation, validation, intake, review, merge or post-merge verification. Any ready work at equal or higher owner priority takes precedence over design, including independent work at the same priority; do not leave a merge-ready PR or actionable review/intake task idle merely because the lane's originally assigned item is blocked. Only when no eligible implementation/validation/intake/review/merge/post-merge item at equal or higher owner priority is ready may the coordinator assign the lane one distinct, dependency-ready work package in the separate **GLAFIRA DESIGN** project tracked by issue #672. A skipped CI lane caused by change-aware selection is not itself evidence that the platform is validated; it can trigger design fallback only after the full ready-work check.

The original implementation/test/acceptance outcome is immutable and must be preserved exactly, including **BLOCKED**, **NOT RUN**, **SKIPPED**, **CANCELLED**, **OWNER_ACCEPTANCE**, or any other recorded state. Report design fallback progress separately; never overwrite or reinterpret the original lane outcome. Design work is a separate deliverable and never substitutes for required tests, physical-device acceptance, security review, owner approval or merge gates.

### Design scope and boundaries

- Product identity: ICQ 2003 + Apple/macOS polish, retaining the ICQ flower.
- One coherent system for Android, iOS, Windows Desktop and Windows Agent.
- Shared color and typography tokens, spacing, surfaces, iconography, controls, navigation, motion, accessibility, and loading/empty/error/model-status/quota states.
- Screen specifications for Chat, Projects/Control Center, Agent Network, model/provider status, Task Queue, Settings, pairing and Backup/Recovery.
- Design may not change Core behavior, owner authority, permissions, cryptography, database schemas, Bridge protocols or security/approval boundaries.

### Assignment rules

- Before assigning a design package, register its canonical hierarchical WBS executable leaf in the execution ledger, map it to a Program 150 step or an explicitly owner-approved cross-step task, and record its issue, executor, exact allowed path, dependencies, base SHA, branch, acceptance and tests. Reservation must precede edits.
- One helper = one active bounded assignment. A blocked helper does not self-select another task; the coordinator assigns a distinct design package.
- The design packages in issue #672 have separate platform/component ownership and non-overlapping files or document sections.
- The design foundation/tokens precede dependent visual specifications. A package may begin only when its declared predecessors are ready; information architecture/wireframes may proceed independently if the issue explicitly marks visual tokens as pending. No duplicated edits or competing source-of-truth files.
- If no dependency-ready design package exists, or every eligible package is already exclusively reserved, the coordinator records a blocked cycle with the actual blocker (missing predecessor or reservation conflict), its owner and the next unblock action. Do not duplicate an assignment, fabricate a deliverable or violate dependency order.
- Each package has its own branch and focused PR and follows `docs/engineering/PR_SIZE_AND_MERGE_POLICY.md`: target 50–150 changed lines and up to 5 files; 151–200 lines only for one coherent independently testable unit and up to 5 files; a 201–300-line exception (up to 10 files) requires written approval before implementation; a 6–10-file exception at 200 lines or fewer also requires written approval before implementation. Line and file exceptions are independent. More than 300 lines or 10 files must be split. Tests and required documentation count toward the limits.
- When the original lane becomes actionable again (for example, review requests a code correction), the coordinator pauses or hands off the active design package before reactivating the original assignment. The helper never holds two active assignments. Preserve the design package's file reservation during pause/handoff; on terminal closure as DONE, CANCELLED or SUPERSEDED, explicitly release its file reservation while retaining its WBS history and recording any replacement ID.
- Design proposals require owner review before implementation lanes treat them as accepted. A mockup or design document is not implementation completion.
- The coordinator still owns cross-lane intake, deduplication, review, CI, merge sequencing and post-merge verification.

This rule applies whenever a lane would otherwise be idle for a cycle. It does not authorize parallel work on conflicting files or weaken the dependency order of P1 Core, P2 Backup & Recovery, P3 Bridge/Desktop.

## 13. Three-pass non-response escalation and design reassignment

- A coordinator pass is one completed eleven-workstream reconciliation cycle in which the assigned executor receives a concrete task, the current blocker/acceptance evidence is made available, and the coordinator checks for a substantive response or a new verifiable artifact.
- A substantive response means a code/document change, commit, review that resolves or precisely answers findings, a completed required check with inspectable logs, or a concrete blocker report with evidence and a next action. A bot status ping, reaction, repeated promise, unchanged PR head, or comment that does not address the requested work is not a substantive response.
- After THREE consecutive coordinator passes with no substantive response, the coordinator records the three pass dates/evidence and automatically transfers the task to GLAFIRA or another available qualified executor. The original bot/helper is removed from active ownership; preserve its branch/PR and history, but do not wait for it or permit overlapping edits.
- The replacement executor first checks the exact current main SHA, WBS reservation, dependencies, changed files and existing work. It then takes ownership in a dedicated branch and continues from the last verified state; no work is discarded or duplicated.
- The non-responsive bot/helper is reassigned one distinct, dependency-ready package from the separate GLAFIRA DESIGN project. The design package must have its own WBS leaf, issue, branch, allowed files, acceptance criteria and reservation. It must not overlap another active design reservation or replace the original implementation task's status.
- If the original executor responds after transfer, it must stop editing the transferred implementation. It may contribute review evidence only when explicitly requested by the coordinator.
- A genuine external dependency, unavailable permission, owner-only acceptance or infrastructure outage is a BLOCKED state, not proof of laziness. The coordinator still transfers actionable work where possible and records the exact blocker; design fallback is assigned only if no higher-priority ready task exists.
- This escalation rule does not weaken dependency order, exact-head CI, review, owner approval, physical-device acceptance or merge gates. No direct merge to main is authorized by the transfer itself.

## 14. Canonical reservation transaction and interrupted-delivery recovery

The execution ledger on protected `main` is the single canonical development-reservation register. The ledger is shared metadata, never an exclusive implementation file: unrelated work with disjoint implementation paths may proceed while ledger updates are serialized.

1. **One reservation writer:** the coordinator serializes reservation commits. Only one reservation-changing PR may be integrated at a time. Create each reservation branch from the exact current `main` SHA; before merge, refresh its base and reconcile the full ledger for duplicate WBS IDs, Work Item IDs, paths, active owners and dependencies. If `main` or the ledger changes, re-read and regenerate the reservation; do not merge a stale competing reservation.
2. **Atomic activation:** a reservation becomes active only after its ledger commit is merged to `main`. Before that merge it is `PROPOSED` / `PENDING_RESERVATION`; no implementation branch edits or dispatch are authorized. The owner may directly set priority, choose an executor or override a plan at any time, but the assignment is operationally active only after the same canonical reservation is recorded and merged. Owner authority is not constrained by the coordinator's recommendation.
3. **Shared ref / compare-and-set:** every reservation names the same canonical ref, `refs/heads/main`, and the exact base SHA read. A merge is accepted only if the branch is refreshed against latest main and the ledger diff still proves that the WBS leaf, Work Item ID and exclusive implementation paths are unreserved. A stale/non-fast-forward update is rejected and must be reconciled; never resolve a ledger conflict by choosing one side blindly.
4. **Delivery record:** persist a unique `deliveryId`, Work Item ID, executor, assignment generation, payload hash, idempotency key, timestamps, and each state transition in the ledger. Delivery state is separate from work status: `RESERVED_NOT_DISPATCHED`, `DISPATCHED_AWAITING_ACK`, `DISPATCH_ACKNOWLEDGED`, `REVOKING`, `REVOKED_FENCED`, `RELEASED`.
5. **Lost ACK:** while `DISPATCHED_AWAITING_ACK`, do not dispatch the same work to another executor and do not release its file reservation merely because an ACK timed out. Query/reconcile the same `deliveryId` and idempotency key. A retry to the same executor reuses that key and generation; it must not create a second assignment.
6. **Safe reassignment:** reassignment is allowed only after the prior executor's lease/grant has been revoked, revocation is confirmed, the old assignment generation is fenced so stale writes are rejected, and the executor is confirmed stopped or its execution environment is terminated. Record evidence, increment generation, transition through `REVOKING` to `REVOKED_FENCED`, then release/transfer the reservation in a new serialized ledger commit. If stop/revocation cannot be proven, keep the task `BLOCKED` and reservation held; do not guess.
7. **Interrupted handoff:** a new coordinator resumes `RESERVED_NOT_DISPATCHED`; reconciles `DISPATCHED_AWAITING_ACK` by delivery ID; treats `DISPATCH_ACKNOWLEDGED` as active work; and completes or resumes a recorded revocation. Every transition is idempotent and append-only. Never infer task completion from delivery acknowledgement.
8. **Acceptance walkthroughs:** record (a) two coordinators attempting to reserve the same WBS leaf from the same old SHA, showing only one reservation can activate; (b) a direct owner assignment passing through the same canonical ledger; (c) dispatch interruption before ACK followed by idempotent same-delivery reconciliation; and (d) lost ACK with failed revocation remaining BLOCKED, then successful fenced revocation before reassignment. Docs CI cannot substitute for these manual governance walkthroughs.
