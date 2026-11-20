# GLAFIRA Network — commercial product, registration, token economy and VPN
**Version:** 1.1-draft  
**Date:** 2026-11-20  
**Status:** owner-approved product direction; implementation specification for review  
**Authority:** GLAFIRA Core and the owner. This document extends, and does not replace, the Core, Bridge, account identity, backup/recovery and network specifications.

## 1. Product definition

GLAFIRA is a commercial distributed AI and VPN ecosystem. It is not merely a local assistant or a volunteer computing project.

The owner operates the initial network control plane, GLAFIRA-Mother, and is the initial token issuer. The network's compute capacity can be used to build and operate multiple owner projects and, in later stages, provide paid compute/AI services to external customers.

The consumer value proposition is:
- access to GLAFIRA AI services using internal network tokens;
- access to a GLAFIRA VPN service using internal network tokens;
- the ability to earn tokens by voluntarily contributing a bounded share of device resources;
- the ability to transfer tokens to another network member by their unique username;
- the ability to use tokens for services or save them for later.

"Free" in consumer communication means that a user may obtain and use services without a cash payment by earning tokens through accepted contribution or receiving tokens from another member. It does not mean that compute, bandwidth, electricity, server capacity or VPN traffic have zero cost. Service access is subject to token balance, tariff, fair-use limits, service availability and the published terms.

## 2. Participants and value exchange

### 2.1 Network owner / issuer
The owner:
- operates GLAFIRA-Mother and the authoritative account registry;
- defines the internal token unit, issuance policy, service tariffs and network budgets;
- funds initial development and may allocate tokens to approved development tasks;
- uses network compute for owner projects such as ВодородАктив, Токен and Химера;
- operates or contracts VPN infrastructure and controls its configuration;
- can later accept external project requests and sell compute/AI services under separate terms.

The owner must not be able to silently rewrite historical wallet transactions. Administrative corrections must be compensating ledger entries with reason, actor, approval and audit record.

### 2.2 Consumer / contributing member
A member can:
- install an app and register an account;
- enroll one or more devices;
- choose whether each device contributes compute;
- earn tokens for verified and accepted work;
- pay for AI usage and VPN access with tokens;
- transfer tokens to another member by unique username;
- receive tokens while offline and see the updated balance on next connection;
- pause or revoke device contribution and revoke lost devices.

Contribution is voluntary, visible and controllable. A user must not be tricked into providing hidden compute, mobile data or VPN bandwidth.

### 2.3 Project customer
A project customer may submit work to the network and pay in tokens or, where later enabled and legally supported, another payment method. A project/customer does not gain access to member devices, personal files, memory, credentials or raw private data. Tasks are submitted through the scheduler and sandboxed execution protocol.

## 3. GLAFIRA-Mother: control plane and authority

GLAFIRA-Mother is the authoritative network service hosted on the owner's server. The owner may operate its Desktop/admin interface on the same host, but the following are separate logical services and security boundaries:

1. **Mother Registry / Identity** — accounts, unique usernames, device enrollment, sessions and revocation.
2. **Network Directory / Rendezvous** — node discovery, service discovery and authenticated signaling.
3. **Task Marketplace / Scheduler** — task offers, budgets, leases, assignment, acceptance and settlement.
4. **Token Issuer and Ledger** — issuance authorization, immutable transaction journal, balances and transfers.
5. **Service Billing** — AI usage metering and VPN subscription/usage charging.
6. **VPN Control Plane** — provisioning and revoking VPN client configurations, server health and tariffs.
7. **Admin Console** — owner's operational UI, dashboards, policies, approvals and audit.

The VPN data plane is not the Mother application: VPN traffic is routed through isolated VPN nodes. Mother provisions access and meters subscription state; it must not become a plaintext inspection point for user traffic.

A single physical server may initially host multiple isolated services, but they must have separate service identities, least-privilege credentials, network rules, resource limits, encrypted storage and backups. VPN traffic must not be able to reach the Mother database or admin endpoints. Capacity and failure-domain constraints must be measured before production.

### 3.1 Availability and recovery
Mother is the initial authoritative issuer and registry, but the network must not depend on one unbacked-up process or disk:
- encrypted, versioned backups and tested restore;
- database transaction log / durable journal;
- monitoring, health checks and alerting;
- documented recovery and key rotation;
- secondary rendezvous/backup endpoint as a later resilience stage;
- explicit degraded/offline behavior for clients.

