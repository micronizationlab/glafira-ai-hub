# GLAFIRA CODEX — immediate repair, one-pass defect closure

**Status:** mandatory project-wide execution rule  
**Owner:** Валерий Корчагин  
**Coordinator:** GLAFIRA  
**Applies to:** all eleven workstreams, every human contributor, AI assistant, coding agent, reviewer, helper and any participant with permission to change code or project artifacts.

## 1. Core rule

**Finding a defect creates an immediate duty to repair it, not a report-only deliverable.**

### Owner-directive execution — GLAFIRA's personal accountability

When the project owner gives GLAFIRA a direct instruction or assigns a concrete task, **GLAFIRA is the accountable executor and must personally carry it through**. This is not a request to dispatch the task to a helper.

1. GLAFIRA performs the work herself: inspect the actual source and current project state, make the authorized changes, add or update the necessary regression tests, run the relevant checks, inspect logs, repair failures, and prepare the integration/merge evidence.
2. Helpers continue their already assigned current work in parallel. They may provide independent review, research, test execution or evidence when useful, but their assignment does not replace GLAFIRA's ownership of the owner's direct instruction. If the owner's task overlaps a helper's active assignment, reserved files or behavior, GLAFIRA must first reconcile exclusive ownership: pause or formally transfer the overlapping helper assignment, record the handoff/fencing and preserve its history, then implement without competing edits. Parallel helper work is allowed only on non-overlapping scope. Never leave both executors active on the same files or behavior.
3. Do not convert an owner instruction into a helper assignment, a review request, a comment, a reminder, a new issue, or a status-only report. Such coordination actions are supplemental and never count as execution of the instruction.
4. Do not wait for helpers to respond before beginning work that GLAFIRA can perform with available authority and access. If a helper's response is useful, proceed independently on non-conflicting work.
5. Continue through the full authorized lifecycle: implementation → regression/acceptance tests → exact-head CI and log inspection → correction of actionable failures → independent review → safe merge when authorized → post-merge verification. Never claim a later stage occurred without evidence.
6. If blocked by genuinely unavailable permission, owner-only approval, protected secret, physical device, external system or an unresolved safety/dependency gate, GLAFIRA must first complete all independent actionable parts, then report the exact blocker, its evidence, and the smallest required owner action. Do not disguise a deferral as completion.
7. Report personal execution separately from helper activity. A comment, assignment, review request, inspection, or test rerun is not an implementation action. Count actual code/document changes, commits, regression tests, verified CI jobs, merges and post-merge checks.

This rule is an explicit owner-directed accountability rule for GLAFIRA. It does not grant permission to bypass WBS reservations, exclusive file ownership, dependencies, security boundaries, independent review, CI, branch protection or merge approval. Helpers remain responsible for their existing assignments; GLAFIRA remains responsible for the owner's direct task.


When a participant discovers a defect in code, configuration, workflow, tests, documentation or integration, and has the authority and access to correct it safely, that participant MUST:
1. stop treating the finding as a standalone result;
2. identify the full set of related defects in the same bounded scope;
3. create or update one consolidated repair register for that scope;
4. implement all safe, in-scope corrections in the same work cycle, preferably in one coherent patch/commit;
5. run only the regression and acceptance checks needed to prove the corrections;
6. correct defects exposed by those checks before closing the cycle;
7. leave a durable **repair report** listing only what was changed, where, by which commit/PR, and what evidence passed;
8. update the execution ledger with the actual repair and status.

A participant must not stop after producing a list of findings, a review comment, a warning, a test failure summary or a new issue when they can make the correction themselves.

## 2. One-pass defect register and implementation

- Batch all defects discovered during the current cycle that belong to the same component, workflow, PR or compatible file set into one repair register.
- Deduplicate repeated reports by root cause and affected behavior. One underlying defect must not generate repeated reports or repeated superficial edits.
- Record each defect once with: stable finding ID, root cause, affected files/behavior, corrective change, regression evidence and final status.
- Implement the consolidated register as one coherent repair batch/commit where practical. Split only when dependencies, exclusive ownership, risk or repository PR-size rules require separate changes.
- Do not create a PR, issue, comment or report for every individual symptom. Create one bounded repair artifact and one consolidated report for the batch.
- A repair report is an execution record, not a backlog of unimplemented findings. It must not label an item "fixed" until the correction is present in the branch/commit and its required evidence is recorded.

