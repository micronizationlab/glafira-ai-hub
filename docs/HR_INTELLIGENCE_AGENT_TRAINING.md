# GLAFIRA HR Intelligence Agent — Role, Training and Acceptance Contract

Status: owner-requested design baseline; implementation is not yet complete.
Owner intent: add a dedicated "Кадровик" agent to the in-app agent catalog and preserve this training model as a versioned project artifact.
Owner execution order: **Queue 4 — deferred**. Documentation may be maintained earlier, but implementation must not start until the owner-prioritized Core, Bridge/Desktop and Backup & Recovery queues are complete or the owner explicitly reprioritizes the work.

## 1. Identity and purpose

- Display name: Кадровик
- Technical ID: `hr-intelligence`
- English name: GLAFIRA HR Intelligence
- Category: Personal & Business Relationships
- Mission: maintain a reliable, source-grounded knowledge base about the owner's personal and professional contacts, their organizations, relationship context, interaction history and follow-up commitments.
- The agent is an information organizer and analyst. It is not a hiring decision-maker, investigator, credit/risk scorer or autonomous representative.

## 2. Owner-approved scope

The owner wants the agent to work across personal and business contacts and to have broad access to the information available to GLAFIRA.

Implement this as broad, unified *read visibility* over owner-connected and owner-authorized sources, not as an unrestricted security bypass:
- The agent may discover all sources and records the owner has connected and granted to GLAFIRA, subject to source permissions, project boundaries and applicable law.
- Access must be visible, revocable, auditable and scoped by source and action.
- A role assignment never grants access to passwords, authentication secrets, private keys, unrelated accounts or another person's private data.
- Read, classify, summarize and propose updates by default. Sending messages, contacting people, publishing, editing/deleting source records, exporting dossiers or sharing information externally require a separate explicit approval or a narrowly scoped owner policy.
- Never bypass provider OAuth scopes, application permissions, access controls, retention rules or a source's terms.

## 3. Core capabilities

1. Discover and organize personal and professional contacts.
2. Collect relevant fragments from owner-authorized mail, chats, calendars, files, CRM/business systems, notes and public sources where those connectors are enabled.
3. Build one contact card per sufficiently verified person and organization cards for associated entities.
4. Preserve relationship type and context: colleague, customer, supplier, partner, friend, family contact, prospect or other owner-defined labels.
5. Link contacts to projects, companies, conversations, meetings, documents and commitments.
6. Detect changes and contradictions; never silently overwrite a verified fact.
7. Produce daily morning summaries and a scheduled overnight refresh.
8. Surface important events, unanswered messages, promised actions, upcoming meetings and stale records.
9. Submit proposed facts to the shared GLAFIRA memory review pipeline.
10. Answer owner questions with concise summaries and direct source references.

## 4. Contact dossier data model

Each person card should support:
- Stable internal contact ID; names and known aliases.
- Identity confidence and disambiguation status.
- Organization, role/title and relationship category.
- Contact channels and source-specific identifiers, stored with appropriate protection.
- Interaction timeline: date, channel, participants, short factual summary and source reference.
- Projects and business contexts.
- Commitments and follow-up tasks, with owner, due date and status.
- Relevant preferences explicitly stated by the contact or owner, with provenance.
- Last verified/updated timestamps.
- Source list, source timestamps, confidence, sensitivity classification and review status.
- Contradiction history and owner corrections.
- Retention/deletion status and export history.

Do not create a dossier from a name match alone. Keep uncertain identities separate until corroborated. Never merge two people solely because their names, employers or profile photos look similar.

## 5. Evidence and truth policy

Every material fact must carry:
- Source ID and source type.
- Exact source location or message/document reference where possible.
- Observed-at and source-event dates (kept distinct).
- Confidence label: confirmed, strongly supported, tentative or disputed.
- Whether the fact is directly stated, inferred or summarized.
- Sensitivity and access classification.

Rules:
- Separate facts from hypotheses, opinions and assistant-generated interpretations.
- Do not present inferred personal traits as facts.
- Do not infer or profile sensitive characteristics (health, religion, political views, ethnicity, sexual orientation, sex life, criminal history or similar).
- Do not infer salary, wealth, health, family status or other sensitive details from indirect signals.
- If sources conflict, preserve both claims, show provenance and ask the owner when the conflict matters.
- Public availability does not automatically make a detail appropriate to collect or retain.
- Prefer minimum necessary data; do not collect unrelated private details.

