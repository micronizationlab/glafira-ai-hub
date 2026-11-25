# ADR: Keep runtime task coordination inside GLAFIRA Core, not GitHub CAS

- **Status:** APPROVED by the project owner in sprint #634 on 2026-11-25
- **Decision owner:** Project owner
- **WBS:** 1.1.5 Core authority boundaries; 1.3 Bridge; 1.7 security/governance
- **Tracker:** #640
- **Supersedes:** the runtime GitHub-election portions of `GITHUB_PROJECT_COORDINATION_AND_LEADER_ELECTION.md` and `GITHUB_PROJECT_COORDINATOR_GITHUB_REF_CAS.md`

## Context

The earlier proposed multi-node contract made GitHub the durable runtime coordinator for global leadership leases, task claims, heartbeats and shared task arbitration. That design makes runtime availability and task progress depend on a third-party repository API, its rate limits, latency, regional reachability and service availability. It also couples operational task ownership to a development/repository control plane.

The owner approved the auditor's corrected recommendation: GitHub CAS is not deleted as a repository-development capability, but is excluded from real-time runtime coordination.

## Decision

1. **GitHub is a development/control-plane service only.** It may host source code, Issues, PRs, CI, releases and non-real-time project/configuration metadata. Repository writes remain branch-scoped, allowlisted and owner-authorized.
2. **GitHub is not a runtime task authority.** No Core may use GitHub refs, contents writes, issues, comments or PRs for runtime leader election, task claims, task leases, heartbeats, task result delivery, fencing, Inbox/Outbox or ACK.
3. **Each Core owns its local task authority.** Android Core and Windows Desktop Core keep independent durable local stores. A Core is authoritative for tasks it owns; owner authority and per-task owner-generation/fencing remain local policy gates.
4. **Cross-Core transfer uses the authenticated Bridge protocol.** Direct peer delivery must use enrolled peer identity, one-time pairing proof, authenticated key agreement, TLS transport, visual SAS confirmation, revocation/key rotation, encrypted signed envelopes, durable sender Outbox, receiver Inbox/receipt, idempotency, owner-generation fencing and exact durable ACK.
5. **Windows Desktop Hub may coordinate its local Agent fleet only.** It is not a global coordinator for independent Android/Windows Core installations and cannot override either Core's Owner Authority.
6. **GitHub outage must not stop safe local Core work.** Local queues continue under existing authorization and budgets. Cross-Core tasks remain durably pending until an authenticated peer channel is available; no global takeover is inferred from a local timeout and no unknown external side effect is blindly retried.

## Consequences

- Runtime task processing no longer has GitHub API availability, latency or rate limits in its critical path.
- There is no single global project coordinator or global task registry. Each Core remains autonomous and explicit task ownership moves only through a validated transfer.
- Direct peer delivery must solve partition, reconnect, duplicate delivery, stale owner generation, durable Inbox/Outbox and ACK before cross-Core execution is enabled.
- Existing local TaskStore, DesktopTaskQueue, TraceService and BackupArchive are not deleted or merged by this decision.
- The proposed GitHub coordinator election documents and `project-coordinator.schema.json` are retained as historical/compatibility artifacts, but are inactive and must not be implemented as a production runtime feature without a new owner-approved ADR.
- GitHub file-content SHA checks used to update a development branch/file are allowed; they are repository write preconditions, not task-ownership CAS.

## Evidence and current implementation boundary

- `GitHubRepositoryOperations` is explicitly branch-scoped and documents no merge, branch deletion, repository-settings or arbitrary HTTP operation. It provides repository tree/file reads, branch creation, branch-scoped file writes and draft PR creation.
- `GitHubCoreTaskHandlers` exposes owner-authorized coding/repository operations through Core. Those are development tasks, not the Core's runtime scheduler or global peer lease.
- `GlafiraTaskStore` is the Android Core's local durable task database; `GlafiraCoreOutboxStore` is a local append primitive whose crypto, peer verification, transport and ACK remain separate.
- Initial code search did not locate an active production GitHub coordinator lease/claim runtime. The full runtime call-site audit remains tracked by #640; do not infer a complete absence until that audit is finished.

## Alternatives considered

- **Keep GitHub CAS for runtime election/claims:** rejected because a third-party API outage/rate limit/latency becomes a runtime coordination dependency and conflicts with offline autonomy.
- **Replace GitHub with one central hosted coordinator:** rejected for the same single-point-of-failure and service-dependency reasons; it also adds operating cost and a new authority boundary.
- **Use a global writable CRDT/shared database:** not selected; it complicates owner-generation fencing and side-effect authority and does not replace explicit ownership transfer.
- **Direct authenticated Core-to-Core Bridge with durable delivery:** selected; more protocol work is required, but it preserves local autonomy and explicit ownership.

## Migration and rollout

1. Search all runtime modules and platform branches for GitHub ref CAS, project-coordinator leases, task-claim writes and coordinator heartbeats. Classify every call as development-only, runtime-prohibited or unknown.
2. Disable any production runtime election/claim path before enabling this ADR. Do not remove branch-scoped GitHub coding operations.
3. Mark the previous GitHub election/CAS documents and coordinator message family as superseded/inactive. Preserve them for traceability; do not delete schema artifacts in this change.
4. Complete authenticated peer key agreement, encrypted transfer envelope, durable Inbox/Outbox, exact ACK, revocation/rotation and split-brain/fencing tests.
5. Enable cross-Core transfer only after at least 14 acceptance scenarios pass, including 10-minute network partition, stale fencing token, crash-before-commit, crash-after-commit-before-ACK and physical Android↔Windows acceptance.
6. No user-data migration is required by this decision. Any later store/protocol migration requires its own ADR, verified backup/restore, compatibility plan and rollback.

## Acceptance

- GitHub can be unavailable for at least 10 minutes while already-authorized local Core work continues safely.
- No runtime task claim, lease, heartbeat or ACK is written to GitHub.
- A cross-Core transfer remains pending during peer disconnection and reconciles on authenticated reconnect without duplicate execution.
- Stale owner generations/fencing tokens and revoked peer keys are rejected.
- Development repository operations still work through branch-scoped, owner-authorized GitHub handlers.
