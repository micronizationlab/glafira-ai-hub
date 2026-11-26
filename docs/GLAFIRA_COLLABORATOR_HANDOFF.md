# GLAFIRA — engineering handoff for collaborating agents
**Snapshot date:** 2026-11-20  
**Purpose:** provide a reliable entry point for any collaborating engineer or AI assistant, including Костя.  
**Important:** this is a handoff and navigation document, not proof that listed features are implemented.

## Mandatory first-visit instruction for every new GLAFIRA helper

Before starting any assigned subproject, every new AI helper/coding agent must:

1. Read this handoff and `.github/copilot-instructions.md` completely.
2. Refresh current GitHub state and exact-head CI; this document's PR/issue tables are snapshots.
3. Analyze the **whole GLAFIRA platform first**—purpose, product architecture, Android and Windows Desktop Core independence, owner authority, security, GitHub execution, Bridge, Backup & Recovery, failover, and current blockers. Return concrete additions and alternative development paths with benefits, risks, dependencies, free-first options and priorities.
4. Submit that analysis for owner discussion. This is not implementation authorization. Do not open feature PRs or change architecture/priorities based on your own recommendations.
5. Then analyze your separately assigned project and clearly distinguish findings from approved decisions.

**One-time analysis only:** the platform-wide GLAFIRA analysis and the helper's assigned subproject analysis are onboarding deliverables, each completed once. After submitting them for owner discussion, continue normal assigned work; do not repeat these analyses at every startup or periodically. Prepare a new analysis or additional proposals only on the owner's separate request. This does not remove ordinary task-specific code, issue, or CI checks.

### Berestovkon — pending owner-requested analysis