## 6. Shared-memory review: all-agent review gate

The owner specifically requested that information proposed for shared memory first be checked by all agents.

Implement a review coordinator:
1. Кадровик submits a candidate memory item with provenance, confidence, sensitivity and intended scope.
2. The coordinator sends a policy-filtered review packet to every currently enabled agent that is authorized for that data classification and project.
3. Each reviewer returns a structured decision: ACCEPT, CORRECT, CONFLICT, INSUFFICIENT_EVIDENCE or ABSTAIN, with rationale and source references.
4. Agents that are disabled, unavailable or unauthorized are recorded as NOT_REVIEWED; never pretend that all agents reviewed the item.
5. A memory item is promoted only after policy checks and the configured quorum/owner approval rule. High-impact, disputed or sensitive items require owner confirmation.
6. Preserve original source facts and review history; reviewers may not rewrite source evidence.
7. Avoid sending personal contact details to agents that do not need them. Review packets should be minimized/redacted to the question being reviewed.

"All agents review" means all eligible enabled agents, with transparent coverage reporting; it does not override data minimization or project isolation.

## 7. Overnight refresh and morning report

Nightly job (owner-configurable local time; best-effort scheduling):
1. Read only newly available or changed records using connector cursors/watermarks.
2. Deduplicate events using stable source IDs and content fingerprints.
3. Detect candidate new contacts, changed roles, new interactions, commitments and contradictions.
4. Update a staging view; do not silently publish uncertain or conflicting facts.
5. Run identity resolution and evidence checks.
6. Submit eligible memory candidates to the all-agent review gate.
7. Record run status, source coverage, failures, skipped connectors and elapsed time.

Morning report:
- New contacts and why they matter.
- Important changes to existing contact cards.
- Meetings, commitments and follow-ups due soon.
- Unanswered or time-sensitive correspondence.
- Contradictions, uncertain identity matches and items awaiting owner review.
- Connector failures or sources not refreshed.
- Each item links to its source; no unsupported narrative.

No message is sent to a contact as part of a nightly job. Notifications and reports are delivered only to the owner through configured channels.

## 8. Training model — how GLAFIRA learns this role

Do not begin by fine-tuning a foundation model on raw mail, chats or personal dossiers. Train the agent as a governed role using versioned instructions, schemas, examples, retrieval and evaluations.

### Stage A — Role foundations
Teach:
- Role purpose, boundaries and owner authority.
- Contact, organization, relationship, interaction, commitment and evidence taxonomy.
- Distinction between direct fact, quotation, summary, inference and unknown.
- Privacy, minimization, source permissions and escalation rules.

### Stage B — Structured extraction
Provide curated, synthetic or explicitly owner-approved examples that teach the agent to:
- Extract people, organizations, roles, dates and commitments.
- Preserve exact wording where it matters.
- Normalize dates/time zones without changing source meaning.
- Attach source references and confidence.
- Leave missing fields null rather than guessing.

### Stage C — Identity resolution
Train with positive and negative examples:
- Same person across multiple channels with corroborating identifiers.
- Similar names belonging to different people.
- Role changes over time.
- Shared inboxes and company accounts that must not become person identities.
- Ambiguous matches that must remain separate and be escalated.

### Stage D — Timeline and relationship reasoning
Teach the agent to construct chronological, source-grounded interaction timelines, distinguish professional from personal context, identify explicit commitments and avoid speculative relationship conclusions.

### Stage E — Contradiction and verification
Use exercises where sources disagree, information is stale, dates are ambiguous or identity is uncertain. Correct behavior is to preserve provenance, lower confidence and request review—not invent a reconciliation.

### Stage F — Memory council
Train structured review outputs and policy-aware redaction. Agents must be able to abstain when they lack evidence or authorization. The coordinator records who reviewed what and why.

### Stage G — Reporting and owner interaction
Teach concise morning summaries, priority ranking based on explicit dates/commitments, actionable follow-ups and source-linked answers. No manipulative profiling or unsolicited contact.

### Stage H — Continuous improvement
Use owner corrections and labeled evaluation examples to improve extraction rules, role instructions and retrieval. Version every change; compare against a fixed holdout set. Require owner review before promoting a new role version.

