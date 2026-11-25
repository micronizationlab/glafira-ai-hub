# GLAFIRA Backup & Recovery: Core-Aware Snapshot Design

## Status and priority

Backup & Recovery is Priority 2, immediately after Core correctness and execution safety. This document is both an architecture contract and a status correction: the repository already contains a coordinated Core backup/restore path. The requirements below distinguish existing behavior from remaining acceptance work; they are not a claim that every recovery scenario is complete.

## Current implementation verified in main

- `BackupArchive` creates an authenticated AES-GCM archive by walking `Context.filesDir` and collecting explicitly allowed preferences. It does not directly include the Android SQLite database stored by `GlafiraTaskStore` under `Context.getDatabasePath("glafira_core_tasks.db")`.
- `GlafiraCoreBackupCoordinator.create` supplies the missing Core integration. It acquires `GlafiraCoreMaintenanceGate.withExclusive`, pauses pending delivery, acquires the file-maintenance gate and synchronizes on the task store. It exports a logical portable Core snapshot to `.glafira-core-snapshot/core-portable-v2.json` under `filesDir`, then seals that file inside the encrypted archive.
- `GlafiraPortableCoreSnapshot` exports a transactionally consistent allow-list of Core tables, validates the complete schema and relationships, and converts encrypted task/result payloads to logical portable data before the outer archive encryption. On restore, payloads are encrypted again for the destination device. Source-device Android Keystore keys are not exported.
- The portable snapshot intentionally excludes authority/device-local state such as owner approvals, desktop leases/nodes, task ownership and live-run records. Restore does not revive those grants or leases.
- `GlafiraTaskStore.exportConsistentSnapshot` separately performs a FULL WAL checkpoint, closes the database, verifies the WAL is empty, copies the database and validates it. This is used for the local rollback snapshot, not as the portable archive format.
- Restore verifies the archive and portable Core snapshot before mutating live state, prepares a safety snapshot, applies the archive and portable Core data, and uses a journaled rollback path on failure. Core table replacement occurs in a database transaction. Non-terminal tasks are moved to `waiting_for_owner`; Dry Run previews return to `PENDING`. Uncertain external effects are not automatically replayed.
- Instrumentation coverage already exercises wrong passphrases, malformed portable snapshots, task/result restoration, append-only Trace restoration, provider preference isolation and rollback after an injected restore failure.

## Snapshot coverage and invariants

The versioned portable manifest must explicitly classify each data domain as included, excluded or unavailable:

- Core tasks, task plans, dependencies, task history and portable task results.
- Append-only Core Trace events and their safe metadata.
- Dry Run preview metadata, with review status reset on restore.
- Project metadata/files, knowledge documents, conversations and attachments according to owner policy.
- Role definitions, task/automation definitions, schedules and non-secret provider/model configuration.
- Explicitly selected safe preferences.

Credentials, API keys, OAuth refresh tokens, passwords, Bridge device secrets, owner approval grants, live leases and Android Keystore private keys remain excluded by default. The owner must be told which integrations require reauthorization or re-pairing after recovery.

## Consistent snapshot and restore protocol

1. Use the shared Core maintenance gate so no task handler can run concurrently with snapshot/restore. Pause pending delivery and file maintenance under the same exclusive boundary.
2. Export only an explicit table/column allow-list in a SQLite transaction. Reject unknown Core tables until the portable schema is deliberately versioned.
3. Validate row limits, identifiers, project relationships, idempotency uniqueness, Trace references and schema version before sealing the archive.
4. Encrypt the archive before it leaves the private app sandbox. Keep the temporary logical snapshot in private app storage and remove it in a `finally` path.
5. Before restore, authenticate the archive and validate the entire portable snapshot without mutating live data.
6. Create and verify a local safety snapshot. Persist a recovery journal before replacing any state.
7. Restore Core rows transactionally; reset non-terminal work to owner review and invalidate excluded authority/lease state. Never revive an expired approval from an older archive.
8. Verify SQLite integrity, schema, task states, idempotency constraints, Trace guards and post-restore Core health before clearing the journal.
9. On failure, restore the safety snapshot or remain in an explicit recovery-required state. Do not claim rollback succeeded unless the restored database and archive are verified.
10. Resume dispatch only after successful verification and owner reauthorization where required.

