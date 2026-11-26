# GLAFIRA Engineering Dispatcher — Mandatory Operating Rule

**Status:** mandatory project execution policy  
**Owner:** GLAFIRA project engineering coordinator  
**Effective:** 2026-11-20

## 1. Continuous priority-aware dispatch

Maintain a rolling backlog of at least 50 concrete, independently actionable work items ("Action Pool A"). Each item must have:
- priority and dependency links;
- exact repository area / files or external research scope;
- expected deliverable and acceptance criteria;
- conflict/resource notes;
- current status, owner, branch/PR/issue and latest validation SHA.

When Action Pool A is exhausted of currently safe work, prepare Action Pool B with at least 50 additional actions. After every completed or newly unblocked action, re-evaluate Pool A from its highest priority before taking another item from Pool B. Refill pools continuously; never let the queue run empty.

Do not interpret "50 actions" as permission to make 50 simultaneous conflicting edits. Parallelize only independent work. Use separate branches/worktrees or non-overlapping files and avoid multiple writers to the same file or subsystem. Respect dependency order and merge boundaries.

## 2. Priority and dependency order

This section is subordinate to the current owner-confirmed priority in the Completion Plan and Execution Control (2026-11-26). The older P0/P1/P2 ranking is historical and must not be used for active dispatch.

1. **P1 — Core:** correctness, durability, ownership/state, bounded execution, dependencies, cancellation, recovery, idempotency, result delivery and audit.
2. **P2 — Backup & Recovery:** encrypted/versioned backup, integrity, restore, recovery staging, rollback safety and clean-device acceptance.
3. **P3 — Bridge / Windows Desktop-Hub / Android client integration:** authenticated Bridge contracts, pairing, durable ingress/ACK, fencing, Desktop Hub host and dependent Android pairing/client work. Bridge/Hub prerequisites precede dependent Android pairing.
4. **P4 — iOS and specialist agents:** protocol alignment, connectors and agent features after the foundation.
5. **P5 — Token/GCU:** non-issuing calculator/specification only unless separately authorized by the owner.
6. **Cross-cutting integration:** review, CI, merge and post-merge actions for eligible higher-priority items may be selected before lower-priority implementation. This is not a product priority or second runtime queue.

Android and Windows Desktop remain primary runtime surfaces, but no application feature work displaces Core or Backup. When a higher-priority item is blocked, select the highest-priority independent ready task only after recording the blocker and rescanning both Action Pools.
## 3. Mandatory GitHub visit protocol

At every GitHub visit:
1. Refresh the repository's current open PRs, open issues, branches relevant to active work, and latest workflow runs.
2. Compare exact PR head SHAs with the SHAs tested by CI. A green run for an older SHA is not acceptance for the current head.
3. Detect every status transition since the previous inspection and inspect the relevant run/job logs, including successful, failed, cancelled, skipped and timed-out outcomes.
4. For a failed or cancelled run, identify whether the cause is code, tests, workflow selection, infrastructure, concurrency or external cancellation. Fix the cause where safe; do not relabel cancellation as a code failure or as success.
5. For successful checks, inspect job summaries and test reports/artifacts to establish which tests actually executed and how many tests ran. A skipped job is not a passed test.
6. Record exact run IDs, commit SHAs, job outcomes, test counts and unresolved gaps in the relevant issue/PR or engineering status record.
7. When any check completes or changes status, immediately dispatch the next highest-priority non-conflicting backlog item. Do not wait for the next user prompt to make that scheduling decision within the active work session.

## 4. Old work is never silently discarded

Continuously revisit the oldest open issues, draft PRs, abandoned branches, unmerged implementation slices and release blockers. Determine whether each is:
- still needed and safely actionable;
- blocked by a named dependency;
- superseded by a merged implementation (with evidence);
- duplicate and safe to close;
- obsolete only after explicit rationale.

Never abandon an item merely because it is old, difficult, or outside the current feature. Keep its issue/PR updated with the reason and next concrete action. Rebase/port stale work carefully; do not blindly merge old branches or overwrite newer mainline code.

## 5. External project work — Project Token / Clover

When repository work is blocked or has no safe next action, continue with non-repository work, especially Project Token/Clover:
- research current AI-provider API and subscription prices, token units, input/output/cached/reasoning billing, discounts, access restrictions and quality-adjusted cost;
- compare Yandex, Grok/xAI, DeepSeek, GigaChat and other relevant providers using dated primary sources where possible;
- research user-acquisition and voluntary device-compute contribution models (DePIN, distributed compute, bandwidth/rewards), including real participation friction, payout evidence, retention, abuse and community reviews;
- compare the proposed Russian launch bundle of 300 RUB/month VPN + 500 RUB/month full GLAFIRA access (800 RUB/month combined), plus separate verified compute-contribution rewards, against Russian VPN and AI alternatives;
- distinguish promotional launch pricing from sustainable unit economics; model compute, bandwidth, support, payment fees, taxes, fraud, device wear, electricity and reward liability;
- investigate how to motivate voluntary participation without meaningless tapping, coercive lock-in, misleading token promises or requiring always-on device access;
- study token transferability, utility, redemption, legal/tax/payment constraints and transparent issuance/settlement.

Do not assume token rewards are guaranteed income. Reward only measured, consented, verified and accepted work; disclose resource use, data, battery, bandwidth and device controls. Keep GCU measurement separate from GLF settlement and do not assume a fixed 1:1 conversion.

## 6. Truthfulness and safety

Never report implementation, test success, merge, deployment, device validation or background execution unless the relevant tool/result proves it. Distinguish code committed, PR opened, CI completed, tests actually executed, and physical-device acceptance. Do not claim continuous background work between sessions unless an actual automation is running. Do not bypass OS permissions, user approvals, provider terms, credential protections or security controls.

## 7. Status format

For each active workstream keep a concise record:
- Priority / task / dependency
- Branch, PR and exact head SHA
- Latest run ID and per-job status
- Executed test counts and artifacts
- Blockers / risks
- Next safe action

This rule applies across GLAFIRA repositories and external project research. It is mandatory for all subsequent project planning and execution.


Global cross-PR platform build serialization is NOT IMPLEMENTED; Android is PR-keyed, iOS is ref-keyed, and Windows workflows lack a shared queue. Issue #744 tracks the durable non-dropping FIFO queue/lock. Until it is implemented and verified, parallel platform build dispatch is BLOCKED; do not describe separate PR runs as serialized.