The required starting point is [GitHub issue #473](https://github.com/micronizationlab/glafira-ai-hub/issues/473). The deliverable must contain three separate parts in this order:

- **First: GLAFIRA itself.** Platform-wide review and development proposals, including what should be added, alternative architecture/product paths, dependencies and free-first choices.
- **Second: Project Token.** Separate business/product/technical analysis. Include the owner's proposal that a day be the basic service billing unit, but do not assume whether it means a rolling 24-hour period or a calendar day. Analyze start, renewal, pause, refunds, insufficient balance, grace period and idempotent charging. No prices or billing behavior are approved.
- **Third: Кадровик.** Separate analysis of the HR project, its value, scope, risks, data/privacy requirements and dependencies on GLAFIRA Core and permissions.

For each recommendation provide: current evidence/state, benefit, risk, dependencies, free-first implementation path, priority and smallest safe next action. This is **analysis and recommendations only**. Do not implement, create feature PRs, modify `main`, start charges, or treat proposals as owner-approved. Post the report in issue #473 or attach a durable report linked from that issue. If you cannot see or act on the issue, explicitly report the access blocker instead of silently omitting the task.

Berestovkon (and every future helper) must acknowledge this startup instruction in its first report. Repository instructions are the durable handoff; a previous chat message or a PR comment alone is not a reliable startup mechanism.

## 1. Read these documents first

1. [Engineering Roadmap](IMPLEMENTATION_ROADMAP.md) — owner priority order and dependency rules.
2. [Core Foundation Specification](GLAFIRA_CORE_FOUNDATION_SPEC.md) — Core authority, memory, agent contracts, review, trace and policy.
3. [Core autonomy, tools and credential security](GLAFIRA_CORE_SECURITY_AND_TOOLS.md) — permissions, credential broker and safe external actions.
4. [Backup & Recovery](GLAFIRA_BACKUP_AND_RECOVERY.md) — encrypted backup, restore and recovery constraints.
5. [Account Auth and Identity](ACCOUNT_AUTH_AND_IDENTITY.md) — account isolation, auth and connected services; read together with the newer Mother decision below.
6. [Commercial Network, Mother, token economy and VPN](GLAFIRA_NETWORK_BUSINESS_AND_TOKEN_ECONOMY.md) — business model, Mother, registration, wallet, token transfers, compute contribution, VPN tariffs and launch gates.
7. [Voice Assistant Plugin](VOICE_ASSISTANT_PLUGIN.md) — optional voice architecture.
8. [HR Intelligence Agent Training](HR_INTELLIGENCE_AGENT_TRAINING.md) — deferred specialist role Кадровик.

## 2. Owner's confirmed product direction

GLAFIRA is a commercial distributed AI + VPN ecosystem. The owner's business benefit is to use the network's pooled compute capacity to build and operate multiple projects and later offer services to outside customers. The consumer benefit is token-priced access to GLAFIRA AI and VPN.

- Owner-operated **GLAFIRA-Mother** is hosted on the owner's server and is the initial authoritative network registry, control plane and token issuer.
- User installs a client, registers with Mother and receives an immutable account ID, unique public username and revocable device identity.
- The initial token is an internal utility/accounting unit. Start with a central authoritative ledger; blockchain is a future compatibility option, not the first implementation.
- Users earn tokens for verified, accepted contribution, spend them on AI and VPN services, and can transfer tokens to another user by unique username.
- Recipient may be offline: Mother posts the transfer in the central ledger and the recipient client synchronizes it on next connection.
- Smartphone contribution ceiling: 10% of available compute, Wi-Fi only, never cellular. Apply battery, thermal, charging and OS limits.
- Windows Desktop contribution: dynamic 20–100% of available capacity when idle, always yielding to owner activity.
- Contribution is voluntary, visible, per-device, pausable and revocable. Do not silently consume device resources.
- VPN may have daily and monthly token tariffs. Prices and fair-use limits are not decided until capacity/cost and legal review.
- Mother control plane and VPN data plane must be isolated. VPN traffic must not reach Mother database/admin interfaces.
- Independent GLAFIRA owners remain autonomous. No cross-owner access to memory, credentials, projects or files without explicit scoped approval.

## 3. Engineering authority and non-negotiable rules

- Owner is final authority. Core owns task lifecycle, policy, permissions, approvals and state.
- Bridge is authenticated transport and device coordination, not a second Core or source-of-truth database.
- Windows Desktop is the owner-operated control/compute host for Windows Agents, not a separate Core.
- Native iOS and Windows Desktop remain first-class deliverables alongside Android.
- Use bounded concurrency; respect task dependencies; preserve crash recovery and idempotency.
- Never blindly replay an uncertain external write. Quarantine it for owner reconciliation.
- No secrets in prompts, logs, Git, screenshots or artifacts. No admin/service-role/issuer keys in clients.
- No user/device resource contribution without clear consent and controls.
- No public launch or completion claim based on docs, catalog entries or an APK build alone; require exact-head CI and acceptance evidence.
- Do not merge PRs or close work without explicit owner approval. Do not force-push or rewrite shared branches without coordination.

## 4. Active implementation order

The complete current owner-approved priority order, reconfirmed on 2026-11-26 and superseding the older 2026-11-20 grouping, is:

1. **P1 Core:** durable ownership/state, SQLite concurrency, bounded workers, dependencies, cancellation, crash recovery, idempotency, result delivery, audit and policy.
2. **P2 Backup & Recovery:** encrypted/versioned backup, integrity, safe restore, recovery staging and clean-device acceptance.
3. **P3 Bridge / Windows Desktop-Hub / Android client integration:** authenticated Task/Result/Lease protocol, pairing, revocation, durable delivery, ACK, heartbeat, reconnect, authoritative Desktop Hub host and dependent Android pairing/client work. Bridge/Hub prerequisites precede dependent Android pairing work.
4. **P4 iOS and specialist-agent feature work:** protocol alignment, agents, connectors and training after the foundations are ready.
5. **P5 Token/GCU:** non-issuing calculator/specification only; no issuance, wallet or payment without separate owner approval.

Android and Windows Desktop remain the primary runtime surfaces, but client features do not displace Core or Backup. Workstream 11 is cross-cutting intake/integration, not a separate product priority; it may perform required review, CI, merge and post-merge work for eligible higher-priority items. Stale P0/P1/P2 rankings elsewhere are subordinate to this current owner order.

## 5. Current PR stack and status snapshot

Repository: `micronizationlab/glafira-ai-hub`.

- **#103** — Core bounded parallel execution and safe maintenance leases; open draft, based on main. Exact-head CI run #1179 passed selected checks, including Android JVM, APK packaging, Android emulator acceptance, shared contracts and workflow lint. iOS/Windows Agent were skipped, not passed.
- **#104** — task dependency graph and dependency-aware claims; open draft, stacked on #103. Exact-head CI run #1177 passed selected Android JVM, APK, emulator, contracts and workflow lint; iOS/Windows Agent were skipped.
- **#105** — emulator disk allocation fix; open draft. Its isolated workflow-only validation does not prove emulator acceptance. The 2 GB disk change is also being applied to the active workspace PR.
- **#111** — four built-in Android workspaces (Личное, ВодородАктив, Токен, Химера), idempotent migration preserving existing data. CI run #1182 passed JVM and APK but skipped emulator acceptance due a selector guard that incorrectly required a helper script absent from the branch. The guard was removed and tests corrected; a new exact-head run #1187 was started on commit `bbdb9707be8eae2814c1a2452b8672cd4acd0ac1`. At snapshot time, confirm the final run result before claiming acceptance.
- **#118** — this commercial network architecture documentation PR; draft, documentation only.
- **#98** — Кадровик implementation PR remains open/draft and deferred to the agent queue. #85 documentation PR is closed and not merged; the training specification remains a required artifact.
- **#66** — Project Dispatcher PR remains open/draft; inspect its current state and dependencies before activating.
- **#71** — older parallel-execution PR is open/draft on a historical queue-recovery base. Compare with #103 before doing any work; do not merge both blindly.
- **#102** — professional roles/voice roadmap documentation PR remains open/draft.

These are snapshot statuses, not permanent truths. Always fetch current PR metadata, branch heads and exact-head CI before modifying or reporting.

## 6. New commercial-network work items

- **#114 (P0):** Mother registration, account registry and device enrollment.
- **#115 (P0):** authoritative wallet, double-entry ledger, issuance and offline username transfers.
- **#116 (P1):** opt-in distributed compute contribution, sandboxing, verification and rewards.
- **#117 (P1):** VPN control plane, token tariffs, per-device provisioning and isolated VPN nodes.
- Related existing work: **#106** shared project workspaces; **#107** AI-provider/energy-token research; **#109** VPN app/self-hosted infrastructure; **#110** native iOS and Windows Desktop; **#112** account-owned replication/recovery; **#113** trusted cooperation between independently owned GLAFIRAs.

## Mandatory PR size rules

Every contributor and AI helper, including Berestovkon, must follow [PR Size and Merge Policy](engineering/PR_SIZE_AND_MERGE_POLICY.md):

- Target **50–150 changed lines** and **no more than 5 files** per PR.
- **200 changed lines is the normal hard ceiling.**
- **201–300 lines** is an exception only with the owner's/integrator's written approval obtained before implementation; maximum 10 files.
- **More than 300 lines is prohibited** for an ordinary implementation PR and must be split.
- Count additions + deletions, not net changes. Tests/docs count too. Generated artifacts must be identified separately and cannot conceal broad handwritten changes.
- One PR = one logical change. Split Android, iOS, Windows, Core, Backup and Bridge work into dependency-ordered PRs. Check current main, active PRs and file overlaps first.
- Never push directly to main, merge your own PR, bypass protection or report skipped/cancelled checks as passed.
- If the task exceeds the limit, stop and submit a short split plan before coding.

These limits apply to every assistant/helper without exception based on contributor identity. The policy document is normative; if this handoff conflicts with it, the stricter rule applies.

## 7. How to collaborate safely

Before coding:
1. Read the relevant specification and issue completely.
2. Fetch the current main and relevant PR base/head; inspect changed files and current CI.
3. Identify dependencies and avoid duplicating existing PR work.
4. Create a focused branch/PR with a clear scope and acceptance criteria.
5. Add or update tests alongside code; test exact branch head.
6. Report exactly what passed, what was skipped and what remains unverified.
7. Keep work in draft until dependencies and acceptance gates are satisfied.

Do not:
- assume a GitHub issue or spec means a feature exists;
- claim skipped tests passed;
- merge stacked PRs out of order;
- duplicate account, task queue, ledger or project-registry sources of truth;
- invent token reward rates, VPN prices, server capacity or guarantees;
- grant cross-user access by default;
- make token cash-value or investment claims.

## 8. Handoff principle

Костя and other collaborators should work from these versioned repository documents and issue/PR history, not from assumptions about an earlier chat. If a decision changes, update the relevant specification and issue first, then implement it in a reviewable PR. Preserve provenance and explain any conflict with older documents.
