# Android Bridge device credential storage

`AndroidKeystoreBridgeCredentialStore` provides local encrypted persistence for credentials of paired Bridge devices.

- A per-device AES-256 wrapping key is generated in Android Keystore and is non-exportable.
- The Bridge HMAC secret is encrypted with AES-GCM before being written to app-private preferences.
- The device ID is hashed for preference/key alias names.
- Granted scopes are stored with the credential and returned as a set.
- `revoke(deviceId)` removes the record and deletes its Keystore key; `revokeAll()` removes all stored device records and keys.

## Security boundary

This is not a pairing protocol. The caller must authenticate a device and establish its secret through a separate trusted enrollment flow before calling `save`. Never accept the secret or scopes from an unauthenticated request or model output. The returned `Credential` is sensitive and should remain in process memory only as long as needed.

The implementation uses synchronous preference commits so a successful return means the encrypted record was persisted. If persistence fails, the newly created wrapping key is deleted.

## Remaining verification

Keystore behavior must be tested on supported physical Android devices, including app-data clearing, OS backup/restore behavior, key invalidation and device revocation. Unit tests alone cannot validate hardware-backed Keystore guarantees. The implementation does not yet provide a pairing UI, enrollment challenge, credential rotation, remote revocation protocol, or durable request replay protection.
