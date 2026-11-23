# GLAFIRA AI HUB — shared project context

Purpose: provide a versioned, non-secret project knowledge pack that Android/Desktop agents and human developers can read before accepting work. This document is project context, not executable authority and not a substitute for owner approval.

Last reviewed: 2026-11-21.

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

## 3. Two distinct Bridge directions — do not conflate them

### Android Core ↔ Windows Desktop CLI
- The existing Android LAN Bridge is an owner-controlled TLS host. It creates its server identity in Android Keystore and exposes a LAN endpoint only while the owner enables it.
- The Desktop Bridge CLI is its client. It submits a one-time invitation, waits for owner approval, imports the one-time credential bundle and stores credentials in an encrypted local vault.
- The owner chooses the actual scopes on Android. Requested scopes are hints only and never grant authority.
- Desktop task polling against Android Core requires explicit owner-granted `desktop.task.claim` and `desktop.task.result` scopes.
- Keep TLS, certificate validation, replay protection, owner approval, revocation and Core authorization intact. Never export Android Keystore private keys or downgrade to insecure transport.
- The current Desktop CLI is a terminal validation utility, not the final Desktop GUI.

### Windows Desktop Hub ↔ Windows Agent fleet
- Windows Desktop Core is a high-capability task-authority node. Windows Desktop Hub is its authenticated Agent-fleet transport and scheduling subsystem; Windows Agents pair with this Hub, heartbeat, advertise capabilities and receive fenced task leases. This local Hub role does not automatically make Desktop the global project coordinator.
- Desktop Core owns canonical task/project policy, owner approvals, task lifecycle, accepted results and audit history for tasks assigned to its ownership domain. The Hub owns the Agent registry, heartbeat/capability inventory, bounded fleet scheduling, worker lease/fencing journal and delivery receipts for tasks explicitly delegated by Desktop Core. Hub results must reconcile to Desktop Core; Hub cannot independently grant permissions, approve actions or create cross-Core ownership.
- Android Core remains an independent peer with its own durable queue and authority for tasks it owns. Cross-Core work requires exactly one task owner, monotonic ownership generation and an explicit signed handoff; pairing or heartbeat alone does not transfer ownership. See `docs/contracts/DUAL_CORE_OWNERSHIP_AND_FAILOVER.md`.
- This is a separate transport and authority direction from the Android-hosted Bridge above. Do not describe the Android device as the Windows Agent fleet's central server, or the Windows Hub as a global replacement for either Core. Global project coordination is a separate GitHub-backed elected role.
- The Windows Desktop HTTPS Hub and multi-agent scheduler are tracked by issue #123. The local Agent pairing/heartbeat/lease/result path is implemented in the current main, but the full Desktop Core→Hub task assignment and canonical result reconciliation, production Agent handlers and two-PC acceptance are not yet end-to-end.
- Enrollment must be owner-approved; TLS certificate pinning, per-device HMAC, scope checks, replay/idempotency protection, lease fencing and explicit approval for external side effects remain mandatory.

See `docs/GLAFIRA_DESKTOP_BRIDGE_TEST_CLIENT.md` for the Android-hosted pairing procedure and issue #123 for the separate Windows Desktop Hub work.

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
- `docs/GLAFIRA_DESKTOP_BRIDGE_TEST_CLIENT.md` — Android-host / Desktop-client pairing procedure.

Before changing architecture or resuming work, read the relevant handoff and inspect the current PR/branch head. Do not assume the status table in a historical document is live; query current GitHub state.
