# GLAFIRA AI HUB — Engineering Roadmap

This roadmap records the owner's priority order. Features remain on the roadmap even when they are not in the current sprint. A specification is not considered implemented until code, tests and an end-to-end acceptance check exist.

## Priority 1 — Backup & Recovery (first)

Goal: protect user data before expanding the product.

1. Encrypted, versioned backup archive created on-device.
2. Integrity verification and safe restore workflow.
3. Daily Android WorkManager schedule with retry and owner-visible status.
4. Free off-device destinations: Google Drive and Yandex Disk using owner-authorized OAuth.
5. Email fallback only for small archives; OAuth only, never mailbox passwords.
6. Replace the current cloud backup only after a new upload has completed and been verified; retain a known-good weekly checkpoint.
7. New-device restore test. API keys, OAuth tokens and device-bound Keystore secrets remain excluded by default and must be reauthorized after restore.

No rented server or paid backend is required for the initial implementation. Android background execution is best-effort, not an exact-time guarantee. Cloud free-tier quotas can change.

## Priority 2 — GLAFIRA Core (the brain and system authority)

Goal: make Glafira a reliable coordinator for the entire AI office.

1. Define and enforce the authority model: owner is final decision-maker; Glafira coordinates; specialist agents advise and execute only within granted permissions; model providers are replaceable engines.
2. Stabilize provider registry, primary-model selection, fallback, timeout, retry, cancellation and error reporting.
3. Improve consultation policy: call peers only when useful; never forward project-restricted data; primary Glafira model owns final synthesis.
4. Implement context assembly with project isolation, role instructions, skill references and source citations.
5. Add tool permission scopes, approval gates, audit trail and safe handoff for unsupported actions.
6. Add coordinator tests: routing, provider failures, secret redaction, peer selection, context boundaries, approval behavior and final synthesis.
7. Add health/diagnostics so the owner can see why a model or tool was selected and whether an operation actually completed.

No external irreversible action (send, publish, purchase, delete, alter business records) without explicit approval or a narrowly scoped owner policy.

## Priority 3 — Employee Runtime, Roles and Voice

1. Make role definitions executable and provider-independent.
2. Add role registry and project-level assignment.
3. Implement the optional Voice Assistant plugin with a simple enable/disable, plugin selection and availability status.
4. Preserve automatic voice delivery profiles: personal chats use the selected informal profile; work chats use the formal profile; uncertain chat type defaults to formal.
5. Apply consent and licensing safeguards for recognizable real-person voices.
6. Add SMM, Accountant, Medical/Scientific Consultant, Lawyer and Commercial Director roles, then further roles such as Analyst and Procurement/Logistics as prioritized by the owner.
7. Voice and role behavior must not change facts, permissions, privacy or authority.

## Priority 4 — Skill Engine

1. Versioned skill manifest and package validation.
2. Local skill library with import/export, install/update/uninstall and rollback.
3. Project-scoped skill assignment.
4. No implicit tool/network/filesystem permissions from installing a skill.
5. Add role-specific test cases and acceptance checks.

## Priority 5 — Knowledge and Evaluation

1. Project-scoped document ingestion and RAG.
2. Source metadata, dates, jurisdiction, citations and access filtering.
3. Owner feedback and improvement proposals.
4. Fixed role benchmarks, regression tests, prompt-injection and privacy tests.
5. Staging, owner approval and rollback for production skill changes.

## Priority 6 — Community Exchange

1. Free sharing and paid package listings.
2. Publisher identity, rights, license, provenance, privacy/security declaration and version history.
3. Moderation, malware/archive validation, revocation notices and quality benchmarks.
4. Start with free package exchange; add payment processing only after legal and security review.
5. Never publish private conversations, customer data, secrets or materials without rights.

## Priority 7 — Model Adaptation

1. Compare candidate foundation models against role-specific benchmarks.
2. Evaluate provider fine-tuning and local LoRA only for tasks with demonstrated need.
3. Use only authorized, curated datasets with source, consent, license and privacy review.
4. Hold out test data and compare against a fixed baseline.
5. Require owner approval before deployment. No automatic training on raw chat history.

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
- The cloud worker uploads a new timestamped archive, reads it back and verifies it before pruning older daily copies; it retains one weekly checkpoint. The selected folder can be a Google Drive or Yandex Disk document provider when exposed by an installed app. This is not a direct Drive/Yandex REST OAuth adapter.
- Still required for Backup & Recovery: real-device/provider acceptance testing, permission-expiry and quota failure tests, clean-device restore test, status notifications, and optional email fallback. WorkManager timing is best-effort.
- GLAFIRA Core hardening has started: cross-provider consultation is owner opt-in and disabled by default; common tokens/passwords/contact details are pattern-redacted; the primary model can return a direct answer from the planning call to avoid duplicate requests; Council synthesis now follows the configured primary model.
- Still required for GLAFIRA Core: field-level data-sharing classifications, broader coordinator tests, timeout/cancellation/fallback policy, context-boundary tests, typed tool registry, approval engine and audit trail.
- Voice runtime and SMM/social connectors remain specifications until implemented and tested.

- Restore semantics hardened: selected backup now replaces the app-private file snapshot, including removing files created after that backup; safety snapshot rollback remains in place. Requires device acceptance tests.
