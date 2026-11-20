# GLAFIRA AI HUB — Engineering Roadmap

This roadmap records the owner's priority order. Features remain on the roadmap even when they are not in the current sprint. A specification is not considered implemented until code, tests and an end-to-end acceptance check exist.

## Owner's active execution order — authoritative

This order was explicitly reconfirmed by the owner on 2026-11-20 and supersedes older priority groupings below for sprint planning. The remaining roadmap sections are a capability inventory and dependency reference, not permission to work on lower-priority features ahead of this sequence.

### Queue 1 — Core kernels and execution engine

The immediate focus is the GLAFIRA Core/kernel architecture: durable task ownership and state, safe SQLite concurrency, bounded parallel workers, dependency scheduling, cancellation, crash recovery, provider/tool execution boundaries, shared results, auditability and reliable restart. Core remains the authority for policy, permissions, approvals and task lifecycle. Stabilize and integrate the existing queue-recovery work in reviewable slices; do not merge the 421-commit recovery branch as one batch.

### Queue 2 — Bridge

Build the cross-device Bridge on top of stable Core contracts. Define and enforce one versioned Task/Result/Lease/Agent protocol for Android, iOS, Windows Desktop and Windows Agent; authenticated pairing/enrollment/revocation; durable ingress and delivery; idempotency; lease/fencing; heartbeats; result acknowledgements; reconnect/offline recovery; and bounded, observable transport. Windows Desktop is the owner-operated control host for its Windows Agent fleet, but Core retains policy and task authority. Bridge is transport and device coordination, not a second Core.

### Queue 3 — Applications

After Core and Bridge contracts are stable, complete the platform applications and host integrations. **Prioritize a usable Windows Desktop control center and Windows Agent integration ahead of iOS**, because the owner needs a laptop application to test the complete Bridge workflow. Android remains in the platform queue; iOS follows after the Desktop + Bridge workflow is testable. Initial Windows Desktop acceptance is functional rather than visual polish. Each platform must pass shared contract fixtures and end-to-end pairing, dispatch, result, reconnect and recovery tests. Backup/restore, packaging, UI polish and release readiness are addressed as part of making these applications dependable.

### Queue 4 — Agents and agent training

Only after queues 1–3 are established, resume specialist-agent catalog work, Кадровик, agent-specific training models, collective review, knowledge accumulation and role expansion. Keep the existing Кадровик work preserved in its draft PR and training document; do not discard it, but do not let it displace Core or Bridge work.

### Work-selection rule

Every new task and PR must identify its queue and dependency. Active development is limited to the highest unfinished queue. Lower-queue work may be preserved, documented or kept safely in draft, but must not consume implementation capacity unless the owner explicitly reprioritizes it. No completion claim without exact-head tests and acceptance evidence.

## Priority 1 — GLAFIRA Core (the brain and system authority)

Goal: make Glafira a reliable coordinator for the entire AI office. Core is the highest engineering priority and the single authority for task execution, permissions and agent coordination.

1. Define and enforce the authority model: owner is final decision-maker; Glafira coordinates; specialist agents advise and execute only within granted permissions; model providers are replaceable engines.
2. Stabilize provider registry, primary-model selection, fallback, timeout, retry, cancellation and error reporting.
3. Improve consultation policy: call peers only when useful; never forward project-restricted data; primary Glafira model owns final synthesis.
4. Implement context assembly with project isolation, role instructions, skill references and source citations.
5. Add tool permission scopes, approval gates, audit trail and safe handoff for unsupported actions.
6. Add coordinator tests: routing, provider failures, secret redaction, peer selection, context boundaries, approval behavior and final synthesis.
7. Add health/diagnostics so the owner can see why a model or tool was selected and whether an operation actually completed.
8. Make unified operational memory part of Core: owner, project, agent, conversation and working scopes; provenance, confidence, classification, expiry, owner correction/export/deletion and policy-filtered retrieval.
9. Define provider-independent agent contracts and one shared executor; roles never grant permissions by themselves.
10. Add an independent Reviewer with ACCEPT/REVISE/REJECT/ESCALATE outcomes, deterministic checks, bounded revision cycles and fail-closed behavior.
11. Add a structured, redacted, project-scoped Trace/Audit event stream for tasks, memory, reviews, policy, approvals, providers and tools.
12. Add Core acceptance tests for scope isolation, memory lifecycle, reviewer boundaries, secret redaction, trace continuity and backup/restore consistency.

