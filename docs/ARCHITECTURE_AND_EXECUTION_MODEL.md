# GLAFIRA architecture and execution model

**Purpose:** consolidate the approved architecture corrections, runtime mechanisms and repository-level implementation boundaries.  
**Authority:** this document is an architecture map. Normative protocol details remain in the linked contracts and ADRs.  
**Implementation status:** a documented target is not evidence that the corresponding runtime path is implemented or enabled.

## 1. Non-negotiable authority model

Authority is ordered and scoped:

1. **Owner** — owns identity, policy, project boundaries, grants, approvals, release decisions and final authority over external effects.
2. **Owning Core** — enforces policy and owns canonical task state within its installation and task ownership domain.
3. **Project** — scopes data, memory, tasks, budgets and access.
4. **Role / Agent** — receives only a bounded task contract and capability set.
5. **Model provider / tool** — replaceable, untrusted execution capability; it cannot grant itself authority.

A model, agent, retrieved document, website, skill, reviewer verdict or trace record cannot grant permissions or change owner policy. Missing, stale or contradictory policy fails closed. External, financial, publishing and destructive operations require a grant bound to the exact action, resource, payload/digest and approval context.

## 2. Two independent Core installations

GLAFIRA is not one global mutable queue:

- **Android Core** is an independently useful owner node for supported local tasks, approvals, chat, durable queueing and result history.
- **Windows Desktop Core** is the primary high-capability owner node for desktop files, long-running work, provider execution and specialist workflows.
- **iOS** is an owner-facing peer client; pairing alone does not make it a third Core authority.
- **Windows Desktop Hub** hosts and coordinates its local Windows Agent fleet. It is not a global Core and cannot override Desktop Core policy.
- **Windows Agent** is a subordinate typed worker. It cannot elect a peer coordinator, own a cross-Core task, approve effects or change permissions.

Each Core has its own durable local store and remains useful when the other Core or GitHub is unavailable. The stores are not a shared writable SQLite database. Exactly one Core owns a task's canonical state at a time. Ownership transfer is explicit, authenticated and fenced; a peer receives a bounded execution assignment or replica, not implicit ownership of all project data.

See [Dual-Core ownership and safe failover](contracts/DUAL_CORE_OWNERSHIP_AND_FAILOVER.md) and [ADR-0001](adr/ADR-0001-runtime-coordination-local-core-not-github.md).

## 3. Durable task execution lifecycle

A task moves through a durable, versioned state machine, not an in-memory callback chain:

1. **Intake** — create a stable task ID, project scope, typed task kind, input digest, idempotency key, dependencies, risk class, resource budget and acceptance criteria.
2. **Policy** — evaluate actor, project, data classification, capability and requested effect. Record the decision. Ask the owner when required.
3. **Plan** — produce a bounded task/DAG plan. Validate project boundaries, dependency existence, cycles, maximum fan-out/depth and resource limits before enqueue.
4. **Claim** — transactionally claim only an eligible task. Record worker/Core identity, lease ID, owner generation, fencing token, state version and expiry.
5. **Execute** — use the shared Core executor and registered typed handler. Respect bounded concurrency, cancellation, timeouts, retry budget and DRY_RUN policy.
6. **Persist** — commit progress, output reference/digest and terminal or waiting state durably. Never treat an in-memory result as completion.
7. **Review** — independently evaluate output against acceptance criteria and evidence. Record verdict, findings, reviewer identity/version and independence level.
8. **Accept / deliver** — the owning Core validates the exact lease, fencing token, owner generation, state version, idempotency key and result digest before accepting a result and emitting the durable ACK.
9. **Recover** — after restart, reconcile persisted state. Resume only operations proven safe to resume; uncertain external effects go to owner review, not blind replay.

Terminal states are immutable except through an explicit compensating task. A timeout is not proof that a remote side effect did not occur.

## 4. Ownership, leases, fencing and idempotency

Every cross-Core task has one canonical ownership record containing at least:

- task and project IDs;
- owner Core ID and monotonically increasing owner generation;
- assigned executor Core/Agent ID;
- lease ID, expiry and fencing token;
- task state version;
- idempotency key and immutable payload digest;
- effect risk and exact owner approval reference, where required.

Every claim, heartbeat, result, handoff and state mutation must carry the current ownership generation and exact lease identity. The receiver rejects stale generations, stale state versions, expired/revoked credentials, mismatched digests and duplicate effects. Generation never resets on retry, restore or reinstall.

Retries reuse the same idempotency identity for the same logical effect. A retry with changed intent or payload is a new task and requires a new policy/approval evaluation.