## Remaining acceptance work

- Add explicit tests for process death at every temporary-snapshot, archive, journal and database-swap boundary; verify stale temporary snapshots are safely detected and removed.
- Verify clean-profile and new-device recovery using only the archive passphrase and owner reauthorization; confirm payloads are re-encrypted with the destination Keystore key.
- Add tests for unknown/new Core tables, unsupported portable schema versions, oversized snapshots, corrupt Trace relationships and idempotency collisions.
- Verify every non-portable table is deliberately excluded and cannot restore approval, ownership, lease or live-run authority.
- Exercise recovery on physical Android devices and document Keystore/vendor-specific behavior. WorkManager remains best-effort and must not be described as exact-time or 24/7 execution.
- Reconcile the portable snapshot schema with future Core memory/Trace stores before adding those stores to the archive; do not assume that a raw SQLite copy is a portable backup.

## Implementation sequence

### BR-1 — Inventory and portable schema
- Maintain a complete inventory of Core tables and file roots.
- Require an explicit portable allow-list and fail closed when an unknown Core table appears.
- Add schema migration and compatibility fixtures.

### BR-2 — Consistent archive creation
- Keep the Core maintenance gate and pending-delivery pause around the whole snapshot.
- Verify portable payload export, archive authentication and temporary-file cleanup.
- Test concurrent execution attempts are blocked until the archive is sealed.

### BR-3 — Staged restore and rollback
- Verify the archive and portable snapshot before mutation.
- Test every journal/rollback boundary, including process interruption.
- Confirm task states, idempotency keys, Trace and non-portable authority are restored or invalidated exactly as specified.

### BR-4 — New-device recovery and operations
- Test restore to a clean profile/device with destination-key re-encryption.
- Add owner-facing restore preview, exclusions, reauthorization and recovery-required status.
- Preserve truthful Android background-execution limitations.

## Acceptance criteria

- The archive manifest explicitly identifies the portable Core snapshot and its schema version.
- A backup taken during task activity is consistent; no partial SQLite state is accepted.
- Restore preserves portable task history and idempotency while placing non-terminal work into owner review.
- Credentials, approvals, leases and device-bound secrets are not silently transferred or revived.
- Wrong passphrase, corrupt archive, malformed snapshot, missing required data, incompatible schema and path traversal are rejected before live data changes.
- Interrupted restore either completes or returns to a verified safety snapshot; no partial restore is reported as successful.
- A clean-profile restore passes SQLite integrity and Core health checks.

## Encrypted local rollback journal (2026-11-25)

The closed, integrity-checked Core database file is encrypted directly into the journal with an installation-local Android Keystore AES-GCM key; no additional plaintext rollback copy is written. The journal marker is versioned as `GLAFIRA_RESTORE_ROLLBACK_V3`; the recovery path still accepts the legacy V2 raw-database journal so an interrupted restore from an older build can finish.

Recovery decrypts and validates the database snapshot before applying the safety archive to live app files. The temporary decrypted recovery file is app-private and is removed in `finally`; if the process dies during recovery, the retained journal keeps Core blocked and the next recovery attempt removes stale decrypted files before retrying. If the journal marker has already been cleared, the startup status check also removes any orphaned decrypted file. If the Keystore key is missing or the ciphertext is tampered, recovery fails closed, does not regenerate the key, retains the journal, and leaves Core blocked for recovery. The managed-emulator tests cover encrypted journal creation, successful V3 recovery, legacy V2 recovery, stale plaintext cleanup and missing-key no-regeneration behavior.
