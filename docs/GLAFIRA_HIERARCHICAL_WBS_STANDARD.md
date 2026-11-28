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


## 13. Strict-Main WBS reservation uniqueness and merge safety

The repository remains owned by the personal GitHub account. Native GitHub Merge Queue and organization transfer are out of scope. Prior live audit observed strict up-to-date disabled and privileged bypass available in `Protect main`; re-read actual ruleset and bypass actors before acceptance. Never claim repository-enforced serialization without fresh evidence. The checker MUST compare the proposed merge candidate with the exact protected-Main ledger; parsing only the candidate ledger is insufficient.

- 1.7.1.2 — PR reservation and merge safety.
- 1.7.1.2.1 — Validate active WBS reservations against the protected Main ledger.
- 1.7.1.2.1.1 — Implement strict-main reservation uniqueness check.
- 1.7.1.2.1.1.1 — Add the required GLAFIRA WBS Reservation Uniqueness PR check, parser and regression fixtures (Work Item `GLAFIRA-GOV-WBS-W01`, issue #736).

**Identity and registry model.** New executable task IDs use `GLAFIRA-S<step>-W<sequence>`; new governance IDs use `GLAFIRA-GOV-<domain>-W<sequence>`. `GLAFIRA-GOV-WBS-W01` is valid. Three exact pre-existing identifiers are grandfathered as immutable legacy aliases: `GLAFIRA-GOV-W01` (governance parent record), `GLAFIRA-CI-W01` (historical CI record) and `GLAFIRA-CODEX-W01` (the registered Codex reservation at WBS `1.7.1.1.1.1.1`, PR #761). They remain valid only at their existing registered records and may not be newly assigned, copied to another parent, or used for a new reservation. The validator must accept those exact legacy records and reject malformed or relocated uses; migrations require an append-only alias/migration event preserving the old identifier. A Work Item ID identifies a registered parent task, not an individual child reservation. Multiple executable leaves may reference the same Work Item ID only when each is explicitly nested under that same parent; reuse across different parents is invalid. The legacy top-level execution-ledger registry is frozen historical data and must not be rewritten by a new reservation. New executable reservations are added through an append-only registry extension with a dedicated `Registered parent` column; the parent is explicit data, never inferred from the Step label or guessed from the Work Item ID. Existing legacy rows remain unchanged until a separately authorized migration. For every executable reservation row, parsers join it to **all** detailed reservation records matching both Work Item ID and Registered parent, and include each matching detail exactly once. A missing/ambiguous parent or missing detail for an executable reservation fails closed; the index row itself is never counted as a reservation. **Legacy parent-only migration rule:** pre-existing registry rows with no executable child reservation may use `PARENT_ONLY:<label>` only when they claim no reserved source paths and are not represented as executable leaves. Pre-existing rows whose parent cannot yet be established must be explicitly marked `LEGACY_UNRESOLVED`; they must remain BLOCKED or OWNER_ACCEPTANCE, claim no reserved paths, and cannot authorize branch/source work. These explicit unresolved rows are not passes and must be migrated by an append-only event that records the verified parent, detailed leaf record and evidence before they can become executable. Any attempt to activate an unresolved row or attach paths fails closed. `GLAFIRA-S016-W01` is linked to parent `1.1 Core` and detail leaf `1.1.4`; W06 is linked to parent `1.7.2.1.1.1` and leaf `1.7.2.1.1.1.1`. Duplicate WBS-leaf checks run across the complete detailed ledger, including terminal history; distinct detailed reservations may never reuse a WBS leaf ID except the narrowly defined, owner-authorized and fully evidenced duplicate-Main conflict pair in **Merge concurrency and bypass** below. This exception applies only to the exact historical pair linked by its conflict incident, terminal SUPERSEDED event and replacement reservation; it does not authorize any new or unrelated duplicate. Exclusive-path collision checks apply to every reservation that has not reached a path-releasing terminal state, including RESERVED, IN_PROGRESS, BLOCKED, IN_REVIEW, MERGED and OWNER_ACCEPTANCE. A path is released only after verified DONE or authorized terminal CANCELLED/SUPERSEDED. A path is released only after verified DONE or authorized terminal CANCELLED/SUPERSEDED; the terminal record and its WBS identity remain immutable history but no longer block a new reservation of that path. Legacy active reservations without Work Item IDs remain protected by a stable key derived from canonical WBS leaf plus tracker; missing or ambiguous identity fails closed.

**Immutable reservation identity and append-only events.** Reservation identity, canonical WBS leaf, registered parent, tracker, original authorization and reserved paths are immutable. Status and current executor are projections of an append-only event chain, not free-form edits. Every transition appends a unique event containing reservation key, sequence, from/to values, actor, timestamp, reason and evidence. The validator permits a projection change only when the corresponding event is appended in the same candidate and preserves every prior event. The candidate is compared to exact protected Main; deletion, rewriting, reordering or omission of prior events is rejected.

**Lifecycle state machine.** Canonical status values are `PROPOSED`, `READY`, `RESERVED`, `IN_PROGRESS`, `IN_REVIEW`, `MERGED`, `OWNER_ACCEPTANCE`, `DONE`, `BLOCKED`, `CANCELLED` and `SUPERSEDED`; descriptive labels such as “NOT IMPLEMENTED” are notes, not status values. PROPOSED is inactive and may become READY only after scope/dependency review. READY may become RESERVED only after exact path ownership, base SHA and required authorization are recorded. RESERVED→IN_PROGRESS requires a registered implementation branch and the first authorized post-reservation commit to any reserved deliverable path (source code or documentation); that first commit must atomically include the matching registry `Status` projection and append-only transition event; a frozen pre-reservation candidate does not count. A Draft PR remains IN_PROGRESS. IN_PROGRESS→IN_REVIEW occurs only when the PR is marked Ready for review; this also applies when a corrected PR is re-marked Ready after requested changes. IN_REVIEW→IN_PROGRESS is allowed for requested corrections. IN_REVIEW→MERGED requires that PR's exact merge commit SHA. MERGED→OWNER_ACCEPTANCE is used when technical acceptance and post-merge checks pass but owner-controlled physical/device acceptance remains. `OWNER_ACCEPTANCE` is a non-executable acceptance gate, never a source-edit authorization; OWNER_ACCEPTANCE→DONE requires explicit owner acceptance evidence. OWNER_ACCEPTANCE→BLOCKED is allowed only with `blocked_from=OWNER_ACCEPTANCE` and a concrete blocker. MERGED→DONE remains available when no owner-only acceptance is outstanding. Any nonterminal state may enter BLOCKED only with blocker identity/evidence and a `blocked_from` field preserving the exact prior state. Repeated blocker updates while already BLOCKED append evidence but MUST NOT overwrite the original `blocked_from`. BLOCKED may resume only to that recorded prior state after a timestamped blocker-resolution event and evidence; this includes `blocked_from=MERGED`: restore to MERGED, then require the ordinary MERGED→DONE acceptance gate. **Narrow terminal recovery exception:** an owner-authorized duplicate-Main conflict may transition `BLOCKED→SUPERSEDED` without first resuming to `blocked_from`, but only through a separate append-only terminal event that preserves the original `blocked_from`, records reason `DUPLICATE_MAIN_CONFLICT`, quiesced-branch and revoked-write evidence, and the approved replacement reservation ID. This exception is not a resume and does not permit any other BLOCKED→terminal transition. A BLOCKED record may never jump directly to DONE or bypass its saved prior state. CANCELLED requires a reason, quiesced branch and revoked write-access evidence. SUPERSEDED requires all of those plus the replacement reservation ID. Missing or ambiguous evidence preserves the reservation as active.

**Executor handoff.** The registered parent task and reservation identity do not change. Current executor may change only through an append-only handoff event recording old executor, new executor, authorizing coordinator/owner, reason, timestamp, branch/commit handoff and proof that old write access stopped before new access begins. A non-response escalation does not silently alter the record. Reserved paths and WBS parent remain unchanged unless a separate reservation amendment is approved.

**Exclusive paths.** Exclusive path overlap among currently active reservations (RESERVED, IN_PROGRESS, BLOCKED, IN_REVIEW, MERGED and OWNER_ACCEPTANCE) is rejected. OWNER_ACCEPTANCE continues to hold its reserved paths until verified DONE or authorized terminal CANCELLED/SUPERSEDED. A path released after verified DONE or authorized terminal CANCELLED/SUPERSEDED may be reserved by a new Work Item/WBS leaf; the prior terminal reservation remains in history and its WBS leaf ID is never reusable. A path held by any active reservation remains unavailable. This is not a sequential-sharing exception within one logical work item, and it does not change the no-sharing rule for GLAFIRA-S093-W01. Section 10 is authoritative: .3 exclusively owns `app/src/androidTest/java/com/micronizationlab/glafira/GlafiraTaskCancellationInstrumentedTest.kt`; .4 exclusively owns `app/src/androidTest/java/com/micronizationlab/glafira/GlafiraTaskRestoreCancellationInstrumentedTest.kt`. They are separate sibling leaves and must not share, stack or sequentially extend the same instrumentation file. The historical PR #696 same-file arrangement was superseded by PR #752 and cannot authorize future overlap.

**Exact-SHA validation and freshness.** Run on pull_request opened/synchronize/reopened/ready_for_review/edited. When protected Main advances, a push-to-main reconciliation must enumerate open PRs and rerun/dispatch validation for each current merge candidate. Every result records exact base SHA and head SHA. If checked base differs from current protected Main, the result is stale/UNKNOWN and must fail closed; an unchanged PR head does not make an old green check reusable. Any parse, dispatch or evidence failure is non-green.

**Merge concurrency and bypass.** A live Main reread is not an atomic compare-and-set, mutex or protection against concurrent privileged merges. With no native Merge Queue and privileged bypass available, the coordinator uses a single-flight procedure: one reservation merge at a time; after each merge, reread Main and rescan all reservations before allowing the next. If bypass, concurrent merge or unexpected Main advance occurs, stop all reservation dispatch/merges, enumerate affected PRs, compare exact base/head and full candidate ledgers against new Main, resolve collisions and rerun validation before resuming. If duplicate WBS leaves have already reached Main, preserve both original reservation records and all prior events unchanged; append a conflict incident naming both reservation IDs, merge SHAs and detection evidence. Keep the earlier canonical reservation as the valid owner; transition the later conflicting reservation to `BLOCKED` with `blocked_from` equal to its prior state and reason `DUPLICATE_MAIN_CONFLICT`, quarantine its branch and revoke further writes. Preserve that original `blocked_from` through all subsequent blocker updates. The owner may then explicitly authorize the one terminal `BLOCKED→SUPERSEDED` recovery transition, without resuming the prior state, through an append-only event that preserves `blocked_from` and records reason `DUPLICATE_MAIN_CONFLICT`, quiesced-branch and revoked-write evidence, and a link to a separately approved replacement reservation with a new Work Item/WBS leaf. Append a resolution event linking both original records, the later record's terminal disposition, replacement ID and owner decision. Only after that terminal event may its exclusive paths be released. The two original records and their original duplicate WBS values remain immutable historical evidence; the validator may tolerate that exact historical duplicate pair only when the complete incident + terminal disposition + replacement chain is valid and owner-authorized. No other duplicate leaf is exempt: any unlinked, active, incomplete or unauthorized duplicate remains fail-closed. Never delete, rewrite or renumber either historical record. This is an operational stop-and-reconcile rule, not repository-enforced serialization.

**Required regression acceptance.** Include positive and negative fixtures for: PROPOSED/READY activation; every allowed and forbidden lifecycle transition; BLOCKED resume only to prior state including BLOCKED(from MERGED)→MERGED→DONE; owner-authorized duplicate recovery BLOCKED→SUPERSEDED preserving blocked_from; repeated BLOCKED updates cannot overwrite blocked_from; unauthorized BLOCKED→SUPERSEDED rejected; CANCELLED/SUPERSEDED reason and replacement ID; executor handoff audit; append-only event tampering; registry index not double-counted; governance ID accepted; same-parent Work Item reuse accepted and cross-parent reuse rejected; duplicate WBS leaf rejected across active/terminal history; path overlap rejected when the prior owner is active; path re-reservation accepted after verified DONE or authorized terminal CANCELLED/SUPERSEDED; exact grandfathered legacy IDs `GLAFIRA-GOV-W01`, `GLAFIRA-CI-W01` and `GLAFIRA-CODEX-W01` accepted only at their registered records and rejected when newly assigned/relocated; registry index joined to all matching detailed records exactly once; deletion/downgrade; separate .3/.4 paths accepted and overlapping paths rejected; stale base and Main-push revalidation; malformed/missing identity; exact base/head output; bypass/concurrent merge stop-and-reconcile including two already-merged duplicate records and append-only recovery. Workflow must fail closed on incomplete comparison.


**Controlled shared-ledger exception:** the canonical execution ledger is a shared file and is not ordinarily eligible for whole-file ownership by one leaf. A reservation may explicitly grant a narrow ledger sub-scope only when it names the exact Work Item ID. The reservation PR may perform a one-time append-only creation of that Work Item's registry-extension row and detailed reservation record; it must not reformat or rewrite existing legacy registry rows. After registration, only that row's `Status` projection may change, and only in the same commit as its matching append-only transition event; later events for the same Work Item ID may be appended. All other rows and prior history remain immutable. This sub-scope is recorded as an allowed path in both the reservation and the detailed WBS record. No other reservation/ownership PR may merge during the single-flight window. W06 is the current explicit instance.

### Owner-authorized exception protocol for a known false-green check
A false-green check is never re-labelled PASS. A one-time exception may be used only when the repository owner explicitly authorizes the exact PR, exact base/head, exact waived check, scope and reason in writing; the append-only ledger records the run/job evidence and the remaining required checks. The exception applies only to the named PR and scope, does not alter the check's conclusion, and cannot be reused by a follow-on pin or implementation PR. The final exact-head evidence (base SHA, final head SHA, required check names, run IDs and job IDs) must be published in a top-level PR conversation comment after the last review-driven commit; this does not change the PR head. After merge, record the evidence and exact merge SHA in a separate append-only ledger amendment. Never commit final-check evidence to the PR head it validates. For the sole owner-authorized docs-only reservation PR #788, the owner's explicit approval is the only required human approval; no third-party human approval is required. Automated review findings remain technical input and actionable findings must be triaged. Security scanning, all other applicable exact-head checks, path/ownership validation and post-merge reconciliation remain mandatory. **Current sole exception:** owner authorization on 2026-11-27 applies only to docs-only reservation PR #788 on live Main base `8f0a8354434c031e1b6f331f2b69b15256d9d7c5`; the Project Dispatcher false-green remains NOT PASSED. It does not authorize Dispatcher implementation or production-source integration.

## 14. Dispatcher inventory reconciliation reliability

- `1.7.2` — Dispatcher inventory reconciliation.
- `1.7.2.1` — Exact open-PR inventory and base/head identity comparison.
- `1.7.2.1.1` — Bounded inventory transport and failure-state reporting.
- `1.7.2.1.1.1` — Large-inventory and injected-failure regression.
- `1.7.2.1.1.1.1` — Fix argv overflow and fail-closed comparison handling; Work Item `GLAFIRA-S000-W06`; owner-approved cross-step work in Workstream 7 CI/CD & security under the owner's 2026-11-27 instruction to continue all 11 workstreams (not Program Step 0); issue #768.
- **Immediate parent:** `1.7.2.1.1.1` — Large-inventory and injected-failure regression.
- **Reserved implementation paths:** `.github/workflows/glafira-project-dispatcher.yml`; `docs/GLAFIRA_PROJECT_DISPATCHER.md`; `docs/GLAFIRA_SHARED_PROJECT_CONTEXT.md`; `docs/GLAFIRA_EXECUTION_LEDGER.md` (controlled shared-ledger sub-scope: only the `GLAFIRA-S000-W06` registry `Status` cell and append-only W06 status events; no other ledger rows or history may change). Comparison/fetch/test helper scripts are generated under `$RUNNER_TEMP` by the workflow and are not repository paths. `.coderabbit.yaml` is outside this WBS leaf and must be split from candidate PR #791.
- **Predecessors / dependencies:** none. Independent of #744's durable non-dropping FIFO build queue.
- **Executor:** GLAFIRA is the designated executor. Earlier candidate PR #791 (`36b912af`) is frozen and superseded for this repair. Current focused implementation candidate is PR #795 (`fix/dispatcher-fail-closed-inventory-20261127`, head `ce186427baf06c1525a7f5c2e262c11d9f43bf9d`); its current head predates the reservation merge and is not eligible for merge yet. After #788 merges, rebase PR #795 onto exact current Main, verify the single-file W06 scope, and create the first authorized post-reservation commit to a reserved deliverable path (source code or documentation). That same commit must update only the W06 registry `Status` projection and append the matching transition event, triggering `RESERVED→IN_PROGRESS`; the candidate's historical pre-reservation commits do not count. Continue PR #795 as the sole W06 implementation PR; if its scope cannot be reconciled, close it as superseded and preserve useful commits in a fresh Draft PR. The same implementation PR carries an exact-Main pin attestation recording its base SHA and tested head SHA; no separate pin PR or pre-merge pin merge is required. It remains Draft until all tests and owner approval pass, then becomes Ready for review. CodeRabbit/Proofline findings are advisory technical input, not required human approvals; the owner is the sole approver. No Amazon Q-only assignment.
- **Acceptance:** large PR inventory passes without argv overflow; jq/script parse or execution failure becomes explicit UNKNOWN/degraded and cannot be mistaken for changed refs; UNKNOWN/degraded MUST make the Project Dispatcher check conclude failure (or another explicitly non-green conclusion), never green/success; genuine inventory deltas remain detectable; exact PR number/head SHA/non-empty base SHA matching remains intact; regression fixture covers large input, injected failure and real inventory changes. **Owner-approved, narrowly scoped exception — 2026-11-27 (reaffirmed):** PR #788 is documentation-only and may merge after refresh onto current Main, the owner's explicit approval and all other applicable exact-head checks pass, notwithstanding the known false-green Project Dispatcher result. The owner is the sole human approver for this reservation; no third-party human approval is required. Automated review findings remain technical input and actionable defects must be triaged. The Dispatcher result is explicitly NOT PASSED / WAIVED FOR THIS DOCS-ONLY RESERVATION ONLY; latest exact-head reproduction: PR #788 head `3a38c5919d7f72de0c9632f71398d00b9fd4a2fb`, run `46033550520`, job `140623132644` reported GitHub success while the log ended with `/usr/bin/jq: Argument list too long`; this is a false-green, not a pass. This waiver is recorded under the owner's explicit instruction and must not be requested again. It does not waive Security Scan, applicable docs/path checks, technical triage of actionable findings or final ownership rescan. It does not authorize Dispatcher implementation, the implementation pin amendment, or any implementation PR. The implementation source merge remains blocked until the false-green defect is fixed and verified on its exact source head; the owner is the sole human approver. A dedicated Draft implementation PR may be prepared after the reservation merge solely to implement/test the fix; the leaf remains RESERVED until the registered branch has its first authorized post-reservation source commit. The frozen pre-reservation candidate #791 does not count. That new commit triggers RESERVED→IN_PROGRESS and the same implementation PR must update only the W06 registry `Status` cell and append the corresponding W06 status event to `docs/GLAFIRA_EXECUTION_LEDGER.md`, without rewriting any other row or prior entry. The Draft PR remains IN_PROGRESS while Draft; marking it Ready for review triggers IN_PROGRESS→IN_REVIEW. The same implementation PR must include the exact-Main pin attestation recording its base SHA and tested source head SHA; no separate pin PR or pre-merge pin merge is required. Before marking Ready, the corrected Dispatcher workflow must run on the exact source head without jq/parse/transport errors, and large-inventory plus injected-failure regressions must prove injected jq/script failure yields explicit UNKNOWN/degraded and a non-green Dispatcher conclusion, while genuine inventory deltas and exact PR/head/non-empty-base matching remain correct. Independent review and all applicable checks must pass. A green conclusion accompanied by jq/parse/transport errors is failure, not a pass. Missing, skipped, stale or inconclusive evidence blocks source integration. Docs CI is required only if present; path-gated workflow lint may be skipped for docs-only changes. The implementation PR must run the regression harness and all exact-head platform checks selected by its changed paths; skipped is not passed.
- **Reservation lifecycle / mutual exclusion:** this WBS registration is not implementation authorization. Use a single-flight coordinator procedure: one reservation merge at a time, followed by a fresh Main read and full ownership rescan. A live reread is not an atomic mutex or compare-and-set and cannot prevent a concurrent privileged merge or bypass. No atomic merge guard or Merge Queue has been configured or verified. For this single owner-authorized #788 documentation reservation only, the owner accepts the residual concurrency risk of the single-flight procedure; this cannot be reused for another reservation. If Main advances unexpectedly or a bypass/concurrent merge occurs, stop all reservation merges and reconcile every candidate against exact new Main before resuming. After reservation merge, canonical status remains `RESERVED` until the registered implementation branch has its first authorized post-reservation commit to any reserved deliverable path (source code or documentation); the frozen pre-reservation candidate #791 does not count; current implementation candidate #795 must be rebased and receive a new authorized post-reservation commit. That new commit triggers `RESERVED→IN_PROGRESS`; the same implementation PR must append the matching W06 status event to `docs/GLAFIRA_EXECUTION_LEDGER.md` without rewriting any existing ledger content. A Draft implementation PR remains `IN_PROGRESS`; only when it is marked Ready for review append `IN_PROGRESS→IN_REVIEW`. Requested corrections append `IN_REVIEW→IN_PROGRESS`; when the corrected PR is re-marked Ready, append `IN_PROGRESS→IN_REVIEW` again. The same implementation PR carries the exact-Main pin attestation; after all evidence and owner approval pass, the PR may be marked Ready and merged. Only its exact merge commit permits `IN_REVIEW→MERGED`; acceptance and post-merge checks permit `MERGED→DONE`. The branch must be refreshed onto live Main and duplicate ownership rescanned immediately before merge. Reservation merge alone never authorizes source integration.
- **Status:** `RESERVED` after reservation registration and after the docs-only reservation PR merges. Transition only through the canonical state machine above; descriptive labels are not statuses.

## 15. Bridge pairing UX design reservation — GLAFIRA-S000-W08

- **Task:** `1.3.1.1.1` — Owner-visible Android↔Windows Hub pairing UX.
- **Subtask:** `1.3.1.1.1.1` — Pairing invitation, proof comparison, approval, device inventory, revocation and recovery screens.
- **Executable leaf:** `1.3.1.1.1.1.1` — Specify and validate the complete Bridge pairing UX; Work Item `GLAFIRA-S000-W08`; issue #677.
- **Accountable executor:** GLAFIRA (owner-authorized; active assignment begins only after a fresh priority scan is recorded).
- **Independent reviewer:** Proofline (review-only; not the executor); owner acceptance remains a separate gate.
- **Reservation-PR allowed paths:** `docs/GLAFIRA_HIERARCHICAL_WBS_STANDARD.md` §15 and the W08 registry row + detailed W08 record only in `docs/GLAFIRA_EXECUTION_LEDGER.md`. No other ledger row, prior event or history may be edited.
- **Exclusive future deliverable:** `docs/design/BRIDGE_PAIRING_UX.md` only, after this registration is accepted/merged and an exact post-merge Main/path ownership pin is recorded.
- **Dependencies:** #123 Windows Hub enrollment/pairing contract; #366 owning-Core/trusted-recipient boundary; #392/#381 Android client dependency chain; shared tokens #679/#684 (wireframes only until approved).
- **Predecessors / priority gate:** (1) read-only source/contract inventory; (2) a complete current scan of both Action Pool A and the separate Workstream 11 helper-intake/integration pool recorded in issue #782, including timestamp, protected Main SHA and both pool snapshots; (3) verify neither pool contains equal- or higher-priority dependency-ready implementation, validation, intake, review, merge or post-merge work; (4) owner acceptance of this WBS registration. Re-read Main and both pools immediately before assignment; any intervening change invalidates the scan. Until the two-pool scan and owner acceptance are evidenced, this leaf remains PROPOSED and unassigned.
- **Numbered procedure:** (1) inventory actual Android client and Windows Hub pairing contracts; (2) specify expiring invitation and QR/manual-code fallback; (3) specify one-time bootstrap proof and visual SAS/fingerprint comparison with mismatch abort; (4) permit controlled display of the short-lived, one-time bootstrap proof only on the owner-approved enrollment screen, with explicit expiry/single-use behavior; (5) prohibit reusable credentials, private keys and bearer secrets from logs, diagnostics, screenshots and persistent UI; (6) specify least-privilege scopes and explicit owner approval/denial; (7) specify paired-device inventory, revoke/rotate, reconnect and restart recovery; (8) define loading/empty/success/error/expired/cancelled/revoked and accessibility states; (9) map each action to an existing contract or mark it FUTURE.
- **Acceptance:** complete state/screen/control inventory; no trust based on device name or arbitrary LAN endpoint; SAS comparison and ephemeral QR/manual-code proof are displayed only in the controlled owner-approved enrollment flow; no reusable credentials or secrets are logged or persisted; distinct paired/connected/offline/pending/revoked states; independent review and owner approval. This design does not establish live LAN/two-PC acceptance.
- **Out of scope:** TLS, cryptography, protocol, permissions, runtime, database/schema, Android HTTPS server, Hub authority and security-policy changes.
- **Lifecycle:** PROPOSED until owner acceptance and the complete priority scan are recorded; owner acceptance transitions PROPOSED → READY before merge; exact reservation-PR merge evidence transitions READY → RESERVED. Design-file editing remains blocked until a separate exact post-merge Main/path pin authorizes the exclusive future deliverable.

## 16. Windows Agent UX design reservation — GLAFIRA-S000-W09

- **Ancestor chain:** `1.8` — Windows Agent workstream; `1.8.1` — owner-controlled Agent operation; `1.8.1.1` — Agent enrollment and lifecycle UX.
- **Task:** `1.8.1.1.1` — Windows Agent subordinate-worker UX.
- **Subtask:** `1.8.1.1.1.1` — Consent, authenticated state, task progress, pause/revoke, diagnostics and resource screens.
- **Executable leaf:** `1.8.1.1.1.1.1` — Specify and validate the complete Windows Agent UX; Work Item `GLAFIRA-S000-W09`; issue #676.
- **Owner approval:** explicit owner directive on 2026-11-28 to create the shared defect register and act across all 11 workstreams; this authorizes the W08/W09/W10 registration records, not implementation of future design artifacts.
- **Accountable executor:** GLAFIRA (owner-authorized; active assignment begins only after a fresh priority scan is recorded).
- **Independent reviewer:** Proofline (review-only; not the executor); owner acceptance remains a separate gate.
- **Reservation-PR allowed paths:** `docs/GLAFIRA_HIERARCHICAL_WBS_STANDARD.md` §16 and the W09 registry row + detailed W09 record only in `docs/GLAFIRA_EXECUTION_LEDGER.md`. No other ledger row, prior event or history may be edited.
- **Reservation provenance:** W08 pairing UX is registered only by PR #801. This W09-only registration is PR #837 on branch `wbs/GLAFIRA-S000-W09-windows-agent-design-reservation-v2`, based on W08 branch head `fa50f06fddf612aa5e02358f4e21d5e4eb4a6745`; changed files are this §16 section and the W09 registry row + detailed W09 record only.
- **Exclusive future deliverable:** `docs/design/WINDOWS_AGENT_UX.md` only, after owner acceptance, merge reconciliation and an exact post-merge Main/path ownership pin.
- **WBS predecessors:** none; W09 is a separate design lane, not blocked by W08 as a WBS predecessor. The following are external issue/contract dependencies, not WBS IDs: #76 Agent service/heartbeat/task runtime; #123 Windows Hub coordinator/enrollment contract; shared tokens #679/#684 (wireframes only until approved).
- **Predecessors / priority gate:** (1) read-only inventory of current Agent/Hub contracts; (2) scan both Action Pool A and the separate Workstream 11 helper-intake/integration pool in #782 immediately before assignment, recording timestamp, protected Main SHA, both pool snapshots and every equal- or higher-priority dependency-ready item ID; (3) W09 may become READY only if neither pool contains equal- or higher-priority ready work. Re-read Main and both pools immediately before assignment under the single-flight reservation procedure; if any relevant state changes between scan and assignment, invalidate the evidence and repeat the full scan. Do not assign from a stale snapshot. Until owner acceptance and a passing fresh scan are recorded, keep this leaf PROPOSED and inactive.
- **Numbered procedure:** (1) inventory existing service, tray, heartbeat, polling, durable progress/result ACK, pause/reconnect and Hub-approved profile contracts; (2) specify consent/enrollment summary with coordinator identity/fingerprint, capabilities/scopes and resource ceiling; (3) define green only for authenticated healthy connection, gray for paused/stopped/disconnected/not checked, red only for explicit error/revocation/auth failure; (4) specify idle/running progress with bounded task metadata and no sensitive tray payload; (5) specify pause/resume, owner-priority preemption, shutdown and network-loss recovery without blind replay; (6) specify resource usage, per-task/aggregate limits, throttling and the visible state when a ceiling is reached; (7) specify revoke, credential/endpoint rotation, reconnect and redacted diagnostics; (8) define keyboard, screen-reader, high-contrast and focus-order accessibility for the tray/detail panel inside the existing Windows Hub UI; do not introduce a separate Agent application or standalone Agent window; (9) validate positive enrollment, denied consent, expired invitation, invalid/mismatched proof, network failure, duplicate/concurrent approval, revoke/rotate, resource exhaustion and recovery; (10) label unimplemented controls FUTURE and never imply generic shell or unrestricted filesystem access.
- **Acceptance:** complete tray/detail panel flow inside the existing Windows Hub UI from enrollment through pause/revoke/recovery, with no separate Agent application or standalone Agent window; process-running is distinct from authenticated-connected and task-running; capabilities derive only from the authenticated owner-approved Hub profile; resource ceilings and throttling are explicit and testable; all keyboard/screen-reader/high-contrast acceptance checks pass; positive, negative, failure, concurrency and recovery cases are documented with expected states; subordinate worker authority is preserved. This design does not claim Agent execution or physical two-PC acceptance.
- **Out of scope:** Agent source, protocol, database, permissions, cryptography, security-policy, task-handler or scheduler changes; silent compute/data access; Hub/Core election.
- **Post-W08 merge rule:** when W08 PR #801 is squash-merged, do not merely retarget this branch. Rebase/recreate W09 on the resulting Main merge commit, verify the diff contains only W09 registration files, then retarget to `main` and rerun all checks.
- **Merge reconciliation rule:** after this reservation PR merges, before any subsequent reservation is accepted, reconcile the merged PR against the ledger. If W09 is still READY, append the exact merge SHA and READY → RESERVED event and update only the W09 status cell; hold all further registrations until this reconciliation is complete.
- **Lifecycle:** W09 remains PROPOSED; owner authorization is recorded, but no PROPOSED → READY event is claimed by this reservation PR. Only after owner acceptance and a passing scan of both pools may the ledger append a uniquely identified PROPOSED → READY event with scan evidence and transition metadata. The exact reservation-PR merge SHA then records READY → RESERVED in a separate append-only event. Design-file editing remains blocked until a separate exact post-merge Main/path pin authorizes the exclusive future deliverable.

## 17. Core pending-delivery recovery guard — GLAFIRA-S000-W10

- **Ancestor chain:** `1.1` — Core; `1.1.7` — pending delivery and provider-dispatch safety; `1.1.7.1` — recovery-aware dispatch preflight.
- **Task:** `1.1.7.1.1` — Fail-closed pending delivery during unsafe Core recovery.
- **Subtask:** `1.1.7.1.1.1` — Shared recovery preflight at the provider-dispatch boundary.
- **Executable leaf:** `1.1.7.1.1.1.1` — Make pending delivery honor the shared no-follow recovery guard and prove provider/result-write suppression; Work Item `GLAFIRA-S000-W10`; issue #828.
- **Owner approval:** explicit owner directive on 2026-11-28 to create the shared defect register and act across all 11 workstreams; this authorizes the W10 cross-step registration, not bypassing implementation gates.
- **Accountable executor:** GLAFIRA (active assignment begins after a fresh priority scan is recorded).
- **Independent reviewer:** Proofline (review-only; not the executor); owner acceptance remains a separate gate.
- **Reservation-PR allowed paths:** `docs/GLAFIRA_HIERARCHICAL_WBS_STANDARD.md` §17 and the W10 registry row + detailed W10 record only in `docs/GLAFIRA_EXECUTION_LEDGER.md`. No other ledger row, prior event or history may be edited.
- **Reservation PR evidence:** PR #834; branch `wbs/GLAFIRA-S000-W10-pending-delivery-journal-guard`; exact base SHA `06b9bfdee05c9cc626b5fafb7446870b53a0cc5c`; changed files are WBS §17 and only the W10 registry row + detailed W10 record in the ledger.
- **Exclusive implementation paths after reservation merge:** `app/src/main/java/com/micronizationlab/glafira/PendingDelivery.kt` and the new focused test `app/src/androidTest/java/com/micronizationlab/glafira/PendingDeliveryInstrumentedTest.kt` only. The first implementation commit may additionally update only the W10 `Status` cell and append W10 lifecycle events in `docs/GLAFIRA_EXECUTION_LEDGER.md`, as required by §13; no other ledger row/history is authorized.
- **Dependencies:** PR #827 must merge first because it supplies the shared symlink-safe `GlafiraCoreBackupCoordinator.hasInterruptedRestore` guard; exact post-merge Main/path ownership pin is required before source edits. No dependency on #832 or UI work.
- **Reservation predecessors:** (1) owner approval recorded above; (2) a fresh complete Action Pool A / ready-work scan in #782 immediately before assignment, recording timestamp, Main SHA and priority-relevant ready item IDs; (3) verify no equal- or higher-priority item became dependency-ready between scan and assignment. If the pool changes, repeat the scan. Until the fresh scan is evidenced, keep this leaf PROPOSED and inactive.
- **Implementation predecessors only:** #827 must merge first; then inspect its exact merged source, verify the shared guard rejects symlink paths without following/deleting targets, and record a separate exact post-merge Main/path pin. These implementation prerequisites do not block W10's PROPOSED → READY or READY → RESERVED reservation lifecycle.
- **Numbered procedure:** (1) read the merged shared guard and its no-follow semantics; (2) replace marker-only `restore.pending` detection in `isPendingDispatchBlocked` with the shared guard, failing closed on any unsafe journal state; (3) preserve early return before queue claim, provider request, history append, result persistence and delivery ACK; (4) add a narrow injectable online/provider seam only if required for deterministic tests, with production defaults unchanged; (5) create an instrumentation fixture whose journal path is a symlink to a target without `restore.pending`; (6) call the actual `executePendingMessage` production path and assert the provider callback is never invoked, the queue remains unclaimed and no result/history writes occur; (7) compare target artifacts byte-for-byte before/after; (8) test absent journal and normal pending-marker cases; (9) test normal dispatch with deterministic validated-online/provider success; (10) run Android JVM, instrumentation, Debug APK, Shared Contracts, Security Scan and exact-head Dispatcher, inspecting JUnit counts and complete logs.
- **Acceptance:** symlinked journal directory without `restore.pending` blocks the actual `executePendingMessage` path before provider execution and result persistence; target artifacts remain byte-for-byte unchanged; no marker/normal journal preserves dispatch behavior; a real pending marker remains blocking; deterministic normal dispatch still claims, calls provider, stores result and completes; no symlink is followed, deleted or modified; exact-head independent review and all required CI pass.
- **Out of scope:** changes to archive format, backup/recovery implementation, cryptography, provider protocol, queue schema, retry policy, UI, unrelated Core files or runtime scheduling.
- **Lifecycle:** owner approval is recorded above; fresh priority scan → PROPOSED → READY before reservation merge; exact PR #834 merge SHA → READY → RESERVED. Source implementation remains blocked until #827 is merged and the separate exact post-merge Main/path pin is recorded. If post-merge reconciliation is interrupted, reconcile the merged PR and append READY → RESERVED with its exact merge SHA before accepting any subsequent reservation.
