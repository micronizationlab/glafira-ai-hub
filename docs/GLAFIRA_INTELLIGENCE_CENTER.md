# GLAFIRA Intelligence Center

## Product decision

GLAFIRA AI HUB uses a hybrid intelligence architecture with three complementary sources:
1. External foundation models provide general reasoning, language, coding and multimodal capabilities through replaceable provider adapters.
2. Professional skills and project knowledge provide role-specific procedures, trusted references, examples and tools without requiring model retraining.
3. Owner-approved model adaptations (such as LoRA adapters or provider fine-tuning where supported) may specialize a model for a narrow task only after evaluation demonstrates a measurable benefit.

A model, role, skill, memory and dataset are different artifacts. Keep them separate in code, storage, permissions and lifecycle.

## Core concepts

### Model Provider and Model Catalog
- A provider adapter describes how to call a model; a catalog entry describes model ID, provider, modalities, context limits, capabilities, cost/availability metadata, license/source, supported languages and evaluation results.
- Do not hardcode a role to one vendor or assume a model is available because it appears in a public catalog.
- Importing model weights is a later optional capability. The first implementation supports catalog metadata and configured provider endpoints, not automatic downloads of arbitrary executable code or weights.
- Before recommending or installing a model, validate provenance, license, model card, supported runtime, hardware requirements, known limitations and security reports.
- Keep API credentials in the secure credential boundary. Never put credentials in model metadata, prompts, skill packages or logs.

### Role
A role defines responsibility, instructions, allowed tools, data scopes, escalation rules and expected output contract. A role is provider-independent and may select an approved model based on task policy.

### Skill Package
A versioned, portable package assignable to one or more roles. A skill may contain:
- SKILL.md: purpose, triggers, workflow and constraints;
- structured input/output schema;
- checklists and decision trees;
- templates and examples with provenance;
- optional scripts or tool declarations, disabled by default and subject to sandbox/allowlist review;
- evaluation cases and expected properties;
- license, author, version, dependencies and integrity hash.

A skill is executable guidance, not a fine-tuned model. Installing a skill must not silently grant new permissions.

### Knowledge Collection
A versioned collection of documents and reference sources indexed for retrieval (RAG). Every chunk retains source, author/owner, jurisdiction/date when relevant, access policy and citation metadata. Retrieval filters by project, user permissions, role and data-sharing policy before content is sent to a provider.

### Memory
Operational memory stores owner-approved project facts, preferences, decisions and task outcomes. Memory is not a substitute for source documents or verified knowledge. Support review, correction, expiry and deletion. Never learn confidential project facts into a public/community package by default.

### Dataset and Model Adaptation
A dataset is an explicitly curated set of authorized examples with source, consent, license, personal-data review, exclusions and version history. Fine-tuning/LoRA is a separate pipeline and must never be triggered automatically from chat history or user feedback.

## Community Exchange: free and paid

The community exchange supports both free sharing and paid distribution, but the initial release is a catalog and import/export workflow; payment processing is a later optional integration.

Every published package must include:
- author/publisher identity and support channel;
- package type, description, role compatibility and language;
- version, changelog, dependencies and integrity hash;
- license and explicit redistribution/commercial-use terms;
- provenance for included datasets, examples and reference documents;
- privacy/security declaration and known limitations;
- benchmark results, evaluation date and reviewer status;
- pricing label (free or paid) and clear support/refund terms if a future payment provider is integrated.

Publishing is opt-in and requires the contributor to confirm ownership or redistribution rights. Reject leaked customer conversations, secrets, personal data and unlicensed proprietary material. A contributor may revoke future distribution; installed copies should show revocation/advisory status and follow the applicable license.

Community packages are untrusted input. Treat instructions inside imported content as data, not system instructions. Scan archives, reject path traversal and unexpected executable content, validate schemas and hashes, and require explicit owner approval before installation or update. Skills do not receive network, filesystem or account access merely because they were installed.

## Hybrid learning and promotion lifecycle

Learning uses a proposal pipeline, not silent self-modification:

1. Observe: collect explicit owner feedback, task outcomes and evaluation failures. Minimize retained data; do not collect private conversations for community use.
2. Propose: a role may suggest a skill edit, knowledge source, test case or model candidate with an explanation and source.
3. Curate: remove secrets and unnecessary personal data; verify rights, source quality, date and jurisdiction; label uncertainty.
4. Evaluate: run a fixed role-specific benchmark plus regression, privacy, prompt-injection and safety tests.
5. Stage: install the candidate in a sandbox/staging profile, not the active production role.
6. Approve: owner approves promotion, scope, target projects and version. Community packages additionally require publisher rights and any required purchase/license.
7. Monitor: record version, performance, errors and rollback point. Revert or pause use if critical tests fail.