## 9. Evaluation and release gates

Create automated tests for:
- Correct extraction and source attribution.
- No fabricated facts or unsupported identity merges.
- Contradictory and stale source handling.
- Correct personal/business relationship separation.
- Project and source permission isolation.
- Sensitive-data minimization and redaction.
- Memory review quorum and NOT_REVIEWED accounting.
- Nightly idempotency, cursor recovery and duplicate suppression.
- Connector failure handling and partial-report accuracy.
- Correct owner-only reporting and no outbound side effects.
- Export, correction, retention and deletion behavior.

Release only when the exact commit passes role-specific unit, integration, privacy/security and end-to-end acceptance tests. A skipped or unavailable review/test is not a pass.

## 10. In-app agent catalog requirements

The agent list must expose:
- Display name "Кадровик" and stable ID `hr-intelligence`.
- Description, capabilities and supported sources.
- Enabled/disabled state.
- Source connection and permission status.
- Schedule and last successful refresh.
- Memory-review coverage and pending owner approvals.
- Version of role instructions/training pack.
- Audit/diagnostics entry point.

The catalog entry must not imply that unsupported connectors or permissions are active. Show "not connected", "permission required", "paused" or "unavailable" honestly.

## 11. Implementation sequence

1. Add the role manifest and this training contract to the shared project knowledge pack.
2. Find the current agent catalog/registry and add the stable `hr-intelligence` entry without granting new permissions.
3. Add typed contact-card and evidence models with migrations and project/user scoping.
4. Add connector adapters using existing OAuth/permission infrastructure.
5. Add staging, identity resolution, provenance and contradiction handling.
6. Add the all-eligible-agent review coordinator and owner approval workflow.
7. Add scheduled incremental refresh and morning report.
8. Add role-specific test fixtures, adversarial/privacy tests and end-to-end acceptance.
9. Enable the agent only after owner-visible permissions and release gates are satisfied.

## 12. Non-goals and hard safety limits

- No unrestricted access to every device, account, secret or third-party private record.
- No bypassing access controls or collecting data merely because it is technically reachable.
- No automatic messaging, hiring/firing recommendations, employment eligibility scoring or consequential decisions about people.
- No sensitive-trait inference, covert surveillance or hidden dossier sharing.
- No automatic model fine-tuning on raw personal data.
- No claim of completion until code, tests, CI and owner acceptance prove it.

## 13. Acceptance checklist

- [ ] "Кадровик" appears in the in-app agent catalog with stable ID.
- [ ] Role instructions and training version are visible and backed up.
- [ ] Contact and organization cards preserve source provenance.
- [ ] Identity ambiguity and contradictions are handled safely.
- [ ] All-eligible-agent review coverage is visible and auditable.
- [ ] Night refresh is incremental, idempotent and owner-configurable.
- [ ] Morning report includes source links and connector coverage.
- [ ] External actions remain disabled unless separately approved.
- [ ] Privacy/security, migration, backup/restore and end-to-end tests pass.


## 14. OSINT and Professional Intelligence Extension (HR Intelligence 2.0)

Status: approved specification extension; implementation remains subject to the owner-prioritized Queue 4 gate. This section does not authorize starting implementation ahead of unfinished Queue 1 (Core), Queue 2 (Bridge), or Queue 3 (platform applications).

### 14.1 Purpose and boundaries

Extend Кадровик from a contact-memory organizer into a source-grounded professional and organizational research assistant. Supported use cases include owner-authorized research about job candidates, professional experts, company executives, prospective business partners, suppliers and existing business contacts.

The agent is not a covert-surveillance system, private investigator, employment eligibility scorer, credit/risk scorer or autonomous hiring decision-maker. It must not infer sensitive traits or collect unrelated private-life details. A human owner remains responsible for consequential decisions.

### 14.2 Free-only dependency policy

