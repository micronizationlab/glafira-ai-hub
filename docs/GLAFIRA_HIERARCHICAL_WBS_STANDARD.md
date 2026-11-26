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
- Parallel work is allowed across any workstreams for explicitly independent executable leaves when each has a unique WBS ID, executor, branch and exclusive file/behavior reservation; no unfinished predecessor dependency, overlapping API/schema/resource, or runtime authority conflict may exist. Siblings are not required if the cross-lane dependency graph is independent.
- Independent leaves may share a predecessor already accepted as DONE; a common completed predecessor does not make them dependent on one another. Any unfinished predecessor must be DONE before its dependent leaf starts.
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
- **Dependencies:** `.1` has no WBS predecessor. `.2` depends on `.1` DONE. `.3` depends on `.1` and `.2` DONE. `.4` depends on `.1` and `.2` DONE, Backup re-entry leaf `1.2.1.2.1` DONE, and the separate Backup production cleanup leaf `1.2.1.2.2.1.1` DONE. `.3` is not a predecessor of `.4`. The old reference to PR #703 is invalid: PR #703 is CLOSED UNMERGED, so it is not evidence of a completed predecessor and must not be treated as merged.
- **Fresh base pinning:** the docs reservation merge SHA cannot be its predeclared child branch base. After the reservation merges, pin `.1` to the then-current Main SHA in a separate merged ledger amendment. Pin each dependent leaf only after all its required predecessor merges; the amendment records the exact Main SHA that contains those commits. No child branch is created or edited before its pin amendment merges.
- **Storage acceptance (.1):** because `GlafiraTaskStore` uses Android SQLite, transaction/fan-out/artifact-preservation acceptance must run in the named AndroidJUnit4 instrumented test on a managed emulator. A JVM-only policy test is supplementary, not sufficient. Require named XML evidence for owner/generation fencing, same-project descendants only, bounded fan-out, unknown-outcome preservation, exact-deadline handling, actor trace, and artifact/review-rejection preservation. A non-empty unrelated XML file is not acceptance.
- **UI/API acceptance (.2):** the owned instrumentation test file must prove captured owner/generation/state-version binding, one-use biometric session, restore guard, project-specific confirmation, non-blocking UI and distinct cancelled/stale/blocked results. Its tests are part of `.2`, not borrowed from sibling leaves.
- **Policy acceptance (.3):** separately test the state allowlist and attempt-count guard; specifically reject `QUEUED` with `attemptCount == 0` when an unresolved execution-outcome marker exists; verify repeat cancellation is idempotent; cover stale generation/state version, wrong owner/Core, cross-project isolation, claim/cancel race and audit preservation. Require the exact named test cases in the managed-emulator JUnit XML.
- **Restore acceptance (.4):** force a controlled interleaving in which cancellation passes its initial marker precheck, then attempts to acquire the maintenance gate while restore holds it. Assert that the lock acquisition—not merely the marker precheck—causes fail-closed rejection and no task mutation. Preserve unrelated cache files; do not attempt to prove this while production cleanup still deletes files by name alone. The Backup cleanup predecessor must first make cleanup ownership-aware.
- **Reservation merge gates:** exact-head Docs CI, Security Scan and Project Dispatcher; independent CodeRabbit/Proofline documentation review with every blocking finding resolved; owner-controlled merge. Platform/JVM/emulator gates apply to corresponding implementation PRs and must actually execute there. No skipped/cancelled job counts as pass.
- **Status:** leaf reservation pending its own exact-head documentation gates and review. All four leaves are RESERVED IN PLAN but not active implementation assignments until this reservation merges; `.1` remains BLOCKED from branch creation until its separate exact-base pin amendment merges. No Program Step 93 completion is claimed.
## 12. Corrected GLAFIRA DESIGN — Windows Desktop / Hub UX WBS registration

