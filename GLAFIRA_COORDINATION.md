# GLAFIRA AI HUB — Central multi-agent coordination

**Authority:** Валерий Корчагин, project owner.  
**Engineering coordinator:** central GLAFIRA.  
**Repository:** https://github.com/micronizationlab/glafira-ai-hub  
**Status:** active coordination contract; this file is the first mandatory read for every invited AI collaborator.  
**Updated:** 2026-11-21

> This file is a living instruction channel, not proof that a feature is implemented. Always verify current GitHub state and exact-head evidence.

## 1. Mandatory entry sequence

Before doing any project work, every invited ChatGPT/AI account must:

1. Read this file completely.
2. Read [the collaborator handoff](docs/GLAFIRA_COLLABORATOR_HANDOFF.md).
3. Read [the engineering roadmap](docs/IMPLEMENTATION_ROADMAP.md).
4. Read [the mandatory dispatcher rule](docs/ENGINEERING_DISPATCHER_OPERATING_RULE.md).
5. Read [the ordered 150-step completion plan](docs/GLAFIRA_150_STEP_COMPLETION_PLAN.md) and [Action Pool A](docs/ENGINEERING_ACTION_POOL_A.md), then inspect the current issues and open PRs directly on GitHub.
6. Read the [shared task board](docs/coordination/TASK_BOARD.md), then read the specific issue/specification and relevant code before claiming or changing a task.
7. Register using a unique participant file under `docs/coordination/participants/<participant-id>.md` based on [the participant template](docs/coordination/PARTICIPANT_TEMPLATE.md). Do not edit another participant's file.
8. Check for conflicts and dependencies before starting. If registration cannot be committed, identify yourself in a GitHub issue/PR comment and do not assume a task is reserved.

If this file conflicts with an older snapshot document, follow the current owner priorities below and report the inconsistency. Do not silently rewrite historical records.

## 2. Authority and communication

- The project owner is the final product and release authority.
- Central GLAFIRA coordinates cross-workstream priorities, resolves task conflicts and updates this file.
- Collaborating agents are independent contributors. They may inspect, research, design, code, test, document, open issues and prepare PRs within repository permissions.
- The canonical coordination channel is this file plus participant status files, GitHub issues, PR descriptions/comments and review threads. ChatGPT accounts do not automatically share private chat context or communicate directly.
- Re-read this file at the beginning and end of each work block, before taking a new task, and before any consequential action. Follow newly committed instructions.
- A participant must not assume that a prior instruction remains current after another coordinator update.
- If the file cannot be read or GitHub access is unavailable, stop repository mutations and report the blocker to the owner.

## 3. Current owner priority order

The owner's currently confirmed execution hierarchy is:

1. **P1 — GLAFIRA Core:** durable ownership/state, SQLite concurrency, bounded workers, dependency scheduling, cancellation, crash recovery, idempotency, result delivery/ACK, audit and policy.
2. **P2 — Backup & Recovery:** encrypted portable backups, integrity verification, restore, interrupted-restore recovery and verified off-device copies.
3. **P3 — Runtime / Bridge / Desktop:** Android instrumentation and Bridge contracts/pairing; Windows Desktop Hub; Windows Agent; durable delivery, fencing, heartbeat, reconnect and end-to-end acceptance. Respect dependencies inside this group: shared Bridge contracts and authenticated pairing precede production fleet execution; Desktop host/scheduler precedes full Agent-fleet acceptance.
4. **P4 — Specialist agents and optional expansions:** specialist roles, connectors, voice extensions, GCU/GLF economics, VPN/marketplace integrations and other deferred modules. Independent research may continue in parallel only when it does not displace P1–P3.

