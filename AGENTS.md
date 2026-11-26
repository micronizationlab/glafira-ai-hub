# Mandatory instructions for all GLAFIRA contributors and agents

Before any work, read in full:
1. `docs/GLAFIRA_HIERARCHICAL_WBS_STANDARD.md` — permanent project/task numbering and strict step order.
2. `docs/GLAFIRA_EXECUTION_CONTROL.md` — startup, ownership, tests, CI, review, merge and reporting gates.
3. `docs/GLAFIRA_150_STEP_COMPLETION_PLAN.md` and `docs/GLAFIRA_EXECUTION_LEDGER.md` — current acceptance plan and evidence history.
4. `.github/copilot-instructions.md` and the relevant architecture/security contracts.

Mandatory: every assignment must name a registered hierarchical WBS leaf ID, predecessor IDs, executor, owned files, acceptance tests and branch. Work one executable step at a time. Do not start a dependent next step until the previous step is accepted and recorded as DONE. Parallelize only independent tasks with separate IDs and exclusive file ownership. Never skip a blocked predecessor, duplicate existing work, push directly to main, bypass CI/review/owner approval, or claim a skipped test passed.

If the required WBS node does not exist, stop and ask the coordinator to register it before implementation. Report exact evidence and the next eligible step after each cycle.

 
## Canonical coordinator and helper assignment rules (2026-11-26)

- The owner retains priority, direct-assignment, override and stop authority. Current order is Core → Backup & Recovery → Bridge/Windows Desktop; Android/Desktop remain primary runtime surfaces but do not displace Core/Backup. Stale dispatcher rankings are subordinate.
- GLAFIRA coordinates operational selection. Before new implementation, reconcile both engineering Action Pools, Workstream 11 helper intake, issues, PRs, review requests, CI failures, merge-ready work, branches and reservations. Project Dispatcher is PR/CI evidence only.
- Android Core and Windows Desktop Core remain independent runtime authorities. Workstream 11 is development-time intake/integration, not a second runtime queue.
- One helper has at most one active assignment. Before reassignment, suspend/transfer the old task, preserve status/reservation and fence the former executor. After three completed passes without substantive evidence, transfer actionable work to GLAFIRA/qualified executor; the returning helper stops editing it. The replacement cannot also receive DESIGN or another task.
- DESIGN is fallback only after no equal/higher-priority dependency-ready implementation, validation, intake, review, merge or post-merge task remains. Preserve the original lane status; genuine external/owner blockers remain BLOCKED.
- Reservations use protected Main, exact-SHA compare-and-set and serialized merge-before-dispatch. The current ruleset has no merge queue/strict checks, so coordinator single-flight is procedural; stop and reconcile after any unexpected Main advance or bypass.
- Build/release lanes are globally serialized by repository/workflow/lane/configuration; PR-specific concurrency groups do not prove serialization. Inspect actual workflow controls; skipped/cancelled/queued/in-progress are not passes.

- PR-size exceptions are approved in writing **before implementation begins**. For 201–300 changed lines, approval at PR submission is retrospective and invalid. A separate file-count-only exception permits 6–10 files for a coherent change of up to 200 lines only with the same prior written approval, exact file list and reason. More than 10 files or more than 300 lines must be split.