- `1.11.1.1.6` — Windows Desktop / Hub workspace UX design task.
- `1.11.1.1.6.1` — Screen-by-screen specification package.
- `1.11.1.1.6.1.1` — Produce `docs/design/WINDOWS_DESKTOP_HUB_UX.md` (the executable leaf; seven hierarchy components).
- **Work Item ID:** `GLAFIRA-GOV-DESIGN-W02`, a cross-step design Work Item allowed by the explicit owner-approved cross-step ID rule. Tracker: issue #674; umbrella and owner-approved fallback authority: issue #672. No legacy Program 150 step is claimed.
- **Fallback eligibility:** #674 is a Windows Desktop/Hub design package, not an automatic Token/GCU assignment. At each coordinator cycle, record the exact original Windows Desktop lane WBS item, its issue/PR, current implementation/test/acceptance status, concrete blocker and evidence that no equal-or-higher-priority ready implementation, validation, intake, review, merge or post-merge task is available. If that evidence is absent, do not dispatch this fallback. Never overwrite the original lane status.
- **Pending-token gate:** before any early information-architecture or wireframe drafting, verify that issue #674 contains the exact marker `VISUAL_TOKENS_STATUS: PENDING (blocked on owner acceptance of #679)`. If the marker is absent or #679 has been accepted, stop and update the issue/ledger before proceeding. Early IA/wireframes may proceed only with tokens visibly pending; final color, typography, spacing, radius and surface values remain blocked until owner acceptance of #679.
- **ICQ identity:** retain the ICQ flower as a mandatory acceptance criterion, alongside ICQ 2003 identity and restrained Apple/macOS polish.
- **Leaf reservation gate:** this WBS registration is a parent reservation only. After its reservation PR merges, create a separate pre-edit leaf-reservation amendment recording the implementation branch, exact then-current Main SHA, executor, independent reviewer, allowed path, dependencies, numbered procedure and acceptance tests. That amendment must merge before creating/editing the implementation branch or drafting the deliverable.
- **Implementation scope:** only `docs/design/WINDOWS_DESKTOP_HUB_UX.md`. No runtime code, protocol, database, permission, cryptography, Core authority or security changes. Do not overlap #679 tokens, #677 Bridge pairing, #678 Backup UX, #680 Multi-AI, #712 Android operational screens or #713 accessibility ownership.
- **Acceptance:** screen inventory; navigation map; control/state transitions including loading, empty, offline, approval-pending, reconciliation-required, error, success and cancellation; Hub pairing/trust/revocation visibility without secrets; responsive multi-pane and narrow-window behavior; keyboard/focus/screen-reader/reduced-motion behavior; traceability to existing code/contracts; explicit unresolved decisions; ICQ flower present; pending-token gate respected; independent review and owner acceptance.
- **CI separation:** this docs-only reservation requires exact-head Docs CI, Security Scan, Project Dispatcher, independent docs review and owner approval. The later design implementation PR requires its own exact-head docs CI, Security Scan, Dispatcher, independent review and owner acceptance. Neither design PR substitutes for Windows Desktop build, Bridge/pairing, security, two-PC or physical-device acceptance.
- **Status:** RESERVED IN PLAN / NOT DISPATCHED. Drafting is blocked until the parent reservation and separate pre-edit leaf-reservation amendment both merge, and the pending-token marker is verified on issue #674.
## 13. Eleven-lane batch cadence and non-response handoff

Canonical executable leaf: `1.11.2.1.2.1.1` — document and enforce the eleven-lane batch-cycle cadence. Parent task: `1.11.2.1.2`; Work Item ID: `GLAFIRA-GOV-BATCH-W01` (explicit owner-approved cross-step governance item). This registration does not claim any Program 150 step complete.

