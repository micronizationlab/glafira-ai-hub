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
| P0 | Core durable result outbox and explicit ACK | [Issue #266](https://github.com/micronizationlab/glafira-ai-hub/issues/266), [Issue #258](https://github.com/micronizationlab/glafira-ai-hub/issues/258) | Verify current ownership-generation prerequisite, exact delivery identity/digest, restart-safe retry and duplicate ACK handling. |
| P0 | Core failure-injection matrix | [Issue #260](https://github.com/micronizationlab/glafira-ai-hub/issues/260) | Refresh current issue list; identify exact open Core failure-matrix issue and run logs before claiming. |
| P0/P1 | Android backup action/accessibility regression | [PR #352](https://github.com/micronizationlab/glafira-ai-hub/pull/352), [PR #353](https://github.com/micronizationlab/glafira-ai-hub/pull/353) | Resolve exact-head UiAutomator semantics issue; retain behavior assertion; rerun emulator on exact head. |
| P1 | Android instrumentation and Bridge contracts | [Roadmap](../IMPLEMENTATION_ROADMAP.md), [Action Pool A](../ENGINEERING_ACTION_POOL_A.md) | Verify instrumentation suite is selected and reports non-empty test counts; inspect Bridge lease/ACK/reconnect gaps. |
| P2 | Windows Desktop Hub and Agent | [Roadmap](../IMPLEMENTATION_ROADMAP.md) | Inspect current Desktop PR stack; choose non-overlapping contract, scheduler, enrollment or UI task; prove Desktop ↔ Agent lifecycle. |
| P3 | Oldest unfinished work | [Action Pool A](../ENGINEERING_ACTION_POOL_A.md) | Refresh oldest open issues/PRs, reconcile stale branches against current main, port only verified useful work. |
| P4+ | Voice, Android UX, specialist roles, Connect architecture | [Issue #361](https://github.com/micronizationlab/glafira-ai-hub/issues/361) | Connect architecture/spec can proceed in parallel; runtime follows Core/Bridge/Desktop foundations. |

> The Core failure-matrix reference above is intentionally marked for refresh rather than guessing an issue number. Replace it with the verified current issue before assigning work.

## Active reservations

No participant reservations are recorded in this initial board. Each invited participant must create their own `docs/coordination/participants/<participant-id>.md` file and reserve an exact task scope there. Central GLAFIRA reconciles conflicts.

## CI evidence standard

For every PR record: current head SHA, run ID, per-job result, executed test count, skipped tests, artifact/report links and next action. Green checks from an earlier SHA are stale evidence.