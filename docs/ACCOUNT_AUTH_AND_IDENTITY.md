# GLAFIRA AI HUB — account identity, onboarding and connected accounts

## Product decision

GLAFIRA is a multi-user product. Each person has an independent account, profile, projects, employee settings, provider keys, password vault and backup destinations. A local app install is a device, not the user's identity.

The initial experience is an owner-controlled invitation:
1. The owner creates an invitation bound to one email address, with an expiry and one use.
2. The user enters the email and one-time activation code/password supplied by the owner.
3. The invitation is validated by a trusted backend function. The code is never stored in the Android app, source code or an LLM prompt.
4. The user verifies email and creates a personal password.
5. The user completes a profile and chooses backup settings. Optional fields can be skipped.
6. The user may later link Google ID, Yandex ID, or both as additional sign-in methods.

Prefer a one-time invitation code over a reusable shared password. If product UX requires a temporary password, treat it as a one-use credential, expire it quickly, require replacement immediately, and store only a strong server-side password hash. Do not place an admin/service-role key in the APK.

## Free-first identity backend (proposed)

Evaluate Supabase Auth as the first no-rented-server candidate:
- email/password authentication;
- built-in Google OAuth;
- custom OAuth2 provider support for Yandex ID, subject to a real integration proof;
- PostgreSQL profile/invitation records with Row Level Security;
- a small Edge Function or tightly scoped database RPC for invitation redemption and owner administration.

The free plan currently advertises 50,000 monthly active users and up to three custom OAuth providers; its database is limited and inactive projects may pause. Re-check provider pricing, quotas, availability and terms before launch. No quota is a guarantee of permanent free operation.

Alternative: Firebase Authentication Spark is a strong no-cost option for email/social auth, but Yandex identity federation and invitation provisioning need a separate proof of concept. Do not choose an architecture based only on the fact that one provider offers free email/password auth.

No rented VM/server is required for this design, but a hosted identity/backend service is still a backend. A truly serverless-free local-only mode cannot securely issue/revoke invitations across devices or centrally manage multiple users.

## Identity and profile data

Minimum account profile:
- immutable internal user ID (UUID);
- verified email;
- display name;
- optional organization/business name;
- optional business/postal address, clearly labelled and not required;
- locale/time zone;
- onboarding completion state;
- created/updated timestamps.

Keep authentication identity separate from profile data. Never use email address as the primary database key. Users can change email without losing projects or backups.

## Account linking

Google ID and Yandex ID are optional sign-in identities, not blanket permissions to all services.
- Link an identity only after the user has authenticated to the existing GLAFIRA account and completes the provider's OAuth flow.
- Prefer explicit/manual linking. Supabase documents automatic linking for matching verified email addresses and manual linking as a beta capability; this behavior must be deliberately configured and security-tested rather than assumed.
- Require verified email claims and an authenticated existing session before linking. Never treat an unverified email string as proof of ownership.
- Support unlinking, token revocation, device/session logout and account deletion.
- Show which identity is used for sign-in and which API permissions are separately connected.

## Separate service grants (least privilege)

Use independent, revocable OAuth connections for each capability:
- Google Drive backup: request the narrowest available file scope (prefer app-created/app-shared files such as drive.file) and keep backups inside a dedicated GLAFIRA folder.
- Gmail: separate consent for reading metadata, reading messages, drafting and sending. Start with the least powerful required scope; sending mail is a separate capability and requires user confirmation or a narrow policy.
- Yandex Disk: separate Yandex OAuth app/permission grant for Disk access; do not assume Yandex ID login itself grants Disk access. Yandex distinguishes user-login apps from API-access apps; register the appropriate app types separately because their permission rules differ.
- Yandex Mail: separate integration and permission proof; do not infer that Yandex ID login grants mailbox access.

Tokens are stored encrypted using Android Keystore-backed local protection and, if sync is introduced, a separately designed end-to-end encrypted token envelope. Tokens never enter model prompts, logs, backups in plaintext or Git. Revocation and expiry must be visible to the owner.

## User-owned backup destinations

Each user configures their own destinations:
- Google Drive, Yandex Disk, both, or local-only;
- selected folder;
- daily schedule and Wi-Fi preference;
- last successful backup time, archive size and last error;
- optional notification email.

Backups are encrypted on-device before upload. The recovery key must not be derived solely from a device-bound Android Keystore key. Provide a user-held recovery phrase/passphrase and verify restore on a clean device. The account profile stores destination metadata, never the recovery secret. Cloud tokens are scoped to the user's own account and revocable.

The current WorkManager schedule is best-effort, not an exact-time guarantee. The current Storage Access Framework folder selection is a first integration path, not yet a production Google/Yandex OAuth connector. Per-user account isolation and provider-specific API upload remain implementation work.

## User isolation and authorization

Every profile, project, chat, agent assignment, vault and backup record is scoped to authenticated user ID. Server tables use Row Level Security and deny-by-default policies. The client never chooses an arbitrary user ID for reads/writes. Admin functions are isolated from normal user sessions. Owner invitations are auditable and revocable.

No cross-user training or experience sharing by default. Community exchange is opt-in, consent-based and uses sanitized, user-approved skill/knowledge packages, never raw chats, secrets or vault records.

## CSV import from Google Password Manager

The password vault import path is local-only:
1. User unlocks the local Password Vault with its master password.
2. User chooses the Google Password Manager CSV using Android's system file picker.
3. The importer reads the file in memory with a strict size/row limit and validates expected columns (name, url, username, password, note).
4. It shows counts and duplicate handling; the user confirms the import.
5. Entries are encrypted into the local vault. The original CSV is not copied into app storage, uploaded, indexed, backed up as a separate plaintext file or sent to any model.
6. User is reminded to delete the exported CSV from Downloads and the file manager trash after confirming the vault contents.

Never print CSV rows, credentials, or passwords to logs, analytics, crash reports, support bundles or LLM prompts. Import failure must not partially overwrite the vault. A future importer should add a transaction/rollback test before release.

## Delivery stages

1. Finish and test local CSV importer against real Google export variants (quotes, commas, newlines, BOM, empty rows, duplicates, large files); audit rollback and plaintext lifetime.
2. Implement local profile and lock screen without cloud identity; keep this mode clearly marked as device-local and single-user.
3. Prove Supabase email/password + invitation redemption + RLS using a disposable test project.
4. Prove Google and Yandex ID account linking on Android; verify same-email collision handling.
5. Implement separate Google Drive and Yandex Disk backup grants, then Gmail/Yandex Mail with narrow scopes and explicit user consent.
6. Add multi-device recovery, token revocation, session management, deletion/export and security review.

No production sign-in or mail/drive integration is considered complete until tested on real Android devices with revoked tokens, lost network, expired sessions, account linking collisions and cross-user isolation tests.
