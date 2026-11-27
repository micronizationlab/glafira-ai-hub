# GLAFIRA hierarchical work breakdown and step-order standard

**Status:** mandatory project governance rule  
**Owner:** Валерий Корчагин  
**Coordinator:** GLAFIRA  
**Applies to:** every human contributor, AI assistant, coding agent, reviewer, helper and all eleven workstreams  
**Canonical execution control:** `docs/GLAFIRA_EXECUTION_CONTROL.md`  
**Canonical history:** `docs/GLAFIRA_EXECUTION_LEDGER.md`

## 1. Canonical hierarchy

The entire GLAFIRA program uses one permanent dotted-decimal hierarchy. The root is project **1 — GLAFIRA**. Every child is numbered by appending the next integer to its parent's identifier.

| Level | Meaning | Example |
|---|---|---|
| 1 | Main project | `1 GLAFIRA` |
| 1.1 | Subproject | `1.1 Core` |
| 1.1.1 | Chapter within that subproject | `1.1.1 Identity and ownership` |
| 1.1.1.1 | Section | `1.1.1.1 Owner identity` |
| 1.1.1.1.1 | Task | `1.1.1.1.1 Define owner identity contract` |
| 1.1.1.1.1.1 | Subtask | `1.1.1.1.1.1 Add persistence migration` |
| 1.1.1.1.1.1.1 | Executable step | `1.1.1.1.1.1.1 Add migration test` |

The same rule continues to any depth needed. Do not invent a second numbering style for chapters, tasks, subtasks or individual actions.

## 2. Root project and eleven permanent subprojects

The root is always `1 GLAFIRA`. Its first-level subprojects map to the eleven permanent workstreams:

- `1.1` Core and owner authority
- `1.2` Backup and Recovery
- `1.3` Desktop Bridge and device pairing
- `1.4` Windows Desktop and Hub
- `1.5` Android application
- `1.6` iOS application
- `1.7` CI/CD, security and release engineering
- `1.8` Windows Agent
- `1.9` Commercial Network and compute economics
- `1.10` Multi-model runtime and specialist roles
- `1.11` Helper intake, review and integration

A workstream may contain many chapters, sections, tasks, subtasks and steps. The workstream list is stable; owner-approved scope changes may add children but must not silently repurpose an existing identifier.

## 3. Identifier stability and compatibility

1. Every node receives one unique permanent ID when it is first approved.
2. Never renumber an existing node because of reordering, cancellation, changed priority or a new architecture.
3. A cancelled or replaced node remains in the register as `CANCELLED` or `SUPERSEDED`, with a reason and replacement ID if applicable. Its number is never reused.
4. A split creates new child IDs beneath the original parent; it does not erase the original history.
5. Existing Program 150 acceptance IDs (for example `S016`) remain valid legacy acceptance references. Map each one to one or more WBS IDs in the plan/ledger; do not silently renumber or pretend the two namespaces are identical.
6. Every issue, branch, PR, commit, test report and ledger entry must include its WBS ID(s), plus any legacy Program Step ID(s).

## 4. Mandatory step-by-step execution

Every participant MUST work in this order:

1. Read this standard, execution control, current plan, ledger, relevant contracts and the assigned issue.
2. Refresh current `main`, open PRs, branches, changed files and exact-head CI; search for existing work before creating anything.
3. Identify the exact WBS leaf step being assigned. If it does not exist, propose its parent path and obtain coordinator/owner registration before implementation.
4. Confirm predecessor steps and dependencies. A step cannot start until every required predecessor is `DONE` with evidence.
5. Reserve the leaf step in the ledger before editing: executor, files, branch, base SHA, dependencies, acceptance criteria and tests.
6. Implement only that step. Do not combine unrelated steps or expand scope without updating the WBS and receiving approval.
7. Run the specified tests, inspect all failures and correct the source or test defects. Do not stop a test suite at its first failure if the suite can safely complete and produce a full defect list.
8. Record exact evidence and status in the ledger. A step is `DONE` only after its acceptance criteria, review, required exact-head CI, merge and post-merge checks are satisfied.
9. Only then start the next sequential step. If blocked, mark `BLOCKED`, record the reason and stop dependent work; do not silently skip ahead.
10. Report the completed step ID, evidence, remaining blocker and next eligible step to the coordinator.