If Mother is unavailable, clients may use already-issued, locally cached, unexpired VPN credentials only according to the configured lease policy. New registration, token transfers, issuance and final settlement wait for the authoritative service to recover. Clients must not invent offline token balances or independently mint tokens.

## 4. Registration and account creation

### 4.1 Product decision
Registration is performed by GLAFIRA-Mother on the owner's server. The app is a client; installation does not itself create a trusted network identity. This is the intended product model and supersedes any earlier proposal that assumed a third-party identity provider would be the authoritative registry. External identity providers may be optional sign-in methods later, but Mother remains the account and network authority.

### 4.2 Minimum registration data
Collect only what is necessary:
- immutable internal `account_id` (random UUID or equivalent);
- unique public `username` (case-folded uniqueness rules, reserved names and rename policy);
- authentication credential or passkey public-key registration;
- verified recovery channel (email is the initial recommended option; phone should be optional, not mandatory);
- accepted Terms, Privacy Notice and compute-contribution disclosure versions/timestamps;
- account creation time, status and security events.

Real name, home address, contacts, advertising profile and access to phone contacts are not required for ordinary registration. Do not require Google/Yandex login if an independent account can be created securely. Date of birth or additional verification should be requested only where jurisdiction, age restrictions or abuse controls make it necessary.

### 4.3 Recommended first registration flow
1. User opens GLAFIRA and selects **Create account**.
2. App connects to the official Mother endpoint using TLS and validates the server identity.
3. User chooses a unique username.
4. User registers a passkey where supported, or creates a strong password with verified email recovery. MFA can be enabled and required for high-risk actions.
5. Mother verifies the recovery channel and checks username availability/rate limits/abuse rules.
6. User reviews and accepts current terms and privacy/compute disclosures.
7. Mother creates the account and a first device enrollment record.
8. The device generates its own cryptographic key pair; the private key stays in secure platform storage. Mother stores the public key and device metadata.
9. Mother issues a revocable device session and returns the account profile, token wallet identifier and initial settings.
10. The user sees a dashboard explaining AI, VPN, tokens, contribution controls and how to disable contribution.

The app must not embed a Mother admin key, service-role key, signing secret or token-issuer private key. Registration endpoints are public but rate-limited and abuse-protected. Administrative enrollment/invitation can be retained as an optional controlled beta mode, not the only public registration path unless the owner chooses a closed beta.

### 4.4 Identity and username rules
- Public username is a human-readable routing alias, not the security identity.
- Transfers and invitations resolve the username to an immutable account ID on Mother.
- Cryptographic account/device IDs are not derived from IP addresses.
- IP address, port and network route are mutable connection metadata.
- Usernames cannot be reassigned immediately after account deletion; use a tombstone/reservation period to prevent impersonation and misdirected transfers.
- Before a transfer, the sender sees the resolved recipient username and a non-sensitive confirmation hint.

### 4.5 Device enrollment and recovery
Each device has its own key and revocable device record. Adding a device requires an authenticated account session and a step-up check (passkey/MFA/recovery or approval from an existing trusted device). Lost devices can be revoked remotely. Personal GLAFIRA state is account-owned and recovered using the existing encrypted backup and replication specification; Mother account identity does not make Mother a plaintext copy of every user's private memory.

## 5. Token model and issuance

### 5.1 Internal utility token
The initial token is a network-internal unit of account and utility credit. It is used to reward verified contributions and pay for GLAFIRA network services. It is not automatically:
- a cryptocurrency;
- a claim on fiat currency;
- redeemable for electricity;
- transferable to external blockchains;
- interchangeable with OpenAI, Google, Yandex or other provider billing credits.

Blockchain compatibility may be considered later, but the first implementation uses a centrally authoritative ledger controlled by Mother. This avoids introducing consensus, bridge and custody risks before the product has a working network.

### 5.2 Issuance policy
Mother is the initial issuance center. Token issuance must be an explicit, auditable operation with:
- issuance authority and policy version;
- project or reward budget;
- reason code (accepted compute task, approved development grant, promotion, correction);
- amount and unit precision;
- source task or approval ID;
- recipient account ID;
- actor/signature and timestamp;
- supply/budget impact.

For compute rewards, tokens are minted only after the task result passes the configured verification and acceptance policy. A task being assigned or a device merely being online is not by itself sufficient to earn a reward unless a separately defined availability reward is enabled.

No fixed token-per-hour or token-per-CPU rule is promised before real device benchmarks, energy/cost analysis, workload verification and abuse testing. Reward rates must account for useful work, quality, uptime, device class, energy cost, network cost and project budget. Publish the calculation method and version.