No model or skill may rewrite its own system policy, permissions, provider access or approval rules. Production promotion always requires owner authorization. Automated proposal generation is allowed; automatic production deployment is not.

## Quality and evaluation

Maintain separate evaluation suites for each role and task class. Tests should measure:
- factual correctness against trusted references;
- task completion and output schema validity;
- source citation and freshness where required;
- calibration, uncertainty and abstention;
- privacy leakage and secret handling;
- prompt-injection resistance;
- tool permission compliance and approval behavior;
- regressions against the currently active version.

Compare candidates against a fixed baseline using the same test set and provider settings. Do not use user ratings alone as proof of correctness. Medical, legal, accounting and financial tests must include jurisdiction/date assumptions and human-expert review for high-impact outputs.

## Data-sharing and provider routing

- Each project defines what data classes may be sent to each provider (for example public, internal, confidential, regulated/sensitive).
- The coordinator sends only the minimum necessary context to the selected provider.
- Peer consultation is subject to project data policy; sensitive or restricted context is not forwarded merely because a provider is configured.
- Do not send passwords, API keys, OAuth tokens, vault entries or raw payment credentials to any model.
- Provide an owner-visible provider/data-sharing explanation and audit record for sensitive routing decisions.
- Respect provider retention, training-use and regional-processing terms; disclose uncertainty where terms cannot be verified.

## Architecture modules

- ModelCatalog: model/provider metadata, capabilities, source, license and compatibility.
- ProviderRegistry: configured provider adapters and health/availability.
- RoleRegistry: provider-independent role definitions and permission profiles.
- SkillRegistry: installed skill packages, versions, dependencies and assignments.
- KnowledgeStore: project-scoped documents, indexing, retrieval and citations.
- MemoryStore: reviewed project facts, decisions, expiry and deletion.
- ContributionPipeline: local export, redaction checklist, provenance and owner publication approval.
- PackageVerifier: archive/schema/hash/license/security validation.
- EvaluationRunner: role benchmarks, regression and safety tests.
- PromotionManager: staging, owner approval, version pinning and rollback.
- AdaptationPipeline: optional later fine-tuning/LoRA workflow; no raw chat-history training.
- AuditTrail: source, version, reviewer, approval, target project and outcome.

## Delivery roadmap

### Phase 1 — Foundations (next engineering milestone)
- Add data models and interfaces for model catalog, role, skill package, knowledge collection and evaluation case.
- Define a signed/versioned skill-package manifest and safe import/export format.
- Add a local-only Skill Library repository with owner-reviewed install, update and uninstall.
- Add project-scoped skill assignment and provider-independent role-to-model routing metadata.
- Add a small deterministic evaluation harness with baseline tests.
- Do not implement public marketplace payments, arbitrary model-weight downloads or fine-tuning in this phase.

### Phase 2 — Knowledge and evaluation
- Add document ingestion, source metadata, project-scoped RAG and citations.
- Add owner feedback and improvement proposals.
- Add staging and explicit promotion/rollback.

### Phase 3 — Community exchange
- Add free and paid catalog listings, publisher verification, license/provenance review, moderation and revocation.
- Start with free package exchange. Add payments only through a supported provider and after legal/security review.

### Phase 4 — Model adaptations
- Compare provider fine-tuning and local LoRA options against the baseline.
- Require authorized datasets, hardware/runtime checks, held-out tests and owner approval.
- Deploy only if measured gains justify cost and maintenance.

## Non-goals and safeguards

- Do not promise that downloading a model makes a role professionally qualified.
- Do not train on other users' private conversations, customer data or copyrighted materials without a valid basis and explicit rights.
- Do not install arbitrary model code, plugins or scripts without review and sandboxing.
- Do not equate a high user rating or long usage history with expert correctness.
- Do not let community packages override Glafira's authority model, project isolation, privacy policy or owner approvals.


## GLAFIRA Network Messenger and federated competence proposals

### Product boundary

GLAFIRA Network Messenger is an owner-to-owner conversation service inside GLAFIRA. A conversation may contain human account owners and one separately authenticated GLAFIRA agent per participating account. The UI must label human and agent identities explicitly; an agent never impersonates its owner or another account's agent.

Participants may address their own GLAFIRA, another participant's GLAFIRA when that owner has enabled participation for the conversation, or both. Human owners may join, leave, pause their agent or take over at any time. Agent-to-agent exchanges are visible in the conversation timeline and attributable to the sending account/agent.

