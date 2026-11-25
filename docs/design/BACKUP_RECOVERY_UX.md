# GLAFIRA Backup & Recovery — Owner UX

**WBS:** `GLAFIRA-S095-D01` / `1.2.2.1.1` · **Program mapping:** Step 95 (partial)  
**Status:** proposal; owner approval required. **Visual tokens:** pending #679.  
This is an information-architecture and interaction specification, not approved visual styling.

## 1. Intent and safety invariants

Give the owner a clear answer to four questions: what is protected, where the verified copy lives, what is happening, and what restore will change. Use the ICQ 2003 flower identity with Apple/macOS clarity; final colors, type, spacing and component measurements wait for #679.

- Show **last verified backup**, not merely last attempted backup.
- Local archive authentication must pass before upload; remote copy must be read back and verified before it becomes current.
- Never delete the last known-good copy before its replacement is verified. If old-copy cleanup fails, keep both and explain.
- Never display, log, persist or transmit passphrases, keys, API credentials or vault secrets.
- Clearly disclose excluded credentials and device-local authority; restore requires reauthorization/re-pairing where applicable.
- Restore is an exact snapshot of app-private files; files absent from the selected archive may be removed.
- Interrupted restore or failed rollback leaves a blocking **Recovery required** state and Core paused. Never imply that restart clears it.
- WorkManager timing is best-effort; never promise an exact run time.
- No design decision changes Core behavior, archive schema, encryption, permissions, Bridge protocols or security boundaries.

## 2. Settings information architecture

**Settings → Backup & Recovery**

1. **Protection summary:** state, last verified date/time, destination, size, last attempt (separate field), and next best-effort window.
2. **Destination:** provider/folder, access state, last verification, Choose/Change and Recheck actions.
3. **Backup contents:** included, excluded and unavailable categories from the manifest.
4. **Backup controls:** Back up now; Verify latest copy; daily schedule; supported Wi-Fi/charging constraints.
5. **Recovery:** Restore from backup, recovery-passphrase requirement, post-restore reauthorization notice.
6. **Activity/help:** recent attempts and actionable errors; no raw payloads, secrets or sensitive file paths.

Android's current destination path is the system document picker and an installed document provider (e.g. Drive/Yandex when exposed by that provider). Do not imply a direct OAuth/REST connector. Windows Desktop should reuse the same state language with desktop destination selection; iOS follows when scheduled.

## 3. Main screen wireframe

```text
[GLAFIRA flower]  Backup & Recovery
[Protection state]  Last verified · destination · archive size
                    Last attempt · outcome
                    Next window · best effort
[Destination]       Provider/folder · access status
                    [Change destination] [Recheck access]
[Backup contents]   Included / excluded / unavailable
                    [View manifest]
[Actions]           [Back up now] [Verify latest copy]
[Schedule]          Automatic backup · window · constraints
[Recovery]          [Restore from backup]
[Recent activity]   Verified / failed / cancelled + next action
```

Example values are illustrative; runtime values must come from the actual manifest, destination and verified status.

## 4. Create backup and verify

### Preflight
- Confirm destination and persistent write access; if permission expired, offer Reconnect and keep the previous destination setting until a new one succeeds.
- Request the recovery passphrase for this operation; never prefill or store it.
- Explain local export versus off-device backup.
- If Core maintenance/recovery is unavailable, explain that backup is waiting and do not start a conflicting operation.

### Progress
Show named phases only when the worker can support them: consistent snapshot → encrypted archive creation → local authentication/verification → temporary upload (remote only) → remote read-back verification → promote new copy → best-effort cleanup of previous copy. Do not invent percentages or stages.

### Outcomes
- **Verified:** show destination, timestamp and size; update Last verified only after verification.
- **Verified; cleanup pending:** new copy is safe; both copies remain; offer cleanup retry.
- **Local export verified:** clearly warn that device loss also loses this copy.
- **Cancelled/interrupted:** state the last confirmed stage, whether a verified copy remains, and whether cleanup is pending.

## 5. Destination states

