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

**Repair-on-discovery:** every executor/reviewer with write authority follows `docs/GLAFIRA_CODEX.md`. Defects discovered during inventory, implementation or validation are consolidated by root cause and fixed in the same cycle wherever authorized. Do not finish a cycle with finding-only comments or tests-only work when a source correction is possible. The cycle produces one repair register/report with the committed changes and evidence. Read-only reviewers hand off one deduplicated register to the named file owner.


1. **Inventory:** refresh main, PRs, branches, changed files and prior work.
2. **Define:** write hypothesis, source evidence, smallest change, risks, dependencies and tests.
3. **Reserve:** register Work Item ID and exclusive file/behavior ownership.
4. **Implement:** one logical change on one dedicated branch from fresh main (or documented dependency branch).
5. **Review diff:** verify only intended files changed; compare against all open PRs and main; reject duplicate hunks/behavior.
6. **Test:** run targeted tests first. Expand only to required project checks.
7. **Build/CI queue:** enqueue once per unique commit SHA + workflow/lane. Never run parallel builds. Reuse existing completed/in-progress run for the same SHA and workflow.
8. **Inspect and repair evidence:** read job results and failure logs; aggregate all findings for the owned scope into one deduplicated repair register. Fix source/test/configuration defects in the same cycle, then rerun the relevant checks on the corrected SHA. Classify only genuine environment failures, cancellations, timeouts or external blockers as unresolved. Never stop at a test report when a safe correction is possible.
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
- Global cross-PR CI/build serialization is NOT IMPLEMENTED. Android is PR-keyed, iOS is ref-keyed and Windows workflows lack a shared queue. Issue #744 tracks the durable non-dropping FIFO queue/lock. Until it is implemented and verified, parallel platform build dispatch is BLOCKED; do not claim builds are globally serialized.
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

Respect the complete owner-approved order: P1 Core → P2 Backup & Recovery → P3 Bridge / Windows Desktop-Hub / Android client integration → P4 iOS and specialist-agent feature work → P5 non-issuing Token/GCU. Android and Windows Desktop are the primary runtime surfaces, but client work does not displace Core or Backup. Bridge/Hub prerequisites precede dependent Android pairing. All eleven streams remain active for non-conflicting work without bypassing dependencies or shared-file ownership.

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
- Each package has its own branch and focused PR and must follow `docs/engineering/PR_SIZE_AND_MERGE_POLICY.md`: target 50–150 changed lines, up to 5 files; 151–200 lines only for one coherent independently testable unit and up to 5 files; 201–300 only with written justification and explicit owner/integrator approval before implementation begins and up to 10 files; over 300 must be split. The file limit is independent of the line limit. Tests and required documentation count toward the limits.
- When the original lane becomes actionable again (for example, review requests a code correction), the coordinator pauses or hands off the active design package before reactivating the original assignment. The helper never holds two active assignments. Preserve the design package's file reservation during pause/handoff; on terminal closure as DONE, CANCELLED or SUPERSEDED, explicitly release its file reservation while retaining its WBS history and recording any replacement ID.
- Design proposals require owner review before implementation lanes treat them as accepted. A mockup or design document is not implementation completion.
- The coordinator still owns cross-lane intake, deduplication, review, CI, merge sequencing and post-merge verification.

This rule applies whenever a lane would otherwise be idle for a cycle. It does not authorize parallel work on conflicting files or weaken the dependency order of P1 Core, P2 Backup & Recovery, P3 Bridge/Desktop.

## 13. Three-pass non-response escalation and design reassignment

- A coordinator pass is one completed eleven-workstream reconciliation cycle in which the assigned executor receives a concrete task, the current blocker/acceptance evidence is made available, and the coordinator checks for a substantive response or a new verifiable artifact.
- A substantive response means a code/document change, commit, review that resolves or precisely answers findings, a completed required check with inspectable logs, or a concrete blocker report with evidence and a next action. A bot status ping, reaction, repeated promise, unchanged PR head, or comment that does not address the requested work is not a substantive response.
- After THREE consecutive coordinator passes with no substantive response, the coordinator records the three pass dates/evidence and automatically transfers the task to GLAFIRA or another available qualified executor. The original bot/helper is removed from active ownership; preserve its branch/PR and history, but do not wait for it or permit overlapping edits.
- The replacement executor first checks the exact current main SHA, WBS reservation, dependencies, changed files and existing work. It then takes ownership in a dedicated branch and continues from the last verified state; no work is discarded or duplicated.
- After transfer, rescan the full ready-work queue. Assign the returning helper the highest-priority eligible implementation, validation, intake, review, merge or post-merge task available. Use GLAFIRA DESIGN only when no equal- or higher-priority ready task exists; any design package must have its own WBS leaf, issue, branch, allowed files, acceptance criteria and reservation. It must not overlap another active design reservation or replace the original implementation task's status.
- If the original executor responds after transfer, it must stop editing the transferred implementation. It may contribute review evidence only when explicitly requested by the coordinator.
- A genuine external dependency, unavailable permission, owner-only acceptance or infrastructure outage is a BLOCKED state, not proof of laziness. The coordinator still transfers actionable work where possible and records the exact blocker; design fallback is assigned only if no higher-priority ready task exists.
- This escalation rule does not weaken dependency order, exact-head CI, review, owner approval, physical-device acceptance or merge gates. No direct merge to main is authorized by the transfer itself.