## 5. Sequencing and parallel work

- Default execution within a task is sequential: `step N` before `step N+1`.
- Parallel work is allowed only for explicitly independent sibling tasks whose dependency graph, files, APIs, schemas and resources do not overlap.
- Every parallel item needs its own WBS leaf ID, owner, branch, file reservation and acceptance criteria.
- One logical behavior or shared file set has one active implementation owner. Reviewers may inspect but must not create competing implementations.
- The eleventh workstream must collect helper work, map it to WBS IDs, deduplicate it and reserve ownership before integration.
- No participant may bypass a predecessor, change the owner-approved priority, or merge its own work around the integration gates.

## 6. Required record for every executable step

Each leaf step must have:

- WBS ID and title;
- parent task and legacy Program Step mapping, if any;
- objective and explicit out-of-scope items;
- accountable executor and independent reviewer;
- predecessor IDs and dependency graph;
- allowed files and exclusive file ownership;
- branch, base SHA, PR and changed-file list;
- implementation procedure in numbered micro-steps;
- acceptance criteria and positive, negative, failure, concurrency and recovery tests as relevant;
- exact CI workflow/job/run IDs and log evidence;
- status: `PROPOSED`, `READY`, `RESERVED`, `IN_PROGRESS`, `BLOCKED`, `IN_REVIEW`, `MERGED`, `DONE`, `CANCELLED` or `SUPERSEDED`;
- merge SHA, post-merge validation and next eligible WBS ID.

A plan checkbox, document statement, green unrelated CI run or successful build alone is not completion evidence.

## 7. Change control

- The owner approves changes to project invariants, priorities, root/subproject structure, security boundaries and acceptance criteria.
- The coordinator maintains the canonical WBS and ledger, resolves duplicate assignments and enforces dependencies.
- Helpers may suggest a correction or architecture reset, but may not silently rewrite approved requirements.
- If evidence shows the current design is wrong, stop the affected dependent steps, create a numbered decision/reassessment task, compare options and migration/rollback costs, and record the owner's decision before implementation.
- Preserve working behavior and data. A rewrite is justified only when measured reliability, security, maintainability or delivery improves enough to justify migration risk.

## 8. Merge and reporting rules

The existing PR size, review, exact-head CI, owner approval, merge and post-merge rules in `docs/GLAFIRA_EXECUTION_CONTROL.md` remain mandatory. A WBS step does not become `DONE` merely because its PR merged. The ledger must link the exact PR, merge SHA, required CI runs, test evidence and any physical-device acceptance.

Every cycle report must state the current `main` SHA, WBS ID, legacy step mapping, executor, branch/PR, changed files, tests and logs, merge/post-merge status, blockers and next eligible step.

## 9. Worked example

```text
1 GLAFIRA
└── 1.1 Core
    └── 1.1.1 Owner identity and task authority
        └── 1.1.1.1 Owner identity persistence
            └── 1.1.1.1.1 Define identity contract
                └── 1.1.1.1.1.1 Add contract fields
                    └── 1.1.1.1.1.1.1 Add validation test
                    └── 1.1.1.1.1.1.2 Add serialization test
                └── 1.1.1.1.2 Implement migration
                    └── 1.1.1.1.2.1 Add migration code
                    └── 1.1.1.1.2.2 Verify upgrade and rollback
```

The next executable step is not eligible until its predecessor is accepted and recorded. This rule is mandatory for every participant and every workstream.


## 10. Core task queue lifecycle — owner cancellation WBS registration

The following owner-approved cross-step task is mapped to legacy Program Step 93 (task queue UI cancellation). This registration does not claim Step 93 complete.