### 5.3 Supply and governance controls
Before public launch, the owner must approve:
- token precision and display format;
- whether total supply is capped, budgeted or elastic;
- maximum issuance per period and per account;
- project-specific issuance budgets;
- treasury and reserve accounts;
- emergency pause and incident response;
- correction, dispute and refund policies;
- whether tokens expire (default recommendation: no expiry unless the owner explicitly chooses and discloses it).

The ledger must show issued, spent, transferred, reserved and outstanding balances. Do not call a token "cash", "investment", "profit" or promise monetary return. Obtain jurisdiction-specific legal/accounting review before external sale, cash redemption, exchange listing or representing tokens as backed by assets.

## 6. Wallet and person-to-person transfers

### 6.1 User experience
Wallet screen displays available balance, reserved balance, transaction history and a **Transfer** action.

Transfer flow:
1. Sender taps **Transfer**.
2. Sender enters recipient's unique username and amount.
3. App asks Mother to resolve the username and displays the exact recipient for confirmation.
4. Sender confirms.
5. Mother validates sender authentication, account status, recipient status, amount precision, available balance, limits and risk checks.
6. Mother commits the transfer atomically to the ledger and creates a durable recipient-delivery event.
7. Sender sees **Accepted / awaiting recipient sync** if the recipient device is offline, or **Delivered** after the recipient client acknowledges synchronization.
8. On next connection, the recipient wallet syncs the signed ledger event and displays the received tokens.

The recipient does not need to be online at the time of transfer. The central ledger records the account credit immediately; the offline state affects notification/client synchronization, not the existence of the transfer. This avoids a transfer disappearing if the recipient never reconnects.

### 6.2 Ledger invariants
- Double-entry accounting: every transfer has equal debit and credit entries.
- Immutable journal: never edit or delete posted entries; corrections use compensating entries.
- Atomicity: debit and credit are committed in one database transaction.
- Idempotency: repeated client requests with the same idempotency key produce one transfer only.
- No overdraft: available balance cannot become negative.
- Concurrency safety: concurrent transfers cannot spend the same balance twice.
- Strong authorization: sender must confirm amount and recipient; high-risk transfers may require step-up authentication.
- Auditability: record sender, recipient, amount, transaction ID, idempotency key hash, status, timestamp and risk/approval metadata; never record passwords or private keys.
- Delivery retries are at-least-once at transport level but idempotent at the client; recipient balance must never be credited twice.
- Rate limits, transfer limits, account holds, dispute/fraud controls and admin review must be configurable.

### 6.3 Transaction states
Suggested states:
- `REQUESTED` — request received, not yet posted;
- `REJECTED` — validation failed; no debit/credit;
- `POSTED_PENDING_SYNC` — ledger debit and credit committed, recipient client has not acknowledged sync;
- `DELIVERED` — recipient client acknowledged the ledger sequence;
- `REVERSED` — a compensating reversal entry exists; original entry remains visible.

A transfer is not cancellable merely because the recipient is offline: after posting it is a completed ledger event. A reversal requires a separate authorized compensating transaction.

## 7. Compute contribution and reward system

### 7.1 Consent and device budgets
Contribution is off by default until the user sees and accepts a clear explanation, then can be enabled per device. It is always visible and can be paused or revoked. The product may remember the user's choice; a future setting may re-enable discovery on app launch only if the user has explicitly chosen that behavior.

Initial owner-provided device limits to preserve as product constraints:
- **Smartphone:** maximum 10% of available compute budget, Wi-Fi only; never use cellular data for contribution. Enforce charging/battery, thermal, memory, foreground activity and OS background restrictions. The 10% is a ceiling, not a guaranteed constant load.
- **Windows Desktop / home PC:** configurable dynamic contribution budget from 20% to 100% of available capacity when the device is idle, with immediate yield to owner activity and user-defined quiet hours/limits.
- Do not run tasks when thermal, battery, bandwidth, disk, privacy or user-presence conditions violate policy.
- Show current task, resource usage, network traffic, earned tokens and pause/stop controls.

These are initial policy ceilings and require device-specific measurement. Actual work depends on hardware, task type, thermal state, operating system and network.