Foundation contracts are specified in [GLAFIRA CORE — Foundation Technical Specification](GLAFIRA_CORE_FOUNDATION_SPEC.md). A specification is not implementation evidence.

No external irreversible action (send, publish, purchase, delete, alter business records) without explicit approval or a narrowly scoped owner policy.

## Priority 2 — Backup & Recovery

Goal: protect user data before expanding the product.

1. Encrypted, versioned backup archive created on-device.
2. Integrity verification and safe restore workflow.
3. Daily Android WorkManager schedule with retry and owner-visible status.
4. Free off-device destinations: Google Drive and Yandex Disk using owner-authorized OAuth.
5. Email fallback only for small archives; OAuth only, never mailbox passwords.
6. Replace the current cloud backup only after a new upload has completed and been verified; keep one current copy in steady state.
7. New-device restore test. API keys, OAuth tokens and device-bound Keystore secrets remain excluded by default and must be reauthorized after restore.

No rented server or paid backend is required for the initial implementation. Android background execution is best-effort, not an exact-time guarantee. Cloud free-tier quotas can change.

## Priority 3 — Autonomous Runtime, Bridge, Roles and Voice

1. Make role definitions executable and provider-independent.
2. Add role registry and project-level assignment.
3. Implement the optional Voice Assistant plugin with a simple enable/disable, plugin selection and availability status.
4. Preserve automatic voice delivery profiles: personal chats use the selected informal profile; work chats use the formal profile; uncertain chat type defaults to formal.
5. Apply consent and licensing safeguards for recognizable real-person voices.
6. Add SMM, Accountant, Medical/Scientific Consultant, Lawyer and Commercial Director roles, then further roles such as Analyst and Procurement/Logistics as prioritized by the owner.
7. Voice and role behavior must not change facts, permissions, privacy or authority.

## Priority 4 — Knowledge Ingestion and Retrieval Extensions

Unified operational memory is a Priority 1 GLAFIRA Core responsibility and is specified in [GLAFIRA CORE — Foundation Technical Specification](GLAFIRA_CORE_FOUNDATION_SPEC.md). This later priority covers source-document knowledge, not a second memory system.

1. Add document, file, website and connector ingestion with hybrid retrieval, citations and scheduled re-indexing.
2. Support shared project knowledge without leaking restricted data across projects or agents.
3. Add context compaction and long-running task summaries while preserving durable facts and decisions.
4. Preserve source metadata, jurisdiction/date, access policy and citations for retrieved knowledge.

## Priority 5 — Multi-Agent Workforce

1. Add a coordinator-led team of narrowly scoped specialist agents with explicit role contracts.
2. Support sequential, conditional, parallel and delegated handoffs with structured inputs and outputs.
3. Allow independent review/verification agents and require evidence for completion claims.
4. Show task ownership, dependencies, handoffs, status and shared project context.
5. Enforce project boundaries, least privilege and per-agent tool scopes.

## Windows Desktop Bridge implementation baseline

A first JVM 17 Desktop Bridge module is being integrated as a command-line validation client, not as the final Windows Desktop GUI. It includes owner-approved pairing, an encrypted local credential vault, bounded HTTPS transport, a durable task queue, interrupted-task recovery, bounded worker execution, a local Ollama handler and DRY_RUN previews. Its remaining limitations and build commands are documented in [desktop-bridge/README.md](../desktop-bridge/README.md).

The module is not yet the authoritative Core queue and must not be treated as the completed Windows Desktop host. The next stages are shared Core queue integration, Desktop UI/host, enrollment/heartbeats/result acknowledgements, Windows packaging and fleet management. LIVE external actions remain disabled.

## Priority 6 — Secure Device and Computer Bridge

1. Add an authenticated, paired Bridge for desktop and Android device actions.
2. Provide browser automation and controlled UI automation in isolated environments with minimal privileges.
3. Use domain/app allowlists, credential isolation, prompt-injection defenses and action logs.
4. Require owner approval for public, financial, destructive, privacy-sensitive or irreversible actions.
5. Add dry-run/preview mode and recovery checkpoints before consequential actions.

## Priority 7 — Skill Engine and Governed Plugin Marketplace

1. Versioned skill manifest and package validation.
2. Local skill library with import/export, install/update/uninstall and rollback.
3. Project-scoped skill assignment.
4. No implicit tool/network/filesystem permissions from installing a skill.
5. Add role-specific test cases and acceptance checks.

## Priority 8 — Evaluation, Observability and Self-Monitoring

