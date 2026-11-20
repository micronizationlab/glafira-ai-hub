# Windows Desktop Control Center, Agent Tools, File Intelligence and GLF Economy

**Status:** owner-approved architecture requirements; specification only.  
**Date:** 2026-11-20  
**Tracking:** Issue #152  
**Implementation queue:** Queue 3 for Windows Desktop host/UI; dependent Core/Bridge contracts remain Queue 1/2. Specialist-agent implementations remain Queue 4. Wallet/marketplace are future design work and are not authorized for implementation by this document alone.

## 1. Product and priority

Windows Desktop is the owner's primary laptop control center and must be prioritized ahead of iOS within Queue 3. The owner needs a usable Windows application to test the actual Desktop + Bridge + Windows Agent system. iOS must not block this work.

The first Desktop milestone is functional, not visual polish. It must provide real working navigation, settings, forms, diagnostics, Bridge status and agent/device controls. A mobile-like visual language may be retained, but the information architecture must suit a laptop and multi-panel workflows.

Authoritative engineering sequence remains:
1. Queue 1 — Core kernels and execution engine.
2. Queue 2 — Bridge.
3. Queue 3 — Applications, with Windows Desktop ahead of iOS.
4. Queue 4 — specialist agents and training.

Do not interrupt higher unfinished queues with lower-queue implementation. Documentation and design contracts may proceed in parallel.

## 2. Windows Desktop information architecture

Desktop Settings has three distinct tabs:
1. **General** — preserve existing general settings and existing controls.
2. **Agent APIs & Tools** — six specialist-agent cards, two primary connector slots per agent (12 slots total).
3. **Wallet** — future GLF wallet and compute marketplace interface.

The Desktop control center also needs operational surfaces for:
- Core/task queue health and execution status;
- Bridge pairing, connection, devices, leases and result delivery;
- Windows Agent fleet and resource availability;
- Backup/restore status and manual actions;
- agent catalog and per-agent concise tool availability;
- file ingestion and document-processing status;
- approvals, audit and diagnostics.

The Settings page is the authoritative place for credentials and full connector management. Agent cards show concise status only; do not duplicate secret-entry forms throughout the application.

## 3. Credential and connector management

### 3.1 Connector registry, not model selector

AI reasoning/model providers remain in the AI model selector. Specialist APIs, data connectors, local command-line tools and MCP services belong in a separate Tool/Connector Registry.

A connector slot may represent:
- an API with an API key;
- an OAuth connection;
- a bot token or scoped access token;
- a local tool requiring installation/version validation but no API key;
- a read-only official data source requiring no authentication.

Do not invent or require API keys for tools that do not use them.

### 3.2 Six roles and initial candidate connectors

These are candidates for validation, not approved production integrations. Before activation, verify current official docs, terms, source license, commercial-use permission, quotas, security, Windows compatibility and whether the interface is a documented API or merely a public website.

| Agent | Candidate 1 | Candidate 2 | Key/source restriction |
|---|---|---|---|
| Кадровик | OpenOSINT (local, passive-first) | Maigret (local username-profile lookup) | Local tools; optional upstream source keys disabled unless independently verified free and lawful |
| Пресс-секретарь | Telegram Bot API | VK API | User-authorized bot/app tokens and explicit scopes |
| Банкир | Bank of Russia official statistics API | FNS official open-information services | Russian official sources only; does not grant access to personal bank accounts |
| Врач | NCBI PubMed E-utilities | NLM RxNorm/RxNav | Free public research/drug terminology APIs; not clinical decision systems |
| Юрист | Official Russian Legal Information Portal | Russian official court sources, including the Arbitration Case Index | Russian legal sources only; verify lawful access and documented interface; do not mislabel unofficial scraping as an official API |
| Программист | GitHub REST API | GitLab API | Scoped tokens/OAuth; repository and CI permissions least-privilege |