### 7.2 Safe execution
- Only signed, versioned, allowlisted task packages may execute.
- Use sandboxing, strict CPU/RAM/disk/network/time quotas and no default access to personal files, browser profiles, credentials, microphone, camera or private project data.
- No arbitrary shell commands or unrestricted model-generated code on participant devices.
- Task inputs are explicitly classified; only the minimum task data is sent.
- Use leases, fencing tokens, heartbeat, cancellation, result hashes and idempotent result submission.
- For high-value work, verify using redundant execution, deterministic checks, trusted validators or sampled review. Reward only the accepted useful result.
- Protect against fake work, Sybil devices, collusion, replay, result tampering, reward farming and denial-of-service.
- Participant nodes remain independently owned; no participant gains control over another's GLAFIRA.

### 7.3 Reputation and capacity ratings
Keep separate measured dimensions:
- compute capacity: benchmarked throughput by workload class, memory, storage and network;
- reliability: uptime, completed leases and failure rate;
- quality: accepted-result rate and verification outcomes;
- latency/availability: observed scheduling and response performance;
- knowledge/capability domains: demonstrated task categories and validated expertise, not a self-reported raw knowledge score.

Reputation must be evidence-based, time-decayed where appropriate, explainable and appealable. Do not expose private user/device details in public ratings.

### 7.4 Reward calculation
A versioned reward formula may include accepted work units, benchmark class, quality multiplier, reliability and task urgency, bounded by project budget. It must subtract or account for service cost and prevent double rewards. Publish example calculations after benchmarks; do not invent exact token rates now.

## 8. AI services, VPN and tariffing

### 8.1 AI usage
AI services consume tokens according to published internal tariffs. Meter actual provider/model usage and network compute separately:
- model/provider input, output and cached tokens where applicable;
- image/audio/video/tool or other modality charges;
- local network compute work units;
- orchestration/storage/egress costs where relevant.

Vendor API credits remain vendor-specific. GLAFIRA tokens pay the GLAFIRA service tariff; they do not magically pay a provider API invoice unless Mother has funded that invoice.

Provide per-task estimated cost before expensive work where possible, a hard budget cap, usage receipt and dispute path. Never silently run up external provider costs.

### 8.2 VPN service
The consumer sees VPN plans priced in GLAFIRA tokens. Tariffs may include:
- daily pass;
- monthly subscription;
- optional longer plans;
- usage/fair-use limits such as traffic or speed tiers where operationally necessary.

Exact token prices, traffic limits, regions, concurrency and server count remain undecided until server hardware, hosting geography, bandwidth costs, legal analysis and capacity tests are complete.

VPN subscription is a service entitlement:
- Mother checks wallet balance and plan;
- payment is posted to the ledger;
- VPN control plane provisions a per-device credential/profile;
- client connects to a healthy VPN node;
- expiry or revocation disables the profile;
- renewal is explicit unless the user enables auto-renew;
- no shared static private keys across users or devices.

The VPN client should support the platform's safe capabilities: connect/disconnect, server selection, key rotation, kill switch where supported, DNS leak protection, IPv6 policy, auto-reconnect, split tunnelling where supported and clear status. The control plane should not retain browsing history. Operational metadata must be minimized, disclosed and retained only as required for security, billing and applicable law.

### 8.3 Tariff and fair-use transparency
Before activation show:
- token price and billing period;
- start/end time and time zone;
- included traffic/speed/concurrency policy;
- server region and availability;
- renewal behavior;
- refund/reversal policy;
- what metadata is collected and retained.

Do not market "unlimited" or "guaranteed free" until tested and contractually supported.

## 9. Token use cases and network growth

Initial use cases:
1. Pay for VPN access.
2. Pay for GLAFIRA AI requests and tasks.
3. Pay for work performed by the network for the user's projects.
4. Transfer tokens to another member by username.
5. Receive token grants for onboarding, referrals or community support only under published budgets and anti-abuse rules.

Owner-side monetization:
- use network capacity to develop and operate owner projects;
- sell AI/compute services to external customers later;
- build additional products on the same network;
- offer premium service tiers where capacity and terms permit.

Member-side value:
- reduce or avoid cash payments by earning tokens;
- use the network's AI capacity and VPN;
- gift tokens to another member;
- choose contribution limits and retain control of their devices.

No promise of cash income or guaranteed token resale is made.

## 10. Registration, service and network APIs (logical contract)

The first API contract should be versioned and transport-independent. Candidate operations:

**Identity**
- `POST /v1/registration/challenges`
- `POST /v1/registration/complete`
- `POST /v1/auth/session`
- `POST /v1/devices/enroll`
- `POST /v1/devices/{deviceId}/revoke`
- `GET /v1/account/profile`

