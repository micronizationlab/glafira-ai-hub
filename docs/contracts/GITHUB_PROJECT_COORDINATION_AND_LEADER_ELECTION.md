# GitHub-backed GLAFIRA project coordinator and peer fleet contract

**Status:** proposed normative contract; implementation and platform acceptance are separate release gates.  
**Applies to:** Android GLAFIRA, Windows Desktop GLAFIRA, Windows Agent GLAFIRA workers, and iOS GLAFIRA.  
**Product rule:** every GLAFIRA build produced for this project includes the project participation runtime and enables project participation by default after owner-approved GitHub enrollment. It resumes eligible GitHub work automatically when the host OS permits background execution, without requiring the chat screen to remain open. The owner can pause participation or revoke a node; it must not require repeated manual start commands.

## 1. One project, one coordinator, many cooperating nodes

All enrolled GLAFIRA installations are project nodes, not competing independent assistants. Each node has a stable installation ID, platform, software version, registered capabilities, owner-approved project scopes, credential epoch, and revocation status.

The first eligible node to successfully acquire the project's coordinator lease through the GitHub-backed election protocol becomes the initial coordinator. "First" means the first valid claim committed to the authoritative project coordination record, not the earliest local clock or app-install timestamp.

The coordinator maintains the project task inventory and dependency graph, assigns non-overlapping work units to available peers, arbitrates claims/retries/integration order, reconciles PRs/CI/reviews/results, and relinquishes leadership through a signed handoff or lease expiry.

All other online nodes automatically join as contributors. They may accept only assigned or atomically claimable unowned tasks. They must not duplicate a task already leased to another node. Proposals enter the shared queue before execution.

## 2. GitHub is the shared coordination rendezvous

GitHub is the durable rendezvous and coordination authority for project leadership and task claims. The protocol must use a GitHub-backed compare-and-swap/fencing mechanism, not local timestamps, device-local SQLite, ordinary issue comments, or last-write-wins.

The implementation must define a project-scoped coordination record and monotonic coordinatorGeneration; unique nodeId and installationId; coordinator lease ID, expiry, heartbeat sequence and credential/key epoch; atomic claim/renew/transfer semantics with stale-write rejection; durable task IDs, task state version, claim lease and idempotency key; deterministic election order; explicit revocation and owner-approved enrollment.

A contender may acquire leadership only when no valid coordinator lease exists, or with a valid transfer from the current coordinator. Concurrent claims must have exactly one winner. Use Git ref fast-forward/CAS semantics or another GitHub primitive whose concurrency guarantees are demonstrated by tests. Do not assume a read-then-write API sequence is atomic. If the selected primitive cannot guarantee one winner, failover is not ready to enable.

## 3. Leader election and automatic succession

1. A node authenticates to GitHub using an owner-approved credential with minimum repository scopes.
2. It registers/refreshes its node record and advertises capabilities, platform, app version and last-seen sequence.
3. If no live coordinator lease exists, eligible nodes compete for a claim. The first claim committed by authoritative compare-and-swap becomes coordinator at generation N.
4. The coordinator renews its lease before expiry and records a monotonically increasing heartbeat sequence.
5. If the coordinator goes offline, peers do not promote themselves based on a local timeout alone. After authoritative lease expiry, an online eligible peer competes for generation N+1 using compare-and-swap.
6. The winning peer becomes coordinator; others become contributors.
7. The former coordinator, when reconnected, accepts the newer generation and is fenced from leader writes. It rejoins as a contributor unless it later wins a valid election.

Election preference is a valid explicit handoff target; otherwise currently online eligible nodes are ordered by stable project policy with deterministic tie-breaker. Do not rely on unsynchronized wall clocks. The first successful initial claim is not permanently privileged.

If GitHub is unavailable, no new global leader may be elected and no peer may assert global coordinator authority. Nodes may continue strictly local, already-authorized work that cannot conflict with shared project state; shared writes and externally visible integration wait for reconciliation.

## 4. Task partitioning and conflict prevention

Every task has a durable project-scoped ID, immutable input digest, dependencies, capability requirements, risk class, owner generation, state version, idempotency key, assigned node, claim lease and expiry.

The coordinator partitions work into small independent units. Before execution, a contributor atomically claims a task and verifies eligibility, satisfied dependencies, absence of a valid claim, current enrollment/revocation status, granted capabilities, input digest and coordinator generation.

Two nodes must never both believe they own the same mutable task. Stale claims, stale results and stale coordinator generations are rejected. Conflicting file edits use isolated branches/worktrees or serialized path ownership; shared branch writes are prohibited.

