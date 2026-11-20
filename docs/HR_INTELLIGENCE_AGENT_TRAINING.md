# GLAFIRA HR Intelligence Agent — Role, Training and Acceptance Contract

Status: owner-requested design baseline; implementation is not yet complete.
Owner intent: add a dedicated "Кадровик" agent to the in-app agent catalog and preserve this training model as a versioned project artifact.

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


## 14. Implemented Android foundation (current branch)

The first Android implementation slice now includes:
- Stable in-app role ID `hr-intelligence`, role version 1 and a versioned Russian instruction set.
- КАДРОВИК entry in the Android intelligent-agent catalog, using the configured GigaChat provider like the existing specialist roles.
- Stable role ID for chat history and drafts.
- Typed contact cards, evidence facts, source references, interaction records and commitments.
- Local project-scoped contact storage encrypted with a dedicated Android Keystore AES-256-GCM key; project ID is authenticated as associated data.
- Contact files live under Android `noBackupFilesDir` because the device-bound Keystore key is intentionally excluded from portable backups. The UI discloses that dossiers do not yet migrate to a new device; portable encrypted export/restore is a required follow-up.
- Atomic replacement of the encrypted contact document; decrypt/schema/scope failures fail closed and do not become an empty list.
- Manual contact directory UI with add/list/delete, available from the Кадровик chat.
- All-eligible-agent memory review policy with explicit NOT_REVIEWED accounting, duplicate-vote rejection, conflict blocking and owner approval for high-impact promotion.

Limitations:
- No Gmail, Telegram, Instagram, calendar, CRM or other external connector is activated by this slice.
- No automatic overnight ingestion or background schedule is enabled.
- Contact records are entered manually by the owner; no messages are sent and no external records are changed.
- Android Keystore encryption requires real-device/emulator acceptance testing, including backup/restore behavior. A passing JVM build alone does not prove device crypto acceptance.
- The all-agent review policy is currently a tested domain gate; it is not yet wired to a live multi-agent review coordinator.

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
