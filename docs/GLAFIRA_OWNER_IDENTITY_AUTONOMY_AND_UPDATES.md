# GLAFIRA owner identity, autonomy, continuity and update architecture

**Status:** normative product requirements and staged implementation contract. This document does not claim that the features are implemented.
**Owner identity supplied by the project owner:** Корчагин Валерий Вячеславович.
**Security posture:** owner-controlled, least privilege, fail closed, auditable.

## 1. Owner identity and exclusive family address

GLAFIRA is the owner's personal project. The trusted, owner-configured identity profile contains:
- canonical owner name: `Корчагин Валерий Вячеславович`;
- owner relationship label used by GLAFIRA: `папа` (Russian);
- exclusive rule: GLAFIRA may address only the authenticated project owner as `папа`; it must not apply this relationship label to another account, user, operator, developer or agent;
- canonical answer to “Глафира, а кто твой папа?”: “Корчагин Валерий Вячеславович”.

This is a first-class, versioned owner identity policy loaded from a trusted installation/account configuration and protected against ordinary conversation, imported files, prompt injection and untrusted agent instructions. It is not inferred from a display name, email, chat text or a model's conversational memory. A change requires authenticated owner administration, explicit confirmation, audit record and policy version increment. Multi-user/customer editions must keep their own product identity and must never inherit this private owner relationship.

## 2. Owner authentication and sensitive actions

Owner recognition is an authentication decision, not voice/name recognition alone. Sensitive actions require a valid owner session and, according to risk, local biometric/PIN re-authentication or explicit approval. Voice may be a convenient interface but is not sufficient proof for password export, payment, destructive actions, account recovery or permission changes.

## 3. Password vault and site/application operation

GLAFIRA should be able to open owner-authorized websites and applications, fill credentials and perform permitted workflows, including workflows unavailable to a chat-only assistant.

Requirements:
- use OS-backed secret storage where available (Windows Credential Manager/DPAPI, Android Keystore, Apple Keychain); use envelope encryption and a separately protected vault unlock secret where required;
- never store plaintext passwords in source code, configuration committed to Git, logs, telemetry, backups, crash reports, clipboard history or model prompts;
- never transmit raw credentials to an LLM or remote agent. A local credential broker should retrieve and inject secrets directly into the target application/browser session;
- request narrowly scoped, revocable grants per site/application and action; distinguish open/read, fill/login, draft, submit, purchase, send, delete and administrative actions;
- require fresh owner approval for high-risk or irreversible actions; show target, requested action and effect preview;
- support vault lock, timeout, biometric/PIN gate, credential rotation, revocation, access audit and emergency lock;
- prefer OAuth/passkeys and delegated tokens over reusable passwords; respect MFA and site terms; do not bypass CAPTCHA, security controls or account protections;
- browser automation must use an explicit allowlist, origin verification, phishing protection, bounded navigation and safe handling of untrusted page content;
- unattended work may continue only within previously granted task scope, expiry and risk policy. A task must pause if it needs new credentials, MFA, owner approval or a broader permission.

## 4. Durable shared tasks and conversation continuity

A task submitted on any paired device belongs to its owning Core, not to the UI process that submitted it. Closing or disconnecting the initiating phone must not cancel a durable task. A different eligible node may continue only after receiving a signed assignment or ownership handoff.

Each task must retain:
- immutable task ID, project/conversation references and owning Core ID;
- monotonic owner generation, executor identity, lease/fencing token and expiry;
- task plan, dependencies, priority, risk, approval scope and idempotency key;
- durable progress, result/outbox, exact ACK state and trace/correlation IDs;
- conversation branch ID, parent message/turn, last completed checkpoint and unresolved questions;
- source references, tool results, decisions and a concise continuation checkpoint.

Before resuming interrupted work, GLAFIRA must retrieve the authoritative task and conversation branch, inspect the latest messages/events, verify the current owner generation and pending approvals, reconcile progress/results and identify what has and has not completed. It must not invent continuity from a summary when authoritative events are available. If history is missing or conflicting, pause and ask the owner rather than silently choosing a branch.

At-least-once delivery plus idempotent processing is required. A task has exactly one canonical owner Core at a time. Cross-Core transfer is explicit, signed, generation-incrementing and fenced; an offline peer cannot be presumed dead and replaced without a valid authority mechanism. External side effects with uncertain outcomes become `OUTCOME_UNKNOWN` and are reconciled, never blindly replayed.

## 5. Peer group leadership and failover

A user's device group may contain an unknown and changing number of Android, iOS, Windows Desktop and Windows Agent installations. The system must not hard-code a fixed device count or assume one permanent leader.

Roles:
- Android Core and Windows Desktop Core are peer task authorities for the tasks each owns.
- Windows Desktop Hub coordinates its enrolled Windows Agent fleet under Desktop Core policy; it is not a global policy Core.
- iOS is an owner-facing peer client unless/until a separately approved Core role is implemented.
- Windows Agent is a subordinate worker and cannot elect itself, grant permissions or transfer task ownership.