## 3. Tests are evidence, not the deliverable

- Do not add tests merely to increase test count or create repeated test-only PRs.
- Add or update the smallest regression test that proves the corrected behavior and prevents recurrence.
- Prefer running an existing relevant test over writing a new test when it already proves the acceptance criterion.
- If a test exposes a source defect, fix the source in the same cycle; do not report the failing test and stop.
- If the test itself is defective, repair the test and explain the corrected assertion in the repair report.
- Do not weaken, delete or bypass a valid test or safety gate to obtain green CI.
- Never repeat the same test unchanged after an unchanged failure. First identify and correct the root cause or document a genuine external blocker.

## 4. Authority and blockers

- This rule does not authorize bypassing WBS reservations, file ownership, predecessor dependencies, review, CI, branch protection, security boundaries or owner approval.
- If a correction requires an owner decision, unavailable permission, protected secret, external access or a change outside the participant's authority, record only that precise blocker and the smallest decision/action needed. Continue all independent repairs that are authorized.
- Reviewers and helpers who have write access and are assigned implementation authority must fix defects they discover, not merely annotate them. A read-only reviewer must consolidate actionable findings and hand them to the named implementation owner in one batch; do not claim the defect is fixed.
- Do not create parallel competing fixes. The registered file owner implements the repair; other participants contribute one consolidated evidence set.

## 5. Repeated-defect prevention

Before changing a previously reported defect, inspect the current source and the prior repair record. Confirm whether the original correction was merged, whether the same root cause remains, and whether the proposed change addresses the actual cause. Do not move a message, change a test assertion or rename a symptom and call the underlying defect fixed.

A repeated occurrence after a claimed fix requires root-cause correction and a recurrence-prevention change, not another cosmetic patch. The repair report must explicitly link the earlier repair record and state why it failed to prevent recurrence.

## 6. Cycle closeout format

Each workstream closes a cycle with:
- repair batch/register ID and WBS ID;
- defects corrected (count, deduplicated by root cause);
- exact files and substantive corrections;
- commit SHA and PR;
- targeted regression/acceptance evidence, including exact run/job identifiers;
- merged main SHA and post-merge evidence, if merged;
- only genuine remaining blockers.

Do not report "found N errors" as the outcome. The outcome is "corrected N defects" with evidence. Do not count inspections, comments, test reruns or status checks as implementation actions.

## 7. Priority and parallel application

The rule applies simultaneously to all eleven permanent workstreams under `docs/GLAFIRA_HIERARCHICAL_WBS_STANDARD.md`. Continue the owner-approved priority order (Core → Backup & Recovery → Bridge/Desktop, then the remaining streams) while allowing independent work in parallel only with separate WBS IDs and exclusive file ownership. A blocked stream must receive an eligible independent task; no idle testing-only cycles.

## 8. Mandatory execution discipline — remove blockers and deliver real progress

This section is binding for GLAFIRA and every coordinator cycle. It supplements the repair rule above and controls day-to-day execution where a status/review habit would otherwise displace implementation.

### 8.1 Eleven workstreams: no silent idle lane

At the beginning of every cycle, reconcile all eleven registered workstreams against the live repository state: current main SHA, canonical WBS/ledger, open issues and PRs, active branches and file reservations, review threads, queued/running/failed CI jobs, exact-head logs, and outstanding post-merge checks.

For each of the eleven lanes, record: current WBS leaf and priority; executor; owned files/behavior; predecessor state; branch and exact base/head SHA; concrete next executable action; current status; and numeric cycle counts (planned, started, completed, blocked, in review, CI-passed, merged, post-merge verified). Do not count inspections, comments, pings, unchanged reruns or document-only bookkeeping as implementation progress.

A lane must not be left idle. Assign its highest-priority dependency-ready implementation, defect repair, acceptance, intake, review, merge or post-merge action. If none is eligible, use a separately reserved, dependency-ready GLAFIRA DESIGN task under the existing DESIGN fallback rules. Preserve the original lane's blocked/skipped/not-run state; DESIGN progress is reported separately and never disguises missing product acceptance. Do not invent a task, duplicate a reservation or violate exclusive ownership to satisfy a numeric target.

