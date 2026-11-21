# GLAFIRA Backup & Recovery

## Requirement

Backup and recovery are mandatory core capabilities, not an optional add-on. The first implementation must work without a rented server or paid backend. Backups are created and encrypted on the Android device before upload.

## Data coverage

The backup manifest must explicitly classify:
- project metadata and settings;
- role definitions, instructions, skill packages and assignments;
- user-approved project memory and decision records;
- knowledge documents and source/citation metadata;
- chat history and selected attachments, subject to user settings;
- provider/model configuration (provider IDs and model IDs, not live credentials by default);
- integration configuration and non-secret metadata;
- automation definitions, approval policies and audit records;
- app data/schema version and restore migration metadata.

Do not silently omit a data category. The archive manifest lists included, excluded and unavailable categories.

## Secrets and key recovery

- Default behavior excludes API keys, OAuth refresh tokens, passwords and vault secrets. After restore, integrations request reauthorization.
- If encrypted secret export is later supported, it must be a separate explicit opt-in, re-encrypted under a user-controlled backup passphrase/key, and clearly warned. Never rely on Android Keystore device keys as the only backup decryption key.
- Encrypt locally before upload using authenticated encryption (for example AES-256-GCM or an audited equivalent). Derive/wrap the backup key using a password-based KDF with a unique random salt and current recommended parameters. Store algorithm/version/KDF parameters in the archive header.
- The recovery secret must be shown to and safely stored by the owner. Do not email the passphrase or store it beside the backup. If the owner loses the recovery secret and no recovery mechanism exists, the encrypted archive may be unrecoverable; state this plainly.
- Use cryptographically secure random nonces and never reuse a nonce with the same AES-GCM key.

## Archive and integrity

- Use a versioned archive format with manifest, app/schema version, creation time, included categories, file sizes, content hashes and encryption metadata.
- Compress before encryption.
- Verify the local archive can be parsed and authenticated before upload.
- Use authenticated encryption and a manifest checksum/hash. A plain checksum alone is not an authenticity mechanism.
- Use a temporary remote object/file for upload; only replace the previous current backup after upload completes and remote metadata/size can be verified.
- The selected policy is one current encrypted backup per user destination. Upload a new temporary copy, read it back and verify it, then delete the older current copy. Never delete the last known-good copy before the replacement is verified. If the provider refuses deletion, keep the new verified copy and show a cleanup warning.
- Avoid retaining temporary duplicate archives after successful verification.

## Free destinations (no rented server)

Implement destinations as independent adapters:
1. Google Drive: user-authorized OAuth; use the Drive API and an app-specific folder or appDataFolder where appropriate. Replace the managed backup object rather than creating an unbounded sequence. Respect API quotas and available account storage.
2. Yandex Disk: user-authorized OAuth and official Disk REST API/WebDAV as supported by the selected flow. Use overwrite/replace semantics and verify the remote result. Respect account quota and API limits.
3. Email: optional fallback only for small archives. Prefer a supported OAuth mail API; never ask for or store an email account password. Enforce attachment-size limits and show a clear failure when the archive is too large. Email should also be used for status notifications where configured.
4. Local export: owner-triggered export to device storage, USB/PC or Android document picker. Local-only copies are not sufficient protection against loss of the device.

Free-tier capacities, provider quotas and API terms can change; query/display current availability where possible and never promise permanent free storage.

## Scheduling and retry

- Use Android WorkManager for periodic background backup. Periodic work is best-effort and may run later due to Doze, battery optimization, connectivity and OS scheduling; do not promise execution at an exact clock time.
- Default cadence: daily, with configurable preferred time/window, Wi-Fi-only and charging constraints.
- Run a backup after important milestones or before schema migrations when appropriate, without creating unlimited copies.
- Retry transient network failures with backoff. Distinguish authentication expiry, quota exceeded, no space, network failure, archive failure and verification failure.
- Notify the owner of success, last verified backup time, size, destination and actionable failure. If no cloud destination is configured, warn clearly that only local data is protected.
- Provide manual Back up now and Verify backup actions.

## Restore

- Restore is a deliberate owner-initiated workflow. Show archive date, app/schema version, included data, excluded secrets and source before changing local state.
- Verify authentication tag, manifest, hashes and compatibility before applying anything.
- Restore into a staging area, validate database and files, then atomically switch to the restored state where feasible.
- Create a safety snapshot of current local data before restore.
- Support migration from older archive versions and reject unsupported/corrupt archives without overwriting current data.
- After restore, request provider/API reauthorization for excluded secrets and run a health check.
- Include an explicit full-device/new-device recovery test in release acceptance.

## UI

- Backup enabled/status.
- Destination(s) and connected/disconnected state.
- Last attempt and last verified success.
- Archive size and included/excluded categories.
- Daily schedule and optional Wi-Fi/charging constraints.
- Manual backup, verify and restore.
- Recovery-key/passphrase setup and clear recovery warning.
- Notifications and troubleshooting details.

