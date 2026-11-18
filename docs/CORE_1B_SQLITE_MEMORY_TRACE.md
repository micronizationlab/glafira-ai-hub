# Core-1B — SQLite Memory, Encryption and Trace

**Status:** implementation branch; acceptance is pending CI and Android runtime tests.  
**Depends on:** merged Core-1A contracts (PR #40).  
**Branch:** `core/core-1b-sqlite-memory-trace`.

## Delivered in this phase

### One Core SQLite database

- `GlafiraCoreDatabase` owns `glafira_core.db` under the app's private `filesDir`, so the existing file-based backup archive includes the database and its SQLite sidecar files.
- Memory records and Trace events share the same database and transaction boundary.
- WAL is enabled; SQLite secure-delete is requested.
- Trace rows are append-only at the SQL layer (UPDATE/DELETE triggers reject mutations).
- Future Core task-queue persistence must be migrated into this database instead of creating a parallel Core database.

### Encrypted memory

- Every memory payload is encrypted, not only CONFIDENTIAL/RESTRICTED records.
- AES-256-GCM uses a non-exportable Android Keystore key.
- Memory ID, schema version, scope, kind, classification, status, confidence, provenance basis and lifecycle timestamps are authenticated as AES-GCM additional data.
- Content, provenance, author/modifier IDs, supersession reference and tags are inside the encrypted payload.
- Only bounded filtering metadata (scope, scope ID, kind, classification, status, confidence and timestamps) remains queryable in SQLite; memory content, provenance, authorship and tags are ciphertext.
- Search decrypts only candidate rows that pass scope, status, expiry, classification and policy checks; there is no plaintext FTS index.
- Delete clears the payload and leaves a content-free DELETED tombstone plus an audit event.

### Access and policy

- Repository access contexts have internal constructors.
- `CoreAccessContextFactory` obtains the actor from `CoreIdentityProvider`, intersects requested memory scopes with a fixed actor-type ceiling, and checks policy before issuing a context.
- The repository rechecks policy for reads, writes and deletes. Agent-created records must carry matching actor provenance; only OWNER can confirm memory.
- Agents cannot obtain OWNER scope from the factory.
- The application still needs to connect a production `CoreIdentityProvider` to the authenticated owner/session boundary. Until that wiring exists, no autonomous agent should be given a repository access context.

### Trace

- Trace is appended to the same SQLite database.
- Sanitization is enforced at the repository append boundary, not only by the service.
- Only a fixed allow-list of metadata keys and simple token values is persisted. Unknown keys, free text, emails, phone-like values and recognized token patterns are dropped/redacted.
- Event append from a memory write occurs in the same SQLite transaction as the memory mutation.
- Reads are project-scoped through an internally issued `TraceAccessContext`.

## Explicit limitations / next acceptance work

- This PR does not wire the repository, identity provider or Trace service into the application runtime/UI.
- The SQLite schema is at version 1; future schema changes require additive migrations.
- Search is bounded metadata filtering plus in-process decrypted text/tag matching; it is not semantic search.
- Trace pagination cursor and retention controls are not yet implemented. Trace is append-only in this phase.
- Keystore keys are device-bound and are not included in backups. A restored database on another device cannot decrypt the original device's memory payloads; owner reauthorization/re-encryption is required.
- The database file and SQLite WAL/SHM sidecars are included by the existing `filesDir` backup walk. A coordinated checkpoint/close gate is still required before snapshot or restore while the live database is open.
- Android Keystore encryption/decryption, SQLite transaction atomicity, WAL recovery, secure-delete behavior and backup/restore interaction require emulator/device instrumentation tests. The current CI workflow runs JVM unit tests and APK compilation, not those device acceptance tests.
- `CoreIdentityProvider` must return identity from a verified app-owned session, never from agent/model-supplied fields.

## Acceptance criteria

1. CI unit tests and Android debug APK build pass.
2. Instrumentation test confirms stored payload bytes do not contain plaintext memory content.
3. Instrumentation test confirms AES-GCM tamper/AAD mismatch fails closed.
4. Instrumentation test confirms agent scope isolation and owner-only confirmation.
5. Instrumentation test confirms memory mutation and Trace append commit or roll back together.
6. Instrumentation test confirms trace UPDATE/DELETE are rejected and secret-like attributes are not persisted.
7. Backup/restore test verifies database file consistency and explicitly reports device-bound key limitations.