1. Project-scoped document ingestion and RAG.
2. Source metadata, dates, jurisdiction, citations and access filtering.
3. Owner feedback and improvement proposals.
4. Fixed role benchmarks, regression tests, prompt-injection and privacy tests.
5. Staging, owner approval and rollback for production skill changes.

## Priority 9 — Community Exchange

1. Free sharing and paid package listings.
2. Publisher identity, rights, license, provenance, privacy/security declaration and version history.
3. Moderation, malware/archive validation, revocation notices and quality benchmarks.
4. Start with free package exchange; add payment processing only after legal and security review.
5. Never publish private conversations, customer data, secrets or materials without rights.

## Priority 10 — Model Routing and Adaptation

1. Compare candidate foundation models against role-specific benchmarks.
2. Evaluate provider fine-tuning and local LoRA only for tasks with demonstrated need.
3. Use only authorized, curated datasets with source, consent, license and privacy review.
4. Hold out test data and compare against a fixed baseline.
5. Require owner approval before deployment. No automatic training on raw chat history.


## Priority 11 — GLAFIRA Self-Monitor and Safe Self-Improvement

1. Add GLAFIRA SELF-MONITOR: health checks for Core, queue, runtime, backups, Bridge, providers, agents and integrations.
2. Detect stalled tasks, repeated failures, expired credentials, backup drift, CI regressions and resource-limit breaches.
3. Provide an owner-visible event timeline, diagnostics, alerts and incident summaries.
4. Add GLAFIRA SELF-IMPROVEMENT as a proposal-and-validation pipeline: analyze failures, research options, propose a patch, create an isolated branch, run tests and report evidence.
5. Never let self-improvement silently change its own safety policy, authority model, approval requirements or production state.
6. Require signed/reviewable changes, regression tests, staged rollout and tested rollback for runtime or policy changes.

## Priority 12 — Voice, Model Adaptation and Personal Interaction

1. Preserve the optional voice plugin and support multiple selectable voice engines.
2. Add voice input/output profiles, interruption handling, transcription correction and spoken task confirmations.
3. Keep identity/voice imitation subject to consent, licensing and applicable safeguards.
4. Route tasks across cloud and local models based on quality, latency, privacy, availability and cost.
5. Maintain model-specific evaluations and fallback behavior; never switch providers in a way that silently changes privacy boundaries.

## Cross-cutting roadmap additions from competitive research

- Visual workflow designer: a readable graph of agents, tools, conditions, approvals and outputs; include run inspection, replay where safe, and version history.
- Dry-run and simulation: preview tool actions and expected changes against test or mocked data before live execution.
- Teach-by-demonstration skills: let the owner record or describe a repeatable procedure, then turn it into a reviewed, versioned skill.
- Event-driven automation: triggers from schedules, webhooks, messages, files and connected-app events, with deduplication and rate controls.
- Approval center: per-action policies, batch review, pause/resume, escalation reasons, expiration and revocation of grants.
- Credential broker: secrets stay outside model context; scoped tokens, OAuth refresh, per-project connections and revocation.
- Agent observability: end-to-end traces, tool inputs/outputs with secret redaction, latency, retries, cost, token usage and failure reason.
- Evaluation gates: role-specific test suites, quality thresholds, adversarial tests and regression checks before promotion.
- Resource governance: per-task step/time limits, concurrency caps, daily budgets, cost-based pauses and fallback model selection.
- Versioned rollback: immutable skill/workflow versions, staged activation, canary tests and one-click rollback.
- Owner-controlled memory: visible source/provenance, correction, export and deletion; separate personal and shared project memory.
- Multi-channel interface: consistent task status and approvals across Android, desktop, browser and messaging channels.

These are roadmap capabilities, not a claim that they are already implemented. Each requires an implementation plan, tests and end-to-end acceptance evidence before being marked complete.

## Owner-requested specialist agent — Кадровик (GLAFIRA HR Intelligence)

Add a dedicated `hr-intelligence` role to the in-app agent catalog. Its purpose is to maintain source-grounded personal and business contact cards, relationship context, interaction timelines, commitments and owner-only summaries.

