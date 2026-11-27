# GLAFIRA Four-App Ecosystem Architecture

Status: owner-approved architecture baseline; implementation and acceptance are separate gates.
Date: 2026-11-19

## 1. Owner-approved product model

GLAFIRA is one ecosystem delivered through four coordinated applications:

1. **GLAFIRA Android** — native mobile owner client.
2. **GLAFIRA iOS** — native mobile owner client.
3. **GLAFIRA Desktop for Windows** — full desktop workspace and the host/control plane for Windows Agent workers.
4. **GLAFIRA Agent for Windows** — deliberately reduced, background-first worker installed on additional Windows devices.

The four applications share one product identity, versioned contracts, task semantics, status vocabulary and design system. They do not share identical screens or identical responsibilities.

The owner has approved the visual direction **ICQ 2003 + restrained Apple/macOS Big Sur polish**:
- preserve the recognizable five-petal green ICQ flower as the GLAFIRA app symbol;
- airy, light, pearl-white surfaces with pale icy-blue accents;
- subtle silver separators and selective gloss/highlights, not pervasive heavy glass;
- generous whitespace and a light, friendly messenger feel;
- compact, strictly bounded rectangular message bubbles;
- avoid dense dashboards, bulky cards, heavy shadows and excessive decoration.

Visual approval is a separate gate: no broad UI redesign or wholesale screen replacement before the owner reviews the actual Android, iOS, Desktop and Agent mockups.

## 2. Authority and topology

Do not confuse the Windows Desktop host role with the logical security authority.

- **Owner** remains the final authority for identity, permissions, sensitive operations and approvals.
- **GLAFIRA Core** remains the single logical authority for task policy, project isolation, provider/tool admission, approval state and audit.
- **Windows Desktop** is the primary owner-operated Windows host and the coordinator/server for its enrolled Windows Agent worker fleet. It exposes the authenticated Agent endpoint, maintains the Desktop-side dispatch view, assigns eligible work and collects results.
- **Windows Agents** are subordinate execution nodes. They do not become independent GLAFIRA brains, policy authorities or peer owners. They execute only typed, authorized, bounded work delegated by their paired Desktop host/Core.
- **Android and iOS** are owner-facing clients. They can submit tasks, inspect status, review plans and approvals, and receive results through the shared Bridge/Core contracts. They do not need to be running for already-authorized Desktop-hosted worker jobs to continue, provided the Desktop host remains available.

Logical topology:

```
                    OWNER
                      |
          +-----------+-----------+
          |                       |
   Android / iOS             Windows Desktop
   owner clients             workspace + Agent host
          |                       |
          +---- authenticated ----+
                 Bridge / Core
                      |
              Desktop task broker
                      |
        +-------------+-------------+
        |             |             |
     Agent 01      Agent 02 ...  Agent N
     Windows       Windows        Windows
     worker        worker         worker
```

For the first deployment, Desktop-to-Agent communication should work on a trusted local network. No rented server, paid relay, public endpoint or cloud dependency is introduced without separate owner approval. Remote access can later use an owner-controlled secure network path; it must not weaken pairing or authorization.

## 3. Four application responsibilities

### 3.1 Android

- Native Android owner client and current mobile control surface.
- Task/chat creation, project context, notifications, status, approvals and settings.
- Local encrypted storage and Android Keystore for device-bound credentials.
- May host the existing LAN Bridge only when explicitly enabled by the owner; it is not the permanent Agent host.
- Must retain existing working functionality while the visual system is introduced incrementally.

### 3.2 iOS

- Native SwiftUI owner client with the same user-facing task and project concepts as Android.
- Uses the same versioned Bridge protocol, authorization rules, task IDs, idempotency rules and status model.
- Stores device credentials in device-only Keychain with local authentication where required.
- Native iOS project, signing and distribution remain separate delivery gates; a Swift package alone is not a shippable iOS app.

### 3.3 Windows Desktop

- Full Windows workspace: chats, projects, tasks, agent fleet, approvals, settings, diagnostics and audit.
- Hosts the authenticated Windows Agent control endpoint and task broker.
- Enrolls, lists, monitors, pauses, resumes and revokes Agent devices.
- Performs capability-aware scheduling, bounded concurrency, dependency checks, leases, retries and result collection.
- Continues approved work when the main window is closed if the owner has enabled tray/background operation.
- Clearly distinguishes closing the window from stopping the Desktop host. A real host shutdown must stop new leases and drain/checkpoint active work safely.
- Uses Windows-protected local credential storage (for example, DPAPI/Windows Credential Manager), not Android Keystore APIs.

### 3.4 Windows Agent