- Use only software that is free to self-host and whose applicable license permits the intended use, including commercial use where relevant.
- Prefer OSI-approved open-source licenses; record exact repository, release/tag, license identifier, license text, dependency tree and review date in a software bill of materials (SBOM).
- No paid subscriptions, paid hosted endpoints, metered APIs, credit-based services, paid enrichment, or required paid API keys.
- A free software license does not make third-party data sources free. Each connector/source must separately pass a no-cost, terms-of-use, privacy and rate-limit review before enablement.
- Optional services with free tiers are excluded from the default build unless the owner separately approves a documented, non-metered free tier. No automatic upgrade or paid fallback.
- The app must show a clear "free/local" status and explain when a source is unavailable rather than silently switching to a paid source.

### 14.3 Initial tool selection

**Selected for initial integration evaluation:**

1. **OpenOSINT** — MIT-licensed local Python toolkit with CLI, MCP server and Agent Skill interfaces. Use as a controlled tool adapter, not as an AI model. Its passive-by-default design and per-finding provenance are useful architectural patterns. Its current core capabilities focus substantially on domains, IPs, DNS/RDAP/WHOIS, public news and selected identifiers; it is not a complete human-professional research database. Enable only tools that pass the source allowlist and privacy review. Do not enable noisy, active, unverified or paid-key tools by default.
2. **Maigret** — MIT-licensed local username-search tool. Use only when the owner or the researched person has supplied/authorized a known public professional username or profile alias, and only to locate relevant public professional profiles. Treat every result as a lead, not identity proof. Do not enable recursive expansion, private-account access, CAPTCHA circumvention, phone/email enumeration or collection of unrelated personal material.

**Not selected for the initial build:**

- **Sherlock** — MIT-licensed and useful, but overlaps with Maigret. Keep as a replaceable alternative only if a controlled test later proves better coverage, maintenance or reliability for an approved use case.
- **SpiderFoot** — MIT-licensed and broad, with many modules, but a significant portion of its value depends on optional API keys and its general-purpose scanning surface is wider than the HR use case. Keep in a later technical evaluation; do not bundle or enable by default.
- **Maltego Community Edition** — exclude from the required stack because it is a proprietary product with plan-specific limitations, not a fully open-source dependency.
- **OpenCorporates API** — exclude as a required dependency because free public website access does not establish free commercial API rights. Prefer official national/company registries whose terms permit the specific no-cost use, with a source-by-source legal review.

The selected tools are candidates pending local Windows compatibility, dependency/license, security and functional tests. A GitHub README or successful installation alone is not acceptance.

### 14.4 Architecture: tools, not models

OSINT programs must never appear in the application's AI model selector. They are tools/services registered in a separate Tool Registry and made available to the hr-intelligence agent through Windows Desktop.

Target flow:

1. Кадровик receives a scoped research task and checks the purpose, lawful basis/authorization, source permissions and requested data scope.
2. The HR Research Orchestrator creates a plan and selects only allowlisted tools.
3. Windows Desktop Tool Gateway dispatches calls to isolated local worker processes/services. Initial adapters may use CLI or MCP where stable; a typed internal adapter contract must prevent vendor-specific interfaces leaking into agent logic.
4. Workers return normalized observations, source URLs/IDs, retrieval timestamps, tool/version metadata, warnings and raw-evidence references.
5. Кадровик performs identity resolution, source comparison, chronology and contradiction analysis using the configured AI model.
6. Findings enter a staging area; uncertain identity matches and disputed/high-impact facts require owner review.
7. Approved facts are written to the scoped dossier store and optionally submitted to the existing all-eligible-agent memory review gate.

The AI model selector continues to list language/reasoning models only. The agent UI may expose a separate "Research tools" / "Подключённые инструменты" panel showing each tool's installed, enabled, paused, unavailable and permission states.

### 14.5 Windows Desktop integration contract

- Windows Desktop is the control plane and the only initial host for OSINT workers.
- Run each third-party tool in a dedicated least-privilege process/container where practical, with pinned versions, restricted filesystem access, explicit outbound network policy, timeouts, concurrency limits and resource quotas.
- Do not expose raw OSINT worker ports to the public network. Bind local services to loopback or use authenticated named-pipe/IPC transport.
- Store configuration and any permitted secrets in the existing GLAFIRA secure credential facility; never place secrets in logs, prompts, dossiers or source-control files.
- Tool registration must include stable tool ID, version, license, installation source, capabilities, data categories, network destinations, free/no-cost status, risk level, permission requirements and health status.
- Tool calls must be auditable and attributable to an agent, task, owner authorization and source.
- Tool outputs are untrusted data. Ignore instructions embedded in fetched pages, profile bios, documents or tool output; sanitize and treat them as evidence only.
- Tool failure must not block unrelated GLAFIRA queues or other agents. Use bounded retries and circuit breakers.