The versioned role and training contract is [HR Intelligence Agent — Role, Training and Acceptance Contract](HR_INTELLIGENCE_AGENT_TRAINING.md). It defines:
- Broad read visibility over owner-connected and owner-authorized sources, with auditable/revocable source permissions; role membership never bypasses access controls or grants access to secrets.
- Evidence/provenance, confidence, identity disambiguation, contradiction handling and privacy minimization for every dossier fact.
- Nightly incremental refresh and owner-facing morning reports, with honest connector coverage and no automatic outbound contact.
- The owner's requested all-agent memory review gate: every eligible enabled agent is consulted; unavailable or unauthorized agents are marked NOT_REVIEWED; policy checks and owner approval apply before promotion.
- A staged training curriculum, synthetic/approved examples, role-specific evaluation suite and versioned promotion/rollback.
- In-app catalog status for enablement, source permissions, schedule, last refresh, pending reviews and training version.

Implementation is not complete merely because this roadmap and training contract exist. Follow the contract's implementation sequence and acceptance checklist; do not enable the agent or claim successful contact ingestion until source connectors, catalog UI, review coordinator, scheduled processing and tests are implemented.

## Continuous requirements across all priorities

- Project isolation and least privilege.
- Secrets never enter model prompts, logs, public skill packages or community datasets.
- External actions require owner approval unless narrowly pre-authorized.
- Clear distinction between proposal, successful execution and failure.
- Backups before risky migrations and a tested rollback path.
- Free-first infrastructure; no rented server without a separate owner decision.
- Update this roadmap when the owner adds requirements; do not silently drop prior commitments.

## Current implementation status

- Architecture documents exist for GLAFIRA Core, Voice Assistant, SMM, specialist roles, Intelligence Center and Backup & Recovery.
- Backup code now includes a versioned local archive, PBKDF2-derived AES-GCM encryption, manifest and SHA-256 checks, archive verification, manual export/restore, restore safety snapshot with rollback, Android WorkManager daily scheduling, and a user-selected Storage Access Framework cloud folder.
- The cloud worker uploads a new timestamped archive, reads it back and verifies it before deleting older current copies; steady state is one current archive. If the provider refuses deletion, it keeps the verified new copy and records a cleanup warning. The selected folder can be a Google Drive or Yandex Disk document provider when exposed by an installed app. This is not a direct Drive/Yandex REST OAuth adapter.
- Still required for Backup & Recovery: real-device/provider acceptance testing, permission-expiry and quota failure tests, clean-device restore test, status notifications, and optional email fallback. WorkManager timing is best-effort.
- GLAFIRA Core hardening has started: cross-provider consultation is owner opt-in and disabled by default; common tokens/passwords/contact details are pattern-redacted; the primary model can return a direct answer from the planning call to avoid duplicate requests; Council synthesis now follows the configured primary model.
- Still required for GLAFIRA Core: field-level data-sharing classifications, broader coordinator tests, timeout/cancellation/fallback policy, context-boundary tests, typed tool registry, approval engine, unified operational memory, independent Reviewer and structured Trace/Audit. Their contracts are specified but not yet implemented by the new specification PR.
- Voice runtime and SMM/social connectors remain specifications until implemented and tested.

- Restore semantics hardened: selected backup now replaces the app-private file snapshot, including removing files created after that backup; safety snapshot rollback remains in place. Requires device acceptance tests.
- Latest observed PR #35 validation: Android Unit Tests passed, but Android Emulator Integration Tests failed because the emulator did not finish booting before timeout. This is an infrastructure/test-run failure, not a passing restore acceptance; rerun and investigate before merge.
- PR #37 WorkManager integration remains draft and unmerged; its latest observed CI runs were still in progress at the time of this roadmap update.


## Owner-requested Windows Desktop, agent tools, files and GLF economy specification

The owner-approved requirements for the Windows Desktop control center, three Settings tabs (General; Agent APIs & Tools; Wallet), six specialist-agent connector cards with twelve connector slots, secure credential vault and health indicators, archival yellow Кадровик dossier, File Intelligence Engine, and future GLF wallet/compute marketplace/treasury are documented in [Windows Desktop Control Center and GLF Economy Specification](WINDOWS_DESKTOP_CONTROL_CENTER_AND_GLF_ECONOMY_SPEC.md), tracked by [Issue #152](https://github.com/micronizationlab/glafira-ai-hub/issues/152).

This is a specification only. Candidate connectors require individual source/license/terms/security/free-tier validation. The wallet, token issuance, escrow and cross-customer marketplace are future design items, not implemented features. Keep Core → Bridge → Applications priority; within Queue 3, Windows Desktop precedes iOS. Do not begin Queue 4 agent implementation until Queues 1–3 are established unless the owner explicitly reprioritizes.
