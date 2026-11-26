# Mandatory instructions for all GLAFIRA contributors and agents

Before any work, read in full:
1. `docs/GLAFIRA_HIERARCHICAL_WBS_STANDARD.md` — permanent project/task numbering and strict step order.
2. `docs/GLAFIRA_EXECUTION_CONTROL.md` — startup, ownership, tests, CI, review, merge and reporting gates.
3. `docs/GLAFIRA_150_STEP_COMPLETION_PLAN.md` and `docs/GLAFIRA_EXECUTION_LEDGER.md` — current acceptance plan and evidence history.
4. `.github/copilot-instructions.md` and the relevant architecture/security contracts.

Mandatory: every assignment must name a registered hierarchical WBS leaf ID, predecessor IDs, executor, owned files, acceptance tests and branch. Work one executable step at a time. Do not start a dependent next step until the previous step is accepted and recorded as DONE. Parallelize only independent tasks with separate IDs and exclusive file ownership. Never skip a blocked predecessor, duplicate existing work, push directly to main, bypass CI/review/owner approval, or claim a skipped test passed.

If the required WBS node does not exist, stop and ask the coordinator to register it before implementation. Report exact evidence and the next eligible step after each cycle.

## Dispatch, queue ownership and non-response handoff

- The development coordination view is reconciled from WBS, issues, PRs, branches and reservations; it is not a second persisted Core queue. Android Core and Windows Desktop Core remain independent authorities for tasks they own. Never create one cross-Core durable queue or move ownership by UI projection.
- The engineering Action Pool is the repository issues/PRs/reviews/branches/workflow runs plus `docs/engineering/ELEVENTH_WORKSTREAM_PR_INTEGRATION.md` and coordinator issue #548. The GLAFIRA DESIGN/helper Action Pool is issue #672 and its child design issues under `docs/design/`. Reconcile both before assigning DESIGN. Product priority is Core → Backup & Recovery → Bridge/Windows Desktop → Android/iOS → Windows Agent/specialists → Multi-model/Token → DESIGN; CI/Security gates are cross-cutting. Workstream 11 is integration support, not a product-priority tier.
- One executor has one active leaf. Suspend and record a BLOCKED assignment, preserve its reservation and revoke its former executor's write access before reassigning the freed slot. Release a terminal CANCELLED/SUPERSEDED reservation only after the branch is quiesced and the former executor cannot push.
- After three evidenced unanswered coordinator passes, initiate transfer to GLAFIRA by default, but activate it only after the former executor's write grant is revoked, the process/environment is confirmed stopped, and the old generation is fenced. If any proof is missing, keep the task BLOCKED and reservation held. GLAFIRA must suspend or hand off any current active leaf before taking the transferred leaf; the transferred leaf is its only active assignment. The former helper may receive DESIGN only after losing edit rights and after the full ready-work scan. A returning helper must notify the coordinator and must not resume the transferred branch.
- Independent leaves may run across lanes only with unique IDs and executors, no unfinished predecessor, and no overlapping files/APIs/schemas/resources. A shared completed predecessor is allowed.
- Every reservation must identify an independent reviewer distinct from the author/executor; resolve all blocking findings on the current head before merge.