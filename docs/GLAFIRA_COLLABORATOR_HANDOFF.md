# GLAFIRA — engineering handoff for collaborating agents
**Snapshot date:** 2026-11-21 (status snapshots must be refreshed against live GitHub state before action)  
**Purpose:** provide a reliable entry point for any collaborating engineer or AI assistant, including Костя.  
**Important:** this is a handoff and navigation document, not proof that listed features are implemented.

## 1. Read these documents first

0. [Central Multi-Agent Coordination](../GLAFIRA_COORDINATION.md) — mandatory owner priorities, free-only resource policy, 20 × 150 cycle protocol and task reservation.
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
- **Free-only development constraint:** no required paid plan, paid API tier, rented cloud resource or mandatory subscription. Verify free-tier quotas, region availability and terms; free quotas are not unlimited. Any paid exception requires prior explicit owner approval.
- No public launch or completion claim based on docs, catalog entries or an APK build alone; require exact-head CI and acceptance evidence.
- Do not merge PRs or close work without explicit owner approval. Do not force-push or rewrite shared branches without coordination.

## 4. Active implementation order

The owner's current engineering priority order is:

1. **P1 — Core:** durable queue, concurrency, dependencies, cancellation, ownership generation, ACK, crash recovery, idempotency, Trace/audit and policy.
2. **P2 — Backup & Recovery:** encrypted portable backup, integrity verification, restore, interrupted-restore recovery and verified off-device copy. Android manual-backup UX and physical Drive/Yandex checks are part of this priority.
3. **P3 — Runtime / Bridge / Desktop:** Android instrumentation and shared contracts; authenticated Bridge pairing, revocation, durable delivery, ACK, heartbeat, fencing and reconnect; Windows Desktop Hub and Windows Agent. Bridge contract/pairing dependencies precede production fleet work; Desktop host/scheduler precedes full Agent acceptance.
4. **P4 — Android UX, voice and specialist agents:** Council/task queue UX, STT/TTS, iOS parity, Кадровик and other roles after foundation gates.
5. **P5 — Commercial/optional integrations:** GCU/GLF, VPN, token economics and Project Token/Clover research only where they do not displace P1–P3 and never with a required paid resource.

Old issues and branches remain visible and must be triaged into the appropriate current priority; age alone is not a priority.

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