- Every cycle reconciles all eleven lanes and records evidence-backed counts for planned, assigned, DONE, BLOCKED, SUSPENDED, in-review, CI-passed, merged and post-merge-verified executable leaves. Target 3–5 genuine leaves per lane only when the backlog and dependencies permit; never invent or duplicate work to meet a quota.
- One executor holds one active leaf. Planned successors are candidates, not automatic dispatch. Whenever a slot opens, restart the Section 15 global scan.
- Workstream 11 checks ready intake, independent review, merge and post-merge actions before DESIGN fallback. It does not outrank the latest owner-approved product priority for implementation.
- A blocked leaf may be formally suspended to free its executor; preserve the original BLOCKED state and file reservation and revoke the former executor's write access before dispatching the next leaf.
- After three evidenced unanswered coordinator passes, transfer the unfinished leaf to GLAFIRA by default. GLAFIRA owns that leaf as its only active assignment. The former executor loses edit rights on it and may receive one distinct DESIGN leaf only after the full ready-work scan. A returning helper must notify the coordinator on entry and must not resume the transferred branch.
- If another qualified executor is explicitly selected as replacement, that executor must have a free slot and may hold only the transferred leaf; do not assign DESIGN or another leaf to that replacement until a formal suspension/handoff.
- File reservations remain active during suspension/transfer. On DONE, CANCELLED or SUPERSEDED, release only after integration/terminal evidence and branch quiescence; for CANCELLED/SUPERSEDED preserve history, reason and replacement ID.
- Cross-lane independent leaves are allowed; common completed predecessors are allowed. Unfinished dependencies, shared files, schema/API ownership or resource conflicts block parallel execution.
- Runtime queues remain per independent Core authority. The coordinator's development view is reconciled from WBS/issues/PRs/branches/reservations; it is not a second durable runtime queue.
- Every reservation checklist includes exact-head Docs CI (for docs-only reservation), Security Scan, Project Dispatcher, independent review with all blocking findings resolved, and owner approval. Platform checks belong to the implementation PR and must actually execute there.
- The project currently has no verified global cross-workflow build queue. Do not claim build serialization; record this as a separate CI infrastructure gap until a shared queue is implemented and proven to preserve every PR validation request.
## 14. Backup rollback-temp cleanup ownership prerequisite for Core cancellation `.4`

- `1.2` — Backup and Recovery.
- `1.2.1` — Local rollback-journal confidentiality and integrity.
- `1.2.1.2` — Interrupted restore recovery and restart re-entry (existing task).
- `1.2.1.2.2` — Ownership-aware decrypted rollback-temp cleanup.
- `1.2.1.2.2.1` — Implement and instrument ownership-aware cleanup.
- `1.2.1.2.2.1.1` — Ensure cleanup deletes only stale artifacts owned by the same installation/generation and never removes a temp held by an active restore (seven-component executable leaf).
- **Work Item:** `GLAFIRA-S025-W01`; tracker issue #566; legacy Program Step 25 is a related acceptance reference only and is not marked complete.
- **Predecessors:** existing re-entry leaf `1.2.1.2.1` DONE; existing plaintext canary leaf `1.2.1.1.1` DONE. PR #720 is still open/unmerged and its leaf is not DONE; this cleanup leaf cannot start until #720's test-only re-entry acceptance is merged and validated.
- **Executor/reviewer:** GLAFIRA coordinator; independent CodeRabbit and Proofline; owner approval and merge.
- **Exclusive files after separate pin amendment:** `app/src/main/java/com/micronizationlab/glafira/GlafiraCoreBackupCoordinator.kt` and `app/src/androidTest/java/com/micronizationlab/glafira/GlafiraCoreBackupCoordinatorCleanupInstrumentedTest.kt`. The test file is separate from PR #720's re-entry test file and Core cancellation `.4`.
- **Procedure:** (1) identify every temp artifact created by rollback recovery; (2) persist ownership metadata binding artifact path, installation, generation and active cleanup lease; (3) acquire the Core maintenance gate before stale cleanup; (4) delete only artifacts whose ownership marker proves they are stale and belong to this installation/generation; (5) retain unknown or actively owned files and fail closed; (6) preserve encrypted journal/key deletion ordering; (7) verify process-death, concurrent coordinator and cleanup-failure cases.
- **Acceptance:** managed AndroidJUnit4 instrumentation must create active, stale, foreign/unowned and malformed-marker temp files; prove active/foreign/unowned files survive; prove only a confirmed stale owned file is deleted; force concurrent restore and cleanup to prove maintenance-gate serialization; verify no plaintext rollback data or key is exposed; verify cleanup failure leaves Core blocked and does not delete the rollback key or pending marker prematurely. Named JUnit XML and exact-head emulator logs are mandatory.
- **Status:** RESERVED IN PLAN / NOT DISPATCHED. After the prerequisite PR #720 is accepted, a separate pre-edit leaf-reservation amendment must pin the exact fresh Main SHA and branch before production code edits. This leaf must be DONE before Core cancellation `.4` can start.