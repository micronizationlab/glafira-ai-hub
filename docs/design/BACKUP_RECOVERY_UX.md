# GLAFIRA Backup & Recovery — Owner UX specification

**WBS:** `GLAFIRA-S095-D01` / `1.2.2.1.1`  
**Program mapping:** Step 95, partial — manual backup prerequisites, progress, verification and actionable errors  
**Status:** Design proposal; owner approval required  
**Visual tokens:** Pending issue #679. This document defines information architecture and interaction states, not final colors, typography, spacing or component measurements.

## 1. Design intent

Give the owner a calm, explicit view of what is protected, where the verified copy lives, what is happening now, and what recovery will change. Prefer clear status and reversible choices over decorative progress or reassuring but unverified success language.

Product identity follows the GLAFIRA direction: ICQ 2003 familiarity and the flower mark, refined with Apple/macOS clarity. Use semantic status names in this specification; visual treatment waits for the shared token proposal and owner approval.

### Non-negotiable safety rules

- Show **last verified backup**, not merely last attempted backup.
- Never claim a backup is safe until the local archive authenticates and the remote copy has been read back and verified.
- Never delete or replace the last known-good copy before a new copy is verified.
- Never expose, log, transmit beside the archive, or persist the recovery passphrase, key material, API credentials or vault secrets.
- Make excluded credentials and device-local authority visible. Restore requires provider reauthorization and peer re-pairing where applicable.
- Restore is an exact snapshot operation for app-private files: explain that files absent from the selected archive may be removed.
- If restore or rollback is interrupted, preserve the journal and show **Recovery required**. Do not silently resume normal Core dispatch.
- Android WorkManager scheduling is best-effort; never promise an exact execution time.
- A design state never authorizes a change to Core, archive schema, encryption, permissions, Bridge protocols or security boundaries.

## 2. Information architecture

**Settings → Backup & Recovery**

1. **Protection summary**
   - Current state: Not configured / Local only / Scheduled / Backing up / Verified / Attention required / Recovery required.
   - Last verified copy: date/time, destination and archive size, or “No verified backup yet”.
   - Last attempt and its outcome are separate from last verified success.
   - Next scheduled window, explicitly labelled “Best effort”.
2. **Destinations**
   - Selected destination and provider name.
   - Connection/write-access state and last successful verification.
   - Actions: Choose or change destination; Recheck access.
   - On Android, the current supported path is the system document picker and an installed document provider (for example, Drive or Yandex Disk when exposed by that provider). Do not imply that a direct OAuth/REST connector exists.
3. **Backup contents**
   - Included categories, excluded categories and unavailable categories from the archive manifest.
   - Explicit note: API keys, OAuth refresh tokens, passwords, vault secrets, Android Keystore keys, owner approvals, desktop leases and live authority are excluded by default.
4. **Backup controls**
   - Primary: Back up now.
   - Secondary: Verify latest copy.
   - Schedule: enabled/disabled, daily cadence and best-effort window; Wi-Fi/charging constraints where supported.
5. **Recovery**
   - Restore from a selected backup.
   - Explain the recovery passphrase requirement before opening the restore flow.
   - Explain reauthorization/re-pairing after restore.
6. **Activity and help**
   - Recent attempts, verified results and actionable failures.
   - No secrets, raw payloads or sensitive file paths in activity descriptions.

## 3. Main screen — wireframe hierarchy

```text
[GLAFIRA flower]  Backup & Recovery

[Protection state]
  Last verified: 24 Nov, 21:14 · Drive · 184 MB
  Last attempt: 25 Nov, 09:02 · Verification failed
  Next window: Daily · best effort

[Destination]
  Drive folder · Write access available
  [Change destination] [Recheck access]

[Backup contents]
  Included: projects, approved memory, chats, documents, Core task history
  Excluded: passwords, API keys, OAuth tokens, vault secrets, device keys
  [View full manifest]

[Actions]
  [Back up now]  [Verify latest copy]

[Schedule]
  Automatic backup [On/Off]
  Preferred window · Wi-Fi only · While charging

[Recovery]
  [Restore from backup]

[Recent activity]
  Verified / Failed / Cancelled entries with timestamps and next actions
```