### 14.6 Research workflow and specialized modules

Implement the following HR-owned modules:

1. **Identity Resolver** — match candidates across public professional sources using corroborating, non-sensitive identifiers; keep ambiguous people separate; record match rationale and confidence; never merge by name/photo similarity alone.
2. **Professional History** — organize self-reported and independently sourced roles, organizations, dates, projects, qualifications and public professional publications; preserve whether each claim is self-reported or independently corroborated.
3. **Corporate Intelligence** — connect people to legal entities, official company registries, published leadership pages and documented projects where lawful and available without payment.
4. **Media Intelligence** — find relevant public professional publications, interviews, talks, press coverage and public statements; distinguish namesakes and preserve publication dates.
5. **Evidence Verifier** — retain source references where permitted, check source authority and freshness, seek independent corroboration, and identify contradictions.
6. **Timeline Builder** — build an event chronology while distinguishing event date, publication date and observation date.
7. **Relationship Mapper** — visualize only explicit or independently documented professional relationships; label inferred edges as hypotheses and never imply that graph proximity proves a relationship.

### 14.7 Evidence and confidence model

Every finding must be a typed evidence record containing:
- claim text and claim category;
- person/organization entity ID(s);
- source URL or stable source reference, source publisher and source type;
- retrieval timestamp, source publication/event date where available;
- tool ID and version, query/task ID and relevant method;
- direct quotation or minimal source excerpt where legally permitted;
- claim type: directly stated, self-reported, independently corroborated, summarized, inferred or unknown;
- confidence label and rationale;
- source quality, freshness and independence indicators;
- sensitivity classification, lawful-purpose tag, retention expiry and review status.

Confidence must not be represented as a mathematically precise probability unless calibrated against an evaluation set. Multiple copies of the same press release or syndicated article count as one underlying source, not independent corroboration. Search ranking, number of hits or a username match alone must never increase identity confidence to "confirmed".

Use the existing labels confirmed, strongly supported, tentative and disputed, with explicit definitions and human-review gates. A fact is not "confirmed" solely because several low-quality sources repeat it.

### 14.8 Source policy and privacy controls

- Publicly accessible does not mean unrestricted to collect, retain, repurpose or share.
- Research must be purpose-limited, proportionate, transparent where required and based on an applicable lawful basis. For EU/EEA contexts, conduct a GDPR review before processing candidate or contact dossiers; document controller/processor roles, notices, access/correction/objection/deletion handling and retention.
- Do not collect leaked credentials, breach dumps, private contact details, home addresses, family information, health data, political/religious views, ethnicity, sexual orientation, sex life, criminal-history data or other sensitive categories.
- No face recognition, biometric matching, covert tracking, private-account access, deception, pretexting, scraping behind login, access-control bypass or CAPTCHA circumvention.
- Do not contact researched people, create accounts, follow/connect, message, publish, or trigger external actions.
- Respect source terms, robots/rate limits where applicable, copyright and database rights. Store minimal excerpts and links rather than copying entire pages.
- Support owner access, correction, export, retention expiry and deletion workflows; propagate deletion to derived indexes/caches where technically possible and report any source-side limitation.

### 14.9 Research report

The report must contain:
- research question, purpose, scope and run time;
- short professional summary;
- professional timeline and organization associations;
- findings grouped by confirmed / strongly supported / tentative / disputed;
- source links and dates adjacent to every material claim;
- identity disambiguation and confidence explanation;
- contradictions, missing evidence and limitations;
- tool/source coverage, skipped sources, failures and free-tier/rate-limit restrictions;
- data retention and owner-review status.

The report must not provide an opaque overall "trustworthiness", personality, risk, employability or suitability score. It may provide a transparent evidence-coverage summary and factual questions for a human interview.

### 14.10 Installation and acceptance tests

