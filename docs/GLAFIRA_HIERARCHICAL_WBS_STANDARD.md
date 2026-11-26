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
7. Work Item IDs for Program-step tasks use `GLAFIRA-S<step>-W<sequence>`. Explicitly owner-approved cross-step governance/design tasks may use `GLAFIRA-GOV-<domain>-W<sequence>` only when the ledger records the approving owner decision, canonical WBS parent, issue/tracker and scope. This is a narrow identity-format exception, not an exemption from a unique executable WBS leaf.

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
- Independent siblings may share a predecessor already accepted as `DONE`; a common completed predecessor does not make siblings dependent on one another. Every unfinished predecessor must be DONE before its dependent leaf starts.
- Reservation writes are serialized against canonical `refs/heads/main`. A reservation is active only after its ledger commit merges to latest main; stale-base reservations must refresh and revalidate before dispatch. The ledger is shared metadata, not an exclusive implementation path.
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
- `1.1.3.1.1.1.4` — Restore/cancellation serialization acceptance: pending restore marker and a controlled maintenance-gate contention interleaving must fail closed, leave the task unchanged and verify cleanup. WBS predecessors: `.1` and `.2` DONE plus separate Backup cleanup leaf `1.2.1.2.2.1.1` DONE. `.4` owns its own instrumentation file; it is independent of `.3` and must never be stacked on or edit `.3` test file. `.3` may be accepted on its own evidence; the parent task remains open until `.4` is also accepted.

Each executable leaf must also have a numbered, reproducible procedure before source work:
- `.1` storage: (1) verify task/owner in one transaction; (2) compare owner ID, generation and state version; (3) enforce never-claimed allowlist; (4) record owner cancellation and close artifacts; (5) propagate same-project dependencies in bounded batches; (6) preserve uncertain external writes.
- `.2` UI/API: (1) capture ownership before biometric; (2) show task/project confirmation; (3) consume one-use biometric proof; (4) pass the captured fence to storage; (5) serialize with restore; (6) refresh UI with distinct outcomes.
- `.3` acceptance: (1) owner-bound fixtures; (2) allowed/disallowed state tests; (3) attempt and generation fencing; (4) project/dependency isolation and large fan-out; (5) unknown outcomes, zero-attempt plus unresolved-marker rejection, read-only expiry and deadline edges; (6) claim race, approval audit and repeated-cancellation idempotence; (7) exact-head JVM/emulator CI with named JUnit XML cases.
- `.4` restore race: (1) use a test-owned journal generation marker; (2) hold the maintenance gate for an active restore; (3) let cancellation pass its initial precheck before it attempts to acquire the held gate; (4) assert the gate acquisition fails closed and task state remains unchanged; (5) verify recovery; (6) preserve unrelated cache files and require ownership-aware production cleanup from Backup leaf 1.2.1.2.2.1.1; (7) exact-head instrumentation and post-merge validation.

Executor for each planned leaf: `micronizationlab`; independent reviewers: CodeRabbit and Proofline; final acceptance: project owner. Each leaf owns a separate instrumentation test file; there is no shared-file stacking or sequential reuse. Each leaf has a unique Work Item ID (`GLAFIRA-S093-W01-01` through `-04`). Branch names are recorded in the reservation, but no branch may be created until its post-predecessor exact Main SHA is recorded by a merged pin-amendment PR.

The parent reservation PR #696 is merged. This leaf-reservation PR must reserve all four executable leaves together before `.1` is unlocked. Each leaf receives a unique Work Item ID, branch name, executor, independent reviewer, exclusive source/test paths, numbered procedure, exact predecessors and independent acceptance gates. The docs-only reservation PR has separate merge gates: Docs CI, Security Scan, Project Dispatcher, independent documentation review with all blocking findings resolved, and owner approval; Android platform jobs are not required for this documentation-only reservation. After this reservation merges, a separate pin-amendment PR records the exact fresh `main` SHA for `.1` and merges before `.1` branch creation. `.2` is pinned only after `.1` is DONE and merged; `.3` only after `.1` and `.2` are DONE and merged; `.4` only after `.1`, `.2`, and Backup cleanup leaf `1.2.1.2.2.1.1` are DONE and merged. Each amendment is merged before the corresponding implementation branch is created or edited. Dependent branches are created from fresh Main containing all required predecessor commits; never pin a pre-reservation SHA or rely on a Git branch stack to substitute for a WBS predecessor. For cancellation, verify owner-Core identity and generation fencing for the target and every affected descendant, independently of biometric proof. Explicit state policy: `QUEUED`, `WAITING_FOR_NETWORK` and `WAITING_FOR_OWNER` are cancellable only when `attemptCount == 0` and no unresolved execution-outcome marker exists; any prior attempt, `RETRY_SCHEDULED`, or unknown/persistence-failure outcome is not cancellable. Test the allowlist and attempt-count guard independently. The `.4` restore/cancellation serialization check is mandatory and remains a separate leaf from `.3`. No slice may be marked DONE until its exact-head tests, review, merge and post-merge validation are complete; the parent task and Program Step 93 remain open until all required owner acceptance is satisfied.

