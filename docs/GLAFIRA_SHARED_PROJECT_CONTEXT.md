# GLAFIRA AI HUB — shared project context

Purpose: provide a versioned, non-secret project knowledge pack that Android/Desktop agents and human developers can read before accepting work. This document is project context, not executable authority and not a substitute for owner approval.

Last reviewed: 2026-11-23.

## 1. Product intent

GLAFIRA is a distributed AI assistant with Android, Windows Desktop, Windows Agent and iOS nodes. Android and Windows Desktop Cores retain useful independent task authority; no single device is a permanent single point of failure. A separate GitHub-backed project coordinator role is elected among eligible enrolled GLAFIRA nodes and does not replace per-task Core ownership or policy authority.

The long-term product experience combines a polished, calm Apple-like interface with friendly, conversational character. Prioritize correctness, security, recovery and task integrity before decorative UI work.

## 2. Independent Core authorities and elected project coordination

- Desktop Core is the primary high-capability execution node for complex tasks, local files, tools, long-running work and agents.
- Android Core is a limited but independently useful node for chat, available models, local tasks, durable queueing, results and owner approvals.
- When Desktop is unavailable, Android must continue its supported limited work. When Android is unavailable, Desktop must continue its permitted local work.
- Reconnection must reconcile durable state without losing results or executing one task twice.
- Device capabilities and authority are independent. A device does not inherit the other's permissions merely because it is paired.

Project-level coordination requirements:
- the first eligible node to win the GitHub-backed coordinator claim becomes the initial project coordinator;
- other online nodes join as contributors and receive non-overlapping tasks;
- coordinator succession requires authoritative lease expiry or signed handoff plus a higher fencing generation;
- stale coordinators and duplicate task claims are rejected; GitHub outage cannot create a second global leader.

Per-task Core ownership remains distinct. Required protocol concepts:
- stable task IDs and per-installation Core IDs;
- durable per-Core queues and inbox/outbox;
- explicit owner/lease records and monotonic ownership generations;
- fencing tokens and effect-level idempotency;
- safe takeover only after valid lease expiry/reconciliation;
- explicit `OUTCOME_UNKNOWN` for interrupted work whose external effect may have happened;
- durable result delivery, acknowledgement and conflict handling;
- offline behavior and split-brain prevention.

The document `docs/contracts/DUAL_CORE_OWNERSHIP_AND_FAILOVER.md` is the accepted architecture contract. It is not proof that all these mechanisms have been implemented.

## 3. Windows Desktop Hub is the authoritative Bridge endpoint

### One pairing and Bridge authority
- Windows Desktop Hub is the authoritative Bridge host and pairing/enrollment authority for connected GLAFIRA clients and the Windows Agent fleet. Pairing tickets, device enrollment, credential issuance, scope assignment, revocation and Bridge transport policy are owned by the Desktop Hub boundary.
- Android and iOS are client nodes. They connect to the Desktop Hub when it is available; they do not host the authoritative Bridge listener, issue fleet credentials or independently approve device enrollment. A mobile device being paired does not make it a Bridge server.
- Do not use the legacy Android-hosted Bridge server or old Android-owner-approval flow as the architecture for new work. Any remaining legacy implementation is historical/compatibility code until it is explicitly retired; it must not become a second pairing authority.
- The current Desktop CLI/Bridge test utilities are transitional validation tools, not the final user-facing Desktop GUI. They must follow the Hub-owned enrollment and credential contract rather than creating a parallel Android-hosted trust path.

### Core and Hub authority boundaries
- Desktop Core owns canonical task/project policy for its ownership domain: owner identity and generation, task lifecycle, owner approvals for sensitive actions, accepted results and canonical audit history.
- Desktop Hub owns Bridge connectivity and enrollment, the device/Agent registry, heartbeats and capability inventory, bounded fleet scheduling, worker leases/fencing, request replay/idempotency controls and delivery receipts for tasks explicitly delegated by Desktop Core.
- Hub enrollment/pairing is not permission to perform every operation. Scopes are least-privilege, owner-approved and revocable. The Hub cannot independently approve external side effects, bypass Core policy, or create cross-Core task ownership.
- Android Core remains an independently useful Core for tasks it owns. When Desktop is unavailable, Android continues its supported local work. Cross-Core work requires an explicit authenticated handoff, exactly one task owner, a monotonic ownership generation and effect-level idempotency; pairing or heartbeat alone never transfers ownership.
- Windows Agents pair with the Desktop Hub, advertise bounded capabilities and execute only fenced tasks delegated by Desktop Core. Agents do not become Core authorities and cannot approve actions or widen their own scopes.

### Security and implementation status
- Keep TLS with certificate validation/pinning, per-device authentication, scoped authorization, replay/idempotency protection, credential revocation, lease fencing and explicit approval for sensitive external effects.
- Never export mobile Keystore/Keychain private keys, put reusable credentials in logs or allow a client-provided scope request to grant itself authority.
- The local Windows Hub Agent pairing/heartbeat/lease/result path is present in current main. Full Desktop Core→Hub task assignment, canonical result reconciliation, production Agent handlers and two-PC end-to-end acceptance are not yet complete; do not describe the entire fleet workflow as production-ready.
- The Windows Desktop Hub and multi-agent scheduler remain tracked by issue #123. The accepted two-Core ownership/failover contract remains `docs/contracts/DUAL_CORE_OWNERSHIP_AND_FAILOVER.md`.


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

## 6. Key project documents

- `README.md` — repository entry point and current project overview.
- `docs/GLAFIRA_SHARED_PROJECT_CONTEXT.md` — this versioned, non-secret cross-platform project context.
- `docs/GLAFIRA_OWNER_IDENTITY_AUTONOMY_AND_UPDATES.md` — owner identity, autonomy and update architecture.
- `docs/contracts/DUAL_CORE_OWNERSHIP_AND_FAILOVER.md` — accepted two-Core ownership/failover contract.
- `docs/GLAFIRA_PROJECT_DISPATCHER.md` — dispatcher v1 operation and limitations.
- `docs/MOBILE_PERSONAL_DATA_BRIDGE.md` — proposed Android/iOS OS-mediated personal-data capability layer.

Only reference documents that exist in the current repository. Before changing architecture or resuming work, inspect the relevant current document and the exact PR/branch head. Do not assume the status table in a historical document is live; query current GitHub state.