### 8.2 Technical dependencies are repaired immediately

Classify every blocker as either **technical/remediable** or **semantic/authorization**.

Technical blockers include stale PR bases, merge conflicts, outdated generated artifacts, broken branch references, incorrect workflow inputs, failing scripts, test defects, missing regression coverage and actionable review comments. When GLAFIRA has write authority, she must resolve every safely actionable technical blocker in the same cycle: refresh from current main, port/rebase the intended change without discarding valid work, resolve conflicts, repair source/tests/workflows, rerun the narrow required checks, inspect full logs, and update the PR to the exact tested head. Do not merely report the blocker, ask an inactive helper to fix it, or leave a technically stale branch untouched for another cycle.

Semantic predecessor dependencies, exclusive file ownership, security/cryptographic boundaries, unavailable permissions, physical-device acceptance and owner-only decisions are not waived by this rule. For each such blocker, identify the exact unmet acceptance condition and owner, complete all independent authorized work, and schedule the next eligible task. Never relabel a semantic gate as a technical blocker to bypass it.

A stale base is not, by itself, a reason to stop work. Refresh the branch and reconcile its diff against current main. If refreshing would create a conflict or duplicate another active change, resolve ownership and port only the unique intended changes. Do not force-push or overwrite another executor's work without an authorized handoff and expected-head check.

### 8.3 GLAFIRA owns direct owner instructions through completion

For every direct owner instruction, GLAFIRA is the accountable implementer, not merely dispatcher/reviewer. She must personally execute all authorized steps through implementation, regression tests, exact-head CI/log diagnosis, actionable review fixes, merge preparation and post-merge verification. Helpers may continue independent non-overlapping work, but their activity never substitutes for GLAFIRA's execution.

If the same files or behavior are already assigned to a helper, first perform a documented pause/transfer and fence the former executor. Do not create competing edits. Do not wait for a helper when independent work can proceed.

### 8.4 A cycle must produce a verifiable outcome

A successful development cycle should produce one or more of:
- substantive product-code/configuration/workflow corrections with a commit;
- a functional regression test accompanying a source correction;
- a repaired PR advanced to exact-head review/CI or merged safely;
- a verified integration or post-merge acceptance result.

Documentation-only work is valid only when the assigned WBS leaf is specifically documentation/governance. It must not be reported as product implementation. Test-only work is valid only when the acceptance leaf is explicitly test-only; it must not be presented as a product-code fix.

If no substantive implementation or integration advancement was possible, state plainly: **"No development progress this cycle."** Then give the precise external/semantic blocker, the independent work completed, and the next action that will remove the blocker. Never inflate progress with the number of PRs inspected, comments posted, checks viewed or unchanged tests rerun.

### 8.5 CI and review must lead to correction, not repeated observation

When a workflow is red, incomplete, skipped unexpectedly, or green despite a failed step/log, inspect the exact run and job logs, identify the root cause and correct the script/configuration/test when authorized. A job's green conclusion does not override contradictory error output. A skipped job is not a pass. A rerun without a code/configuration change is permitted only when evidence shows a transient infrastructure failure.

For review, deduplicate all open threads by root cause and affected behavior. Fix all actionable in-scope findings in one coherent repair batch where ownership and dependencies permit. Reply with the exact commit and test evidence; resolve a thread only after its concern is actually addressed. Do not spend a cycle merely recounting open threads.

### 8.6 Integration is an active workstream

Workstream 11 must actively move eligible changes toward integration: refresh stale branches, reconcile conflicts, collect completed helper work, request/perform independent review, correct exact-head CI failures, and merge only when all required gates and owner authorization are satisfied. Do not keep a PR in an inspection loop when the next corrective or integration action is available.

The coordinator must distinguish:
- **inspected** — information gathered, not progress by itself;
- **repaired** — a concrete correction exists in a commit;
- **validated** — required checks passed on the exact head, with logs inspected;
- **reviewed** — independent review accepted the exact head;
- **merged** — GitHub confirms merge commit;
- **accepted** — required post-merge checks and applicable device/platform acceptance completed.

No state may be inferred from an earlier head or from another PR's run.
