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
- The eleven workstreams are independent delivery/release lanes by default. A cross-lane dependency is valid only when a specific API, schema, protocol, migration or artifact is technically required by the consumer; administrative sequencing, a shared parent issue or a shared documentation file is not enough.
- Independent PRs start from current Main and target Main. Do not stack PRs on unmerged PR branches unless the changes are inseparable parts of one deliverable and the technical dependency is documented. Do not create parent-reservation/child-reservation chains for unrelated work.
- Each accepted lane slice is released independently as soon as its own review, CI, security and acceptance gates pass. Do not hold it for unrelated lanes or bundle unrelated changes into one release. Keep cross-lane indexes in a separate integrator-owned update so shared docs do not block product releases.
- A lane with no concrete dependency-ready implementation/review/research task is assigned a standalone GLAFIRA DESIGN work item in that cycle, including deferred Multi-AI and Token/GCU. Preserve the implementation lane's DEFERRED/BLOCKED status; design is a separate deliverable, not implementation acceptance.
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


## 10. Core task queue lifecycle — owner cancellation WBS reservation

The following owner-approved task maps to legacy Program Step 93. This is a parent reservation only; it does not claim any child leaf implementation complete.

- `1.1.3` — Core task queue and dispatcher.
- `1.1.3.1` — Task queue lifecycle.
- `1.1.3.1.1` — Owner cancellation of unclaimed tasks; Work Item `GLAFIRA-S093-W01`; issue #669.
- `1.1.3.1.1.1` — **Implement the safe owner cancellation path** (registered six-component subtask).
- `1.1.3.1.1.1.1` — `.1` storage/state primitive: state allowlist, project-bound transaction, owner-Core/generation fencing for target and every affected descendant, history, bounded propagation and unresolved-outcome preservation.
- `1.1.3.1.1.1.2` — `.2` owner API and Android queue UX: fresh one-use biometric proof, captured ownership fence, project-bound confirmation, restore-lock guard and distinct outcomes. Predecessor: `.1` DONE.
- `1.1.3.1.1.1.3` — `.3` policy/instrumentation acceptance for state/history/idempotence, owner-Core and generation fencing, project/dependency isolation, unknown outcomes, read-only expiry, deadline edges, independent attempt guard, approval audit and claim/cancel races. Predecessors: `.1` and `.2` DONE.
- `1.1.3.1.1.1.4` — `.4` restore/cancellation serialization acceptance. Predecessors: `.1` and `.2` DONE **and** production recovery-temp ownership isolation PR #703 merged and recorded DONE. `.4` does not depend on `.3`; `.3` does not depend on #703.

### Independent leaf and file ownership

- `.1` owns `app/src/main/java/com/micronizationlab/glafira/GlafiraTask.kt`, `app/src/main/java/com/micronizationlab/glafira/GlafiraTaskStore.kt`, and `app/src/test/java/com/micronizationlab/glafira/GlafiraTaskPolicyTest.kt`.
- `.2` owns `app/src/main/java/com/micronizationlab/glafira/GlafiraCoreOwnerApprovalStore.kt`, `app/src/main/java/com/micronizationlab/glafira/GlafiraCoreRuntimeOwner.kt`, and `app/src/main/java/com/micronizationlab/glafira/TaskQueueScreen.kt`.
- `.3` exclusively owns `app/src/androidTest/java/com/micronizationlab/glafira/GlafiraTaskCancellationInstrumentedTest.kt`.
- `.4` exclusively owns `app/src/androidTest/java/com/micronizationlab/glafira/GlafiraTaskRestoreCancellationInstrumentedTest.kt`.
- `.3` and `.4` are independent sibling leaves after `.1` and `.2`. They must not stack, share, or sequentially extend the same instrumentation file. Git branch ancestry does not alter WBS dependency order.

### Exact state and acceptance policy