- A small worker service, not a second Desktop application and not a full GLAFIRA UI clone.
- Installs on a Windows device and, after first-time trust is established, starts with Windows and reconnects to its configured Desktop host automatically.
- Normally operates in the background. A small tray/status/settings surface may show host connection, device identity, capabilities, current task, pause state and diagnostics.
- Receives only typed task envelopes from its paired Desktop host, executes permitted work using local CPU/RAM and explicitly granted local capabilities, then returns structured results and health telemetry.
- Advertises capabilities and resource limits; Desktop must not assume all Agents have equal CPU, RAM, storage, network or installed tools.
- Has explicit pause, disconnect, revoke, uninstall and resource-limit controls.
- Does not expose a generic remote shell, arbitrary command execution, unrestricted filesystem access or silent credential harvesting.
- Does not silently use the Agent device owner's credentials, files, browser sessions, microphone, camera or accounts. Any such capability requires explicit enrollment scope, local policy and appropriate owner approval.

## 4. Agent enrollment and connection lifecycle

"Automatically connects after installation" means automatic discovery/reconnection after a trusted enrollment has been completed; it does not mean an untrusted installer silently gains control of a PC.

1. Agent installer is launched by a local Windows user with clear disclosure of background operation and resource use.
2. Agent discovers or is configured with the Desktop host address and validates its authenticated TLS identity.
3. First enrollment is approved on the Desktop by the owner, showing device name, fingerprint, requested scopes and resource capabilities.
4. Desktop issues a unique revocable device credential. The credential is stored locally in the Windows protected credential boundary.
5. Agent reconnects automatically after restart/network interruption with bounded backoff and a heartbeat.
6. Desktop tracks last-seen time, health, version, capabilities, active task, resource use and authorization scopes.
7. Owner can pause, restrict, rotate credentials, revoke or remove an Agent. Revocation takes effect before any new task can be claimed.
8. A revoked or reinstalled Agent must enroll again; device identity is not inferred from IP address or hostname alone.

## 5. Distributed task execution contract

All task dispatch must be typed, versioned, auditable and idempotent.

A task envelope should include:
- protocol/schema version;
- task ID and stable idempotency key;
- parent task and dependency IDs where applicable;
- project and conversation scope;
- issuing Desktop host ID and target Agent ID (or eligible capability class);
- typed operation and validated bounded payload;
- requested capability/scope and risk classification;
- deadline, resource budget and cancellation token;
- approval reference when required;
- trace/audit correlation ID.

A result envelope should include:
- matching task and idempotency IDs;
- Agent ID and execution attempt;
- accepted/running/succeeded/failed/cancelled/waiting-for-owner outcome;
- structured output or sanitized error;
- start/end timestamps and resource usage;
- artifact references with integrity metadata;
- trace/audit correlation ID.

Scheduling rules:
- Desktop is the dispatch coordinator for its Agent fleet.
- Use bounded global concurrency and per-Agent concurrency/resource limits.
- Claim tasks transactionally; two Agents must never receive the same live lease.
- Enforce dependency readiness before dispatching child tasks.
- Use lease expiry and heartbeat to detect abandoned workers, but never blindly replay uncertain external side effects.
- Read-only work may be retried only when the operation registry classifies it as safe and idempotent.
- Uncertain writes, sends, publishing, purchases, deletions or other consequential actions go to owner reconciliation/approval rather than automatic replay.
- Agent failure must not prevent independent sibling tasks from completing; dependent tasks wait or fail according to explicit dependency policy.
- Desktop shutdown drains or checkpoints active work and records recoverable state.
- Result delivery is acknowledged and durable; duplicate result messages are deduplicated.

The target fleet is at least ten Agents, with architecture that can grow beyond ten without hard-coded device counts. Actual concurrency is controlled by owner settings and measured host/worker capacity, not by the number of installed Agents alone.

## 6. Security, privacy and trust boundaries

- Owner approval and GLAFIRA Core policy remain mandatory; a model response is never an authorization.
- Mutual device identity, TLS, per-device revocable credentials, replay protection, rate limits and strict payload validation are required.
- Agent credentials are unique and device-bound; never embed a shared fleet secret in installers.
- Secrets and passwords remain in the owner's local protected credential stores. Do not send raw passwords, API keys or reusable browser credentials to Agents or models.
- The Agent receives only the minimum data required for its specific task and project scope.
- Local Agent files/resources are unavailable by default. Capabilities must be explicitly granted and revocable.
- External side effects require an action-bound owner approval or a narrowly scoped pre-authorized policy.
- Audit records include actor, device, task, scope, approval, outcome and time, but never secret values.
- Agent updates must be signed, integrity-checked, versioned and rollback-capable before automatic rollout is enabled.
- A compromised Agent must be isolatable and revocable without disabling the whole GLAFIRA network.