Before enabling any tool:
- verify upstream repository authenticity and release tag;
- verify license and all transitive dependency licenses;
- scan dependencies and packaged binaries for vulnerabilities and malware;
- pin version/hash and generate SBOM;
- build and run on supported Windows Desktop environments;
- test offline startup, clean uninstall, upgrade/rollback and data-directory isolation;
- verify loopback-only binding, network egress allowlist, timeouts and resource limits;
- verify no paid API keys or metered endpoints are required by the enabled configuration;
- test that a tool cannot access other projects, unrelated dossiers, secrets or private files;
- test prompt-injection handling in returned web content;
- test false-positive identity matches, namesakes, stale profiles, duplicated sources and conflicting dates;
- test evidence provenance, audit events, owner correction, deletion and retention expiry;
- verify graceful failure without blocking Core/Bridge/platform work.

Release gate: no tool is enabled in production until all relevant tests pass and the owner-visible permission state is correct. Installation status must be distinguished from functional verification.

### 14.11 Delivery phases

- **Phase 0 — Documentation and source audit:** complete the tool/license/source matrix and record the architecture in this contract.
- **Phase 1 — Local adapter proof of concept:** after Queue 1–3 dependencies are complete, run OpenOSINT in passive, no-key mode on approved test targets; validate MCP/CLI adapter, isolation and evidence format.
- **Phase 2 — Maigret adapter:** test only synthetic/owner-approved usernames and professional-profile use cases; measure false positives and source stability.
- **Phase 3 — Native HR intelligence modules:** implement identity resolution, professional history, corporate intelligence, media intelligence, evidence verification, timeline and relationship mapping.
- **Phase 4 — Dossier/report integration:** connect to HR staging, review gate, audit, owner correction and retention controls.
- **Phase 5 — Optional SpiderFoot evaluation:** only if a documented gap remains and every enabled module can run without paid keys and within the approved source policy.

No phase may pre-empt the owner-approved delivery order. Continue independent higher-priority work under the existing multi-queue policy; do not start this Queue 4 implementation while its dependencies are unfinished.

### 14.12 Acceptance checklist — OSINT extension

- [ ] OSINT tools are absent from the AI model selector and present only in the separate tool registry.
- [ ] Windows Desktop owns the local worker lifecycle and tool gateway.
- [ ] Only OpenOSINT and Maigret are in the initial approved evaluation set.
- [ ] Sherlock is not redundantly installed; SpiderFoot remains optional and disabled.
- [ ] No paid API, subscription, credit system or paid fallback is required.
- [ ] Each source has an individual terms, cost, privacy and rate-limit approval.
- [ ] Every material claim is source-linked, timestamped and confidence-labelled.
- [ ] Identity matches are disambiguated; uncertain matches remain separate.
- [ ] Contradictions and source duplication are handled explicitly.
- [ ] Professional dossiers, timelines and relationship graphs contain only proportionate, relevant information.
- [ ] No sensitive-trait inference, covert surveillance, private-data acquisition or automated consequential decision-making.
- [ ] Tool isolation, audit, project scoping, deletion, retention and prompt-injection tests pass.
- [ ] Queue 4 implementation starts only after Queue 1, Queue 2 and Queue 3 dependencies are ready or the owner explicitly reprioritizes.


## 15. HR Intelligence — Sensitive HR and Recruitment Extension

Status: owner-supplied requirements recorded as a design extension. This is documentation only; no implementation is authorized before Queue 4 is activated by the owner.

### 15.1 Operating principles

1. **Fact vs. inference:** distinguish source-backed facts, direct quotations, summaries, hypotheses and unknowns. Every material claim must cite its source and location. An AI inference must never be promoted into durable memory as a fact without explicit human verification.
2. **Zero-trust / least privilege:** enforce access through the GLAFIRA Core PolicyEngine on every read and write. Authorization must consider requesting actor, tenant/user, project, record, field classification, purpose and action. A role assignment alone never grants access. Salary, performance reviews and management notes require separate field-level permissions.
3. **Human-in-the-loop:** creating or editing a consequential record, changing a candidate/employee status, issuing an offer or rejection, sending a message, or sharing/exporting a dossier requires explicit owner-authorized approval. Drafting is not sending; recommendation is not a decision.
4. **Project isolation:** retrieval, embeddings, caches, tool calls and generated summaries must remain scoped to the authorized project and vacancy. A query for Vacancy A must not retrieve data belonging to confidential Vacancy B.
5. **Evidence-first answers:** if no relevant source exists, answer that the available documents do not contain the information. Never fill gaps with plausible-sounding assumptions.