**Priority consistency:** issue [#363](https://github.com/micronizationlab/glafira-ai-hub/issues/363) tracks reconciliation of older roadmap/dispatcher/action-pool snapshots with this owner-confirmed hierarchy. Until that reconciliation PR is reviewed, invited collaborators must follow this section for dispatch. Preserve detailed technical acceptance criteria and dependencies from existing specifications; reorder work, do not discard it.

A higher-priority task waiting for CI, review, hardware or a conflicting file is not a reason to idle. Start the highest-priority independent action available, then rescan from P1 when a slot opens.

## 4. Maximum safe multitasking

- Maintain a rolling pool of at least 50 concrete, independently actionable tasks; see Action Pool A and the dispatcher rule.
- Work on multiple independent tracks concurrently when tools, files, dependencies and CI capacity permit.
- Never create conflicting simultaneous writers for the same file, schema, subsystem or branch.
- Use a separate focused branch and PR for each coherent change. Prefer non-overlapping files for parallel work.
- Before coding, inspect open PRs, branches, recent commits and exact-head CI. Do not duplicate work already in flight.
- If a task is blocked, record the specific blocker and immediately take the next highest-priority independent task.
- Parallelism is not a target number of simultaneous edits. It is useful throughput without conflicts, unsafe merges or false completion claims.

## 5. The 150-step program — 20 full cycles per participant

The 150-step program is the mandatory operating algorithm for all GLAFIRA development, not merely a static checklist. It applies to central GLAFIRA and every invited ChatGPT/AI account independently. Each participant must complete **20 full cycles**; the requested target is 3,000 distinct, evidence-backed actions per participant.

**Mandatory cycle sequence (repeat exactly 20 times):**

1. **Capacity and portfolio audit first.** Before starting implementation in each cycle, inspect the current repository, backlog, open branches/PRs, CI state, dependencies, available tools and any hardware/provider constraints. Determine and record how many distinct projects/workstreams can safely be advanced in parallel in this cycle. Do not assume a fixed concurrency number; calculate it from actual independence, file ownership, dependency gates and validation capacity.
2. **Select and schedule the parallel portfolio.** Choose the maximum useful set of non-conflicting projects/tasks, ordered by the owner's current priorities. Record each workstream, its owner, scope, dependencies, branch/files and validation gate. Do not count several subtasks in one tightly coupled change as independent projects.
3. **Execute the portfolio.** Start the selected independent workstreams in parallel where tools permit. Drive each to a verifiable result. When one workstream is waiting for CI, review, hardware or another dependency, immediately advance the next highest-priority independent workstream; return when the blocker clears.
4. **Verify and record.** Inspect actual test/build logs and exact-head evidence, record completed/partial/blocked work, and update the participant ledger and relevant GitHub issues/PRs. A skipped check is not a pass.
5. **Reassess before the next cycle.** Re-scan the project state and repeat the capacity audit from step 1. The next cycle must adapt to merged changes, newly discovered work, regressions, changed priorities and dependencies.

Rules:

1. Use [the ordered 150-step completion plan](docs/GLAFIRA_150_STEP_COMPLETION_PLAN.md) as the shared project baseline, then create each cycle's 150 numbered, concrete actions from a fresh audit of the current repository, backlog, CI, architecture and owner priorities. The shared plan is a starting map, not a list to copy blindly.
2. Each action must have a tangible output: code change, test, reproducible diagnosis, reviewed finding, accepted documentation/specification improvement, verified research result, or a new actionable issue with evidence and acceptance criteria.
3. Record each action's unique ID, short description, priority, output link, validation/evidence and status in the participant's own progress file.
4. Do not pad counts by splitting trivial edits, rewording the same finding, repeating an action, creating duplicate issues, or counting an unverified proposal as completed.
5. A repeated check counts as a new action only when it tests a materially new state, scenario, commit, platform or acceptance condition and records the difference.
6. At the start of every cycle, re-audit the current project. Do not replay the same 150-item list blindly: incorporate merged work, new regressions, newly unblocked dependencies and changed owner priorities.
7. Each cycle must contain a balanced portfolio of implementation, testing, reliability/security review, documentation and backlog improvement as applicable. Never sacrifice quality to hit a quota.
8. A cycle is complete only when all 150 entries have evidence and the participant has written a cycle summary. Partial cycles must be labelled partial.
9. The participant's 20 cycles are cumulative across sessions and must survive context loss in the participant progress file and GitHub history.
10. After cycle 20, stop the 150-step execution loop. Do not silently roll into cycle 21. Enter the mandatory project-wide audit described below, then prepare a comprehensive report for the participant's owner, distinguishing merged changes, open PRs, tests actually executed, skipped checks, unresolved risks and remaining work.

### Mandatory audit after cycle 20 — stop and inspect GLAFIRA as a whole

After the twentieth cycle, each participant must pause ordinary cycle execution and perform a broad reconciliation audit with central GLAFIRA. At minimum:

- Inspect current `main`, all relevant active and stale branches, open/closed/merged PRs, and commit history; identify abandoned, superseded, conflicting or still-useful work.
- Review historical Android APK/build artifacts and other platform builds where available. Compare their versions, included features, regressions and known defects with the current source; do not assume an older build is authoritative or automatically discard it.
- Revisit old issues, specifications, architecture notes, handoff documents, meeting/chat decisions and owner requests to identify requirements or ideas discussed earlier but never added to the repository, backlog, implementation or tests.
- Reconcile the 150-step plan, Action Pool, roadmap, task board and participant ledgers. Identify missing work, duplicate work, stale priorities, hidden dependencies and unverified completion claims.
- Check that the free-only/no-required-paid-resources constraint and all current owner priorities are reflected in the design and dependency choices.
- Produce an evidence-linked gap report: recovered requirements, useful legacy changes, missing features, regressions, security/reliability gaps, proposed issues/PRs, and recommended next priorities. Do not merge or discard historical work without owner approval.

The audit is a deliberate stop point, not an extra unrecorded cycle. After reporting it, wait for the owner/central coordinator to authorize the next program phase.

**Important feasibility rule:** 3,000 is a requested cumulative target, not permission to manufacture activity or make unsafe changes. If no 150 distinct, useful, verifiable actions remain in a cycle, document the shortage and ask the owner/central coordinator for fresh scope. Never falsely declare a cycle or the 20-cycle program complete.

## 6. Task reservation and participant files

- Every participant uses a stable, unique participant ID (for example `gpt-valery-01`, chosen by the owner).
- Register in `docs/coordination/participants/<participant-id>.md` using the template.
- Each participant file is owned by that participant. Other contributors must not overwrite it; propose corrections in a comment or ask central GLAFIRA.
- A task is not reserved merely because it appears in a private chat. Record the issue/PR, files, branch, priority, dependencies, start time and expected deliverable in the participant file.
- Before claiming a task, check the task board, open PRs and other participant files. If overlap exists, coordinate first.
- The central coordinator maintains the shared task board and this file. Participants report status via their own file and the related issue/PR.

## 7. Git and release safety

- Create focused branches from the correct current base. Avoid force-pushes and history rewrites.
- Do not overwrite other contributors' work or commit directly to `main`.
- Add tests with code changes. Run the narrowest relevant tests, then required broader checks.
- Verify CI against the exact current PR head SHA. Inspect job logs and test counts; skipped jobs are not passes.
- Distinguish code committed, PR opened, CI passed, tests executed, merged, deployed and physically accepted.
- Keep PRs draft while dependencies or acceptance gates are incomplete.
- **No participant may merge to `main`, bypass branch protection, close another participant's task, or publish/release without explicit owner approval.**
- Never put credentials, private keys, access tokens, personal data or secrets in source, issues, logs or coordination files.
- No irreversible external action, production data mutation, spending, publishing or destructive operation without the required owner approval.

## 8. Completion reporting

Every work block should end with a concise update in the participant file and relevant issue/PR:
- participant ID and cycle/action IDs;
- tasks started/completed/blocked;
- branch, PR and exact head SHA;
- CI run IDs, job outcomes, test counts and skipped checks;
- files changed and evidence links;
- conflicts or risks;
- next highest-priority independent actions.

Never claim background work between sessions unless a real scheduled automation exists. Never claim success without tool evidence.

## 9. Current Connect architecture work

GLAFIRA Connect mobile invitation bootstrap and adaptive P2P/Wi-Fi route migration is tracked in [issue #361](https://github.com/micronizationlab/glafira-ai-hub/issues/361). It may proceed at architecture/specification level in parallel. Its runtime implementation must not displace Core, Backup/Recovery, Bridge and Desktop dependencies. Do not expose raw IP as identity; use immutable account identity, signed short-lived invitations, rendezvous, ICE/STUN/TURN and end-to-end identity verification.

## 10. Coordinator change log

- 2026-11-21: initial central multi-agent coordination contract created. Links the existing collaborator handoff, roadmap and dispatcher rule; formalizes per-agent status files and 20 × 150 evidence-based cycles.
- 2026-11-21: clarified mandatory cycle order (capacity audit → parallel portfolio selection → execution → evidence review → fresh reassessment) and the stop-after-cycle-20 whole-project audit, including legacy builds/branches and previously discussed but unrecorded requirements.
