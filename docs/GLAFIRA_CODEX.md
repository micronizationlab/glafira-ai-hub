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


## 8. Independent workstreams and independent releases — default rule

The eleven workstreams are parallel product areas, not a mandatory serial release train. The coordinator MUST keep their delivery and release paths independent unless a real technical dependency is demonstrated.

1. **No artificial release chains.** Do not create a parent PR, reservation PR, umbrella PR or unmerged branch merely to make an unrelated workstream wait. A PR in one lane must not be a merge prerequisite for another lane unless the second change genuinely consumes an API, schema, protocol, migration or artifact that does not yet exist in Main.
2. **Main is the default base.** Every independent implementation or design package starts from the latest verified `main` SHA and targets `main`. Do not stack PRs on unmerged PR branches. A stacked PR is permitted only for inseparable changes to one deliverable; document the exact technical dependency and close/retarget the stack as soon as that dependency lands.
3. **WBS dependencies are technical, not administrative.** A predecessor is valid only when its accepted output is required by the child. Shared review calendars, a common document, the desire to serialize all merges, or a parent issue do not create a technical dependency.
4. **Avoid shared-file bottlenecks.** Independent lanes must use lane-owned files and contracts. Do not make every lane edit the same execution ledger, roadmap or WBS file. The coordinator/integrator maintains cross-lane indexes in a separate integration change; lane deliverables remain independently reviewable and mergeable. Where a shared record must be updated, use a short, isolated integration update and do not attach unrelated product work to it.
5. **Release each accepted slice on its own.** Each lane may open, review, test and merge its own small release as soon as its actual dependencies and acceptance gates pass. Do not hold a ready release for unrelated lanes, and do not batch independent deliverables into one mega-release.
6. **A dependency must be explicit and minimal.** Record the exact producer artifact/API/version, consumer need, acceptance condition and removal point. If a consumer can work against a stable interface, mock, fixture or documented contract, it should proceed independently rather than wait for the producer's entire release.
7. **No speculative reservations.** Do not create WBS parent/child reservation PR chains for work that can be reserved in the canonical ledger before editing. Use a focused reservation artifact only where repository policy or a real shared-resource conflict requires it. A reservation is not a product release and must never be presented as implementation progress.
8. **Freshness is not a reason to serialize unrelated work.** Refresh each PR from Main immediately before merge, re-check its exact head, base SHA, changed-file overlap, review and CI. If Main moved, refresh only the affected PR; do not force all other lanes into a common rebase train.

A lane's release independence never waives its own tests, security, review, owner approval or post-merge verification. It removes only artificial dependencies on unrelated lanes.

## 9. Mandatory DESIGN fallback for every lane without executable work

A workstream must never disappear from a cycle merely because it is deferred, has no issue assigned, is waiting for a build/device, is blocked by a dependency, or has no implementation task ready. The absence of a concrete task is itself a dispatch gap that the coordinator must repair.

1. At the start of each cycle, scan every workstream's ready backlog and active assignments. If the lane has no concrete, authorized, dependency-ready implementation/review/research task that can produce a substantive result in this cycle, assign it one bounded **GLAFIRA DESIGN** package.
2. This rule explicitly includes the deferred Multi-AI/specialist lane and the Token/GCU lane. They must work on independent product/UX/architecture design packages while runtime implementation remains deferred. Do not report them as active implementation lanes or invent implementation progress.
3. A DESIGN package must have its own WBS leaf, issue, one accountable executor, exact allowed artifact/files, acceptance criteria, independent reviewer and branch from current Main. Prefer one standalone `docs/design/<LANE>_*.md` deliverable. Do not make it wait on an unrelated design package or a global design-system PR; state any visual assumptions locally and reconcile them later.
4. A DESIGN package must produce a tangible artifact: user flows, screen/state specification, information architecture, interaction rules, data disclosure, error/empty/loading states, accessibility and implementation acceptance criteria as applicable. A comment saying "work on design" is not a deliverable.
5. Keep the original lane status truthful (`DEFERRED`, `BLOCKED`, `WAITING_FOR_HARDWARE`, etc.). DESIGN is a separate active work item; it does not turn deferred implementation into active implementation and does not satisfy implementation/test/merge acceptance.
6. One executor receives one active bounded package at a time. If the designated executor does not respond for three consecutive coordinator cycles, transfer the package to GLAFIRA or another qualified executor, preserve history and notify the previous executor. Do not let a non-responsive helper cause the lane to idle.
7. Re-evaluate the lane after each cycle. When a real implementation task becomes ready, prioritize it according to owner-approved priorities; finish or hand off the design package cleanly without discarding its artifact.

The coordinator must publish a per-cycle 11-lane matrix with, for every lane, the concrete work item, executor, artifact/PR, status and next action. A blank lane or a lane described only as "deferred" is a failed dispatch cycle.
