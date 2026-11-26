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


## 12. Canonical coordinator-governance WBS registrations

The following owner-approved cross-step governance work belongs to Workstream 11. These are governance tasks, not claims that any Program 150 implementation step is complete. For work-item identity, `S000` is reserved for explicitly owner-approved cross-step governance work that has no single legacy Program 150 step; it does not imply legacy Program Step 0.

- `1.11.2` — Coordinator execution governance.
- `1.11.2.1` — Cycle serialization and work assignment.
- `1.11.2.1.1` — Design fallback and ready-work governance; subtask `1.11.2.1.1.1`; executable leaf `1.11.2.1.1.1.1`; Work Item `GLAFIRA-S000-W01`; issue #685. This is the canonical corrected registration for PR #701.
- `1.11.2.1.2` — Eleven-workstream batch-cycle governance; subtask `1.11.2.1.2.1`; executable leaf `1.11.2.1.2.1.1`; Work Item `GLAFIRA-S000-W02`; issue #548. This is the canonical corrected registration for PR #721.

For both leaves, the owner is the priority/override authority; GLAFIRA is the operational coordinator; independent review is separate from authorship; the allowed files and exact base SHA must be recorded in the canonical ledger before edits. Dispatcher output is evidence only. Both Action Pools, issues, PRs, helper intake, branches and reservations must be reconciled.

### Batch-cycle acceptance and sequencing

- The coordinator may plan 3–5 real executable leaves per lane where the dependency graph permits; this is a planning target, not a quota. Do not fabricate steps, duplicate work or bypass predecessors.
- Each helper has at most one active assignment. A blocked or transferred assignment is suspended/handed off first, with its original status preserved and its reservation transferred; only then may the helper receive another assignment. A replacement taking over a task cannot also receive a DESIGN or second global-ready assignment.
- DESIGN fallback is eligible only after the complete ready-work scan finds no equal- or higher-priority dependency-ready implementation, validation, intake, review, merge or post-merge item.
- Preserve reservations through active, blocked, suspended and transferred states. Release only on verified DONE or authorized terminal CANCELLED/SUPERSEDED, with reason and replacement recorded.
- Cross-workstream tasks retain their owning Core/Workstream; they are not collapsed into a single runtime Core queue. Workstream 11 is an integration/intake view, not a second durable assignment queue.
- Build/release lanes remain globally serialized by repository/workflow/lane/configuration. PR-specific concurrency groups do not prove serialization.
- The cycle report records numeric counts per lane for planned, started, DONE, blocked, in-review, CI-passed, merged and post-merge-verified leaves. Sub-actions are not separate WBS steps.
- Acceptance requires consistency across Execution Control, Completion Plan, Ledger, AGENTS.md and Copilot instructions; exact-head docs/security/dispatcher checks; independent review; owner approval; and post-merge verification.

### Windows Desktop / Hub UX design registration

- `1.11.1.1.6` — Windows Desktop / Hub workspace UX task.
- `1.11.1.1.6.1` — subtask: information architecture and screen inventory.
- `1.11.1.1.6.1.1` — executable design deliverable: `docs/design/WINDOWS_DESKTOP_HUB_UX.md`; Work Item `GLAFIRA-S000-W03`; issue #674 / umbrella #672.
- This is an explicitly owner-approved cross-step DESIGN fallback task, not a Program 150 implementation step. It may be assigned only when the source lane has no eligible equal- or higher-priority ready work; its source lane's BLOCKED/NOT RUN/other outcome remains unchanged.
- The six-component subtask is not itself an executable leaf. The seven-component ID above is the only executable reservation target. Drafting requires a separate pre-edit implementation reservation recording the exact current main SHA, implementation branch, executor, independent reviewer, exclusive file and acceptance.
- Shared visual tokens remain PENDING until #679 is accepted. Information architecture may proceed only if issue #674 explicitly records the token-pending marker. Retain the ICQ flower in the product identity and acceptance criteria.


## 13. Reservation corrections — Core cancellation leaves and Windows Hub UX design

This section is the current proposed correction to the historical #717 and #718 reservation snapshots. It supersedes conflicting same-file stacking, premature branch-base, WBS-depth and fallback wording in those snapshots. It does not claim either reservation is merged or any implementation is complete.

### Core owner-cancellation leaves — Program Step 93

Parent task: `1.1.3.1.1` / Work Item `GLAFIRA-S093-W01` / issue #669. The seven-component executable leaves are:

- `1.1.3.1.1.1.1` — `.1` storage/state primitive.
- `1.1.3.1.1.1.2` — `.2` owner-authenticated API/Android queue UX; predecessor .1 DONE.
- `1.1.3.1.1.1.3` — `.3` policy/instrumentation acceptance; predecessors .1 and .2 DONE.
- `1.1.3.1.1.1.4` — `.4` restore/cancellation serialization acceptance; predecessors .1 and .2 DONE **and** production recovery-temp isolation PR #703 merged and recorded DONE. .4 does not depend on .3.