- Only `QUEUED`, `WAITING_FOR_NETWORK`, and `WAITING_FOR_OWNER` may be cancelled, and only when `attemptCount == 0` and no unresolved execution-outcome marker exists.
- `RETRY_SCHEDULED` is never cancellable. Any prior attempt, unknown outcome, persistence-failure outcome, or unresolved marker is not cancellable regardless of the task's apparent waiting state. Tests must reject these cases both with and without a marker where applicable.
- `.1` managed-emulator/JVM evidence must prove named cases for `sameProjectDescendantsOnlyAreFailed`, `largeDependencyFanOutUsesBoundedBatches`, unrelated project/task/artifact preservation, each allowed state with zero attempts and no marker, each allowed state with an unresolved marker, any prior attempt, `RETRY_SCHEDULED`, unknown outcome without a marker, and persistence-failure outcome. It must also prove owner-Core identity and generation fencing for the target and every affected descendant.
- `.2` must capture task ID, project ID, ownerCoreId, ownerGeneration and stateVersion **before** confirmation/biometric authentication; show the project-bound confirmation; then obtain a fresh short-lived one-use biometric proof bound to that captured tuple and action. Pass the captured fence unchanged to storage. If any captured field changes before commit, reject; never reread and silently substitute a new generation after authentication. Distinct UI outcomes: cancelled, already claimed/attempted, stale ownership, restore in progress, approval expired/reused, and persistence/error.
- `.3` exact managed-emulator JUnit XML must cover allowed/disallowed states, independent attempt-count guard, `readOnlyExpiry`, deadline edges, owner-Core/generation fencing, project and dependency isolation, large fan-out, unknown/persistence-failure outcomes, approval audit, claim/cancel race, history, and `repeatedOwnerCancellationIsIdempotent` proving replay adds no duplicate history/Trace/dependency/artifact writes.
- `.4` must exercise actual maintenance-gate contention: cancellation passes its initial precheck; restore acquires and holds the gate and publishes its journal marker; cancellation attempts the held gate and is demonstrably blocked; after release, cancellation rechecks under the gate, rejects because restore is pending, and leaves task/history/Trace/artifacts unchanged. Also prove complete task/file restore, unrelated recovery-temp preservation and rollback-key retention on journal-delete failure.
- Unowned-temp preservation is blocked until production PR #703 is merged; no test-only workaround. PR #703 is currently CLOSED UNMERGED, so `.4` remains BLOCKED while `.1`, `.2` and `.3` are not blocked by #703.

### Numbered reproducible procedures

- `.1` storage: (1) load task and owning Core in one transaction; (2) capture owner ID, generation, state version, attempt count and outcome marker; (3) verify the task and every affected descendant belong to the same owning Core/generation; (4) enforce the exact three-state allowlist and zero-attempt/no-marker condition; (5) reject RETRY_SCHEDULED, any prior attempt and unknown/persistence-failure outcomes; (6) record cancellation/history atomically; (7) propagate only same-project descendants in bounded batches; (8) preserve unrelated tasks and artifacts.
- `.2` API/UI: (1) capture task/project/owner/generation/stateVersion before confirmation; (2) show exact project/task confirmation; (3) request fresh biometric proof after confirmation; (4) bind and consume proof once against the captured tuple; (5) pass the captured fence unchanged to storage; (6) reject stale owner/generation/state or restore lock; (7) render the distinct outcomes listed above.
- `.3` acceptance: (1) owner-bound fixtures; (2) allowed/disallowed state and attempt cases; (3) owner-Core/generation fencing for target and descendants; (4) project/dependency isolation and bounded fan-out; (5) unknown outcomes, read-only expiry and deadline edges; (6) approval audit and claim/cancel race; (7) repeated-cancel idempotence; (8) exact-head JVM/emulator CI.
- `.4` restore race: (1) create a test-owned journal generation marker; (2) hold restore under maintenance gate; (3) allow cancellation initial precheck to complete; (4) assert cancellation blocks on the held gate; (5) release gate and assert cancellation rechecks/rejects without mutation; (6) verify task/file restoration and unrelated-temp preservation; (7) force journal deletion failure and verify key preservation; (8) exact-head instrumentation and post-merge validation.

### Reservation and exact-base sequence

