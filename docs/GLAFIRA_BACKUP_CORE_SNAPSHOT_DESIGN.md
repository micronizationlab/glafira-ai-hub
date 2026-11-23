# GLAFIRA Backup & Recovery: Core-Aware Snapshot Design

## Status and priority

This document refines the existing Backup & Recovery design for the persistent GLAFIRA Core task queue. Backup & Recovery is Priority 2, immediately after Core correctness and execution safety. It must not weaken Core authorization, task idempotency, or Bridge pairing controls.

This is an implementation plan, not a claim that the described snapshot/restore flow is already implemented.

## Problem found in the current archive

The current `BackupArchive` implementation collects files only from `Context.filesDir` and separately exports a small allowlist of preferences. Android SQLite databases live under `Context.databasePath` / the app's `databases/` directory, not under `filesDir`. The persistent Core task queue therefore is not guaranteed to be included in the existing archive. A backup that omits the task database cannot claim to preserve queued, waiting, retry-scheduled, completed, and failed task records.

The current restore flow also applies files one at a time and deletes files absent from the archive. Its safety snapshot/rollback is useful protection, but it is not an atomic whole-app restore and must not be treated as one.

## Snapshot coverage

The versioned manifest must classify each data domain as included, excluded, or unavailable:

- Core task queue SQLite database, including task metadata and encrypted payload ciphertext.
- SQLite WAL/SHM state or a verified checkpointed database snapshot; never copy a live database file without a consistency protocol.
- Project metadata, project files, knowledge documents, and approved project memory.
- Conversation history and attachment metadata/files according to owner policy.
- Role definitions, task/automation definitions, schedules, and owner policy.
- Provider/model IDs and non-secret integration configuration.
- Explicitly selected safe preferences.
- Audit and recovery metadata needed to explain task state after restore.

Credentials, API keys, OAuth refresh tokens, passwords, Bridge device secrets, and Android Keystore private keys remain excluded by default. The archive must state that device-bound keys are not portable and integrations may require re-pairing or reauthorization on a new device.

## Consistent Core snapshot protocol

A snapshot must be coordinated with the single Core execution path:

1. Acquire a process-wide backup/snapshot lease shared by interactive execution, foreground runtime, WorkManager, and Bridge dispatch.
2. Stop claiming new tasks. Let a currently executing operation reach a safe checkpoint or finish within a bounded timeout. Do not kill an operation after an external side effect may have started.
3. Persist task transitions and the idempotency/operation journal. Any ambiguous external side effect remains in a recoverable/owner-review state; never blindly replay it.
4. Checkpoint SQLite WAL and obtain a consistent database snapshot under the same queue/database coordination boundary. Include all required SQLite state if a checkpoint cannot be completed.
5. Freeze the snapshot file set and manifest before streaming into the encrypted archive.
6. Resume Core claiming only after the snapshot has been sealed or the operation has failed safely.

The snapshot lease must be crash-safe: after process death, Core must be able to determine that no live snapshot owner remains and recover without leaving the queue permanently paused.

## Archive integrity and encryption

- Keep the existing versioned archive envelope but extend the manifest with app version, schema versions, data domains, relative paths, sizes, SHA-256 digests, and explicit exclusions.
- Compress before authenticated encryption.
- Authenticate the manifest and every archive payload through the archive's authenticated-encryption boundary; a standalone hash is not authenticity.
- Enforce archive size, entry count, decompressed-size, path traversal, duplicate path, and symlink limits before applying any data.
- Verify the entire archive locally before upload and verify a read-back copy before rotating a remote backup.
- Never place a recovery passphrase beside the archive or in logs. Never export Android Keystore private keys or Bridge credentials as ordinary files.

## Restore and recovery protocol

Restore is an owner-initiated maintenance operation:

1. Pause Core dispatch, Bridge action admission, and scheduled backup/update workers. Keep the owner informed of the paused state.
2. Verify the archive authentication tag, manifest, every file digest, supported format/schema versions, and required data domains before modifying live state.
3. Create and verify a fresh safety snapshot of the current state.
4. Extract to a private staging directory. Reject duplicate paths, path traversal, unexpected files, oversized entries, missing database files, and incompatible schemas.
5. Validate the staged SQLite database read-only and run integrity checks. Confirm that the queue schema and encrypted payload records can be read without attempting to decrypt task payloads using unavailable device keys.
6. Apply the staged state using a recoverable commit protocol. Prefer directory/database file swaps with durable journal markers over per-file mutation. Preserve the safety snapshot until post-restore health checks pass.
7. Run schema migrations only after compatibility checks and only with a verified pre-migration safety snapshot.
8. Run Core health checks: queue can open, task states parse, idempotency constraints hold, no task is silently converted into an executable state, and owner approval grants remain invalid unless their original trusted storage is available.
9. Resume Core only after success. On any failure, restore the safety snapshot or remain safely paused with a clear recovery-required state; never claim rollback succeeded unless verified.
10. Require owner reauthorization/re-pairing for excluded credentials and device-bound secrets.

## Task replay and side-effect safety

- Restore must preserve task states exactly; it must not convert `RUNNING`, `WAITING_FOR_OWNER`, or ambiguous tasks into `QUEUED` automatically.
- A restored `RUNNING` task requires operation-journal reconciliation before retry.
- Preserve project-scoped idempotency keys and uniqueness constraints.
- Expired owner approval grants must not be revived by restoring an older archive. Approval storage needs trusted expiry/revocation checks independent of archive time.
- External writes, payments, publishing, destructive actions, and Bridge dispatch remain blocked until owner authorization is re-established.

## Implementation sequence

### BR-1 — Inventory and snapshot boundary
- Identify every persistent data root and database used by the current app and Core.
- Introduce a Core-owned snapshot lease and a queue pause/checkpoint interface.
- Add manifest coverage tests that fail if a required data root is omitted.

### BR-2 — Consistent archive creation
- Add the Core SQLite database to the archive through the snapshot boundary, not by an uncoordinated file copy.
- Include required WAL/SHM or produce a verified checkpointed snapshot.
- Add archive round-trip tests for queued, waiting, retry, succeeded, and failed tasks.

### BR-3 — Staged restore and rollback
- Validate database integrity and schema compatibility before mutation.
- Implement journaled restore activation and verified rollback.
- Test interrupted restore at every mutation boundary.

### BR-4 — New-device recovery and operations
- Test restore into a clean app profile/device using only the documented recovery secret and owner sign-ins.
- Verify that missing Keystore credentials do not result in silent task execution or false success.
- Add owner-facing status, restore preview, reauthorization, and recovery-required states.

## Acceptance criteria

- A verified backup includes the Core task database and reports this explicitly in its manifest.
- A backup taken during task activity is a consistent snapshot; no partial SQLite state is accepted.
- Restore preserves task states and idempotency keys and does not replay uncertain external side effects.
- Wrong passphrase, corrupt archive, missing database, incompatible schema, and path traversal are rejected before live data changes.
- Interrupted restore either completes or returns to a verified safety snapshot; no partial restore is reported as successful.
- A clean-profile restore can open the queue and pass SQLite integrity and Core health checks.
- Device-bound credentials remain excluded and are reauthorized/re-paired after recovery.
- Background backup remains best-effort under Android WorkManager constraints; no exact-time or 24/7 guarantee is claimed.
