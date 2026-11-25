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


## 11. Multi-step batch cycles across all eleven workstreams

Hierarchy: `1.11.2` — Batch-cycle coordination; `1.11.2.1` — Governance and assignment cadence; executable leaf `1.11.2.1.1` — Document and enforce the eleven-lane batch-cycle cadence. Work Item: `GLAFIRA-GOV-BATCH-W01`.

- The coordinator plans a bounded sequential package for every one of the eleven permanent workstreams at the start of each cycle. The planning target is 3–5 genuine executable leaves per lane when the current backlog and dependency graph permit. This is not a quota: do not invent tasks, duplicate existing work, split acceptance artificially, or bypass a predecessor to reach the target.
- Preserve exactly one active bounded work item per helper. The coordinator may pre-plan multiple leaves, but assigns the next leaf only after the current one is completed and its evidence reviewed, or explicitly released as blocked.
- Independent leaf tasks may run concurrently only with distinct WBS IDs, executors, branches and exclusive file/behavior ownership. A dependent leaf waits for all required predecessors to be accepted and recorded DONE.
- A leaf is verified complete only when it reaches `DONE` under the canonical definition (acceptance, review, exact-head CI, merge and post-merge checks). Keep file/behavior reservations until `DONE`; after that, release ownership and assign the next eligible leaf immediately without waiting for the slowest lane. If a task is suspended or transferred before `DONE`, the coordinator must record the handoff and transfer/retain its exclusive reservation so no second writer can overlap. The helper never self-selects or changes lanes.
- A blocked lane first receives eligible ready work at equal or higher owner priority (implementation, validation, intake, review, merge or post-merge verification). Only when none exists may the coordinator suspend/transfer the blocked assignment with its file reservation preserved, then assign a separate bounded GLAFIRA DESIGN leaf from issue #672 to an available helper. Preserve the original blocker/status; design is separate work and never substitutes for the blocked acceptance. When unblocked, resume the primary package under an explicit reservation handoff.
- Workstream 11 maintains the cross-lane queue, helper intake, deduplication, reservations, integration review and evidence ledger. It must not serialize unrelated implementation work.
- Track planned, assigned, completed, blocked, in-review, CI-passed and merged leaves per lane. Metrics count only evidence-backed outcomes.
- This cadence does not relax security, owner approval, exact-head CI, serialized build/release, review, PR-size, dependency or protected-Main rules.


**Executable procedure for `1.11.2.1.1`:** (1) refresh all eleven lane queues and exact Main/PR/CI state; (2) map existing work to permanent WBS leaves and remove duplicates; (3) build each lane's dependency-ready sequential package, targeting 3–5 real leaves only where available; (4) reserve one active leaf per helper and exclusive files/behavior before dispatch; (5) dispatch independent leaves concurrently and hold dependent leaves; (6) retain file ownership until the leaf is DONE after merge and post-merge checks; (7) on a formal handoff, transfer or retain the reservation and prohibit the prior executor from editing; (8) on block, scan the global ready queue, then assign a unique #672 design fallback only to an available helper; (9) resume the primary lane through an explicit reservation handoff; (10) record planned/assigned/completed/blocked/review/CI/merged counts and exact evidence. Acceptance: all five governance instruction sources agree, no second queue is introduced, and no existing one-active-item/dependency/merge rule is weakened.


### Registered hierarchy for `GLAFIRA-GOV-BATCH-W01`

- `1.11.2` — Multi-lane batch-cycle coordination.
- `1.11.2.1` — Governance and assignment cadence.
- `1.11.2.1.1` — Document and enforce the eleven-lane batch-cycle cadence (Work Item `GLAFIRA-GOV-BATCH-W01`).

