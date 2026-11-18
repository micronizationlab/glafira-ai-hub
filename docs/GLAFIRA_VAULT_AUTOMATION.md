# GLAFIRA Password Vault automation boundary

## Goal

GLAFIRA may retrieve and use an owner's saved account credentials for an explicitly
authorized task without asking the owner to copy a password into chat. Credentials
must be delivered only to a trusted in-process destination adapter.

## Current implementation

- Google Password Manager CSV import is available from the Password Vault screen.
- Imported records are stored in the app-private `password_vault.enc` file.
- The vault envelope uses AES-256-GCM; the encryption key is derived from the owner's
  master password with PBKDF2-HMAC-SHA256 (310,000 iterations).
- `VaultSession.withCredential` is an internal, short-lived access API. It requires
  an exact HTTPS origin and an owner-approved host allow-list supplied by trusted app
  state. It fails closed for missing or ambiguous entries.
- The password is exposed to the callback as a mutable character array and wiped
  when the callback returns.
- `vault.credential.use` is registered in the Core tool catalogue with its own scope.

## Required integration before this is operational

1. Build an owner-facing per-site grant screen. The owner selects exact HTTPS hosts
   and optionally a specific username. The model cannot create or expand this grant.
2. Implement a trusted credential adapter that calls `withCredential` and types or
   submits the secret directly into the intended authenticated app/browser surface.
3. Add a destination binding: the task must bind the grant to the expected origin,
   app identity/session and operation. Reject redirects to an unapproved origin.
4. Keep the credential entirely out of model prompts, provider requests, Bridge
   payloads, conversation history, screenshots, telemetry and logs.
5. Add a short expiry, single-task use where possible, revocation and secret-free
   audit metadata.
6. Test import, exact-host matching, ambiguity, lock/expiry, process death and
   physical-device behavior.

## Desktop Bridge boundary

The Desktop Bridge's HMAC credential is a device-to-device transport secret. It is
not a website password and must never be reused as one. Website passwords stay in
the Android Password Vault and are released only to a trusted destination adapter.
The current Bridge operation dispatcher supports a bounded project/conversation
operation set; it is not yet a general remote browser controller or shell.

## Security limitations

The current vault is encrypted at rest using a key derived from the master password.
It is not yet a biometric/Android-Keystore-unlockable zero-knowledge vault, and no
claim of hardware-backed protection for the vault master key is made. The current
short-lived lease reduces accidental exposure but cannot guarantee erasure of all
immutable plaintext copies created by the platform/runtime. A complete automation
flow must be validated on physical Android and Desktop devices before being treated
as production-ready.
