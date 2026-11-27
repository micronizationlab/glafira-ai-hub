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


## 8. Mandatory finding → correction → verification trace (all workstreams)

This rule is universal. It applies to every finding from code review, CI, tests, security scans, manual inspection, user reports, production incidents, documentation review and helper submissions across all eleven workstreams. No finding may be treated as closed merely because a comment was resolved or a commit was created.

For **each individual finding**, the repair register and the execution ledger MUST preserve one traceable record containing all applicable fields below. Fields 3 and 4 describe a repair and are not required for `NOT_REPRODUCED` or `WONT_FIX`; those dispositions must instead satisfy the evidence requirements in field 5:

1. **Finding:** stable finding/thread/incident ID, exact concern, affected behavior and file/line or workflow/job evidence.
2. **Root cause:** the confirmed cause, or an explicit UNCONFIRMED status when evidence is insufficient.
3. **Correction (repair dispositions only):** the exact change made, affected file(s), commit SHA and PR/merge SHA. For `BLOCKED`, state the precise blocker and owner action, linking any partial correction when one exists. `NOT_REPRODUCED` and `WONT_FIX` do not require a correction or commit; use their required evidence and accountable decision in field 5 instead.
4. **Verification (repair dispositions only):** identify the specific test, review assertion, static check, workflow/job or manual acceptance procedure that proves the correction; include exact command or check name, run/job ID, tested commit SHA and actual outcome. For a documentation-only correction, identify the exact diff/section reviewed and applicable successful documentation/CI checks. `NOT_REPRODUCED` and `WONT_FIX` do not require a test proving a nonexistent correction; provide the reproduction or technical-decision evidence required by field 5.
5. **Disposition:** FIXED_AND_VERIFIED, FIXED_PENDING_VERIFICATION, BLOCKED, NOT_REPRODUCED or WONT_FIX. For FIXED_AND_VERIFIED, FIXED_PENDING_VERIFICATION and BLOCKED, link the exact correction/commit when one exists and the verification evidence or explicit pending check. For NOT_REPRODUCED, link the reproduction attempt, environment, inputs and observed result; for WONT_FIX, link the technical analysis and explicit accountable owner decision. These two dispositions do not require a nonexistent correction, but must never substitute for a repair that is still required. A skipped, cancelled, queued, in-progress, stale or unrelated check is never verification.

### Required one-to-one accounting

- Every finding has a distinct row or uniquely keyed record. For repair dispositions (`FIXED_AND_VERIFIED`, `FIXED_PENDING_VERIFICATION`, `BLOCKED`), each finding must explicitly point to its correction and verification evidence. For `NOT_REPRODUCED` and `WONT_FIX`, link the evidence and accountable decision required by field 5 instead; never invent a correction or check.
- Every correction must point back to all findings it addresses. Every verification for a repair disposition must identify the correction/commit it validates. For `NOT_REPRODUCED` and `WONT_FIX`, preserve the evidence/decision chain without a nonexistent correction. This creates a bidirectional, auditable chain: **all repair findings → all corrections → all checks**, with explicit evidence-based dispositions for non-repair findings.
- Grouping is allowed for implementation efficiency only; it must never erase the per-finding mapping or leave an implicit relationship for the reader to infer.
- The register is the index; the ledger is the append-only evidence history. Do not duplicate reservation occurrences or rewrite historical evidence. Add a new event when a finding's status or evidence changes.
- A finding can be marked FIXED_AND_VERIFIED only after the exact correction is present in the tested commit and the relevant verification has actually completed successfully. A later source change invalidates evidence for the changed scope and requires fresh verification.
- Review-thread resolution, issue closure, a green aggregate workflow, or a successful unrelated test does not independently prove the finding is fixed. Verify the specific concern against the diff and the appropriate check.
- When one check covers multiple findings, list every covered finding ID in that check record and explain the coverage. When a finding has multiple acceptance conditions, enumerate each condition and its evidence; partial coverage remains pending.
- At cycle closeout, reconcile counts by disposition: total findings; `FIXED_AND_VERIFIED`; `FIXED_PENDING_VERIFICATION`; `BLOCKED`; `NOT_REPRODUCED`; and `WONT_FIX`. The five disposition counts MUST sum to the total. Separately report findings with corrections, findings verified, blocked/unverified findings, correction commits and verification runs; these metrics must reconcile with the individual records and must not imply that `NOT_REPRODUCED` or `WONT_FIX` had a correction.

No workstream, helper, reviewer, coordinator or PR is exempt. This trace is mandatory before reporting a repair batch complete and before resolving a review finding as fixed.