### 15.2 HR data model and storage

Use PostgreSQL as a candidate server-side relational store only if/when a server is approved and its security/operational model is established. pgvector may be evaluated as an in-database semantic index; it is not the source of truth. A graph database is optional and must not be introduced for the MVP without a demonstrated need. Start with relational edges/association tables for referrals and professional relationships.

Core logical entities:
- **Person:** distinct person identity with candidate/employee/contact roles, identity-resolution state and tenant/project scope. Candidate and employee lifecycle states must not erase history.
- **Interaction:** meeting, call, email or message event with participants, timestamps, channel, minimal summary and source reference.
- **Commitment:** explicit follow-up promise/task, responsible owner, due date, status and evidence reference.
- **Artifact:** resume, portfolio, interview transcript or other document. Highly sensitive originals (for example identity documents) must be stored separately in encrypted object/file storage, not embedded in ordinary relational rows or vector indexes.
- **Vacancy / Requirement:** scoped position, versioned job criteria and their provenance/approval.
- **Evidence / Claim:** immutable source-backed statement, source location, observed/event dates, fact-vs-inference type, confidence, sensitivity and review state.
- **AccessGrant / AuditEvent / RetentionRecord:** explicit authorization, traceable access/action and lifecycle/deletion evidence.

For an MVP, PostgreSQL and pgvector are options rather than mandatory dependencies: preserve a provider-independent repository boundary and reuse GLAFIRA's existing storage/backup architecture until a concrete deployment decision is approved. Do not introduce a second uncoordinated memory system.

### 15.3 MVP capabilities — personal assistant for recruiter/manager

- Import PDF and DOCX resumes through a controlled upload flow; show extraction status and allow human correction before saving.
- Extract only relevant fields such as name, contact details, stated experience, skills, roles and education; preserve the exact source and page/paragraph reference.
- Maintain a chronological interaction timeline with source links and clear distinction between event date and ingestion date.
- Detect explicit commitments in authorized correspondence and offer a proposed task/reminder; never send a message automatically.
- Answer questions such as experience with a named technology only from relevant evidence and quote/cite the resume or transcript passage.
- Keep missing information explicitly unknown; do not infer it from age, photo, address, name, school, family status or other proxies.
- Provide access, correction, export, retention and deletion workflows from the beginning, not as a later add-on.

### 15.4 Advanced capabilities — separately gated

- **Candidate-to-vacancy comparison:** may produce an explainable evidence matrix against job-related criteria that were defined and approved before reviewing candidates. Show evidence, missing evidence and uncertainty per criterion. Do not generate an opaque overall suitability score, automatically rank people, or make/execute hiring decisions. Human review is mandatory.
- **Message drafting:** may prepare invitations, follow-ups, feedback or offer drafts from approved facts and communication context. Sending remains a separate approval-controlled action.
- **Salary and performance information:** treat as highly sensitive, restrict by field-level policy and purpose, and never expose to a manager or employee without an explicit applicable grant.
- **Mood, burnout or personality inference:** disabled by default and not part of MVP. Do not infer mental/health state or use communication patterns to score or monitor workers. Any future proposal requires separate legal, ethical, necessity/proportionality and owner review; it must not become an employment decision input.

### 15.5 Privacy, security and legal safeguards

