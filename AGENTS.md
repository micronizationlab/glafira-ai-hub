# Mandatory instructions for all GLAFIRA contributors and agents

Before any work, read in full:
0. `docs/GLAFIRA_CODEX.md` — mandatory immediate repair and one-pass defect closure for all participants.
1. `docs/GLAFIRA_HIERARCHICAL_WBS_STANDARD.md` — permanent project/task numbering and strict step order.
2. `docs/GLAFIRA_EXECUTION_CONTROL.md` — startup, ownership, tests, CI, review, merge and reporting gates.
3. `docs/GLAFIRA_150_STEP_COMPLETION_PLAN.md` and `docs/GLAFIRA_EXECUTION_LEDGER.md` — current acceptance plan and evidence history.
4. `.github/copilot-instructions.md` and the relevant architecture/security contracts.

Mandatory: every assignment must name a registered hierarchical WBS leaf ID, predecessor IDs, executor, owned files, acceptance tests and branch. Work one executable step at a time. Do not start a dependent next step until the previous step is accepted and recorded as DONE. Parallelize only independent tasks with separate IDs and exclusive file ownership. Never skip a blocked predecessor, duplicate existing work, push directly to main, bypass CI/review/owner approval, or claim a skipped test passed.

If the required WBS node does not exist, stop and ask the coordinator to register it before implementation. Report exact evidence and the next eligible step after each cycle.

 
## Canonical coordinator and helper assignment rules (2026-11-26)

- The owner retains priority, direct-assignment, override and stop authority. Complete order: P1 Core → P2 Backup & Recovery → P3 Bridge / Windows Desktop-Hub / Android client integration → P4 iOS and specialist-agent feature work → P5 Token/GCU (non-issuing absent separate owner approval). Bridge/Hub prerequisites precede dependent Android pairing. Stale rankings are subordinate.
- GLAFIRA coordinates operational selection. Always reconcile existing engineering Action Pool A and Workstream 11 helper intake, plus issues, PRs, reviews, CI, merge-ready work, branches and reservations. Reconcile Pool B only when it exists, or when Pool A is exhausted and Pool B is due to be created; never create Pool B prematurely or block safe Pool A work waiting for a nonexistent pool. Project Dispatcher is PR/CI evidence only.
- Android Core and Windows Desktop Core remain independent runtime authorities. Workstream 11 is development-time intake/integration, not a second runtime queue.
- One helper has at most one active assignment. Before reassignment, suspend/transfer the old task, preserve status/reservation and fence the former executor. After three consecutive completed coordinator passes without substantive, verifiable evidence, transfer actionable work to GLAFIRA/qualified executor; any substantive response resets the consecutive-pass count; the returning helper stops editing it. The replacement cannot also receive DESIGN or another task.
- DESIGN is fallback only after no equal/higher-priority dependency-ready implementation, validation, intake, review, merge or post-merge task remains. Preserve the original lane status; genuine external/owner blockers remain BLOCKED.
- Reservations use protected Main, exact-SHA compare-and-set and serialized merge-before-dispatch. The current ruleset has no merge queue/strict checks, so coordinator single-flight is procedural; stop and reconcile after any unexpected Main advance or bypass.
- Global cross-PR build serialization is NOT IMPLEMENTED. Android uses PR-keyed concurrency, iOS uses ref-keyed concurrency, and Windows Agent/deployment/Desktop workflows lack a shared global queue. Issue #744 tracks a durable non-dropping FIFO queue/lock. Until it is implemented and verified, parallel platform build dispatch is BLOCKED; do not describe separate PR runs as serialized. Skipped/cancelled/queued/in-progress are not passes.

- PR-size exceptions require written owner/integrator approval **before implementation begins**. A file-count-only exception permits 6–10 files only for a coherent change of <=200 changed lines, with rationale and exact file list. A 201–300-line change requires a separate prior written line-count approval. If a 201–300-line change also touches 6–10 files, both exceptions and both rationales/file list are required. More than 300 changed lines or more than 10 files must be split.

## Mandatory repair-on-discovery rule

Every participant with write authority must follow `docs/GLAFIRA_CODEX.md`: correct every safely actionable defect in the same cycle, batch related defects into one repair register/implementation, and leave only a repair report with committed changes and evidence. Finding-only comments, repeated cosmetic fixes and test-only activity are not acceptable cycle outcomes.