**Wallet**
- `GET /v1/wallet`
- `GET /v1/wallet/transactions?cursor=...`
- `POST /v1/wallet/transfers/resolve-recipient`
- `POST /v1/wallet/transfers`
- `GET /v1/wallet/events?afterSequence=...`
- `POST /v1/wallet/events/ack`

**Compute contribution**
- `POST /v1/nodes/capabilities`
- `POST /v1/nodes/contribution-policy`
- `POST /v1/tasks/leases/claim`
- `POST /v1/tasks/{taskId}/heartbeat`
- `POST /v1/tasks/{taskId}/result`
- `POST /v1/tasks/{taskId}/cancel-ack`

**Services**
- `GET /v1/services/catalog`
- `GET /v1/services/vpn/plans`
- `POST /v1/services/vpn/subscriptions`
- `POST /v1/services/vpn/devices/{deviceId}/provision`
- `POST /v1/services/ai/usage-receipts`

These are logical API candidates, not a commitment to REST if the existing versioned Bridge contract uses another transport. Every write requires authentication, authorization, schema validation, idempotency and audit. The wallet and task APIs must use the same durable Core lifecycle and authority policy, not a parallel ad hoc queue.

## 11. Data model (logical entities)

- `Account`: accountId, username, usernameNormalized, authMethods, recoveryStatus, status, createdAt, termsVersion.
- `Device`: deviceId, accountId, publicKey, platform, appVersion, capabilities, contributionPolicy, lastSeenAt, status, revokedAt.
- `Wallet`: walletId, accountId, currencyCode, availableBalance, reservedBalance, ledgerSequence.
- `LedgerEntry`: entryId, transactionId, accountId, direction, amount, entryType, sourceRef, policyVersion, createdAt.
- `LedgerTransaction`: transactionId, type, status, idempotencyKeyHash, senderAccountId, recipientAccountId, amount, createdAt, postedAt.
- `TaskLease`: taskId, deviceId, leaseId, fencingToken, budget, deadline, status, resultHash.
- `ContributionReceipt`: receiptId, accountId, deviceId, taskId, verifiedWorkUnits, qualityResult, rewardFormulaVersion, rewardAmount.
- `ServicePlan`: planId, serviceType, tokenPrice, billingPeriod, limits, version, effectiveFrom, status.
- `ServiceEntitlement`: entitlementId, accountId, deviceId, planId, startsAt, expiresAt, status.
- `AuditEvent`: eventId, actor, action, target, approvalRef, timestamp, redactedMetadata.

The wallet balance is a derived/cached projection of the authoritative journal and must be reconcilable. Never rely on a client-supplied balance.

## 12. Security, privacy, abuse and legal review

- TLS for all client-to-Mother traffic; authenticated service-to-service connections.
- Device keys protected by platform secure storage; private keys never leave the device.
- Server secrets in a secret manager or protected service environment, never in app packages or Git.
- MFA/step-up for account recovery, high-value transfers, device revocation and administrative issuance.
- Rate limits, anomaly detection, username squatting protection, bot registration controls and abuse reporting.
- Strict tenant isolation and Row-Level Security or equivalent server authorization.
- Signed task packages, sandboxing, quotas, malware scanning and revocation.
- Separate control-plane and VPN data-plane networks.
- No default collection of browsing history, personal files, contacts or private GLAFIRA memory.
- Audit all issuance, transfer, subscription, device enrollment, admin action and policy change.
- Conduct jurisdiction-specific legal review for VPN operation, consumer terms, privacy, compute contribution, internal credits/tokens, taxation, sanctions and any later external sale/redemption. Do not assert regulatory classification without qualified review.

## 13. Staged implementation order and dependencies

This commercial track is designed to run in parallel as specification, threat-model and contract work, while implementation respects the owner's existing engineering priority order.

### Stage 0 — specification and decisions (now)
- Preserve and review the Core and Bridge work already in progress.
- Approve this business model and the unresolved policy decisions in section 14.
- Define Mother deployment topology and security boundaries.
- Define account, device, wallet, ledger and contribution contracts.
- Create threat models and acceptance fixtures before production code.

### Stage 1 — stabilize the platform foundation
- Finish Core queue concurrency, dependencies, crash recovery, idempotency and audit.
- Stabilize versioned Bridge pairing, leases, heartbeats, result acknowledgements and reconnect.
- Preserve Android, native iOS, Windows Desktop and Windows Agent as first-class deliverables.
- Complete account-owned replication, encrypted backup and device recovery.
- Keep the agent catalog and specialist training work preserved but sequenced after Core/Bridge foundations.