- `1.1.3` — Core task queue and dispatcher.
- `1.1.3.1` — Task queue lifecycle.
- `1.1.3.1.1` — Owner cancellation of unclaimed tasks (Work Item `GLAFIRA-S093-W01`, issue #669).
- `1.1.3.1.1.1` — Implement the safe owner cancellation path.
- `1.1.3.1.1.1.1` — Storage/state primitive: eligible states, project-bound transaction, history, safe descendant propagation and unresolved-outcome preservation.
- `1.1.3.1.1.1.2` — Owner API and Android queue UX: fresh one-use biometric session, restore-lock guard, project-bound confirmation and non-blocking execution. Predecessor: `.1` DONE.
- `1.1.3.1.1.1.3` — Acceptance: policy JVM and Android instrumentation coverage for state/history/idempotence, owner-Core and generation fencing, project/dependency isolation, unknown outcomes, approval audit and claim/cancel races. Predecessors: `.1` and `.2` DONE.
- `1.1.3.1.1.1.4` — Restore/cancellation serialization acceptance: pending restore marker and stale-precheck/lock-contention must fail closed, leave the task unchanged and verify cleanup. WBS predecessors: `.1` and `.2` DONE. The implementation branch may be stacked on `.3` only to extend the same instrumentation file; this Git branch relationship does not make `.3` a WBS predecessor. `.3` may be accepted on its own evidence; the parent task remains open until `.4` is also accepted.

Each executable leaf must also have a numbered, reproducible procedure before source work:
- `.1` storage: (1) verify task/owner in one transaction; (2) compare owner ID, generation and state version; (3) enforce never-claimed allowlist; (4) record owner cancellation and close artifacts; (5) propagate same-project dependencies in bounded batches; (6) preserve uncertain external writes.
- `.2` UI/API: (1) capture ownership before biometric; (2) show task/project confirmation; (3) consume one-use biometric proof; (4) pass the captured fence to storage; (5) serialize with restore; (6) refresh UI with distinct outcomes.
- `.3` acceptance: (1) owner-bound fixtures; (2) allowed/disallowed state tests; (3) attempt and generation fencing; (4) project/dependency isolation and large fan-out; (5) unknown outcomes, read-only expiry and deadline edges; (6) claim race and approval audit; (7) exact-head JVM/emulator CI.
- `.4` restore race: (1) create a test-owned journal generation marker; (2) prepare restore under maintenance gate; (3) simulate stale cancellation; (4) assert task unchanged; (5) verify file recovery; (6) delete journal before key and preserve key on cleanup failure; (7) exact-head instrumentation and post-merge validation.

Executor for each planned leaf: `micronizationlab`; independent reviewers: CodeRabbit and Proofline; final acceptance: project owner. Steps `.3` and `.4` share the instrumentation path sequentially under the same executor; this is not concurrent ownership. Branch names and base SHAs are assigned only in the follow-on leaf-reservation PR.

The parent reservation PR must be merged to Main. A separate leaf-reservation PR must then assign each executable leaf's unique branch, exact base SHA, executor, reviewer, file ownership, numbered procedure, predecessor and independent acceptance gates; that leaf-reservation PR must also be merged to Main before any source edit. The storage, UI/API and acceptance slices are separate focused PRs, each retaining the same Work Item ID and exact executable-step ID. Record each leaf's executor, branch, base, procedure, predecessor and independent acceptance gates in the ledger before future implementation begins; never apply a dependent leaf's gate to its predecessor. For cancellation, verify owner-Core identity and generation fencing for the target and every affected descendant, independently of biometric proof. Explicit state policy: `QUEUED`, `WAITING_FOR_NETWORK` and `WAITING_FOR_OWNER` are cancellable only when `attemptCount == 0` and no unresolved execution-outcome marker exists; any prior attempt, `RETRY_SCHEDULED`, or unknown/persistence-failure outcome is not cancellable. Test the allowlist and attempt-count guard independently. The `.4` restore/cancellation serialization check is mandatory and remains a separate leaf from `.3`. No slice may be marked DONE until its exact-head tests, review, merge and post-merge validation are complete; the parent task and Program Step 93 remain open until all required owner acceptance is satisfied.


## 11. Defect discovery and immediate repair — owner-approved Codex rule

- `1.7.1` — Defect prevention and repair execution.
- `1.7.1.1` — Consolidated defect repair.
- `1.7.1.1.1` — Repair actionable defects in the same execution cycle.
- `1.7.1.1.1.1` — One-pass defect register, implementation and evidence.
- `1.7.1.1.1.1.1` — Apply the mandatory GLAFIRA CODEX to all eleven workstreams and all participants with write authority. Normative procedure: `docs/GLAFIRA_CODEX.md`. This registration does not authorize bypassing ownership, dependencies, review, CI or owner approval.


## 12. WBS reservation uniqueness and strict-main merge safety

The repository remains owned by the personal GitHub account. Native GitHub Merge Queue and organization transfer are explicitly out of scope. The selected control is the owner-configured Protect main ruleset requiring branches to be up to date before merge, combined with a required pull-request status check. The checker MUST compare the proposed merge candidate with the exact protected-Main base; parsing the candidate ledger alone is insufficient.

- 1.7.1.2 — PR reservation and merge safety.
- 1.7.1.2.1 — Validate active WBS reservations against the protected Main ledger.
- 1.7.1.2.1.1 — Implement strict-main reservation uniqueness check.
- 1.7.1.2.1.1.1 — Add the required GLAFIRA WBS Reservation Uniqueness PR check, parser and regression fixtures (Work Item GLAFIRA-GOV-WBS-W01, issue #736).

This leaf has no legacy Program 150 mapping and has no WBS predecessors; its implementation reservation is recorded in the execution ledger before source edits. Its Work Item ID is registered in the ledger top-level registry. WBS leaf IDs are globally unique. A Work Item ID identifies a registered parent task and MAY therefore appear on multiple distinct executable leaf reservations only when every such leaf is explicitly nested under that same registered task; the checker must reject reuse across different registered tasks. A legacy active reservation without a Work Item ID remains protected during migration: derive a stable legacy key from its canonical WBS ID plus tracker reference, report it as legacy, and reject ambiguous or missing WBS/tracker identity. Do not silently drop or downgrade it.

The checker MUST compare the candidate ledger to the exact protected-Main ledger. Existing reservation history is immutable: a PR may append new reservations and may make only a valid terminal transition; it must reject deletion, reassignment, path weakening, or downgrade of an existing active reservation. A terminal transition is valid only when the record includes terminal status, evidence that the branch is quiesced, and evidence that write access was revoked. If any evidence is absent or ambiguous, preserve the reservation as active. It must report exact base and head SHAs checked.

Exclusive path overlap is rejected by default. A narrow sequential-sharing exception is allowed only for explicitly registered sibling leaves of the same parent task, with the same executor, a declared predecessor relationship, and evidence that the earlier leaf is complete before the later leaf starts. This exception permits the known sequential reuse of the shared Android instrumentation file by GLAFIRA-S093-W01 leaves .3 and .4; it does not permit concurrent ownership or any other overlapping paths. Regression fixtures must prove both allowed sequential reuse and rejected concurrent reuse. Repeated Work Item IDs are allowed only for leaves of that same registered parent task, including the separately reserved GLAFIRA-S093-W01 leaves; collisions across tasks remain errors.

The checker must fail closed on malformed or ambiguous records; reject duplicate active WBS leaf IDs, conflicting Work Item IDs, and overlapping exclusive paths; and support the explicit compatibility and sequential-sharing rules above. Acceptance must include: (1) legacy active records without Work Item IDs remain protected; (2) removal/downgrade of a Main reservation is rejected; (3) a terminal transition without all three proofs is rejected; (4) authorized terminal transition is accepted; (5) same-task sequential shared-file use is accepted only after predecessor completion; (6) concurrent shared-file use is rejected; (7) same Work Item ID across different tasks is rejected; (8) same Work Item ID across leaves of one registered task is accepted; (9) base/head SHA identity is emitted.

Serialization premise and limit: after one PR merges, another PR based on the old Main SHA must be blocked by the required up-to-date branch rule. Updating the second branch triggers a fresh uniqueness check against the new Main ledger. This is a protected-branch serial merge protocol, not an atomic Merge Queue. A privileged branch-protection bypass remains outside the guarantee and requires stop-and-reconcile.
