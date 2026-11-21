# GLAFIRA multi-agent task board

**Maintainer:** central GLAFIRA. **Last refreshed:** 2026-11-21.  
**Rule:** this is a dispatch snapshot, not authoritative live status. Before claiming any row, refresh the linked issue/PR, branch head and exact-head CI.

## Dispatch rules

- Read root [GLAFIRA_COORDINATION.md](../../GLAFIRA_COORDINATION.md).
- Reserve a narrow scope in your own participant file before editing.
- A task marked blocked does not authorize idle time: choose the highest-priority independent item.
- Update this board only through a coordinator-reviewed change. Participants update their own files and relevant issues/PRs.

## Current high-priority work map

| Priority | Workstream | Reference | Next safe action / acceptance |
|---|---|---|---|
| P1 security | Main branch protection, required reviews and technical ownership boundaries | [Issue #370](https://github.com/micronizationlab/glafira-ai-hub/issues/370) | Repository API currently reports `main.protected=false`; owner/admin must enable a ruleset, required review and appropriate exact-head checks before broad collaborator write access. |
| P1 | Core owner-generation fencing and atomic ownership handoff | [Issue #366](https://github.com/micronizationlab/glafira-ai-hub/issues/366), [PR #347](https://github.com/micronizationlab/glafira-ai-hub/pull/347) | Add a production CAS handoff API; bind approvals, maintenance wakeups, expiry, transitions and result writes to owner ID + generation; test stale owner N after N→N+1 handoff. PR #347 has an emulator blocker and is not merge-ready. |
| P1 | Core durable result outbox and explicit ACK | [Issue #266](https://github.com/micronizationlab/glafira-ai-hub/issues/266), [Issue #258](https://github.com/micronizationlab/glafira-ai-hub/issues/258) | Verify current ownership-generation prerequisite, exact delivery identity/digest, restart-safe retry and duplicate ACK handling. |
| P1 | Core failure-injection matrix | [Issue #260](https://github.com/micronizationlab/glafira-ai-hub/issues/260) | Refresh current issue list; identify exact open Core failure-matrix issue and run logs before claiming. |
| P2 | Backup action/accessibility and portable restore regression | [Issue #350](https://github.com/micronizationlab/glafira-ai-hub/issues/350), [Issue #371](https://github.com/micronizationlab/glafira-ai-hub/issues/371), [PR #352](https://github.com/micronizationlab/glafira-ai-hub/pull/352), [PR #353](https://github.com/micronizationlab/glafira-ai-hub/pull/353), [PR #318](https://github.com/micronizationlab/glafira-ai-hub/pull/318) | Fix Settings/Backup Button merged accessibility semantics; test the actual clickable node, not a child Text. Preserve missing-folder guidance. In restore tests, compare the pre/post Trace set because lifecycle triggers create valid events. Re-run emulator on exact head. |
| P3 | Android instrumentation and Bridge contracts | [PR #140](https://github.com/micronizationlab/glafira-ai-hub/pull/140), [Roadmap](../IMPLEMENTATION_ROADMAP.md), [Action Pool A](../ENGINEERING_ACTION_POOL_A.md) | Reconcile the heavily diverged Bridge branch against main; split P2 Backup changes from P3 Bridge; verify pairing, revocation, lease/ACK/reconnect and instrumentation. |
| P3 | Windows Desktop Hub and Agent | [Roadmap](../IMPLEMENTATION_ROADMAP.md) | Inspect current Desktop PR stack; choose non-overlapping contract, scheduler, enrollment or UI task; prove Desktop ↔ Agent lifecycle. |
| P3 | Oldest unfinished work and release blockers | [Action Pool A](../ENGINEERING_ACTION_POOL_A.md) | Refresh oldest open issues/PRs, reconcile stale branches against current main, port only verified useful work. |
| P4 | Free-first Multi-AI Orchestrator and account connections | [Issue #367](https://github.com/micronizationlab/glafira-ai-hub/issues/367), [ADR PR #368](https://github.com/micronizationlab/glafira-ai-hub/pull/368) | Design provider registry and parallel/sequential orchestration without displacing P1–P3. OpenAI SIWC Android adapter remains gated on native OAuth feasibility, actual plan eligibility and terms review; no generic ChatGPT-plan proxy or silent paid fallback. |
| P4+ | Voice, Android UX, specialist roles, Connect architecture | [Issue #361](https://github.com/micronizationlab/glafira-ai-hub/issues/361), [PR #364](https://github.com/micronizationlab/glafira-ai-hub/pull/364) | Connect architecture/spec can proceed in parallel; runtime follows Core/Bridge/Desktop foundations. |

> The Core failure-matrix reference above is intentionally marked for refresh rather than guessing an issue number. Replace it with the verified current issue before assigning work.

| P4 cross-cutting | GLAFIRA DESIGN — unified UI/UX system for Android, iOS, Desktop and Agent | [Coordination contract](../../GLAFIRA_COORDINATION.md#11-dedicated-cross-cutting-workstreams) | Reserve one design owner; define tokens, navigation, components, responsive screens and implementation specs. Preserve ICQ 2003 + modern Apple/macOS gloss direction. No Core/Bridge/runtime edits. |
| P4 cross-cutting | GLAFIRA QA & Quality Intelligence | [Coordination contract](../../GLAFIRA_COORDINATION.md#11-dedicated-cross-cutting-workstreams) | Reserve one QA owner; maintain test matrix, exact-head CI/log review, regression/accessibility checks and release evidence. Do not weaken tests or silently change production behavior. |
| P4 cross-cutting | External AI contributor onboarding | [Onboarding contract](EXTERNAL_AI_CONTRIBUTOR_ONBOARDING.md), [Issue #370](https://github.com/micronizationlab/glafira-ai-hub/issues/370) | Start with one independently authenticated contributor after branch protection is enabled; require one reserved scope, one branch, one PR and exact-head evidence. DeepSeek/GigaChat GitHub write paths remain unverified until demonstrated. |

## Active reservations

No participant reservations are recorded in this initial board. Each invited participant must create their own `docs/coordination/participants/<participant-id>.md` file and reserve an exact task scope there. Central GLAFIRA reconciles conflicts.

## CI evidence standard

For every PR record: current head SHA, run ID, per-job result, executed test count, skipped tests, artifact/report links and next action. Green checks from an earlier SHA are stale evidence.

## Ten coordinated workstreams (owner-confirmed operating layout)

This section is the dispatch view for the ten parallel lanes. It does not change the P1–P4 priority hierarchy above. A lane may investigate or prepare independent work while a higher-priority lane waits on CI, but lower-priority work must not displace an actionable P1/P2 task.

| Lane | Scope | Current live reference | Next independently verifiable action |
|---|---|---|---|
| 01 — GLAFIRA Core | SQLite queue, dispatcher, durable task state, result outbox and ACK | [#366](https://github.com/micronizationlab/glafira-ai-hub/issues/366), [#266](https://github.com/micronizationlab/glafira-ai-hub/issues/266), [#258](https://github.com/micronizationlab/glafira-ai-hub/issues/258) | Map every direct task-state/result mutation to the ownership-fenced API; add a transactional CAS handoff only after full schema and call-path audit. |
| 02 — Owner Identity & Security | Creator identity, owner sessions, generation fencing, approvals, grants and authorization | [#366](https://github.com/micronizationlab/glafira-ai-hub/issues/366), [#370](https://github.com/micronizationlab/glafira-ai-hub/issues/370), [Finance safety gate #374](https://github.com/micronizationlab/glafira-ai-hub/issues/374) | Bind approvals and grants to owner ID + generation; keep stale-generation operations fail-closed. Payment execution cannot be enabled before this gate is met. Branch protection remains an owner/admin configuration dependency. |
| 03 — Backup & Recovery | Encrypted backup, restore, interrupted restore, portability and recovery evidence | [#371](https://github.com/micronizationlab/glafira-ai-hub/issues/371), [PR #372](https://github.com/micronizationlab/glafira-ai-hub/pull/372), [#350](https://github.com/micronizationlab/glafira-ai-hub/issues/350) | Re-run emulator acceptance on the exact latest PR #372 head; do not merge while any required instrumentation test fails. |
| 04 — Android | App stability, navigation, permissions, voice, accessibility, bank-app owner-present handoff and physical-device acceptance | [#82](https://github.com/micronizationlab/glafira-ai-hub/issues/82), [#99](https://github.com/micronizationlab/glafira-ai-hub/issues/99), [Finance #374](https://github.com/micronizationlab/glafira-ai-hub/issues/374) | Keep Android regressions in an acceptance matrix; bank login/MFA stays in the bank's trusted UI and UI-assisted access is allowed only where bank terms permit. |
| 05 — Bridge & Pairing | Authenticated enrollment, TLS pinning, credential scope, revocation, reconnect and ACK | [#138](https://github.com/micronizationlab/glafira-ai-hub/issues/138), [PR #140](https://github.com/micronizationlab/glafira-ai-hub/pull/140) | Reconcile old Bridge branches against current main and prove owner-approved pairing, revocation and reconnect without replay. |
| 06 — Windows Desktop | Desktop app shell, authoritative host, persistent scheduler, owner workspace, bank document workspace and installer | [#95](https://github.com/micronizationlab/glafira-ai-hub/issues/95), [#123](https://github.com/micronizationlab/glafira-ai-hub/issues/123), [Finance #374](https://github.com/micronizationlab/glafira-ai-hub/issues/374) | Verify current Desktop branch/PR stack; keep finance workflows behind shared owner approval and audit policy. |
| 07 — Windows Agent | Enrollment, capability registration, bounded workers, leases, heartbeat and result delivery | [#123](https://github.com/micronizationlab/glafira-ai-hub/issues/123), [#138](https://github.com/micronizationlab/glafira-ai-hub/issues/138) | Test the agent lifecycle against the Desktop host: claim, lease fencing, disconnect, restart, result delivery and duplicate suppression. |
| 08 — Web & Communications | Web access, GLAFIRA-to-GLAFIRA contacts/protocol, official messaging connectors | [Connect #361](https://github.com/micronizationlab/glafira-ai-hub/issues/361), [ADR PR #364](https://github.com/micronizationlab/glafira-ai-hub/pull/364), [#348](https://github.com/micronizationlab/glafira-ai-hub/issues/348), [#349](https://github.com/micronizationlab/glafira-ai-hub/issues/349) | Review the Matrix + adaptive WebRTC ADR before any runtime commitment; keep connector execution official-API-only, scoped, auditable and owner-revocable. |
| 09 — Design | One visual language for Android/Windows Desktop/Web: ICQ 2003 reimagined through contemporary iOS architecture, restrained glossy/lacquer glamour; ChatGPT-inspired conversation workspace | [Design #373](https://github.com/micronizationlab/glafira-ai-hub/issues/373), [Design baseline](../design/UNIFIED_INTERFACE_DESIGN_SYSTEM.md), [Desktop UI #95](https://github.com/micronizationlab/glafira-ai-hub/issues/95) | Use authentic ICQ references where verifiable; otherwise iOS is the architecture baseline. Apply ChatGPT conversation patterns. Design-only scope; no runtime/Core/Bridge edits. |
| 10 — QA & Release | Regression strategy, CI logs, exact-head evidence, release artifacts and install acceptance | [#138](https://github.com/micronizationlab/glafira-ai-hub/issues/138), [#370](https://github.com/micronizationlab/glafira-ai-hub/issues/370) | Record exact SHA, run ID, per-job outcome, executed/skipped tests, artifact links and physical-device acceptance; never treat skipped jobs as passes. |

### Dispatch cadence

- Refresh all ten lanes at each work block; reserve and execute only scopes with a concrete next action.
- Keep shared-file edits serial. Parallelize independent reads, audits, tests and separate-file PRs.
- When a lane is blocked by CI or an owner/admin dependency, switch to the highest-priority independent lane.
- Record completed actions only when a commit, test result, issue/PR update or other durable evidence exists. Do not inflate the 150-step counter with repeated status checks.
- The ten lanes are a coordinated backlog, not a claim that ten autonomous agents are running. Actual parallel execution is limited to independent tool operations and contributors who have separately registered.