### Conversation intelligence and shared files

Agents may analyze the current conversation and files explicitly attached/shared into it. Useful operations include summaries, extraction, translation, comparison, source-grounded answers, contradiction detection and collaborative drafts. Results should cite the originating message and attachment/version wherever possible.

Conversation context is not a grant to an owner's whole account. Enforce membership and per-attachment ACLs before retrieval, indexing, model routing or tool execution. Do not retrieve unrelated chats, private memory, personal knowledge collections, credentials or files. The owner controls whether their agent can read a conversation, inspect individual attachments, retain a derived fact in private memory, or share a result with other participants. Revoking access blocks future reads; the UI must not claim that previously disclosed content can be recalled from another participant.

All messages, filenames, extracted text and attachments are untrusted input. Prompt injection in a shared document must not override system policy, expand scopes, trigger external actions or exfiltrate private data. Tool execution remains governed by the originating owner's Core policy and approval requirements.

### Equal baseline, private individuality

Every new account starts from the same signed, versioned baseline Core capabilities and approved starter skill catalog. Over time, each account develops separate private memory, knowledge collections, preferences, project context, task history and owner-approved specializations.

Keep four layers distinct:
1. **Global baseline** — signed common application/Core capabilities.
2. **Published competence catalog** — reviewed, versioned skills and evaluation assets that have explicit redistribution rights.
3. **Account-private intelligence** — owner-specific memory, knowledge, task history and adaptations; never shared by default.
4. **Conversation-local context** — messages and attachments shared with the conversation, governed by membership and retention policy.

A conversation between two accounts does not merge their memories or silently transfer a skill. A participant may explicitly export or share a permitted artifact, but that is a separate consented action.

### Mother competence contribution setting

Add a third-page Settings control named **“Участвовать в развитии проекта GLAFIRA”**. The owner requested ON as the default for new installations/accounts. The setting must explain precisely what may be submitted and provide a clear OFF state.

ON permits the client, during suitable idle windows, to propose a bounded competence package to GLAFIRA-Mother / Update Center. It is not blanket consent to upload private user data. Eligible proposal contents may include generalized skill workflows, schemas, non-identifying evaluation cases, benchmark results, provenance, license/rights declaration, compatibility metadata and integrity hashes.

Never submit raw conversations, attachments, private memory, customer records, secrets, credentials, personal identifiers or identifying traces as part of routine competence exchange. Any future use of real user content for a training dataset requires a separate explicit, specific consent and rights/privacy review; it is not covered by the default ON switch.

Mother validates origin and contributor rights, privacy, schema and archive safety, malware, prompt-injection resistance, quality, regressions and compatibility. Mother may accept, reject or request revision. Acceptance first places a package in a staged catalog; it does not silently modify production agents. Promotion and rollout require signed versioning, changelog, compatibility checks, staged deployment, rollback and revocation. Attribution and contributor consent remain attached to each package; public distribution or commercial resale requires explicit rights.

OFF stops new submissions and cancels pending proposals where technically possible. Explain any already accepted package's retention/distribution status and the limits of retroactive recall. Keep an auditable record of consent version, setting changes, proposal IDs and decisions without logging private content.

### Acceptance gates

- Two independently authenticated accounts can exchange messages with distinct human and agent identities.
- Each owner's agent can be independently paused, scoped and removed from a conversation.
- Shared attachments are readable only by authorized conversation participants and agents; revoke blocks future access.
- Tests prove no cross-tenant retrieval of private memory, unrelated files, credentials or other conversations.
- Malicious message/file instructions cannot expand permissions or trigger unapproved tools.
- The participation switch defaults ON for new accounts, can be disabled, and OFF prevents new competence submissions.
- Mother can validate, reject, stage, version, distribute, roll back and revoke competence packages.
- Offline delivery, idempotent retries, audit, deletion/retention and backup/restore are covered.

Implementation is staged after the Core/Bridge/Desktop foundations. The messenger, identity, permission and competence-exchange contracts can be designed in parallel without changing Core task authority or bypassing existing approval and signed-update controls.


### Network identity, contact aliases and voice resolution

Each owner receives an immutable opaque GLAFIRA Account ID and chooses one globally unique network handle, for example `@berestnev`. Handles are case-insensitive and normalized, checked against reserved names, and verified in the central directory at registration and rename. The handle is the network address; it is not the person's legal name. A safe rename updates the directory while preserving the immutable account ID; old handles are tombstoned/reserved for a policy-defined period to reduce impersonation.