## 5. Durable peer delivery: Outbox → Inbox → exact ACK

Cross-Core delivery is an at-least-once transport with idempotent durable processing; do not claim exactly-once external effects.

- Sender commits an Outbox record and task transition atomically before attempting network delivery.
- The envelope is versioned, signed, encrypted for the enrolled recipient and bound to sender/recipient Core IDs, key epochs, project/task IDs, owner generation, state version, sequence, idempotency key and payload digest.
- Receiver authenticates the peer, validates authorization/fencing/replay window and digest, then commits the Inbox record before acknowledging receipt.
- Duplicate envelopes return the prior durable receipt/result; they do not execute the task again.
- The result is persisted by the canonical owner before an ACK is emitted.
- ACK identifies the exact message, task, lease, generation, state version and result digest. A generic HTTP 200 or transport receipt is not a task-completion ACK.
- Sender retains pending records until the matching durable ACK is verified. Reconnect replays pending envelopes safely.
- Revocation, key rotation, invalid signature, missing key, replay or mismatch fails closed and is visible in the trace.

Crash tests must cover before/after Outbox commit, before/after Inbox commit, after handler side effect but before result commit, after result commit but before ACK, and duplicate/reordered delivery.

## 6. Peer discovery and pairing

mDNS is discovery only; it is not identity or authentication. It may advertise a local candidate endpoint and protocol version but must never authorize a connection.

Pairing target flow:

1. Owner explicitly starts pairing on the receiving Core.
2. A short-lived, one-time invitation is created with a random nonce and narrow scope.
3. QR code transfers the invitation/bootstrap proof to the other device; mDNS may help locate the endpoint on the local network.
4. Peers establish TLS with expected-host/certificate validation and the approved pin or equivalent authenticated channel binding.
5. Both sides display a short authentication string (SAS) derived from the negotiated transcript; the owner confirms it on the intended devices.
6. A separate key-agreement key is bound to the installation identity and key epoch. Signing keys are not reused as encryption keys.
7. Enrollment stores the peer, granted capabilities and expiry. Revocation and key rotation invalidate future operations and outstanding leases as specified by policy.

No LAN discovery result, QR scan or TLS connection alone grants task permissions. Cross-Core execution remains disabled until key agreement, durable delivery, exact ACK, revocation and partition/fencing acceptance pass on real Android↔Windows devices.

## 7. Memory and project isolation

Memory is governed data, not an unrestricted transcript cache:

- OWNER, PROJECT, AGENT, CONVERSATION and short-lived WORKING scopes remain distinct.
- Every record has classification, provenance, status, confidence basis, timestamps and source reference.
- Agent-inferred memory is proposed by default; only owner-confirmed facts become confirmed owner memory.
- Context assembly filters by project, role, classification, expiry and provider policy before retrieval.
- Restricted data is local-only unless the owner explicitly grants a narrowly scoped exception.
- A project agent cannot search or infer another project's confidential memory.
- Conflicts are retained and surfaced; confirmed owner decisions are not silently overwritten.

## 8. Independent review

The Reviewer is not the same execution identity merely renamed. It receives the task contract, acceptance criteria, output reference and redacted evidence, not unrestricted secrets or unrelated project memory.

Review results record reviewer role/version, provider/evaluator identity, independence level, findings and evidence references. If the reviewer is unavailable or not independent, report that limitation and escalate or apply the deterministic policy; do not silently mark the work independently verified. Critical, security-sensitive and external-effect tasks require stronger review gates.

## 9. Persistence, recovery and backup

- Keep the existing SQLite-backed durable task and domain stores; do not create a second queue/database to solve a coordination problem.
- Use transactional state transitions, schema migrations, integrity checks and bounded recovery.
- Database encryption at rest must use a reviewed platform/library mechanism with protected keys. SQLCipher is an implementation candidate, not a claim that the dependency is already installed; verify compatibility, migration, backup and restore before adoption.
- BackupArchive remains the product backup/restore boundary. Do not replace it with Restic/Borg or another backup engine without a measured prototype and owner-approved migration decision.
- Backups must be versioned, authenticated/encrypted, integrity-checked and restored into a safe staging/validation path before replacing live state.
- Device-bound private keys and credentials are not copied into backups. Restore/reinstall creates a new installation identity and requires re-enrollment.
- A failed restore must leave the prior live state intact. Test wrong passphrase, corruption, truncation, incompatible schema, interrupted restore and rollback.
- Recovery must distinguish safe reads, retryable idempotent work, owner-review-required uncertain effects and terminal states.

## 10. TraceService and OpenTelemetry

