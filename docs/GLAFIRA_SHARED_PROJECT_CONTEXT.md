# GLAFIRA AI HUB — shared project context

Purpose: provide a versioned, non-secret project knowledge pack that Android/Desktop agents and human developers can read before accepting work. This document is project context, not executable authority and not a substitute for owner approval.

Last reviewed: 2026-11-21.

## 1. Product intent

GLAFIRA is a distributed AI assistant with Android and Desktop nodes, with iOS planned. The Android and Desktop Cores must each retain useful independent capability; neither device is a permanent single point of failure.

The long-term product experience combines a polished, calm Apple-like interface with friendly, conversational character. Prioritize correctness, security, recovery and task integrity before decorative UI work.

## 2. Accepted two-Core architecture

- Desktop Core is the primary high-capability execution node for complex tasks, local files, tools, long-running work and agents.
- Android Core is a limited but independently useful node for chat, available models, local tasks, durable queueing, results and owner approvals.
- When Desktop is unavailable, Android must continue its supported limited work. When Android is unavailable, Desktop must continue its permitted local work.
- Reconnection must reconcile durable state without losing results or executing one task twice.
- Device capabilities and authority are independent. A device does not inherit the other's permissions merely because it is paired.

Required protocol concepts:
- stable task IDs and per-installation Core IDs;
- durable per-Core queues and inbox/outbox;
- explicit owner/lease records and monotonic ownership generations;
- fencing tokens and effect-level idempotency;
- safe takeover only after valid lease expiry/reconciliation;
- explicit `OUTCOME_UNKNOWN` for interrupted work whose external effect may have happened;
- durable result delivery, acknowledgement and conflict handling;
- offline behavior and split-brain prevention.

The document `docs/contracts/DUAL_CORE_OWNERSHIP_AND_FAILOVER.md` is the accepted architecture contract. It is not proof that all these mechanisms have been implemented.

## 3. Bridge architecture — current implementation versus legacy prototype

### Android Core ↔ Windows Desktop Hub (target peer-device path)
- Android Core remains an independent owner Core. It does **not** currently expose the historical Android-hosted HTTPS listener described by older Bridge documents.
- Current Android code contains Bridge authorization/admission primitives and Keystore credential storage, but these do not constitute a network server or a complete pinned HTTPS client.
- The missing Android owner-facing client, Settings pairing surface, certificate pin validation and peer credential import are tracked by #393.
- Pairing is transport enrollment only. It does not transfer task ownership, copy all project memory, or grant every tool scope. Cross-Core ownership transfer requires a separately signed handoff and monotonic generation per `DUAL_CORE_OWNERSHIP_AND_FAILOVER.md`.

### Windows Desktop Hub ↔ Windows Agent fleet (implemented transport foundation)
- Windows Desktop Hub owns the current HTTPS/TLS listener and Agent fleet transport. It includes certificate identity/lifecycle, short-lived Agent pairing-ticket redemption, pending enrollment, owner approval/revocation paths and task/lease dispatch primitives.
- Windows Desktop Core owns canonical task/project policy, approvals, task lifecycle, accepted results and audit history. Hub owns Agent registry, heartbeat/capability inventory, bounded scheduling, worker lease/fencing journal and delivery receipts only for tasks explicitly delegated by Desktop Core.
- The Agent has DPAPI-backed lease/progress/result journaling and a polling coordinator, but it must remain Not Ready while no bounded production handler/result-artifact contract is registered (#384). Two-PC acceptance and canonical Core result reconciliation remain open under #123.
- Do not treat the Hub as a second policy Core or global ownership authority.

### iOS peer pairing
- iOS has a pinned HTTPS client and peer-pairing UI, but its current `/bridge/v1/pairing` route and pending-owner-approval semantics do not match the Windows Hub Agent `/bridge/v1/pair` enrollment endpoint.
- The protocol and enrollment-role mismatch is tracked by #392. Do not reuse an Agent credential as a Core peer credential.

### Legacy Desktop Bridge CLI
- `desktop-bridge/` is a historical JVM 17 validation CLI built for the former Android-hosted Bridge flow. It is not the production Android↔Desktop pairing architecture and its pairing flow must not be used for new installations until explicitly reconciled with #392/#393.
- The old test-client documentation path referenced by previous revisions is not present in the current repository; do not link users or agents to it.

### Shared safety rules
- Owner approval, least-privilege scopes, TLS pinning, HMAC identity, replay/idempotency protection, revocation and lease fencing remain mandatory.
- Pairing, heartbeat or device unavailability never changes task ownership by itself.
- LIVE external side effects remain individually gated; unknown outcomes are never blindly replayed.

## 4. ChatGPT / OpenAI integration boundary

The Bridge does not connect to the currently open ChatGPT conversation. ChatGPT's account session, private conversation context and account memory are not exposed as a LAN endpoint and cannot be copied into GLAFIRA by writing a host name in Bridge settings.

A future live model integration must be a separate, owner-authorized OpenAI API integration:
- the owner supplies an API credential for the Desktop runtime;
- credentials are stored in the operating system's secure credential store, never in source code or plain settings;
- API use and billing are separate from a ChatGPT subscription;
- the API model runtime does not automatically inherit this ChatGPT account's memory or conversation history.

Project-specific knowledge can and should be shared through versioned project documents, task specifications, decisions, code, tests and GitHub PR history. This file is the stable index; handoff files record current work and exact branch state.

## 5. Current automation boundaries

### Project Dispatcher v1
The dispatcher reads all open PRs, matches CI runs to exact current head SHAs, and reports the latest run for each workflow. It runs after the main Android/Desktop workflow, on PR updates, on a 15-minute schedule and manually. It is read-only and does not merge, push, rerun or assign code-writing tasks.

### Desktop worker
The current Desktop Bridge task worker can poll owner-authorized tasks and run registered local handlers. The current CLI's worker mode expects local Ollama at `127.0.0.1:11434`. It is not yet a general autonomous programmer and does not have unrestricted repository write authority.

### Human/agent integration rules
- Use isolated branches and Draft PRs for independent changes.
- Never reset, force-push or rewrite another developer's branch without explicit coordination.
- Do not merge directly into `main` without reviewing base dependencies and the Runtime Foundation stack.
- Report exact head SHAs and per-job CI results; a green run on an older SHA is stale.
- Keep credentials, tokens, private keys, invitation JSON and one-time credential bundles out of commits, logs and this document.
- Owner approval is required for sensitive actions, external effects, privileged scopes and production release.

## 6. Key handoff documents

- `docs/GLAFIRA_KOSTYA_HANDOFF.md` — central engineering handoff and parallel PR index.
- `docs/GLAFIRA_DESKTOP_SHUTDOWN_HANDOFF.md` — Desktop worker shutdown and OS lock safety.
- `docs/contracts/DUAL_CORE_OWNERSHIP_AND_FAILOVER.md` — accepted two-Core ownership/failover contract.
- `docs/GLAFIRA_PROJECT_DISPATCHER.md` — dispatcher v1 operation and limitations.
- Issues #392/#393 — current iOS and Android peer-device pairing work.

Before changing architecture or resuming work, read the relevant handoff and inspect the current PR/branch head. Do not assume the status table in a historical document is live; query current GitHub state.