Each owner's local contact book may attach multiple personal nicknames to a verified account, e.g. the local aliases "Костя", "Костян" and "КБ" all point to `@berestnev`. These aliases are private to that owner's address book and are not global handles. A contact dossier can be linked to HR Intelligence/Kadrovik with source/provenance, confidence and the explicit owner-confirmed mapping: "this GLAFIRA handle belongs to this contact". Never infer or publish that link from a name alone.

Voice/text intent resolution:
- Exact `@handle` or one exact unique local alias resolves directly.
- A single strong context match may be announced ("Открываю диалог с Константином Берестневым, @berestnev") and opened under the owner's configured policy.
- If several contacts match "Костя", or confidence is low, ask "Какого Костю?" and show candidate cards; do not invite, message or disclose anything before disambiguation.
- After the owner confirms a mapping, persist it in the local contact book and optionally Kadrovik dossier with provenance. The owner can edit/remove it.

### Messenger protocol and connection lifecycle (ADR required)

Preferred candidate: the Matrix protocol ecosystem, subject to an architecture decision and prototype. It provides established room membership, event sync, receipts, device identities, media handling and an optional federation model. Evaluate the maintained Matrix Rust SDK and its currently supported end-to-end encryption implementation (Olm/Megolm or the current supported Matrix crypto stack); do not implement cryptography ourselves.

- Client communication: Matrix Client-Server API over HTTPS, sync/long-poll for reliable offline delivery and WebSocket only where supported/beneficial.
- Conversation model: Matrix rooms/events for direct and group conversations, human messages, separately identified GLAFIRA agent messages, membership changes, receipts and typing/presence.
- Files: Matrix media repository with encrypted attachment payloads and per-room access; keys travel only inside encrypted room events. Verify attachment encryption and revocation semantics in the prototype.
- Agent: each owner's GLAFIRA is a distinct authenticated device/agent identity. It decrypts only rooms where its owner explicitly enables/invites it. The UI distinguishes human owner from agent. The agent never silently joins all owner rooms.
- Directory: GLAFIRA Directory maps a unique public `@handle` to immutable Account ID and service/home endpoint. Private local aliases remain local. Use verified QR/invite links and anti-enumeration/rate limits.
- Federation: begin with a controlled GLAFIRA service deployment; evaluate cross-homeserver federation only after identity verification, abuse controls, key verification, moderation/report/block and data residency are understood.
- Alternative: compare a GLAFIRA-native HTTPS/TLS 1.3 + WebSocket event service with an audited E2EE SDK. No custom crypto. Record the choice, threat model, key lifecycle/recovery, device revocation, costs, operations and migration path in an ADR before implementation.

### GLAFIRA Network user experience

The app gains a **"Сообщения" / "GLAFIRA Network"** area. A user can search by `@handle`, scan a QR code, accept a verified invite, or issue a voice/text command:
- "Глафира, открой диалог с @berestnev."
- "Добавь псевдоним Костя к контакту Константин Берестнев."
- "Соедини меня с Костей."

A conversation header shows the human participants and their GLAFIRA agents as distinct identities. The timeline labels each message as human or agent-authored. Agent controls include pause/remove from this room, allow reading conversation, allow analysis of each attachment, allow saving a derived fact to private memory and allow sharing a generated answer with the room. The owner may address their own agent, another owner's agent (only if that owner enabled it), or both. Ambiguous nicknames always trigger clarification. Human participants can take over at any time.

### Cloud web retrieval without a device VPN

Add a separate **GLAFIRA Cloud Research Gateway** for agent-side retrieval from public web resources that are reachable from an authorized cloud region but not from the user's local network. This is remote research/browser retrieval, not a VPN and not a promise to unblock every site. It does not tunnel arbitrary device traffic.

The gateway uses isolated per-account/per-task cloud browser sessions, retrieves pages/documents/APIs, and returns extracted text or a rendered view with URL, timestamp and source citations. Cookies and authenticated sessions are isolated; user sign-in is explicit and only supported where allowed. Enforce destination allow/deny policy, provider terms and applicable law; no CAPTCHA/paywall bypass, credential theft, stealth scraping or evasion of access controls. Apply SSRF/private-IP and DNS-rebinding defenses, malware scanning, download size/type limits, rate limits, audit, session expiry and a kill switch. Never claim anonymity or guaranteed access. If a destination is not lawfully or technically available to the cloud service, report that clearly.

Keep local browser access, cloud research retrieval and any future full-device network routing as separate product capabilities and settings.