The 12 entries are connector slots, not a promise of 12 secret strings. Some are local modules or public read-only sources. The initial candidate set must be reviewed against the strict free-only policy: no paid subscription, paid API, metered endpoint, paid enrichment, paid fallback, credit-based quota or automatic paid-tier transition. If free terms are unclear, keep the connector disabled pending owner approval.

### 3.3 Status indicators and UX

For each connector:
- **Green:** a successful authenticated or source health-check completed recently.
- **Red:** the source explicitly rejected the credential or authorization.
- **Gray:** not configured, not yet checked, or the source could not be reached. The UI must distinguish these reasons in text; network outage must not be misreported as an invalid key.

Controls: connect/configure, reveal/hide, save, test, revoke/disconnect, rotate credential, inspect scopes/permissions, view last successful check and diagnostic reason.

After saving a secret, close the entry panel and show only a masked credential indicator. Secrets must be stored in Windows-protected credential storage (or the platform's secure vault), never plaintext app preferences, logs, model prompts, dossiers, source control or backups by default. Test and revoke actions require appropriate owner confirmation.

## 4. Кадровик — archival dossier document

The owner may request a dossier using a familiar contact label (for example, “Иван, таксист из телефона”) or a formal full name. Resolve against permitted contacts and sources; if multiple candidates match, ask the owner to select before combining records.

Render a dedicated yellow archival personnel-file document inspired by vintage intelligence dossiers and classic illustrated dossier aesthetics:
- warm yellowed-paper visual language, typewritten headings, restrained stamps and file number;
- portrait at the upper-left, only from an authorized contact/source;
- known names and aliases, contact source, professional history, organizations, projects, documented relationships and interaction timeline;
- evidence-linked claims, publication/observation dates, confidence and rationale;
- separate confirmed, tentative, disputed and unknown information;
- explicit source coverage, skipped sources and unresolved identity ambiguity.

Provide real Copy, Save/Export and Share actions. Copy/export should preserve citations and uncertainty labels. The visual style must never imply certainty beyond evidence. No face recognition, biometric matching, sensitive-trait inference, private-account access, leaked data, home-address enrichment or covert surveillance.

## 5. File Intelligence Engine

Provide a shared ingestion pipeline for agents, subject to project permissions and file access grants.

Initial target formats:
- text and structured text: TXT, MD, CSV, JSON, XML, YAML;
- documents: PDF, DOC/DOCX, ODT, RTF;
- spreadsheets: XLS/XLSX, ODS;
- presentations: PPT/PPTX, ODP;
- images: JPG/JPEG, PNG, TIFF, WEBP, with OCR where appropriate;
- email: EML and MSG where parser support is available;
- archives: ZIP, 7Z, TAR, GZ;
- media: MP3, WAV, M4A audio transcription; MP4/MOV metadata, audio extraction and selected-frame analysis where licensed tools are available.

Extraction must be format-specific and return structured text, tables, page/sheet/slide references, image references and provenance. OCR confidence must be surfaced. Scanned PDFs and handwriting are uncertain and must not be represented as guaranteed accurate.

Security requirements:
- never auto-execute EXE, DLL, scripts, Office macros, embedded commands or active content;
- sandbox parsers and OCR/media tools;
- enforce configurable file-size, page-count, archive-depth, decompressed-size, CPU and wall-time limits;
- malware scanning where available, archive-bomb detection, MIME/signature validation and safe temporary storage;
- protect against parser exploits and malicious embedded content;
- treat extracted text as untrusted data and defend against prompt injection;
- preserve source filename, hash, origin, timestamps, access scope and extraction version;
- enforce project/agent access and retention/deletion policies;
- report unsupported, encrypted, corrupt or partially parsed content honestly.

## 6. GLF wallet — future design

The initial design should be an internal accounting-credit system, not an assumption of public cryptocurrency or blockchain.

Wallet tab areas:
1. Current balance and transaction history.
2. Send/transfer by stable GLAFIRA account ID, with recipient confirmation, amount confirmation and final owner approval.
3. Opt-in contribution of compute resources for GLF rewards.
4. Order a compute team / available resources.

Use an auditable double-entry ledger with immutable transaction IDs and explicit transaction types: issuance, transfer, reservation, release, reward, refund, fee and reversal. Available balance and reserved balance must be separate. All balance changes must be atomic, idempotent and traceable.

## 7. Compute marketplace and contract lifecycle

A compute order must define:
- task scope and deliverables;
- required CPU/GPU/RAM/storage/network capability;
- maximum time and resource budget;
- price and deadline;
- acceptance criteria and allowed data scope;
- provider identity/capability and owner approvals.

Lifecycle:
1. Buyer selects a provider and confirms a signed offer.
2. Core reserves the agreed amount in the buyer's wallet.
3. Buyer GLAFIRA sends the initial signed invitation/offer through Bridge.
4. Provider GLAFIRA validates the sender, contract, permissions and capacity, then accepts or rejects.
5. On acceptance, create a contract-scoped authenticated task channel.
6. Provider executes only the authorized task in an isolated workspace.
7. Provider returns signed results, status and evidence.
8. Buyer reviews and accepts, or invokes the defined dispute/revision path.
9. Core releases the reserve to the provider or returns it according to the contract outcome.

Bridge transports messages, task payloads and results; Core owns policy, approvals, task state and financial state. Neither agent may see the other party's unrelated project memory, credentials or files. Contract IDs, task IDs, nonces, idempotency keys, signatures, audit events and revocation must prevent replay, spoofing and duplicate payment.

For NAT/firewall cases, use authenticated outbound rendezvous/relay or another tested transport. Do not expose arbitrary inbound Windows Desktop ports. Do not claim direct peer-to-peer connectivity until it is tested across real networks.

## 8. Treasury and issuance

The Windows Desktop owner control center may expose treasury/issuance controls only after a separate approved economic and legal design.

- Issuance requires explicit owner authorization.
- Define supply cap, issuance schedule, issuance purpose, reward rules and accounting treatment before activation.
- No agent, worker or provider may mint tokens autonomously.
- Every issuance records operator, timestamp, amount, reason, policy/version, transaction ID and resulting supply.
- Reward compute contribution only for verified completed work and accepted results, not idle uptime or self-reported CPU time.
- Prevent double rewards, duplicate claims, circular self-dealing and uncontrolled inflation.
- Public transferability, redemption, exchange into fiat, sale or external monetary value require separate legal, tax, financial and security review before implementation.

## 9. Acceptance gates

Do not mark these capabilities implemented until code, tests and owner acceptance exist.

Desktop functional acceptance:
- launches on a Windows laptop and exposes the working control center;
- three Settings tabs are reachable and existing settings are preserved;
- credential save/mask/test/revoke works and secrets do not leak into logs/prompts/backups;
- green/red/gray status semantics are tested;
- six agent cards and 12 connector slots render with correct local-tool/API distinctions;
- connector permissions and project scopes are enforced;
- dossier resolves familiar contact names safely and exports/copies a source-grounded yellow document;
- file tests cover every supported format, malformed inputs, encrypted files, OCR uncertainty, malicious archives and prompt injection;
- Bridge pairing, invitation, acceptance, task delivery, result acknowledgement, revocation and offline recovery pass end-to-end tests.

Future economy acceptance:
- double-entry ledger invariants and atomic reservations;
- idempotent transfer and contract transitions;
- owner-authorized issuance with supply cap and audit;
- provider offer/accept/reject;
- escrow release/refund/dispute;
- signed task/result exchange and project isolation;
- NAT/reconnect/replay/duplicate-execution tests;
- security and legal review before any external value or public transferability.

## 10. Non-implementation statement

This document records requirements and a candidate architecture. It does not mean that connector integrations, the Desktop GUI, File Intelligence, the GLF wallet, token issuance, compute marketplace, escrow or cross-customer GLAFIRA Bridge are already implemented or tested.