## 14. Canonical reconciliation — owner authority, reservations, batch dispatch and recovery

This section is the controlling clarification wherever earlier wording in this file, the completion plan, WBS standard, ledger, helper instructions or dispatcher guidance conflicts.

### Owner authority and priority

- The owner alone sets or changes priorities, directly assigns/reserves work, approves cross-priority exceptions, overrides assignments and stops work. GLAFIRA is the operational coordinator and performs queue reconciliation, selection and dispatch within the owner's current decisions. Neither a helper nor Project Dispatcher may assign work independently.
- The complete current owner-approved order is: **P1 Core → P2 Backup & Recovery → P3 Bridge / Windows Desktop-Hub / Android client integration → P4 iOS and specialist-agent feature work → P5 Token/GCU implementation**. Android and Windows Desktop are the primary runtime surfaces, but neither client feature work nor Bridge/Desktop integration displaces Core or Backup. Within P3, Bridge protocol/Hub host prerequisites precede dependent Android pairing/client work; independent Android UX work is eligible only after Core and Backup dependencies are clear. Token/GCU remains non-issuing and lower priority. Workstream 11 is cross-cutting intake/integration, not a product priority; it may perform the required review/CI/merge/post-merge action for an eligible higher-priority item before lower-priority implementation.
- A stale P0/P1/P4 ranking in an engineering dispatcher document is subordinate to this current owner-approved order until the owner changes it. The coordinator records the exact owner decision and its date when changing priority.

### Complete ready-work reconciliation

- Before every assignment, inspect both the engineering dispatcher Action Pool and the Workstream 11 helper-intake/integration pool, plus issues, PRs, review requests, branches, reservations, CI failures, queued/running jobs and post-merge actions. An Action Pool item remains actionable even if it has no issue, PR, branch or ledger reservation yet; register it before dispatch.
- The dispatcher is evidence only for PR/CI state. It is not the canonical queue, selector, reservation service or assignment authority. Workstream 11 is an intake/reconciliation view over existing GitHub/WBS records, not a second persisted runtime queue or Core authority.
- Dispatch the highest-ranked dependency-ready item under the owner priority order. Do not send a helper to DESIGN while equal- or higher-priority implementation, validation, intake, review, merge or post-merge work is ready. A lower-priority item may be selected only when no higher-priority eligible item exists and the assignment is recorded as such.

### Atomic reservation and single-flight dispatch

- The canonical reservation ledger is the append-only ledger on protected `refs/heads/main`. A reservation is not active and no work may be dispatched until its reservation record is merged into the current mainline ledger.
- All reservations, including direct owner assignments and overrides, use the same ledger-ref and the same compare-and-set rule: read the exact main SHA and ledger blob, prepare one reservation update, and submit it against that exact parent. If main advances or the expected blob/parent differs, reject the stale write, re-read and reconcile. Never accept two reservations based on the same stale ledger snapshot.
- Reservation commits are serialized: one reservation/integration merge at a time; after each merge, re-read main and scan for duplicate WBS IDs, Work Item IDs, executors and file/behavior ownership before the next reservation or dispatch. A stale branch or a green check on an old base is not a reservation. The current GitHub ruleset `Protect main` (27810315) enforces non-fast-forward protection and required status contexts, but its strict-check policy is false, it has no merge queue, and privileged repository roles can bypass it. Therefore the single-flight reservation gate is currently a coordinator/owner procedure, not a repository-enforced mutex: if an owner bypass, concurrent merge or unexpected Main advance occurs, stop dispatch, re-read the ledger and reconcile before proceeding. Do not claim the ruleset alone provides atomic reservation.
- The reservation record must identify the canonical WBS executable leaf, owner-approved cross-step mapping where applicable, Work Item ID, predecessor(s), accountable executor, independent reviewer, exact allowed files, branch, base SHA, acceptance and status. File ownership is scoped to the listed paths/behavior; recording a ledger entry does not reserve the entire ledger file against unrelated append-only reservations.