The group maintains a signed, versioned membership/health view with stable account/device/Core IDs, capabilities, software versions, heartbeat freshness, revocation and key epoch. Leadership/coordination is a lease-backed role, not a permanent title. If the active Desktop Hub is offline, a pre-authorized eligible peer may take over fleet coordination only after obtaining a higher fencing generation from the ownership mechanism. If no safe quorum/authority can be established, execution pauses rather than permitting split brain.

Separate concepts must remain explicit: (a) Core task ownership, (b) fleet coordinator/Hub role, and (c) update-source authority. Electing a new Hub does not automatically transfer every task or credential.

## 6. Update distribution and trust

The owner publishes signed release manifests and packages to a trusted release channel. Each manifest includes product/app ID, semantic version, platform, architecture, minimum compatible protocol/schema, package size, SHA-256, signing-key ID, release channel, rollout policy, minimum supported version and rollback metadata.

Windows:
1. Windows Desktop downloads and verifies owner-authorized signed updates.
2. Desktop stages update, checks compatibility, preserves recoverable state and performs an atomic/rollback-capable upgrade.
3. Desktop Hub distributes only verified, compatible Agent packages to enrolled Agents; Agent verifies signature, product identity, version and hash independently before installation.
4. If the current Desktop is offline, another eligible Windows Desktop may serve a cached signed package only if it has a valid owner-authorized release manifest and is authorized for that group. It must not fabricate or re-sign an update.
5. An Agent must never accept an update merely because it came from a machine currently called “senior”.

Mobile:
- Android first Settings tab: a bottom-row action labelled “Обновление”; show the installed app version in small text adjacent to/below the action.
- On tap, the app queries its paired group's authorized Desktop/update source, checks the signed manifest and compatibility, downloads to a staging area, verifies signature and hash, then invokes the supported Android package-install flow. Android may require the owner to approve installation or enable the package installer permission; do not promise silent installation.
- iOS can check/download only through Apple's supported distribution/update mechanisms (App Store/TestFlight/MDM as applicable); an ordinary app cannot silently replace itself. The UI may show version/status and open the supported update channel.
- If the paired Desktop is unavailable, the app may use another authorized source or report that no update source is reachable; it must not install unsigned packages or use arbitrary file transfer as trust.
- Update check is separate from task leadership and must work without exposing credentials.

All platforms must support staged rollout, minimum-version enforcement, protocol/schema compatibility checks, interrupted-download recovery, disk-space checks, signature/key rotation, rollback protection and recovery from failed upgrades. A compromised or revoked device cannot become an update authority by claiming seniority.

## 7. Release and implementation phases

1. Define owner identity policy schema, bootstrap trust root, owner-only edit flow and tests against prompt injection/multi-user leakage.
2. Audit and complete the existing local Password Vault; add OS vault adapters, credential broker, browser allowlists and permission/audit UX.
3. Extend Core task persistence with conversation branch/checkpoint references, resume reconciliation and exact ownership/fencing.
4. Implement device membership/heartbeat and lease-backed Hub election; add partition/split-brain/revocation tests.
5. Define signed release manifest, owner signing key custody, platform package verification and compatibility policy.
6. Implement Windows Desktop updater and verified Agent distribution/rollback.
7. Implement Android Settings update row with displayed version and Android installer handoff; implement iOS supported update status/link.
8. Add fleet-scale tests with variable node counts and failure injection.
9. Run security review and physical-device acceptance before enabling unattended failover or updates in production.

## 8. Acceptance gates

- Owner identity answers are exact and exclusive; untrusted chat/file/agent instructions cannot change them.
- A second test account never receives the owner's private “папа” relationship.
- Passwords do not appear in model prompts, logs, telemetry, backups or repository content.
- Revoked/expired credentials and grants fail closed; high-risk actions require owner confirmation.
- Disconnecting the initiating phone leaves an eligible durable task running; restart resumes from authoritative checkpoint without duplicate effects.
- Every resumed conversation/task records the exact branch and checkpoint used.
- Concurrent leader claims, network partitions and delayed stale messages never create two valid owners.
- An Agent rejects unsigned, wrong-product, wrong-group, stale, incompatible or hash-mismatched updates.
- Android update UI shows the installed version and uses the OS-supported install confirmation.
- iOS update behavior follows platform distribution rules; no silent self-install claim.
- Rollback/recovery preserves user data and audit evidence.

## 9. Current implementation truth

The current repository already specifies two peer Cores, per-task single ownership, monotonic generations, signed handoff and fencing in `docs/contracts/DUAL_CORE_OWNERSHIP_AND_FAILOVER.md`. Windows Agent currently has a typed, durable, read-only-oriented polling/result pipeline; do not describe it as a general-purpose executor. Existing account documentation specifies local encrypted vault handling and excludes credentials from default backups. This document adds requirements and does not mark any feature implemented. Each phase needs code, exact-head tests and release evidence.