The example values above are illustrative only. Runtime values must come from the actual manifest, destination and verified status.

## 4. Create backup flow

### 4.1 Preflight

Before work starts, show a short checklist and block only the actions whose prerequisites are missing:

- Destination selected and persistent write permission available.
- Recovery passphrase supplied for this operation; never prefill it from storage.
- Core maintenance/recovery gate available.
- Current backup state and the last known-good copy identified.
- Explain whether the operation is local export or remote upload.

If the destination permission has expired, do not clear the previously saved destination automatically. Show “Access expired” and offer Reconnect. If the owner cancels the picker, keep the existing destination unchanged.

### 4.2 Progress states

Use a named, ordered progress sequence; never compress it into an indeterminate spinner that looks complete:

1. Preparing a consistent snapshot.
2. Creating the encrypted archive.
3. Verifying the local archive.
4. Uploading a temporary copy (remote destination only).
5. Reading back and verifying the remote copy.
6. Replacing the current backup only after verification.
7. Cleaning up the previous copy (best effort).

The visible progress label must reflect the actual worker stage. If the implementation cannot report a stage, show “Working…” with no invented percentage.

### 4.3 Completion

- **Verified:** show destination, verified timestamp and archive size. Update “Last verified” only after the relevant local/remote verification succeeds.
- **Verified, cleanup pending:** the new copy is safe; the older copy could not be deleted. Explain that both copies remain and offer a cleanup retry. Never present this as a failed backup.
- **Local export verified:** distinguish it from an off-device backup and warn that loss of the device also loses the local copy.
- **Cancelled:** state which durable stage was reached, whether a verified copy remains, and whether a temporary item needs cleanup. Do not imply rollback if it was not verified.

## 5. Destination states

| State | Owner-facing message | Primary action |
|---|---|---|
| Not selected | No backup destination is configured | Choose destination |
| Connected | Destination can be written to | Back up now |
| Permission expired | Access to the selected folder is no longer available | Reconnect |
| Read-only | The provider did not grant persistent write access | Choose another folder |
| Unavailable/offline | Destination cannot currently be reached | Retry when available |
| Quota/storage limit | Destination reports insufficient capacity | Free space or choose another destination |
| Provider error | Provider did not confirm the operation | Retry; preserve last verified copy |

Never promise free capacity or permanent free-tier availability. Do not label a destination “connected” based only on a stored URI; confirm the required access when an operation starts.

## 6. Verify latest copy flow

1. Identify the exact archive and destination being verified.
2. Read the remote object back when the destination is remote.
3. Authenticate the archive and validate the manifest, hashes and compatibility.
4. Show Verified only after all checks pass.
5. On failure, preserve the last known-good copy and explain whether the failure is access, transport, authentication, corruption or compatibility.

A verification action is read-only with respect to the current backup. It must not rotate, replace or delete backup objects.

## 7. Restore flow

Restore is visually and verbally distinct from backup creation because it replaces current local state.

### 7.1 Select and inspect

The owner selects a backup file/provider item. Before asking for the passphrase or mutating local state, show a read-only archive summary:

- Archive creation time and source.
- Application and portable Core snapshot versions.
- Included, excluded and unavailable categories.
- Archive size and authentication/compatibility status, when known.
- Whether provider credentials, device keys, approvals, leases or peer bindings must be recreated.

Do not expose archive payloads or decrypted staging paths.

### 7.2 Explicit preflight confirmation

Use a two-part confirmation:

1. **What will be restored:** exact snapshot contents and included categories.
2. **What will change:** current app-private files absent from the selected archive may be removed; current local state is protected by a safety snapshot before apply; non-portable secrets and authority are not restored.

Require an explicit owner confirmation after this explanation. The final action label should name the consequence, for example “Restore this snapshot”, not a generic “Continue”.

### 7.3 Restore progress

Show distinct phases:

1. Reading selected archive.
2. Authenticating and validating archive/manifest.
3. Validating portable Core snapshot and compatibility.
4. Creating and verifying local safety snapshot.
5. Applying staged files and portable Core state.
6. Verifying database integrity and post-restore Core health.
7. Completing recovery and enabling normal operation.

Do not show “Restored” before the final health verification succeeds. If a phase is not observable, label it as an overall operation rather than inventing detailed progress.

### 7.4 Restore outcomes

- **Restored and verified:** show the archive date and categories restored; list the required provider sign-ins and peer re-pairing actions.
- **Rejected before mutation:** explain wrong passphrase, authentication failure, unsupported version, corrupt manifest or incompatible archive. State that current data was not changed only when the implementation confirms that.
- **Apply failed, rollback verified:** explain that the selected restore failed and the previous local state was restored and verified.
- **Rollback failed / interrupted restore:** show a blocking **Recovery required** state. Explain that Core remains paused, the recovery journal is retained, and the owner must complete recovery before normal work resumes. Do not offer “Ignore” or imply that restarting the app clears the condition.
- **Unknown result:** if the app cannot establish whether apply or rollback completed, preserve the recovery-required state and avoid a success/failure claim until reconciliation.

## 8. Recovery-required screen

This is a blocking state, not an ordinary red error banner.

- Title: **Recovery required**
- Explain that a previous restore was interrupted or could not be fully rolled back.
- Show only verified, non-sensitive facts: last known operation stage, whether a recovery journal is present, and whether Core is paused.
- Primary action: **Continue recovery** (only when the recovery coordinator reports it is safe).
- Secondary action: **View recovery details**.
- Do not expose raw database paths, key material, decrypted snapshots or stack traces.
- Do not allow ordinary backup, task dispatch or project mutation while the Core recovery gate is closed.
- If recovery cannot proceed, show an actionable support/export diagnostic path that is explicitly redacted and does not include secrets.

## 9. Errors and copy principles

Use a stable pattern: **what happened → what remains safe → what the owner can do next**.

Examples (copy proposals; localize before implementation):

- Destination permission expired: “GLAFIRA can’t write to this folder. Your last verified backup is unchanged. Reconnect the folder or choose another destination.”
- Upload interrupted: “The new backup was not verified. Your previous verified copy was kept. Check the connection and try again.”
- Remote verification failed: “The uploaded file could not be verified. It was not made the current backup. Retry or choose another destination.”
- Wrong passphrase: “This backup could not be authenticated. Your current data has not been changed.”
- Unsupported archive: “This backup was created by an incompatible version. Your current data has not been changed.”
- Rollback failed: “Recovery is incomplete. Core is paused to protect your data. Continue recovery before using GLAFIRA.”

Only use “current data has not been changed” after a pre-mutation failure has been established. Never claim a rollback succeeded without a verified result.

## 10. Accessibility and responsive behavior

- Every status has a text label; color and icon are supplementary only.
- Announce state changes and completed/failed operations to assistive technology.
- Progress exposes the current phase as text; do not rely on animation alone.
- Destructive restore confirmation names the selected archive and consequence.
- Controls have persistent labels, visible focus, logical reading order and adequate touch targets.
- Errors are adjacent to the action they explain and remain available after focus moves.
- Android is the first implementation target; Windows Desktop should reuse the same flow and state language with desktop-appropriate destination selection. iOS follows the shared contract when that client is scheduled. No platform should imply unsupported provider integrations.

## 11. Design dependencies and acceptance

- Shared tokens and component styling remain **pending #679**. This package may define information architecture, content hierarchy, transitions and wireframe-level states only.
- Owner approves this specification before any implementation treats it as accepted.
- Acceptance requires all create, verify, restore and recovery-required journeys above; explicit empty/loading/success/error/cancel states; secret exclusion; last-known-good preservation; and accurate distinction between attempted and verified backups.
- This document does not close Program Step 95, #670, clean-device restore, provider round-trip, process-death recovery or physical-device acceptance.