## 11. Corrected owner-cancellation reservation and leaf lifecycle

This section supersedes only the conflicting branch/file/base instructions in the earlier Section 10 reservation; it does not rewrite historical evidence or claim implementation completion.

- The four leaves `.1`–`.4` are reserved together in the same docs-only reservation before `.1` may start. A common completed predecessor may be shared; sibling leaves do not depend on each other unless explicitly listed.
- **Unique Work Item IDs:** `.1` `GLAFIRA-S093-W01-01`; `.2` `GLAFIRA-S093-W01-02`; `.3` `GLAFIRA-S093-W01-03`; `.4` `GLAFIRA-S093-W01-04`. The parent `GLAFIRA-S093-W01` is not reused as a leaf ID.
- **Exclusive files:** `.1` owns `GlafiraTask.kt`, `GlafiraTaskStore.kt`, `GlafiraTaskPolicyTest.kt`, and `GlafiraTaskStoreCancellationInstrumentedTest.kt`; `.2` owns `GlafiraCoreOwnerApprovalStore.kt`, `GlafiraCoreRuntimeOwner.kt`, `TaskQueueScreen.kt`, and `TaskQueueScreenCancellationInstrumentedTest.kt`; `.3` owns only `GlafiraTaskCancellationInstrumentedTest.kt`; `.4` owns only `GlafiraTaskRestoreCancellationInstrumentedTest.kt` plus no production files.
- **Dependencies:** `.1` has no WBS predecessor. `.2` depends on `.1` DONE. `.3` depends on `.1` and `.2` DONE. `.4` depends on `.1` and `.2` DONE and the separate Backup production cleanup leaf `1.2.1.2.2.1.1` DONE. `.3` is not a predecessor of `.4`. The old reference to PR #703 is invalid: PR #703 is CLOSED UNMERGED, so it is not evidence of a completed predecessor and must not be treated as merged.
- **Fresh base pinning:** the docs reservation merge SHA cannot be its predeclared child branch base. After the reservation merges, pin `.1` to the then-current Main SHA in a separate merged ledger amendment. Pin each dependent leaf only after all its required predecessor merges; the amendment records the exact Main SHA that contains those commits. No child branch is created or edited before its pin amendment merges.
- **Storage acceptance (.1):** because `GlafiraTaskStore` uses Android SQLite, transaction/fan-out/artifact-preservation acceptance must run in the named AndroidJUnit4 instrumented test on a managed emulator. A JVM-only policy test is supplementary, not sufficient. Require named XML evidence for owner/generation fencing, same-project descendants only, bounded fan-out, unknown-outcome preservation, exact-deadline handling, actor trace, and artifact/review-rejection preservation. A non-empty unrelated XML file is not acceptance.
- **UI/API acceptance (.2):** the owned instrumentation test file must prove captured owner/generation/state-version binding, one-use biometric session, restore guard, project-specific confirmation, non-blocking UI and distinct cancelled/stale/blocked results. Its tests are part of `.2`, not borrowed from sibling leaves.
- **Policy acceptance (.3):** separately test the state allowlist and attempt-count guard; specifically reject `QUEUED` with `attemptCount == 0` when an unresolved execution-outcome marker exists; verify repeat cancellation is idempotent; cover stale generation/state version, wrong owner/Core, cross-project isolation, claim/cancel race and audit preservation. Require the exact named test cases in the managed-emulator JUnit XML.
- **Restore acceptance (.4):** force a controlled interleaving in which cancellation passes its initial marker precheck, then attempts to acquire the maintenance gate while restore holds it. Assert that the lock acquisition—not merely the marker precheck—causes fail-closed rejection and no task mutation. Preserve unrelated cache files; do not attempt to prove this while production cleanup still deletes files by name alone. The Backup cleanup predecessor must first make cleanup ownership-aware.
- **Reservation merge gates:** exact-head Docs CI, Security Scan and Project Dispatcher; independent CodeRabbit/Proofline documentation review with every blocking finding resolved; owner-controlled merge. Platform/JVM/emulator gates apply to corresponding implementation PRs and must actually execute there. No skipped/cancelled job counts as pass.
- **Status:** leaf reservation pending its own exact-head documentation gates and review. All four leaves are RESERVED IN PLAN but not active implementation assignments until this reservation merges; `.1` remains BLOCKED from branch creation until its separate exact-base pin amendment merges. No Program Step 93 completion is claimed.