### Durable handoff and lost acknowledgement

- Persist every delivery transition in the ledger/assignment record: `RESERVED_NOT_DISPATCHED`, `DISPATCHED_AWAITING_ACK`, `DISPATCH_ACKNOWLEDGED`, `SUSPENDED`, `TRANSFERRED`, and terminal `DONE`, `CANCELLED` or `SUPERSEDED`. Each transition records actor, timestamp, exact task/WBS identity, generation/fencing token, recipient and evidence.
- For `RESERVED_NOT_DISPATCHED`, resume the same reservation or first record an authorized terminal `CANCELLED` or `SUPERSEDED` transition with reason and replacement ID (if any); only then release it. A reason-only release is not permitted. For `DISPATCHED_AWAITING_ACK`, do not blindly retry, release or reassign: first reconcile with the recipient. If the recipient cannot be reached, revoke its assignment lease/capability and advance the monotonic fencing generation in the canonical record; all subsequent writes from the old generation must be rejected. Only after revocation/fencing is durably committed may a replacement receive the work. An ACK alone is not proof of stop; it must identify the exact assignment and generation.
- Delivery retries are idempotent and retain the same assignment identity/idempotency key. Never create a second active assignment while the first generation remains valid. An unresolved or unknown handoff remains BLOCKED/IN_FLIGHT, not silently released.
- A coordinator restart must resolve every nonterminal delivery state from the durable record before issuing new work. Recovery is idempotent; repeated reconciliation cannot create duplicate assignments.

### One active assignment, transfer and DESIGN fallback

- Each helper has at most one active bounded assignment. A planned successor is not an assignment. Before a blocked assignment is suspended or transferred, record its original status and blocker, transfer its exclusive reservation, and fence the former executor from editing. Only then may the replacement receive one new item.
- After three consecutive completed coordinator passes without substantive, verifiable progress, GLAFIRA may take over the actionable implementation task; any substantive response resets the consecutive-pass count. Preserve the original task's status and history; the returning helper must stop editing it. The helper receives a distinct DESIGN assignment only after the transfer is complete and only if it has no other active assignment.
- A helper that takes over a transferred task must not also receive a DESIGN fallback or a second global-ready task in the same cycle. Re-evaluate its slot only after the transferred task is completed or formally suspended/handed off.
- Keep reservations while work is active, blocked, suspended or transferred. Release a reservation only after verified `DONE` or authorized terminal `CANCELLED`/`SUPERSEDED`; record the reason and replacement ID for the latter two. Never release merely because an executor missed an ACK or a task is temporarily blocked.

### Eleven-workstream cycle and build serialization

- Every cycle reconciles all eleven workstreams, but the 3–5 leaf target is aspirational and never a quota. Count only genuine executable leaves. Record numeric counts of planned, started, DONE, blocked, in-review, CI-passed, merged and post-merge-verified steps per lane; sub-actions are evidence within a leaf, not extra steps.
- Work may proceed concurrently only for independent leaves with separate WBS IDs, executors, branches and non-overlapping files/behavior. **Global cross-PR build serialization is currently NOT IMPLEMENTED.** Inspection of current Main found Android concurrency keyed by PR number, iOS keyed by ref, and no workflow-level concurrency on Windows Agent/deployment/Desktop Hub/UI workflows. These groups permit the same lane to run across different PRs. Until a durable non-dropping FIFO queue/lock is implemented and verified, global build serialization remains BLOCKED; do not claim it is enforced or schedule overlapping platform build lanes. A separate tracked CI queue task, issue #744, is required; it remains BLOCKED and unreserved until a WBS leaf and exact-base reservation are approved. GitHub Actions concurrency with one pending slot is not sufficient if it can replace/drop earlier pending runs.
- A leaf reaches DONE only after acceptance, independent review of the exact head, exact-head CI, authorized merge and post-merge checks. A PR, comment, started job, skipped job or partial green suite is not DONE.

### PR-size exceptions — one consistent approval deadline

- The binding size policy is `docs/engineering/PR_SIZE_AND_MERGE_POLICY.md`. Any 201–300-line exception requires written owner/integrator approval **before implementation starts**; approval at PR submission is too late. More than 300 changed lines must be split.
- The five-file limit is independent of line count. A file-count-only exception for 6–10 files and no more than 200 changed lines requires the same written approval before implementation, a single coherent change, exact file list and reason. It is not automatically granted by a line-count exception. More than 10 files must be split.
- All mandatory review steps, design fallback instructions and branch guidance must use these same deadlines and limits. No local wording may weaken or delay the binding approval gate.