# Mandatory instructions for all GLAFIRA contributors and agents

Before any work, read in full:
1. `docs/GLAFIRA_HIERARCHICAL_WBS_STANDARD.md` — permanent project/task numbering and strict step order.
2. `docs/GLAFIRA_EXECUTION_CONTROL.md` — startup, ownership, tests, CI, review, merge and reporting gates.
3. `docs/GLAFIRA_150_STEP_COMPLETION_PLAN.md` and `docs/GLAFIRA_EXECUTION_LEDGER.md` — current acceptance plan and evidence history.
4. `.github/copilot-instructions.md` and the relevant architecture/security contracts.

Mandatory: every assignment must name a registered hierarchical WBS leaf ID, predecessor IDs, executor, owned files, acceptance tests and branch. Work one executable step at a time. Do not start a dependent next step until the previous step is accepted and recorded as DONE. Parallelize only independent tasks with separate IDs and exclusive file ownership. Never skip a blocked predecessor, duplicate existing work, push directly to main, bypass CI/review/owner approval, or claim a skipped test passed.

If the required WBS node does not exist, stop and ask the coordinator to register it before implementation. Report exact evidence and the next eligible step after each cycle.

 
## Canonical coordinator and helper assignment rules (2026-11-26)

- The project owner retains priority, direct-assignment, override and stop authority. Current owner-approved order is **Core → Backup & Recovery → Bridge/Windows Desktop**; Android/Desktop remain the primary runtime surfaces but do not displace Core or Backup. Stale dispatcher rankings are subordinate until the owner changes this order.
- GLAFIRA is the operational coordinator. Before assigning new implementation, reconcile both engineering Action Pools, Workstream 11 helper intake, issues, PRs, review requests, CI failures, merge-ready work, branches and active reservations. The Project Dispatcher reports PR/CI evidence only; it does not select or assign tasks.
- Android Core and Windows Desktop Core remain independently owned runtime authorities. Do not collapse independent Core queues into one runtime queue. Workstream 11 is a development-time intake/integration view over WBS and GitHub records, not a second persisted queue or runtime assignment authority.
- One helper may hold at most one active bounded assignment. A transferred task remains active under its replacement until it is completed or formally suspended/handed off; the replacement cannot also receive DESIGN or another task. The former executor is fenced from editing after transfer.
- After three completed coordinator passes without substantive, verifiable progress, transfer the actionable task to GLAFIRA/another qualified executor. Preserve the original status, history and reservation. The returning helper must stop editing the transferred task; a distinct DESIGN package may be assigned only after the transfer and only if the helper has no other active assignment. A genuine owner/permission/dependency/infrastructure blocker remains BLOCKED, not inactivity.
- DESIGN is a mutually exclusive fallback only after the full ready-work scan finds no equal- or higher-priority dependency-ready implementation, validation, intake, review, merge or post-merge work. Preserve the source lane's exact original status.
- The canonical reservation ledger is protected Main. All owner and coordinator assignments use the same exact-Main-SHA compare-and-set path; a reservation becomes active only after its record merges. Serialize reservation merges and re-read Main after each merge before dispatching another item.
- Platform build/release lanes are globally serialized by repository/workflow/lane/configuration. Separate PR concurrency groups do not authorize simultaneous platform builds; inspect actual workflow concurrency controls. Skipped/cancelled/queued/in-progress checks are not passes.