Keep two complementary layers:

**TraceService / TraceRepository** is the durable product-domain audit trail. It records task lifecycle, owner approvals, policy decisions, memory changes, review outcomes, security events, backup/restore outcomes and result/ACK correlation. It is queryable by authorized project/task context and remains available for recovery, dispute resolution and owner-visible history.

**OpenTelemetry** is additive technical observability. It exports bounded spans, metrics and operational logs for latency, queue depth, worker saturation, retries, transport failures and provider timings. It is not the system of record and must not replace TraceService.

Correlation IDs link the two layers without copying sensitive payloads. Telemetry is redacted, sampled and retention-bounded. Prompts, credentials, raw provider responses, private files and restricted memory are never exported as span attributes. OTel exporter failure must not prevent local Core execution or durable TraceService append. TraceService append failure on a security/authority transition must be handled according to the durable transaction policy, not silently ignored.

## 11. GitHub: development plane, not runtime coordinator

GitHub remains useful and is not removed:

- source code, branches, commits, PRs and reviews;
- Issues, milestones and project planning;
- CI, security scanning, releases and signed build artifacts;
- non-real-time, owner-approved configuration/documentation metadata;
- branch-scoped coding operations initiated through an authorized Core workflow.

GitHub CAS/file SHA preconditions may protect repository writes against stale content. They are not runtime leases, global leadership election, task claims, heartbeats, Inbox/Outbox, fencing or task ACK. No running Core may depend on GitHub API availability to continue already-authorized local work. GitHub outage may delay development-plane operations but must not stop safe local execution.

The approved boundary is recorded in [ADR-0001](adr/ADR-0001-runtime-coordination-local-core-not-github.md). Historical GitHub election schemas/docs are retained for traceability and marked inactive; they are not production runtime instructions.

## 12. Language and platform boundaries

- Kotlin remains the implementation language for the Android Core and existing Kotlin/JVM components. Do not rewrite the working Core to Rust solely for language preference.
- The current Windows Desktop Core/Hub remains on its existing .NET 8/C# architecture. This document does not create a shared Kotlin Android/Windows database or mandate a Desktop rewrite.
- Swift remains the native iOS implementation language; C# remains the Windows Agent/Hub implementation language.
- Cross-platform interoperability is through versioned, language-neutral contracts and shared golden fixtures, not shared mutable process memory.
- Rust or Tauri may be evaluated for a separately bounded component only after a concrete requirement, prototype, maintenance/packaging comparison and owner-approved ADR. They are not current platform mandates.

## 13. Parallel work and integration mechanism

The 11 workstreams may progress in parallel, but shared authority and integration remain serialized:

- Each task has one accountable branch/PR owner and explicit dependencies.
- Assign a single writer for shared contracts, schema versions, Core authority files and cross-platform fixtures.
- Independent work uses separate branches; do not have agents concurrently edit the same file.
- One PR = one independently testable outcome; target no more than 400 changed lines, split larger work into dependency-ordered PRs.
- A workstream may report implementation complete only after exact-head tests and its acceptance evidence pass.
- Integrate to main only after independent review and required CI. After merge, verify the new main SHA and post-merge CI.
- Update the execution ledger from actual merged commits and evidence, never from planned steps or documentation changes alone.

## 14. Release gates

Do not enable cross-Core LIVE execution based only on unit tests or a successful build. Required evidence includes:

- deterministic task state and migration tests;
- process-death/fault-injection at every durable boundary;
- stale owner-generation, fencing, replay and revoked-peer rejection;
- duplicate/reordered delivery and exact ACK correlation;
- 10-minute network partition and safe reconnect;
- backup corruption and restore rollback;
- independent reviewer and project-memory isolation tests;
- physical Android↔Windows pairing, task transfer and recovery acceptance;
- confirmation that GitHub outage does not interrupt safe local Core work.

Until the relevant gates pass, keep the feature disabled or in DRY_RUN and report the remaining limitation plainly.

## 15. Source-of-truth documents

- [Core foundation specification](GLAFIRA_CORE_FOUNDATION_SPEC.md)
- [Dual-Core ownership and failover contract](contracts/DUAL_CORE_OWNERSHIP_AND_FAILOVER.md)
- [GitHub runtime coordination boundary](adr/ADR-0001-runtime-coordination-local-core-not-github.md)
- [ADR index](adr/README.md)
- [Coding standards](CODING_STANDARDS.md)
- [Repository administration](REPOSITORY_ADMIN_SETUP.md)
- [Implementation roadmap](IMPLEMENTATION_ROADMAP.md)