### Stage 2 — Mother registration and account registry
- Deploy Mother identity/registry service on owner's server.
- Implement registration, username reservation, authentication, device enrollment and revocation.
- Add admin console and registration abuse protection.
- Verify multi-user isolation and recovery.

### Stage 3 — authoritative wallet and ledger
- Implement double-entry journal, issuance controls, wallet projections and transfer API.
- Add offline recipient delivery queue, idempotent sync/ack and user-visible transfer statuses.
- Test concurrent transfers, duplicate requests, crash recovery and reconciliation.

### Stage 4 — compute contribution and rewards
- Implement opt-in device budgets and policy enforcement.
- Add signed sandboxed tasks, leases, heartbeat, result verification and cancellation.
- Benchmark representative phones and PCs; publish reward formula only after evidence.
- Add reputation and transparent contribution receipts.

### Stage 5 — VPN service
- Finalize infrastructure BOM and deployment/hardening runbook.
- Separate VPN control plane and data plane; deploy a small pilot.
- Build client provisioning, per-device keys, plan charging, expiry/revocation and connectivity/leak tests.
- Launch daily/monthly token tariffs only after measured cost and legal review.

### Stage 6 — AI services and project marketplace
- Meter AI/provider usage and local compute separately.
- Introduce task budgets, token price catalog and receipts.
- Use network capacity for owner projects first; then controlled external customer pilot.
- Add councils and inter-owner work settlement after peer trust and task contracts are proven.

### Stage 7 — scale and resilience
- Load-test registration, directory, scheduler, ledger and delivery queues.
- Add replicas, secondary rendezvous and regional VPN nodes where justified.
- Validate backup/restore, incident recovery, fraud response and support operations.
- Consider public-chain interoperability only after a separate legal, security and economic decision.

## 14. Owner decisions and open questions

### Confirmed product direction
- GLAFIRA is a commercial ecosystem and business platform, not only a personal assistant.
- Owner's GLAFIRA-Mother is the initial central registry, coordinator and token issuance authority, hosted on the owner's server.
- The same owner's server is intended to host VPN infrastructure initially, with strong logical/network isolation.
- Consumers can obtain AI and VPN services by spending internal tokens instead of cash.
- Users can earn tokens by contributing a small, bounded amount of device resources.
- Initial smartphone contribution ceiling is 10%, Wi-Fi only and never cellular data.
- Initial Windows Desktop contribution budget is dynamic from 20% to 100%, yielding to owner activity.
- VPN may be priced by day or month in tokens.
- A user can transfer tokens to another network member by unique username; Mother posts the transfer and the recipient client synchronizes it when next online.
- Start with a central authoritative ledger; retain blockchain compatibility as a future option.
- Each GLAFIRA owner remains autonomous; no owner gets default access to another owner's private data or devices.

### Must be decided before public launch
1. Public self-registration or invitation-only beta?
2. Username change and reuse policy.
3. Passkey-only, password + verified email, or both?
4. Initial token precision and issuance budget.
5. Daily/monthly VPN token prices and fair-use limits.
6. AI tariff model and which services are included.
7. Contribution reward formula after device benchmarks.
8. Whether any onboarding grants or referrals are offered.
9. Server location(s), legal jurisdiction and data-retention schedule.
10. Abuse, fraud, disputes, refunds and account closure policy.

## 15. Acceptance gates

No public launch claim until all applicable gates pass:

**Registration**
- unique username collision and case-folding tests;
- verified recovery and MFA/passkey tests;
- rate limiting and abuse tests;
- device key enrollment and revocation;
- multi-user isolation tests;
- clean-device account recovery.

**Ledger and transfers**
- atomic debit/credit;
- concurrent overspend prevention;
- idempotent duplicate request;
- offline recipient sync and acknowledgement;
- journal reconciliation;
- reversal and dispute audit.

**Compute**
- opt-in and immediate pause/revoke;
- smartphone Wi-Fi-only enforcement and 10% ceiling;
- Desktop dynamic resource ceiling and owner-priority yield;
- sandbox boundary and forbidden-resource tests;
- crash/cancel/retry without double reward;
- independently verified reward receipt.

**VPN**
- per-device unique keys and revocation;
- token charge and entitlement lifecycle;
- reconnect, kill switch, DNS/IPv6 leak tests where supported;
- server failover and capacity tests;
- privacy/logging disclosure and legal review.