## 7. Shared design system and platform-specific UX

Create one design token set and component language, then implement native/adaptive interfaces per platform.

Shared tokens:
- ICQ-inspired green flower identity and status motif;
- pearl/white base, icy-blue accent, restrained silver/chrome details;
- typography scale, spacing scale, icon weights, thin dividers and subtle elevation;
- status semantics: online, idle, busy, disconnected, paused, needs approval, error;
- consistent task lifecycle, notifications, empty states and error language.

Platform-specific screen priorities:
- Android: chat-first mobile home, task inbox, approvals, agent/network status and settings.
- iOS: equivalent owner workflows using native iOS navigation and conventions.
- Desktop: multi-pane messenger/workspace plus a dedicated "Моя сеть" fleet screen.
- Agent: compact tray/status surface and local diagnostics; no unnecessary chat/project dashboard.

The "Моя сеть" Desktop view should show connected/paused/offline Agents, last seen, capabilities, CPU/RAM load where permitted, current task, queue depth, failures and revocation controls. It must not imply that resource telemetry is available when an Agent has not granted it.

## 8. Current repository reality and integration gaps (2026-11-19)

This architecture is a target, not a claim that all four applications are already implemented.

- The current default `main` tree is Android-centric and does not yet contain the full Desktop app, a standalone Windows Agent product, or a shippable iOS application.
- A Windows `desktop-bridge` Kotlin/JVM CLI/task-worker module exists on the active parallel-execution workstream; it is a worker/Bridge foundation, not yet the complete Desktop workspace or a packaged Agent installer.
- An iOS `GlafiraBridgeKit` Swift package and SwiftUI pairing views exist on a separate draft workstream; this is not yet a complete installable iOS client.
- Existing Desktop and iOS specifications were authored on separate draft branches and must be reconciled with this newer owner-approved topology. In particular, Desktop remains a first-class owner client but additionally serves as the Windows Agent host.
- Several interdependent draft PRs are open. Do not merge branches or replace application files merely to make the repository appear unified. Reconcile dependencies, review diffs, and validate CI first.
- The active parallel-execution PR #71 is draft and its CI runs are currently queued/in progress; do not assume acceptance until all required checks and integration tests pass.

## 9. Phased delivery and acceptance gates

### Phase 0 — repository and architecture audit
- Map the actual contents, branches, open PRs, build workflows and test status for all four targets.
- Identify duplicated/conflicting protocol and authority documents.
- Produce a dependency/integration map; no broad code replacement.

### Phase 1 — canonical contracts
- Reconcile one canonical Bridge protocol, identity model, task/result envelope, task states, idempotency, heartbeat, capability manifest, leases, cancellation, revocation and audit schema.
- Define compatibility/version rules for Android, iOS, Desktop and Agent.
- Add protocol golden-vector and contract tests before platform integration.

### Phase 2 — Windows Desktop host and Agent worker boundary
- Separate Desktop UI/application host from the Agent worker runtime.
- Define the Agent Windows service lifecycle, installer, enrollment, protected credentials, health reporting and update/rollback contract.
- Prove one Desktop can securely enroll, dispatch to, monitor, pause and revoke one Agent.

### Phase 3 — fleet scaling and resilience
- Test 2, 5 and 10 Agents, then higher counts where practical.
- Verify unique task claims, bounded concurrency, dependencies, backpressure, Agent disconnect/reconnect, Desktop restart, worker crash, result acknowledgement and safe recovery.
- Verify resource budgets and that a slow/failing Agent does not starve independent work.

### Phase 4 — mobile parity
- Complete Android and iOS owner workflows against the same contracts.
- Verify task submission, progress, approvals, notifications, offline outbox and result reconciliation across both platforms.

### Phase 5 — design system and visual approval
- Produce separate high-fidelity previews for Android, iOS, Desktop and Agent using the approved airy ICQ/Apple direction.
- Owner approves mockups before implementation.
- Apply design incrementally without replacing working task, Bridge, security or settings logic.

### Phase 6 — release acceptance
- Reproducible builds and automated tests per target.
- Physical-device acceptance for Android and iOS; real Windows host/Agent acceptance on multiple PCs.
- Security review, signed packages, update/rollback and owner-controlled release approval.

## 10. Non-goals and prohibitions

- No silent or unapproved remote control of Agent devices.
- No generic shell or arbitrary command execution exposed to models or remote clients.
- No cloud master database or paid relay assumed.
- No credentials copied between devices as ordinary project memory.
- No exactly-once claim for external side effects; uncertain outcomes require reconciliation.
- No broad screen rewrite before visual approval and regression coverage.
- No merge of draft PRs solely to reduce the open-PR count.
