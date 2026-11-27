# GLAFIRA CODEX — immediate repair, one-pass defect closure

**Status:** mandatory project-wide execution rule  
**Owner:** Валерий Корчагин  
**Coordinator:** GLAFIRA  
**Applies to:** all eleven workstreams, every human contributor, AI assistant, coding agent, reviewer, helper and any participant with permission to change code or project artifacts.

## 1. Core rule

**Finding a defect creates an immediate duty to repair it, not a report-only deliverable.**

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
