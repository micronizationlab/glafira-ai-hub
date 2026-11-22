# GLAFIRA Offline-First Runtime

## Owner principle

GLAFIRA is free-first by design: prefer free/open-source software, built-in operating-system capabilities and hardware the owner already has. Paid AI, subscriptions, rented servers and upgraded hardware may be optional adapters, but must not become hidden prerequisites for ordinary operation. Document licensing, quotas, privacy, battery/storage costs and free alternatives before adopting a dependency.

## Product promise and honest boundary

GLAFIRA must remain useful when the network is unavailable and every AI provider module is disconnected. This does **not** mean that a phone without an AI model can perform arbitrary natural-language reasoning. The baseline is a deterministic local assistant with guided commands, local data access and transparent capability reporting. Open-ended conversation requires an available AI model, either cloud-connected or an optional compatible on-device model.

The UI must distinguish:
- Local / offline basic mode
- AI provider connected
- AI provider unavailable
- Work queued for later delivery
- Peer / Desktop offline and reconciliation pending

Never display a provider-backed answer as if it were generated locally.

## Offline capability layers

### Layer 0 — always local

- Launch the app; access owner profile, settings, local projects, chat history, drafts and local files.
- Read local task state, backup state, connection state and recent diagnostics.
- Create local notes, reminders, drafts and tasks.
- Search locally stored project/chat content.
- Keep a durable outbox and queue eligible work for later; survive process death and reboot where Android permits.
- Cancel local pending work and inspect why remote work is waiting.
- Explain available commands and report unsupported requests without pretending they succeeded.

### Layer 1 — deterministic command router

Implement a small typed, allowlisted parser with explicit intent/result types. It must not call an LLM and must not interpret arbitrary text as executable code.

Initial safe commands may include:
- Open a GLAFIRA screen or section.
- Show task, backup, storage, provider, Bridge or network status.
- Create/search/update a local note or reminder.
- Search local chats and project records.
- Queue a supported operation for later delivery.
- Open a supported Android app or system settings page using an explicit OS intent.

Unknown or ambiguous commands must ask for clarification. Commands with external or sensitive effects must require a clear preview and explicit owner approval. Never invoke shell commands or arbitrary component launches from free-form text.

### Layer 2 — optional on-device intelligence

A local model is optional, not a baseline dependency. Evaluate only models compatible with the actual device's RAM, storage, thermal limits and battery budget. Keep model downloads optional, user-controlled, removable and license-reviewed. Do not make the current Realme 8i a prerequisite for a larger model. If no local model is available, fall back to Layer 0/1.

## Android device-control boundary

GLAFIRA may control only actions exposed through supported Android APIs, intents and permissions. Keep a capability registry with:
- action identifier and schema;
- required Android permission / role / default-app status;
- whether an OS confirmation is required;
- whether the action works offline;
- whether the action is reversible;
- audit/result semantics.

Start with low-risk navigation and app-owned operations. Calls, SMS/messages, contact changes, deletion, security settings, payments, account changes and any external communication require a specific owner-confirmed preview. Android may prohibit or restrict some actions; show the OS limitation instead of claiming control. No hidden accessibility-service takeover or silent surveillance.

## Voice without AI providers

Speech input and speech output are separate optional capabilities.

- Keep text input available in every mode.
- Current Android input uses RecognizerIntent with ru-RU but does not explicitly request offline recognition or verify an offline Russian model. Therefore current offline STT behavior is not guaranteed.
- Where supported, detect whether the installed recognizer offers an offline Russian language pack; provide a clear setup/status explanation. An online-only recognizer must be marked as network-dependent.
- Current TtsHelper uses Android TextToSpeech with Russian locale. It can work locally only if a compatible voice/language is installed and initialized successfully. Detect readiness and fall back to text.
- Do not make voice recognition or TTS depend on a paid cloud service by default.
- Microphone use remains user initiated; no always-listening mode by default.

## Queue, Core and peer behavior

- Local task creation and journaling continue offline.
- Remote/provider tasks remain visibly queued; do not claim completion before an authenticated result/ACK is accepted.
- Reconnect uses stable operation IDs, idempotent replay, deduplication and owner-generation fencing.
- No automatic execution of an action that still requires owner approval.
- Android Core and Windows Core remain independently useful while the other peer is offline. Heartbeat loss alone never transfers ownership.
- Backups and recovery must remain usable without a paid cloud destination; local export/import is the baseline, cloud destinations are optional.

## Privacy and security

- Local-only records stay local unless an owner-approved data-sharing policy permits export.
- Never place passwords, GitHub tokens, private keys or raw authorization material in prompts, logs, backups or peer messages.
- Offline mode must not weaken owner authentication, task ownership, approval or audit controls.
- Store only the minimum permissions needed and request runtime permissions at the moment a feature is used.
- Show clearly when a feature requires network access or external provider processing.

## Acceptance matrix

1. Airplane mode, all AI providers disconnected: app launches and local owner data remains readable.
2. Create, edit, search and delete local notes/reminders; restart the process and verify persistence.
3. Deterministic command tests cover valid, ambiguous, unknown, malformed and confirmation-required requests.
4. Confirm supported OS intents work and unsupported/restricted actions fail closed.
5. Voice input separately tested with offline Russian STT installed, missing, and online-only; text fallback always works.
6. TTS separately tested with Russian voice installed, missing and initialization failure; text fallback always works.
7. Queue survives process death and network outage; reconnect is idempotent and does not auto-execute unapproved work.
8. Run on physical Realme 8i and record exact APK commit/build, Android version, speech engine, installed language packs, free storage, battery impact and airplane-mode results.
9. Verify ordinary operation requires no paid subscription, rented server or upgraded device.

## Current source-audit facts (2026-11-22)

- MainActivity currently sends chat messages through PendingDeliveryRuntime.dispatch(..., fullPrompt); that path is provider-backed, not a local deterministic conversation engine.
- Voice input launches RecognizerIntent.ACTION_RECOGNIZE_SPEECH for ru-RU without EXTRA_PREFER_OFFLINE or offline-model verification.
- TtsHelper uses Android TextToSpeech and checks language availability; actual offline output depends on the installed Android voice.
- The app manifest currently declares INTERNET, ACCESS_NETWORK_STATE, RECORD_AUDIO and READ_CONTACTS. Future device controls must add narrowly scoped permissions only when required and request them at point of use.

This document defines a target architecture and acceptance criteria. It does not claim these capabilities are already implemented.
