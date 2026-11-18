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