File ownership is exclusive and non-overlapping: .1 owns its storage test file; .2 owns its UI test file; .3 owns `GlafiraTaskCancellationInstrumentedTest.kt`; .4 owns `GlafiraTaskRestoreCancellationInstrumentedTest.kt`. .3 and .4 are independent sibling acceptance leaves after .1/.2. They must not stack, share, or extend each other's test file. A Git branch stack never changes the WBS dependency graph.

#### .1 mandatory acceptance identities

The exact managed-emulator JUnit XML for .1 must contain passing named cases for:
- `sameProjectDescendantsOnlyAreFailed`;
- `largeDependencyFanOutUsesBoundedBatches`;
- artifact preservation for unrelated project/task records;
- each allowed waiting state with `attemptCount == 0` and no unresolved outcome marker;
- separate negative cases for `QUEUED`, `WAITING_FOR_NETWORK` and `WAITING_FOR_OWNER` with `attemptCount == 0` **and** an unresolved execution-outcome marker, proving rejection and no task mutation;
- stale owner generation/state version and prior-attempt rejection.

A non-empty XML file or unrelated passing instrumentation is insufficient. The evidence must name the cases above in the exact-head emulator result.

#### .3 mandatory idempotence acceptance

The exact managed-emulator JUnit XML must contain a named repeated-cancellation case (for example `repeatedOwnerCancellationIsIdempotent`) proving the second identical cancellation creates no additional task-history, Trace, dependency, or artifact writes and does not change the terminal state.

#### .4 mandatory restore contention and cleanup acceptance

- The test must exercise actual maintenance-gate contention, not merely observe a pre-existing restore marker. Use a controlled interleaving: cancellation completes its initial precheck; restore acquires and holds the maintenance gate and publishes its journal marker; cancellation then attempts to acquire the held gate and is demonstrably blocked; after the gate is released, cancellation rechecks under the gate, rejects because restore is pending, and leaves task state/history/Trace/artifacts unchanged.
- The .4 exact JUnit XML must contain named cases for lock contention, stale-precheck rejection, complete task/file restoration, unrelated recovery-temp preservation, and rollback-key preservation when journal cleanup fails.
- Unowned `glafira-rollback-recovery-*.tmp` preservation cannot be accepted until PR #703 supplies production ownership-aware cleanup and is merged. A test-only filter or test fixture workaround is not acceptable. This #703 predecessor applies only to .4, not .3.

#### Base-pin and branch-creation sequence

A reservation PR cannot know its own future merge SHA. Therefore:
1. Merge the parent reservation PR only after Docs CI, Security Scan, Dispatcher, independent CodeRabbit/Proofline review, resolution of every blocking finding and owner approval.
2. A separate docs-only pin amendment records the exact resulting Main merge SHA. The pin amendment must merge before the implementation branch is created.
3. If Main advances with conflicting source changes before branch creation, submit another pin-refresh amendment recording the exact then-current Main SHA and required predecessor set; merge that amendment before creating the branch.
4. .1's pin amendment records the parent reservation merge SHA. .2's pin amendment records the exact .1 implementation merge SHA. .3's pin amendment records the exact .2 merge SHA after .1/.2 DONE. .4's separate pin amendment records an exact Main SHA containing .1, .2 and merged PR #703. .3 must not wait for #703.
5. Do not create even an empty implementation branch before its pin amendment merges. The pin value is the amendment's recorded Main SHA, not an assumed future SHA and not an obsolete predecessor PR head.

The WBS reservation checklist includes exact-head Docs CI, Security Scan, Dispatcher, complete independent-review resolution and owner approval. A reservation is not ready while any required finding remains open.

### Windows Desktop / Hub UX — GLAFIRA DESIGN fallback

- Parent task `1.11.1.1.6`; subtask `1.11.1.1.6.1`; executable leaf `1.11.1.1.6.1.1`; Work Item `GLAFIRA-S000-W03`; issue #674 / umbrella #672.
- This is explicitly owner-approved cross-step DESIGN work without a legacy Program 150 mapping. It is not an executable step at the six-component subtask depth.
- The assignment is not active for any source lane until the coordinator records that lane's exact original implementation/test/acceptance work item, current status, concrete blocker, evidence, coordinator-cycle date and why no equal/higher-priority work is eligible. Until those facts are recorded, source-lane eligibility is NOT VERIFIED and the fallback remains BLOCKED; do not presume the Token/GCU lane is blocked or assign it automatically.
- Before information architecture or wireframes begin ahead of shared tokens, issue #674 must visibly carry the explicit marker **VISUAL TOKENS: PENDING (#679)**. The coordinator must verify that marker at assignment time. Final colors, typography, surfaces and token values remain blocked until #679 is accepted.
- Product identity and acceptance must explicitly retain the ICQ flower; "ICQ 2003 identity" without the flower does not pass.
- The reservation PR must merge first. A separate pre-edit leaf-reservation record must then pin the exact current Main SHA, implementation branch, executor, independent reviewer, exclusive deliverable `docs/design/WINDOWS_DESKTOP_HUB_UX.md`, predecessors and acceptance. No drafting is authorized before that leaf reservation merges.
- Acceptance is design-only and cannot substitute for Windows Desktop build, Bridge, security, physical-device or owner acceptance.
