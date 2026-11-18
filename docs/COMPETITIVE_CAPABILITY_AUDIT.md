# GLAFIRA AI HUB — competitive capability audit

Audit basis: source tree and implementation on `feature/glafira-bridge-tls-host` (18 Nov 2026). Status labels distinguish working code paths from declarations, prototypes and future plans. A feature is not considered delivered merely because a registry entry or roadmap mentions it.

## Findings

| Capability | Current evidence in GLAFIRA | Status | Gap / next step |
|---|---|---|---|
| Multi-provider chat | `AiProviderClient`, provider registry, encrypted API-key storage | Implemented for configured cloud providers | Provider model IDs and API compatibility need ongoing validation |
| Multi-model comparison | `CouncilScreen` runs three configured roles in parallel and renders their individual answers plus Glafira synthesis | Implemented as a separate Council screen | Make comparison available from normal chat; make provider selection configurable rather than fixed to OpenAI/DeepSeek |
| Coordinator peer consultation | `GlafiraCoordinator` can select up to two allowlisted configured providers, redact context, and synthesize | Implemented, opt-in | Individual peer results are not normally exposed in chat; improve user-visible provenance and per-provider data-sharing controls |
| Web page reading | `GlafiraWebPageReader` with bounded HTTP(S), redirects, size/time limits and local-address checks | Implemented foundation | Strengthen DNS-rebinding protections and test redirect/SSRF edge cases; page reader is not a browser |
| Web search | `GlafiraBraveSearchProvider` and coordinator routing for search-like requests | Implemented with owner-supplied Brave API key | Add provider-neutral search contract, source provenance, citations and user-visible search controls |
| Typed tool catalogue and broker | `GlafiraToolRegistry` declares scopes/risk; `GlafiraToolBroker` checks trusted scopes, exact-action owner grants, bounded payload/output and invokes only registered handlers | Fail-closed execution broker foundation; concrete adapters and durable approvals are not connected | Add built-in web/project handlers, persistent approval queue, audit/idempotency and provider tool-call loop |
| Task planning / dispatch | `GlafiraTaskPlanner` validates bounded DAG plans; `GlafiraTaskDispatcher` executes batches through explicitly registered suspend handlers, passes dependency outputs, and fails closed on missing handlers/errors | Execution engine foundation; real provider handlers and UI are not connected | Bind roles to configured providers/tools, persist task state, add cancellation/retry, durable progress UI and result review |
| Android ↔ Desktop bridge | Owner-controlled TLS bridge, pairing, replay/rate limits and narrow project/conversation operations; desktop CLI module | In progress / draft PR | Physical-device LAN E2E, durable owner action approval, platform credential adapters, desktop UI/installer and updater remain |
| Password vault | Local AES-256-GCM vault, PBKDF2-HMAC-SHA256 derivation, Google CSV import and deduplication | Implemented foundation | Add scoped credential broker for Glafira; agent isolation, robust alias dedup, recovery and physical-device import tests remain |
| Encrypted backups | AES-GCM archive, PBKDF2, manifest SHA-256, staging, path checks and rollback attempt | Implemented foundation | Verify restore under interruption/corruption on device; add WebDAV/remote target and scheduled remote sync |
| Offline local LLM | No local inference runtime/model manager found in app source/dependencies | Missing | Add optional on-device inference only after RAM/storage/thermal profiling and model licensing review |
| Document RAG | No PDF/Office extraction, chunk index or retrieval pipeline found | Missing | Add private local document ingestion, bounded parsing, chunking, citations and encrypted index |
| Vision / image understanding | No image-input provider path or on-device vision runtime found | Missing | Add image attachment pipeline with explicit consent, provider capability negotiation and private image handling |
| Image generation | No image-generation runtime or provider adapter found | Missing | Optional cloud/local generation adapter; do not bundle large models by default |
| Speech-to-text | Main chat uses Android `SpeechRecognizer`; this is not guaranteed offline | Partial | Add explicit offline-capable STT engine/model and visible offline availability |
| Text-to-speech | TTS helper exists | Partial / implemented | Add voice selection, lifecycle/background controls and accessibility tests |
| AI keyboard | No input-method service / keyboard module found | Missing | Separate Android IME module; user opt-in, local-first processing, no silent capture |
| Extensible Agent Skills / MCP | Typed tool registry exists, but no MCP transport or JS skill runtime found | Missing runtime | Start with audited MCP client/server boundary and signed, permission-scoped skills; never execute arbitrary model-supplied code |
| Passkeys / breach monitoring | Vault has no passkey provider or breach-monitoring integration | Missing | Passkeys through Android Credential Manager; breach checks via privacy-preserving service only with explicit opt-in |
| Secure secret sharing | No expiring one-time secret-share workflow found | Missing | Optional end-to-end encrypted, expiring share with recipient confirmation and revocation |
| WebDAV backup | No WebDAV adapter found | Missing | Add after local backup/restore acceptance tests; require encrypted archive and remote integrity verification |
| Spaces / document artifacts | No document/table/slides editor module found | Missing | Add structured project artifacts after Core dispatcher and RAG foundations |

## Priority order

1. **Stabilize current delivery:** repair failing tests, produce a verified APK artifact, retain the current draft PR and complete backup/restore acceptance.
2. **GLAFIRA Core execution:** connect `GlafiraTaskPlanner` to a real executor registry and connect `GlafiraToolRegistry` to a broker. Start with safe read-only tools and draft-only local actions. Add persistent owner approval and audit before external writes.
3. **Web Access hardening:** provider-neutral search, source provenance/citations, safe page reader and later an owner-controlled browser runtime.
4. **Vault credential broker:** Glafira-only scoped credential resolution; agents never receive the vault or raw secrets.
5. **Document RAG:** local-first document parsing and retrieval with source citations.
6. **Multimodal and voice:** image input, offline STT and optional image generation.
7. **Offline LLM runtime:** device-aware model manager and explicit download/storage controls.
8. **MCP / skills, AI keyboard, passkeys, WebDAV and artifact editor:** staged behind permission, privacy and reliability tests.

## Safety and completion rules

- A declaration, UI entry, plan or documentation page is not proof of an executable feature.
- External sends, publication, CRM changes, payments and destructive operations remain blocked until an owner-approved, exact-action grant is checked immediately before execution.
- Credentials are resolved inside a protected broker; they are never placed in model prompts, peer context, logs or task results.
- Local/offline capabilities must report accurately whether the selected model or recognizer is actually available on the device.
- Each milestone needs unit tests, integration tests and, where applicable, physical-device acceptance before it is marked complete.