## Implementation sequence

1. Define backup manifest, archive versioning, crypto wrapper and storage interfaces.
2. Implemented initial local archive creation, verification, manual export and owner-confirmed restore.
3. Implemented daily WorkManager scheduling and an Android Storage Access Framework folder adapter. The owner can select a Google Drive or Yandex Disk folder when that provider is exposed by an installed Android document provider; this is not a direct provider REST/OAuth connector.
4. Verify upload by reading the remote file back and validating its authenticated archive before rotating the current backup.
5. Keep one current backup only. Replace it safely using upload → read-back verification → delete old copy. If deletion fails, retain both and show a warning rather than risking data loss.
6. Add direct Google Drive/Yandex Disk OAuth adapters only if the SAF provider path proves insufficient; add email fallback only for small archives using OAuth, never account passwords.
7. Complete clean-device restore acceptance tests and test failure cases including expired folder permission, quota, interrupted upload and wrong recovery passphrase.

## Release acceptance criteria

- Backup can be created offline and verified locally.
- A corrupt or incomplete archive never replaces a known-good remote backup.
- No secret is stored in plaintext in the archive or logs.
- A new device can restore the archive using only the documented recovery secret and required owner sign-ins.
- Restore failure leaves current data intact.
- The UI accurately reports last verified backup, not merely last attempted upload.
- The app explains that Android background scheduling is best-effort.
- At least one end-to-end restore test passes on a clean device/profile.


## Restore semantics
Restore applies an exact snapshot of app-private files: it writes verified archive entries and removes app-private files absent from the selected archive. It first creates an encrypted safety snapshot and attempts rollback if applying the selected archive fails. Real-device tests must verify both replacement and removal behavior before release.

## Optional email fallback
Email delivery is for small encrypted archives only and is not the primary destination. The user chooses the recipient address and explicitly connects a mail account with OAuth. Apply a strict attachment-size cap, verify the archive locally before sending, and never fall back to sending an unencrypted archive. If the archive exceeds the cap or the provider rejects it, keep the last verified cloud/local copy and show a clear warning; do not silently omit the backup.


## Core database consistency and archive authentication (2026-11-20)

All manual exports, cloud backups and restores must pass through `GlafiraCoreBackupCoordinator`, not call `BackupArchive` directly.

- The coordinator enters `GlafiraCoreMaintenanceGate.withExclusive`, pauses pending result delivery, and takes the file-maintenance gate before exporting or restoring Core data. The portable archive contains a versioned logical snapshot (`GlafiraPortableCoreSnapshot.PATH`, currently `core-portable-v2.json`), not a raw copy of the source SQLite database.
- The logical snapshot uses schema version 3 and exports an explicit allowlist of Core task, plan, dependency, history, preview, result and metadata-only `core_trace_events` tables. The archive entry path remains `core-portable-v2.json` for compatibility with existing archive readers; the embedded schema version is authoritative. Version-2 logical snapshots are upgraded during validation by deriving redacted task-lifecycle trace rows from the existing task-history table. It rejects unknown `core_*` tables and validates table/column sets, row bounds, identities, idempotency keys, project relationships, task states and payload sizes. Trace rows are append-only, carry no task payload/prompt/provider-response fields, require redaction markers and prohibit secret storage. Encrypted payloads/results are decrypted only inside the app process for inclusion in the already-encrypted archive; they are re-encrypted with the destination device's key during restore. Source-device leases, approvals, live-run authority and other non-portable state are excluded.
- Before applying a restore, the coordinator verifies the authenticated archive and required portable Core entry, parses and validates the snapshot, then creates a separate encrypted safety archive plus a raw SQLite safety database for local rollback. Restore imports the logical snapshot transactionally; non-terminal tasks return as `waiting_for_owner`, and previews return to `PENDING`. If restore fails, the safety archive and database are used to roll back files and SQLite. If rollback itself fails, the journal and safety material are retained and Core remains blocked for recovery.
- Decrypted staging remains under app-private `cacheDir`, never beside a user-selected file in Downloads/shared storage; the selected archive is copied with a strict size bound before verification and restore.
- AES-GCM verification drains the cipher stream to EOF after ZIP parsing so the authentication tag is actually checked. The manifest's app ID, file count, total bytes, unique paths, per-file size and SHA-256 values are checked before apply.
- The restore allowlist is explicit. Only approved non-secret preferences (primary provider and peer-consultation settings) are restored; API keys, OAuth tokens, device-bound Keystore material and arbitrary preference keys are not imported.
- DPAPI/Android Keystore keys are not portable backup material; users must re-authenticate/re-enter device-bound credentials after restoring to a new device.

The instrumentation suite covers Core snapshot inclusion, legacy archive rejection, task result recovery, truncated AES-GCM tag rejection, cross-application archive rejection and traversal-path rejection. No backup/restore feature is considered accepted until the managed-emulator suite passes and a real-device Google Drive round trip is checked.