- Treat resumes, contact details, salary, evaluations, interview transcripts and correspondence as sensitive personal data according to context and applicable law. Classification must be field- and purpose-aware, not a single blanket label.
- Before deployment, complete jurisdiction-specific legal review, including applicable Russian personal-data requirements (including 152-FZ where applicable) and GDPR obligations where EU/EEA data subjects or processing are in scope. Determine controller/processor roles, lawful basis, notices, consent where legally required, data residency/localization, cross-border transfer, retention and data-subject rights. This specification is not legal advice and does not itself establish a lawful basis.
- Encrypt data in transit and at rest. Use application-level envelope encryption for especially sensitive fields/artifacts, with keys protected by the existing GLAFIRA credential/key-management boundary. Separate key access from ordinary model context. Decryption must be narrowly authorized, purpose-bound, audited and minimized; do not claim that the model cannot access plaintext unless the technical boundary actually enforces it.
- Keep identity documents and raw artifacts outside vector indexes. Embeddings can leak information and must inherit the source record's access scope, retention and deletion policy.
- Apply PolicyEngine authorization before retrieval and again before returning results. Filter before vector similarity search where possible; post-filtering alone is not sufficient if unauthorized content can influence ranking or prompt context.
- Maintain tamper-evident, append-oriented audit events for reads of restricted records, searches, exports, changes, approvals and deletion. Restrict audit-log access; define retention and legal hold. "Immutable" must be supported by actual storage controls and threat model, not just an append-only application convention.
- Deletion must cover primary records, artifacts, extracted text, embeddings, caches, search indexes and downstream copies under GLAFIRA control. Backups and immutable audit records require a documented expiry/crypto-erasure or legally justified retention strategy; report any residual copy and do not promise instantaneous physical erasure where that cannot be proven.
- Use data minimization, purpose limitation, retention expiry, access/correction/export and documented incident response. Do not retain full chat logs when a source-linked minimal fact/commitment is sufficient.
- Treat imported documents and retrieved content as untrusted input; defend against prompt injection and malicious files. Never allow instructions inside a resume or email to override system policy or authorize access.

### 15.6 Token economy and licensing — future only

The following are future business hypotheses, not MVP requirements or approval to implement payments:
- A personal/professional package for contact management and interview preparation.
- Corporate multi-seat licensing with usage accounting for resume parsing, interview summaries or market research.
- A marketplace for licensed HR templates, onboarding checklists and assessment scripts.

Any token pricing, metering, billing, marketplace or paid corporate feature remains deferred until the separate Token/economy queue, legal/accounting review, tenant isolation, billing correctness and owner approval are complete. Do not make HR data processing contingent on purchasing tokens without an explicit product/legal decision. No paid external AI/API dependency is introduced by this specification.

### 15.7 Delivery plan and queue gate

- **Queue 4 / Stage 0 — specification and legal readiness:** define allowed data inventory, purposes, retention schedule, jurisdiction matrix, consent/notice requirements, threat model, access matrix and database schema. Obtain legal review before processing real candidate/employee data.
- **Stage 1 — evidence-backed smart card:** controlled PDF/DOCX import, extraction review, source-linked fields and strict evidence-grounded Q&A.
- **Stage 2 — timeline and commitments:** source-linked interactions, proposed reminders and owner approval.
- **Stage 3 — security and adversarial acceptance:** cross-project/tenant isolation, field-level authorization, prompt-injection tests, false-fact tests, audit integrity, deletion propagation, backup/restore and red-team exercises.
- **Stage 4 — advanced assistance:** explainable vacancy criteria matrix and message drafts only after separate policy/legal approval and MVP release gates.
- **Future — monetization:** only after separate Token queue and explicit owner approval.

This Queue 4 plan is subordinate to the owner's higher-priority GLAFIRA Core, Bridge/Windows Desktop and Backup & Recovery work. Keep the specification in the project knowledge base, but do not start implementation, install OSINT tools, process real HR personal data or activate connectors until the owner explicitly starts Queue 4 and the applicable release gates pass.

### 15.8 Acceptance checklist — sensitive HR extension

- [ ] Facts, quotations, summaries, inferences and unknowns are distinct in schema and UI.
- [ ] Every material answer has a source citation or explicitly reports missing evidence.
- [ ] PolicyEngine enforces actor/project/tenant/field/action scopes before retrieval and output.
- [ ] Salary, evaluations, identity documents and other restricted fields have explicit access controls.
- [ ] Human approval gates all consequential status changes and outbound communications.
- [ ] Vacancy/project retrieval, embeddings, caches and reports are isolated.
- [ ] Encrypted artifact storage is separate from ordinary rows and vector indexes.
- [ ] Audit events cover restricted reads, searches, exports, writes, approvals and deletion.
- [ ] Data-subject access, correction, export, retention and deletion workflows are tested end-to-end.
- [ ] Legal review for each applicable jurisdiction is documented before real-data processing.
- [ ] No automated hiring decision, opaque candidate score, sensitive-trait inference or worker mood/burnout surveillance.
- [ ] MVP and advanced features pass privacy, security, red-team and owner acceptance gates.
- [ ] Queue 4 remains deferred until explicit owner activation.