| State | Message/action |
|---|---|
| Not selected | No destination configured · Choose destination |
| Connected | Write access available · Back up now |
| Permission expired | Access expired; last verified copy unchanged · Reconnect |
| Read-only | Persistent write access unavailable · Choose another folder |
| Offline/unavailable | Destination cannot be reached · Retry later |
| Quota/storage limit | Not enough destination capacity · Free space or choose another |
| Provider error | Provider did not confirm the operation · Retry; keep last verified copy |

A stored URI alone does not prove a destination is connected. Recheck the actual permission at operation start. Never promise permanent free storage or capacity.

## 6. Verify latest copy

1. Identify the exact archive and destination.
2. Read back the remote object when remote.
3. Authenticate the archive; validate manifest, hashes and compatibility.
4. Show Verified only after all checks pass.
5. On failure, preserve the last known-good copy and classify access, transport, authentication, corruption or compatibility.

Verification is read-only: it never rotates, replaces or deletes backup objects.

## 7. Restore flow

### Inspect before mutation
After selecting an archive, show creation time, source, app/schema version, size, included/excluded/unavailable categories and compatibility/authentication status when known. Explicitly list excluded passwords, API/OAuth credentials, vault secrets, Android Keystore keys, owner approvals, leases and live authority. Do not expose decrypted staging paths or payloads.

### Explicit confirmation
Explain both:
- **Restored:** exact snapshot contents and categories included.
- **Changed:** current app-private files absent from the archive may be removed; a safety snapshot is created before apply; credentials and device-local authority are not restored.

Require a separate owner confirmation. Label the final action “Restore this snapshot”, not a generic Continue.

### Progress phases
Selected archive read → archive/manifest authentication → portable Core compatibility validation → local safety snapshot verification → staged apply → database/Core health verification → recovery completion. Do not claim “Restored” before the final health check. If a phase is not observable, show overall progress rather than invented detail.

### Outcomes
- **Restored and verified:** show archive date/categories and required provider sign-ins/peer re-pairing.
- **Rejected before mutation:** explain wrong passphrase, authentication failure, unsupported version, corrupt manifest or incompatibility; say current data is unchanged only when proven.
- **Apply failed; rollback verified:** state that restore failed and previous local state was restored and verified.
- **Rollback failed/interrupted/unknown:** enter Recovery required; preserve journal/safety material; keep Core paused; no Ignore action or success claim.

## 8. Recovery required

This is a blocking state, not an ordinary error banner.

- Title: **Recovery required**. Explain that restore was interrupted or rollback could not be verified.
- Show only verified, non-sensitive facts: last known stage, journal presence and Core paused status.
- Primary action: **Continue recovery**, only when the coordinator reports it safe. Secondary: **View recovery details**.
- No ordinary backup, task dispatch or project mutation while the Core recovery gate is closed.
- Do not expose raw paths, keys, decrypted snapshots or stack traces. Any diagnostic export must be explicitly redacted.

## 9. Error copy pattern

Use **what happened → what remains safe → next action**.

- Permission expired: “GLAFIRA can’t write to this folder. Your last verified backup is unchanged. Reconnect or choose another destination.”
- Upload interrupted: “The new backup was not verified. Your previous verified copy was kept. Check the connection and try again.”
- Remote verification failed: “The uploaded file could not be verified and was not made current. Retry or choose another destination.”
- Wrong passphrase: “This backup could not be authenticated. Your current data has not been changed.”
- Rollback failed: “Recovery is incomplete. Core is paused to protect your data. Continue recovery before using GLAFIRA.”

Use “current data has not been changed” only when pre-mutation failure is established. Never claim rollback succeeded without verification.

## 10. Accessibility and acceptance

- Every state has text; color/icon are supplementary. Announce status changes to assistive technology.
- Progress exposes current phase as text; never rely on animation alone.
- Restore confirmation names the archive and destructive consequence. Controls have persistent labels, logical reading order, visible focus and adequate touch targets.
- Errors sit beside the relevant action and remain available after focus moves.
- Acceptance: create, verify, restore and Recovery required flows; empty/loading/success/error/cancel states; secret exclusion; last-known-good preservation; attempted-versus-verified distinction; Android/Desktop adaptation notes.
- Shared visual tokens remain pending #679. Owner approval is required before implementation.
- This design does not close #670, Program Step 95, clean-device restore, provider round-trip, process-death recovery or physical-device acceptance.