- PR #738 has merged to Main as `f938fcb93fda6d00a03167eee1f2413e40af9ae9`. This parent reservation PR #752 was recreated from that exact resulting Main SHA; the ledger and duplicate-ownership state were re-read before the refresh. PR #752 remains a separate parent reservation and must pass its own review/CI/owner gates before any child implementation leaf is pinned or started.
- The parent reservation PR must merge first after Docs CI, Security Scan, Project Dispatcher, independent CodeRabbit/Proofline review, resolution of blocking findings and owner approval.
- A separate leaf-pin amendment must then merge before each implementation branch is created. `.1` pin records exact Main SHA after the parent reservation. `.2` pin records exact `.1` implementation merge SHA. `.3` pin records exact `.2` implementation merge SHA. `.4` pin records exact Main SHA containing `.1`, `.2` and merged PR #703.
- If Main advances incompatibly before a leaf branch is created, merge a pin-refresh amendment recording exact then-current Main SHA and full predecessor set before creating the branch. Do not create even an empty implementation branch before its pin amendment merges.
- Reservation PR is documentation-only and does not count emulator tests as passed. Emulator evidence is required on each implementation leaf's exact head.

### Current status

- Parent reservation: PROPOSED / IN_REVIEW in PR #752; predecessor #738 is merged, and this reservation is refreshed from exact Main SHA `f938fcb93fda6d00a03167eee1f2413e40af9ae9`. It remains inactive until its own merge gates pass and PR #752 is merged.
- `.1`, `.2`, `.3`: NOT STARTED pending separate pin amendments and predecessor acceptance.
- `.4`: BLOCKED until production PR #703 is merged and `.1`/`.2` are DONE.
- Program Step 93 and parent task remain open until every required leaf, review, merge and post-merge acceptance is complete.

## 12. Canonical coordinator-governance WBS registrations

The following owner-approved cross-step governance work belongs to Workstream 11. These are governance tasks, not claims that any Program 150 implementation step is complete. For work-item identity, `S000` is reserved for explicitly owner-approved cross-step work (governance, design fallback or another cross-program task) that has no single legacy Program 150 step; it does not imply legacy Program Step 0.

- `1.11.2` — Coordinator execution governance.
- `1.11.2.1` — Cycle serialization and work assignment.
- `1.11.2.1.1` — Design fallback and ready-work governance; subtask `1.11.2.1.1.1`; executable leaf `1.11.2.1.1.1.1`; Work Item `GLAFIRA-S000-W01`; issue #685. This is the canonical corrected registration for PR #701.
- `1.11.2.1.2` — Eleven-workstream batch-cycle governance; subtask `1.11.2.1.2.1`; executable leaf `1.11.2.1.2.1.1`; Work Item `GLAFIRA-S000-W02`; issue #548. This is the canonical corrected registration for PR #721.

For both leaves, the owner is the priority/override authority; GLAFIRA is the operational coordinator; independent review is separate from authorship; the allowed files and exact base SHA must be recorded in the canonical ledger before edits. Dispatcher output is evidence only. Action Pool A, issues, PRs, helper intake, branches and reservations must always be reconciled. Pool B is checked only if it exists or when Pool A is exhausted and Pool B is due to be created.

- `1.11.2.1.4` — Action Pool A priority and readiness reconciliation; subtask `1.11.2.1.4.1`; executable leaf `1.11.2.1.4.1.1`; Work Item `GLAFIRA-S000-W04`; issue #548. This is a WBS registration only; no active reservation or file edit is authorized until a separate exact-SHA reservation PR merges.
- `1.11.2.1.5` — contributor PR-size exception policy alignment; subtask `1.11.2.1.5.1`; executable leaf `1.11.2.1.5.1.1`; Work Item `GLAFIRA-S000-W05`; issue #548. This is a WBS registration only; `CONTRIBUTING.md` remains unreserved and must not be edited until its separate exact-SHA reservation PR merges.

### Batch-cycle acceptance and sequencing

