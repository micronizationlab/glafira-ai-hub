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
| P2 | Backup action/accessibility and portable restore regression | [Issue #350](https://github.com/micronizationlab/glafira-ai-hub/issues/350), [PR #352](https://github.com/micronizationlab/glafira-ai-hub/pull/352), [PR #353](https://github.com/micronizationlab/glafira-ai-hub/pull/353), [PR #318](https://github.com/micronizationlab/glafira-ai-hub/pull/318) | Fix Settings/Backup Button merged accessibility semantics; test the actual clickable node, not a child Text. Preserve missing-folder guidance. In restore tests, compare the pre/post Trace set because lifecycle triggers create valid events. Re-run emulator on exact head. |
| P3 | Android instrumentation and Bridge contracts | [PR #140](https://github.com/micronizationlab/glafira-ai-hub/pull/140), [Roadmap](../IMPLEMENTATION_ROADMAP.md), [Action Pool A](../ENGINEERING_ACTION_POOL_A.md) | Reconcile the heavily diverged Bridge branch against main; split P2 Backup changes from P3 Bridge; verify pairing, revocation, lease/ACK/reconnect and instrumentation. |
| P3 | Windows Desktop Hub and Agent | [Roadmap](../IMPLEMENTATION_ROADMAP.md) | Inspect current Desktop PR stack; choose non-overlapping contract, scheduler, enrollment or UI task; prove Desktop ↔ Agent lifecycle. |
| P3 | Oldest unfinished work and release blockers | [Action Pool A](../ENGINEERING_ACTION_POOL_A.md) | Refresh oldest open issues/PRs, reconcile stale branches against current main, port only verified useful work. |
| P4 | Free-first Multi-AI Orchestrator and account connections | [Issue #367](https://github.com/micronizationlab/glafira-ai-hub/issues/367), [ADR PR #368](https://github.com/micronizationlab/glafira-ai-hub/pull/368) | Design provider registry and parallel/sequential orchestration without displacing P1–P3. OpenAI SIWC Android adapter remains gated on native OAuth feasibility, actual plan eligibility and terms review; no generic ChatGPT-plan proxy or silent paid fallback. |
| P4+ | Voice, Android UX, specialist roles, Connect architecture | [Issue #361](https://github.com/micronizationlab/glafira-ai-hub/issues/361), [PR #364](https://github.com/micronizationlab/glafira-ai-hub/pull/364) | Connect architecture/spec can proceed in parallel; runtime follows Core/Bridge/Desktop foundations. |

> The Core failure-matrix reference above is intentionally marked for refresh rather than guessing an issue number. Replace it with the verified current issue before assigning work.

| P4 cross-cutting | GLAFIRA DESIGN — unified UI/UX system for Android, iOS, Desktop and Agent | [Coordination contract](../../GLAFIRA_COORDINATION.md#11-dedicated-cross-cutting-workstreams) | Reserve one design owner; define tokens, navigation, components, responsive screens and implementation specs. Preserve ICQ 2003 + modern Apple/macOS gloss direction. No Core/Bridge/runtime edits. |
| P4 cross-cutting | GLAFIRA QA & Quality Intelligence | [Coordination contract](../../GLAFIRA_COORDINATION.md#11-dedicated-cross-cutting-workstreams) | Reserve one QA owner; maintain test matrix, exact-head CI/log review, regression/accessibility checks and release evidence. Do not weaken tests or silently change production behavior. |

## Active reservations

No participant reservations are recorded in this initial board. Each invited participant must create their own `docs/coordination/participants/<participant-id>.md` file and reserve an exact task scope there. Central GLAFIRA reconciles conflicts.

## CI evidence standard

For every PR record: current head SHA, run ID, per-job result, executed test count, skipped tests, artifact/report links and next action. Green checks from an earlier SHA are stale evidence.