**Business**
- token supply and issuance reconciliation;
- project budgets and cost accounting;
- customer-facing tariff disclosure;
- incident response and support procedures;
- owner-approved pilot and rollback plan.

---
This document describes the intended product and technical design. Items marked as open questions or staged work are not implemented merely because they are specified here.


## 16. Owner-approved launch offer, AI pricing and voluntary contribution strategy (2026-11-20)

### 16.1 Introductory commercial offer — hypothesis, not final tariff

The owner proposes testing the following promotional entry prices for the Russian market:
- **VPN:** 300 RUB per month.
- **Full GLAFIRA access:** 500 RUB per month.
- **Combined bundle:** 800 RUB per month.
- **Verified compute contribution rewards:** separate, optional and earned only for accepted useful work.

These prices are a launch hypothesis and must not be advertised as final until competitor pricing, infrastructure cost and legal/payment review are complete. Define promotion duration, renewal/normal price, included devices, fair-use limits, cancellation/refund rules and regional availability before checkout.

The 500 RUB GLAFIRA tier must not imply unlimited access to expensive frontier-model APIs. It needs explicit fair-use quotas, a usage receipt and hard cost limits. No hidden paid fallback, unapproved provider spend, silent model substitution or surprise overage. Users who do not contribute compute retain full paid access at the published price.

### 16.2 Competitive price and value research

Maintain a dated evidence table for:
- Russian VPN providers: monthly and effective annual/multi-year price, devices, traffic, protocols, connection reliability, support, payment methods and refunds.
- AI subscriptions and free tiers: included models, usage limits, tools, voice, files, agent workflows, regional availability and checkout price.
- AI APIs: input/output/cached/reasoning tokens, image/audio/video/tool charges, batch rates, long-context tiers, region/deployment, tax and payment friction.
- Distributed compute / DePIN / bandwidth projects: actual buyer demand, resource cost, reward formulas, payout usability, retention and user complaints.

Keep API metering separate from consumer subscriptions. A subscription price cannot be divided by tokens unless its usage pool and effective limits are known. Provider credits are provider-specific and are not GLAFIRA tokens.

The initial source-linked provider matrix (snapshot 2026-11-20) is maintained in `docs/TOKEN_CLOVER_MARKET_AND_COMPUTE_INCENTIVE_RESEARCH.md`. It includes Yandex, xAI/Grok, DeepSeek, OpenAI, Google Gemini, Mistral, Cohere, Groq, Alibaba/Qwen and Sber/GigaChat. Refresh every price card before a customer quote or financial commitment.

Initial tariff observation: at the 2026-11-20 Bank of Russia USD rate of 81.929 RUB/USD, the published YandexGPT Pro 5.1 API rate of 0.8 RUB per 1,000 input/output tokens is approximately 4.9× Grok 4.7 input and 1.6× Grok 4.7 output list price after FX. This is not a quality comparison. Direct DeepSeek V4.1 Flash has peak/off-peak rates and differs materially from the Yandex-managed DeepSeek listing; exact model revision, endpoint, token accounting, VAT and hosting must be checked before drawing a markup conclusion.

### 16.3 GLAFIRA's differentiation

Multi-model access alone is not a sufficient moat. GLAFIRA's target differentiation is a persistent, owner-controlled AI work environment:
- route tasks among multiple providers based on quality, cost, latency, context and permissions;
- persistent project workspaces and evidence-backed task history;
- specialist agents and coordinated workflows;
- file, voice, tool and integration workflows with explicit approvals;
- continuity across Android, Windows Desktop and optional Windows Agent;
- owner-controlled local/edge execution and, later, an audited peer compute marketplace;
- transparent usage metering, budget caps and accepted-deliverable receipts.

This is a product hypothesis, not a claim that all capabilities are already implemented. Do not market a capability until code, tests and end-to-end acceptance prove it.

### 16.4 No tap-to-earn: useful work only

GLAFIRA must not reproduce tap-to-earn or hamster-style mechanics. Participants must not be asked to tap repeatedly, watch ads, generate fake engagement, keep the app open for points or stay online 24/7 just to inflate an activity score.