Each result carries task ID, node ID, claim lease, coordinator generation, input digest, result digest and idempotency key. The coordinator validates and records one canonical result. Duplicate deliveries are idempotent.

## 5. Git operations and merge authority

Contributors work on isolated branches and open/update PRs for assigned work. They may run tests and review peer changes. The coordinator performs cross-PR overlap checks, orders integrations and may merge only after required checks pass, exact head SHA is verified, the PR is not draft, mergeability is true, and repository protection permits the merge.

No node may disable branch protection, bypass required checks, force-push shared branches, or merge a stale head. Coordinator status does not grant owner approval for secrets, external side effects, financial actions, destructive operations, or permission escalation.

## 6. Background operation and platform reality

The project participation runtime is included and enabled by default in all platform builds after owner-approved GitHub enrollment. The owner may pause it or revoke the node. It schedules eligible work within platform limits:
- Windows Desktop: background service/task scheduler may provide durable execution while machine is awake and service enabled.
- Windows Agent: subordinate worker under enrolled Hub/project policy; election eligibility requires explicit provisioning as a full GLAFIRA node.
- Android: WorkManager/foreground service only for permitted work, network constraints and user-visible long-running execution where required. Do not promise continuous execution after force-stop, battery restrictions or OS termination.
- iOS: BGTaskScheduler/background URL sessions provide opportunistic work within Apple's limits. Do not promise an always-running background process.

All platforms surface participation state, coordinator/contributor role, last GitHub sync, queued work, required permissions and pause reason. Respect user opt-out, OS restrictions, battery/network constraints and owner-approved project scopes.

## 7. Security and recovery

Store OAuth/device-flow credentials and node private keys in platform secure storage (Android Keystore, Windows DPAPI/CNG, iOS Keychain). Never embed shared GitHub tokens or private keys in binaries. Use minimum scopes, explicit enrollment, revocation and key rotation. Bind every coordinator mutation and result to current generation and authenticated node identity. A restored/reinstalled installation gets a new identity and cannot inherit a live lease. External side effects, financial or destructive actions are never replayed merely because a lease expired. Audit leadership changes, claims, assignments, stale-write rejection, PR updates and merge decisions without logging secrets.

## 8. Required acceptance tests

1. First eligible node claims leadership; later nodes join as contributors.
2. Two simultaneous first-claim contenders produce exactly one coordinator.
3. Heartbeat renews lease and blocks premature takeover.
4. Coordinator disappears; after authoritative expiry exactly one peer wins generation N+1.
5. Old coordinator reconnects and is fenced from coordinator writes.
6. Stale node/task lease cannot claim or commit after generation change.
7. Two contributors cannot claim the same task or mutate the same protected work unit.
8. Duplicate task/result deliveries do not duplicate execution or state changes.
9. GitHub outage prevents new global election/shared writes while preserving safe local work.
10. Revocation blocks a node holding cached lease or credential.
11. Android, Windows Desktop, Windows Agent and iOS share protocol fixtures and pass platform tests.
12. PR merge is blocked for draft, stale head, conflicts, failed/pending required checks or protection denial.
13. Background behavior is tested against each platform's lifecycle restrictions.
14. Restore/reinstall cannot impersonate a former coordinator.

## 9. Compatibility and migration

This contract extends the existing per-task Dual-Core ownership model into a project-level multi-node coordinator model. It does not turn iOS into a privileged Core, nor every Windows Agent into a leader-capable Core by default. The project coordinator owns scheduling/arbitration; canonical task/effect authority, per-task fencing, owner approval and risk controls remain in force.

Existing Android/Desktop Core queues, Bridge envelopes, ownership generations, Windows Hub and iOS peer APIs migrate incrementally. Do not replace the current safety model with a weaker single global writable database. Implement GitHub election and task claims first, then connect platform runtimes behind the shared contract.

## 10. Delivery order

1. Shared node identity, enrollment, revocation and protocol fixtures across Kotlin/C#/Swift.
2. GitHub-backed coordinator lease/CAS record with concurrency and stale-generation tests.
3. Durable shared task registry, atomic claims, task partitioning and idempotent results.
4. Android background participation and user-visible state.
5. Windows Desktop coordinator service and Windows Agent contributor worker.
6. iOS peer registration and opportunistic background participation.
7. Cross-platform failover, partition, reconnect and duplicate-delivery acceptance.
8. Enable autonomous coordinator succession only after acceptance tests pass.
