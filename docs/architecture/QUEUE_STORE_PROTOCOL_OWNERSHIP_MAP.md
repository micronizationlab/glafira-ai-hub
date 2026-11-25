# GLAFIRA queue, store and protocol ownership map

**Status:** initial evidence-backed inventory; not yet the final cross-platform authority register.  
**Sprint:** #634 · **Work item:** #635 · **WBS:** 1.1.2, 1.3.1, 1.4.1, 1.7.1, 1.11.1  
**Source snapshot inspected:** Main code links below resolve to commit `5baf5d617d454c1f283cef82ff9a2cfd701df254` (2026-11-25).  
**Rule:** unknown ownership is explicitly marked UNKNOWN; do not infer it from a class name.

## Verified inventory

| Component | Owner / purpose | Durable source of truth | Protocol / ACK | Status and evidence |
|---|---|---|---|---|
| Android Core task queue | Android Core runtime; tasks owned by this Core | SQLite database opened by [GlafiraTaskStore](https://github.com/micronizationlab/glafira-ai-hub/blob/5baf5d617d454c1f283cef82ff9a2cfd701df254/app/src/main/java/com/micronizationlab/glafira/GlafiraTaskStore.kt). The store creates task, history, result, dependency, plan, ownership, live-run, Trace and outbox schemas. | Local task state, idempotency key, owner generation/state version and execution fencing. | VERIFIED for Android local queue and schema boundary. |
| Android Core identity/runtime | Application-scoped [GlafiraCoreRuntimeOwner](https://github.com/micronizationlab/glafira-ai-hub/blob/5baf5d617d454c1f283cef82ff9a2cfd701df254/app/src/main/java/com/micronizationlab/glafira/GlafiraCoreRuntimeOwner.kt) | Installation identity + canonical TaskStore. Installation identity is not human/account owner identity. | Core authorizer, grants, live-run gate and executor. | VERIFIED. Do not conflate installation identity with account ownership. |
| Android domain Trace | Core domain audit; task/policy/approval lifecycle events | [GlafiraCoreTraceStore](https://github.com/micronizationlab/glafira-ai-hub/blob/5baf5d617d454c1f283cef82ff9a2cfd701df254/app/src/main/java/com/micronizationlab/glafira/GlafiraCoreTraceStore.kt) shares TaskStore DB. Append-only API; can append inside caller-owned SQLite transaction. | Domain event IDs, project/task scope, redaction and append-only guards. | VERIFIED. Keep as domain audit; OpenTelemetry is a separate technical layer. |
| Android Core event outbox | Source Core's local durable signed-event journal | [GlafiraCoreOutboxSchema](https://github.com/micronizationlab/glafira-ai-hub/blob/5baf5d617d454c1f283cef82ff9a2cfd701df254/app/src/main/java/com/micronizationlab/glafira/GlafiraCoreOutboxSchema.kt) and [GlafiraCoreOutboxStore](https://github.com/micronizationlab/glafira-ai-hub/blob/5baf5d617d454c1f283cef82ff9a2cfd701df254/app/src/main/java/com/micronizationlab/glafira/GlafiraCoreOutboxStore.kt), same TaskStore database. | Encrypted payload + signature are supplied by caller; store persists recipient, key epoch, generation, digest, sequence and ACK binding fields. Store comments explicitly leave crypto, peer verification, transport and ACK external. | PARTIAL. Schema and append primitive exist; do not call this complete delivery. #266 remains open and blocked on trusted recipient authority #366. |
| Android ownership receiver | Receiver-side owner-generation transfer | [PR #627](https://github.com/micronizationlab/glafira-ai-hub/pull/627), exact head `08edface22f5f8013b8cb1ca5c6dec5d367a6166`; not yet merged at inventory time. | Signature/trust checks, one-use owner session, SQLite CAS, anti-replay receipt and Trace append. No sender transport, payload encryption, Inbox/ACK or end-to-end pairing. | PR-only / CI green; review and merge gate remain open. Cross-Core payload delivery stays disabled. |
| Desktop worker task queue | Desktop Bridge worker's local execution journal; separate from Android Core DB | [DesktopTaskQueue](https://github.com/micronizationlab/glafira-ai-hub/blob/5baf5d617d454c1f283cef82ff9a2cfd701df254/desktop-bridge/src/main/kotlin/com/micronizationlab/glafira/desktop/DesktopTaskQueue.kt) persists records to a local JSON file when configured, or in memory for tests/default construction. | Local states, task ID/idempotency, lease ID and fencing token; interrupted in-flight work fails closed. | VERIFIED as a distinct worker-local queue. Not evidence of a duplicate Android Core database. |
| Desktop worker result delivery | Desktop worker's durable terminal record doubles as pending result event | [DesktopResultOutbox](https://github.com/micronizationlab/glafira-ai-hub/blob/5baf5d617d454c1f283cef82ff9a2cfd701df254/desktop-bridge/src/main/kotlin/com/micronizationlab/glafira/desktop/DesktopResultOutbox.kt) uses the terminal queue record and marks it acknowledged only after remote commit acknowledgement. | Retry delivery of terminal record; no handler replay. | VERIFIED for this worker path. Reconcile with future Windows Desktop Hub ownership before merging alternate outbox logic. |
| Legacy Desktop Bridge worker | Compatibility pull worker | [DesktopBridgeTaskWorker](https://github.com/micronizationlab/glafira-ai-hub/blob/5baf5d617d454c1f283cef82ff9a2cfd701df254/desktop-bridge/src/main/kotlin/com/micronizationlab/glafira/desktop/DesktopBridgeTaskWorker.kt) describes itself as legacy Android-hosted Bridge v1 pull worker; comment says production architecture makes Windows Desktop Hub the authoritative TLS host. | Bridge leases/fencing and remote result ACK. | TRANSITIONAL. Confirm the current Windows Hub runtime call path and retirement criteria. |
| Backup & Recovery | Android Core backup coordinator | [GlafiraCoreBackupCoordinator](https://github.com/micronizationlab/glafira-ai-hub/blob/5baf5d617d454c1f283cef82ff9a2cfd701df254/app/src/main/java/com/micronizationlab/glafira/GlafiraCoreBackupCoordinator.kt) uses the runtime TaskStore and maintenance gate; [BackupArchive](https://github.com/micronizationlab/glafira-ai-hub/blob/5baf5d617d454c1f283cef82ff9a2cfd701df254/app/src/main/java/com/micronizationlab/glafira/BackupArchive.kt) is the authenticated outer archive. | Versioned logical portable snapshot; verification, safety snapshot and rollback. Device-local ownership, approvals, leases, live-run and outbox authority are deliberately excluded. | VERIFIED architecture; clean-device/physical-device acceptance remains open in #25. Do not replace current format in this sprint. |
| Bridge transport / admission | Transport and authenticated admission, not task authority | Android Bridge host/client and Windows Hub paths are being inventoried under #22/#26. | Versioned envelope, TLS pinning, owner-approved enrollment, replay checks, typed dispatch. | PARTIAL; complete path and Desktop parity require code-path map. |
| GitHub API / CAS | Development and repository operations | Core has explicit GitHub task handlers in RuntimeOwner, but this does not make GitHub the Core task queue. | API requests must remain typed, scoped and owner-approved. | Runtime CAS call-site inventory remains OPEN in #640; no assumption that every GitHub API call is CAS. |

## Authority boundaries

1. **Android Core TaskStore** is authoritative only for tasks owned by that Android Core installation.
2. **Windows Desktop Core** must be authoritative for Desktop-originated tasks. The Hub/worker queue is a local execution/transport journal; exact ownership and handoff boundaries must be verified against the current Windows Hub runtime before any consolidation.
3. **Bridge** is authenticated transport/admission. It is not a shared database, task owner or global coordinator.
4. **Core outbox** and **Desktop worker result outbox** are different mechanisms with different owners and persistence. Do not merge or delete either by name alone.
5. **TraceService/TraceStore** remains the domain audit source. OpenTelemetry must not become a substitute or a prerequisite for task commits.
6. **BackupArchive + portable Core snapshot** remains the production restore format until a measured prototype, migration design and rollback plan are approved.

## Explicit gaps to close

- Identify every Windows Desktop Core store, Hub lease journal, worker queue and result journal, with exact call sites and writer ownership.
- Map sender-side task assignment, receiver Inbox, receiver commit receipt, exact ACK and retry/reconciliation for both Core peers.
- Confirm whether Android `core_outbox` append is transactionally coupled to the corresponding task/result producer in all required paths.
- Inventory protocol schema versions, canonical signing bytes, encryption/key agreement, replay retention and revocation semantics.
- Map backup inclusion/exclusion for every Core table and every Desktop store.
- Search all runtime GitHub API/CAS call sites and classify as development-only, read-only connector, or prohibited runtime coordination.

## Review gate

No queue/store removal, database consolidation, protocol rewrite or data migration is permitted from this initial map alone. Update each row with exact source path, symbol, test and commit before making an architecture decision.

## Cross-cutting integration finding discovered during PR #627 review

- PR #627 creates `core_ownership_transfer_receipts` in the canonical TaskStore database.
- Main's `GlafiraPortableCoreSnapshot.exportTo()` rejects every `core_%` table not explicitly classified in `TABLES` or `NON_PORTABLE`. This receipt table is not yet in either list on Main.
- Therefore backup export fails after the ownership-transfer runtime initializes unless the receipt table is explicitly classified. The table is device-local anti-replay evidence and must be added to `NON_PORTABLE`, not exported to another Core.
- Tracked as blocker #644 and linked from PR #627. Required regression: initialize transfer store, create receipt, export/verify portable snapshot, prove receipt remains local and is not exported/restored.
- This is a concrete integration defect; it is not covered by the currently green PR #627 exact-head checks. PR #627 must not merge until fixed and revalidated.