- The coordinator may plan 3–5 real executable leaves per lane where the dependency graph permits; this is a planning target, not a quota. Do not fabricate steps, duplicate work or bypass predecessors.
- Each helper has at most one active assignment. A blocked or transferred assignment is suspended/handed off first, with its original status preserved and its reservation transferred; only then may the helper receive another assignment. A replacement taking over a task cannot also receive a DESIGN or second global-ready assignment.
- DESIGN fallback is eligible only after the complete ready-work scan finds no equal- or higher-priority dependency-ready implementation, validation, intake, review, merge or post-merge item.
- Clarification: a lane with no assigned concrete task, including a deliberately deferred lane, is not exempt from fallback. After scanning its own ready backlog and finding no authorized task it can execute this cycle, assign that lane a standalone DESIGN leaf. In particular, Multi-AI/specialists and Token/GCU receive design work while runtime implementation remains deferred. This does not change their implementation status.
- DESIGN work must be independently mergeable from current Main and must not be chained behind unrelated token-foundation, desktop, Core or other design PRs. A package may state provisional assumptions and be reconciled after integration; it must still deliver a complete, reviewable artifact.
- Preserve reservations through active, blocked, suspended and transferred states. Release only on verified DONE or authorized terminal CANCELLED/SUPERSEDED, with reason and replacement recorded.
- Cross-workstream tasks retain their owning Core/Workstream; they are not collapsed into a single runtime Core queue. Workstream 11 is an integration/intake view, not a second durable assignment queue.
- Global cross-PR build serialization is currently NOT IMPLEMENTED: current workflows use per-PR/per-ref groups or no workflow-level concurrency. Until a durable non-dropping FIFO queue/lock is implemented and verified (tracked in issue #744), parallel platform build dispatch remains BLOCKED. A fixed GitHub Actions group with one replaceable pending slot is not sufficient.
- The PR-size policy permits a separate file-count-only exception for one coherent task of up to 200 changed lines and 6–10 files, only with written owner/integrator approval before implementation. This is independent of the 201–300-line exception.
- The cycle report records numeric counts per lane for planned, started, DONE, blocked, in-review, CI-passed, merged and post-merge-verified leaves. Sub-actions are not separate WBS steps.
- Acceptance requires consistency across Execution Control, Completion Plan, Ledger, AGENTS.md and Copilot instructions; exact-head docs/security/dispatcher checks; independent review; owner approval; and post-merge verification.

### Windows Desktop / Hub UX design registration

- `1.11.1.1.6` — Windows Desktop / Hub workspace UX task.
- `1.11.1.1.6.1` — subtask: information architecture and screen inventory.
- `1.11.1.1.6.1.1` — executable design deliverable: `docs/design/WINDOWS_DESKTOP_HUB_UX.md`; Work Item `GLAFIRA-S000-W03`; issue #674 / umbrella #672.
- This is an explicitly owner-approved cross-step DESIGN fallback task, not a Program 150 implementation step. It may be assigned only when the source lane has no eligible equal- or higher-priority ready work; its source lane's original status remains unchanged. Current source lane #123 is OPEN and READY AFTER the governance/WBS reservation gate, so W03 is NOT ACTIVE / NOT ASSIGNED and no design branch/draft is authorized yet. Re-evaluate only after a concrete blocker and complete ready-work scan are recorded on issue #674.
- The six-component subtask is not itself an executable leaf. The seven-component ID above is the only executable reservation target. Drafting requires a separate pre-edit implementation reservation recording the exact current main SHA, implementation branch, executor, independent reviewer, exclusive file and acceptance.
- Shared visual tokens remain PENDING until #679 is accepted. Information architecture may proceed only if issue #674 explicitly records the token-pending marker. Retain the ICQ flower in the product identity and acceptance criteria.


## 11. Defect discovery and immediate repair — owner-approved Codex rule

- `1.7.1` — Defect prevention and repair execution.
- `1.7.1.1` — Consolidated defect repair.
- `1.7.1.1.1` — Repair actionable defects in the same execution cycle.
- `1.7.1.1.1.1` — One-pass defect register, implementation and evidence.
- `1.7.1.1.1.1.1` — Apply the mandatory GLAFIRA CODEX to all eleven workstreams and all participants with write authority. Normative procedure: `docs/GLAFIRA_CODEX.md`. This registration does not authorize bypassing ownership, dependencies, review, CI or owner approval.
