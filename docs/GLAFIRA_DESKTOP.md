# GLAFIRA Desktop — product and engineering specification

Status: project started; specification only until implementation and acceptance tests pass.

## Owner-approved product decisions

- Android and Desktop are independent, first-class clients. Neither is the permanent master; either can continue work and both use the same Glafira Core authority and synchronized project memory.
- Glafira Core is the single logical authority for policy, project isolation, provider routing, permissions and audit. Multiple physical Core replicas, if introduced later, must use explicit synchronization and conflict rules rather than silently diverging.
- The next development priority after required backup acceptance is GLAFIRA Core hardening and the ChatGPT integration path. Desktop UI implementation follows the Core/Bridge contracts; do not let the desktop shell displace Core work.
- Desktop is Windows-first and ICQ-inspired, with opt-in background tray operation.

## Product goal

Build a Windows-first desktop companion for GLAFIRA AI HUB with the compact, friendly interaction model of classic ICQ (early 2000s), while preserving modern security, accessibility and reliability. It is a separate desktop application, not an Android screen stretched onto a PC.

## Technology direction

- Kotlin + Compose Multiplatform Desktop (JVM) as the first implementation candidate, reusing provider-independent Kotlin domain models and coordinator contracts where practical.
- Keep Android-specific code, Android Keystore, WorkManager and mobile UI out of shared modules.
- Windows 10/11 is the first desktop target. Keep platform services behind interfaces so macOS/Linux can be evaluated later.
- Package a signed installer and provide an update/rollback path only after a reproducible CI build exists.

## UX requirements

- Classic ICQ-inspired contact/project list, compact chat windows, status indicators and readable message history.
- Main window can minimize to the Windows notification area (system tray) and remain running in the background.
- Tray menu: open, show active tasks, pause/resume notifications, connection status and exit.
- Native notifications for incoming messages, completed/failed jobs and approval requests; notification actions must not bypass approval gates.
- Multiple project conversations, agent/role directory, search, file attachments and task progress.
- Optional voice plugin integration through the shared Voice Assistant contract; microphone capture starts only after an explicit user action.
- Respect Windows startup settings: launch-at-login is opt-in, visible and reversible.
- Closing the window and exiting the application are distinct actions and must be clearly explained.

## Architecture

1. Presentation: Compose Desktop windows, navigation, tray and notifications.
2. Application: project/session state, command dispatch, synchronization and lifecycle.
3. Shared Core contracts: project IDs, conversation messages, provider-neutral tasks, typed tool requests, approval records and audit events.
4. Transport: authenticated connection to the Glafira Bridge service using TLS; reconnect with bounded backoff and an offline queue.
5. Local security: OS credential store / DPAPI-backed secret protection on Windows; never reuse Android Keystore APIs on desktop.
6. Provider adapters: configured LLM providers are called through Glafira Core policy, not directly from UI components.

## Shared memory and multi-device consistency

- Both clients read and write the same logical project memory through Core APIs; neither maintains a private authoritative copy.
- Each client keeps only a bounded encrypted offline cache and an outbox of pending changes.
- Messages and commands carry stable IDs, device IDs, project IDs, schema versions and idempotency keys.
- Core assigns canonical ordering/version metadata. Clients reconcile using server/Core versions; do not use last-write-wins for destructive or security-sensitive state.
- Conflicts in user-authored content must be surfaced for resolution or merged only with a documented deterministic strategy.
- Device-bound credentials and secrets are never copied as shared memory. Reauthorize credentials separately on each device.
- A device may continue local drafting and queued non-destructive work offline; external actions wait for Core authorization and connectivity.

## Background operation

- Use a single application process with a tray-resident lifecycle; do not create an always-on hidden microphone listener.
- Network reconnection and queued sync are bounded and observable.
- Long-running jobs must survive window closure; graceful exit must stop or checkpoint work and explain unfinished tasks.
- Do not promise execution while Windows is shut down, asleep, disconnected or terminating the process.
- Startup-at-login and notifications require explicit owner controls.

## Security and privacy

- Pair each desktop installation with an authenticated owner-approved device identity.
- Provide device name, last-seen time, active scopes, revoke/disconnect and session expiry.
- Store credentials in the Windows protected credential store; do not sync raw secrets to Android or cloud.
- Use project isolation and field-level data-sharing policy from GLAFIRA Core.
- Read-only actions may run within granted scopes. External writes, publishing, financial operations and destructive actions require the existing owner approval policy.
- Record audit events without secret values.
- Attachments are untrusted input and must be size-limited, type-checked and isolated.

## Delivery phases

0. Specification and architecture review.
1. Core contracts and Bridge authorization/sync design, coordinated with Core hardening.
2. Desktop shell: window, project/contact list, chat view, settings, tray lifecycle and CI build.
3. Pairing and Bridge transport: device enrollment, authenticated sessions, heartbeat, reconnect and revocation.
4. Project/chat synchronization, offline queue and conflict handling.
5. Notifications, task status and owner approval inbox.
6. Voice plugin adapter and optional startup-at-login.
7. Installer, signing, update/rollback and Windows device acceptance.
8. Evaluate macOS/Linux after Windows acceptance.

## Acceptance criteria

- CI produces a reproducible Windows desktop package.
- Closing the main window leaves the tray process running; explicit Exit terminates it.
- Restart/reconnect does not duplicate messages or execute queued actions twice.
- A revoked device cannot reconnect.
- Project boundaries and provider sharing rules are enforced by tests.
- Android and Desktop converge on the same canonical project/conversation state after reconnect.
- Conflicting edits are not silently discarded.
- No external write executes without the required approval.
- No microphone capture occurs without a user-started voice session.
- App clearly reports offline state, pending work and failed synchronization.
- Windows sleep/shutdown limitations are documented.

## Non-goals for the first desktop milestone

- No autonomous unrestricted control of the user's PC.
- No silent keystroke capture, screen recording or always-listening microphone.
- No password scraping or import from browser profiles.
- No production deployment of a paid hosted backend without a separate owner decision.