- Contribution is opt-in, separately enabled per device, visible, pausable and revocable.
- Reward only verified and accepted useful work. No reward for app opens, taps, referrals alone, raw uptime or unverified device claims.
- Preserve smartphone ceiling: at most 10% of available compute budget; Wi-Fi only; never cellular data. Run only under safe charging/battery/thermal/idle conditions and immediately yield to the owner.
- Windows Desktop/home PC may use a separately configured 20–100% idle resource ceiling, with immediate yield to owner activity.
- These are maximum policy ceilings, not promised continuous utilization or guaranteed earnings.
- Show measured/estimated CPU/GPU/NPU time, data volume, energy confidence, task status, acceptance, reward formula version and pending/posted balance.
- Do not access personal files, browser profiles, credentials, microphone or camera by default. Tasks receive only minimal scoped inputs in an isolated sandbox.
- Publish reward formula and changes in advance; keep historical receipts reproducible. Never retroactively change already accepted rewards without an auditable compensating event and disclosed dispute process.
- If a device class cannot earn more value than its incremental energy, bandwidth, wear, coordination and verification costs, do not offer that task class for public rewards.

### 16.5 Token and reward accounting

Keep GCU (measured compute work) separate from GLF (internal settlement/reward unit). No fixed 1:1 conversion is assumed. The GCU methodology and GLF pricing/reward policy must be versioned and owner-approved.

For every reward, store task class, raw provider/device usage, benchmark version, accepted output/quality result, resource estimates vs measurements, reward formula version, amount, account and ledger transaction ID. Preserve replayability under future formula changes without rewriting historical facts.

No guaranteed income, investment return or fiat value is promised. External transfers, cash redemption, public exchange listings or sale of tokens require separate legal, tax, financial, fraud and consumer-protection review. Public token issuance remains owner-authorized, budgeted and capped by policy; agents cannot mint autonomously.

### 16.6 Pilot and unit-economics acceptance

Before public launch, model at least 100 / 1,000 / 10,000 subscribers and multiple compute participation rates. Include:
- provider API cost by actual model mix, retries, cache, modalities and tools;
- VPN egress, server capacity, IP/region diversity and abuse mitigation;
- compute verification, scheduling, storage, networking and support;
- payment processing, app-store commissions, taxes, refunds and chargebacks;
- token reward liability, fraud, idle capacity, retention and churn.

Pilot metrics: activation, paid conversion, day-7/day-30 retention, monthly active use, AI task mix, cost per accepted deliverable, VPN traffic per paid user, compute participation rate, accepted work per device class, net reward after incremental cost, support burden and refunds. Stop or redesign any task class whose net participant benefit is negative or opaque.



### 16.7 Competitive positioning guardrail

The owner-selected product direction is a full AI office, not a chatbot or thin model aggregator. Competitor research shows that multi-model access, project-scoped chats/files/instructions, provider routing/fallback, web search, file input, voice and agentic research are already available in other products. GLAFIRA must not claim any of these individually as unique.

The target differentiation is the combination of persistent owner-controlled projects and tasks, accountable specialist agents, Windows Desktop + Windows Agent execution, optional user-owned participant compute, an audited funded task marketplace, Mother account/wallet control plane, optional VPN entitlement and transparent GCU/GLF economics.

This is a target-state description, not a current feature claim. The current build was characterized in a 2026-11-18 external technical review as an Android LLM-orchestration client with nascent multi-agent logic; Web/Browser Agent, Desktop Bridge and CRM were still planned. Every public claim must be gated by implementation and end-to-end acceptance.

The proposed 500 RUB GLAFIRA subscription must be compared directly with multi-model aggregators such as Poe, whose published entry subscription starts at $4.99/month (about 409 RUB at the 2026-11-20 CBR rate before local tax/pricing) and uses capped compute points. GLAFIRA's premium over that entry price must be justified by operational workspaces, agents, local/desktop execution, service network value and transparent optional contribution rewards.


### 16.8 Grok Bot and cloud-computer competitor

Grok Bot is a direct agentic-work competitor. Its Premium tier is $30/month and advertises an agent computer, tool sign-in, scheduled routines, desktop/mobile operation and weekly included usage. Multiple bots share a persistent cloud computer per user, including files, browser and logins.

Do not claim that generic agent computers, scheduled execution or tool sign-in are unique to GLAFIRA. The target distinction must be owner-controlled local Windows Desktop/Agent devices, opt-in peer-owned compute, isolated task execution, user-verifiable resource contribution, transparent GLF reward accounting, local/self-hosted execution options and the integrated VPN/service network.

The proposed 500 RUB GLAFIRA tier must have explicit model/compute limits. It cannot imply unlimited frontier-agent work or be positioned as a price-only alternative to a $30 cloud-agent subscription.
