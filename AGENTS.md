# Mandatory instructions for all GLAFIRA contributors and agents

Before any work, read in full:
1. `docs/GLAFIRA_HIERARCHICAL_WBS_STANDARD.md` — permanent project/task numbering and strict step order.
2. `docs/GLAFIRA_EXECUTION_CONTROL.md` — startup, ownership, tests, CI, review, merge and reporting gates.
3. `docs/GLAFIRA_150_STEP_COMPLETION_PLAN.md` and `docs/GLAFIRA_EXECUTION_LEDGER.md` — current acceptance plan and evidence history.
4. `.github/copilot-instructions.md` and the relevant architecture/security contracts.

Mandatory: every assignment must name a registered hierarchical WBS leaf ID, predecessor IDs, executor, owned files, acceptance tests and branch. Work one executable step at a time. Do not start a dependent next step until the previous step is accepted and recorded as DONE. Parallelize only independent tasks with separate IDs and exclusive file ownership. Never skip a blocked predecessor, duplicate existing work, push directly to main, bypass CI/review/owner approval, or claim a skipped test passed.

If the required WBS node does not exist, stop and ask the coordinator to register it before implementation. Report exact evidence and the next eligible step after each cycle.


## Mandatory multi-step cycle cadence — all eleven workstreams

The owner has approved dependency-aware multi-step packages across all eleven permanent workstreams. At each coordinator cycle, plan 3–5 real executable leaf steps per lane where the live backlog permits; this is a planning target, never a quota. Do not invent work, duplicate a task, split acceptance artificially, or bypass predecessors.

**One helper = one active work item at a time remains mandatory.** The coordinator may pre-plan a sequence, but assigns only the next leaf after the current item is completed and its evidence reviewed or explicitly released as blocked. Helpers never self-select or switch lanes. Independent leaves may run concurrently across lanes or within one lane only with distinct WBS IDs, executors, branches and exclusive file/behavior ownership; sibling status is not required. Dependent leaves wait for accepted predecessors; build/release lanes remain serialized.

A leaf is complete only at canonical `DONE` (acceptance, independent review of the current head, exact-head CI, merge and post-merge checks). Keep file/behavior reservations until `DONE`; on authorized terminal `CANCELLED` or `SUPERSEDED`, record the reason/replacement and release the reservation without claiming DONE. For a nonterminal suspension/handoff, transfer or retain the reservation and prohibit the prior executor from editing. Whenever a helper slot opens, restart priority evaluation at P0; the planned successor is a candidate, not the automatic next assignment. If blocked, the coordinator may first formally suspend/transfer the blocked assignment, preserve its status and reservation, and stop the former executor from editing. Then dispatch the highest-ranked eligible equal/higher-priority global-ready implementation, validation, intake, review, merge or post-merge item. Only if none exists may an available helper receive a separate bounded GLAFIRA DESIGN item from issue #672. Preserve the original blocker/status, and resume the primary lane through an explicit reservation handoff when unblocked. Design never substitutes for code, tests, security review, physical acceptance or merge gates.

Workstream 11 coordinates intake, deduplication, exclusive reservations, exact-diff review and safe integration. Cross-lane/per-lane queues are projections of the same Core durable queue, not additional persisted queues or assignment authorities. Track planned/assigned/completed/blocked/review/CI/merged evidence per lane. Reuse the existing Core durable queue, owner generation/fencing, grants, Trace, ACK and recovery for runtime orchestration; do not create a competing queue or use GitHub as real-time runtime coordination. All existing security, owner-approval, CI serialization, PR-size, review and protected-Main rules remain in force.
