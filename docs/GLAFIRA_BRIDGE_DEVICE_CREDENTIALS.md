# Android Bridge device credential storage

`AndroidKeystoreBridgeCredentialStore` provides local encrypted persistence for credentials of paired Bridge devices.

- A per-device AES-256 wrapping key is generated in Android Keystore and is non-exportable.
- The Bridge HMAC secret is encrypted with AES-GCM before being written to app-private preferences.
- The device ID is hashed for preference/key alias names.
- Granted scopes are stored with the credential and returned as a set.
- `revoke(deviceId)` removes the record and deletes its Keystore key; `revokeAll()` removes all stored device records and keys.

## Security boundary

This device credential store is still downstream of pairing: the caller must authenticate a device and establish its separate long-term secret through a trusted owner-approved enrollment flow before calling `save`. Never accept the secret or scopes from an unauthenticated request or model output. The returned `Credential` is sensitive and should remain in process memory only as long as needed.

The implementation uses synchronous preference commits so a successful return means the encrypted record was persisted. If persistence fails, the newly created wrapping key is deleted.

## Remaining verification

Keystore behavior must be tested on supported physical Android devices, including app-data clearing, OS backup/restore behavior, key invalidation and device revocation. Unit tests alone cannot validate hardware-backed Keystore guarantees. The implementation does not yet provide a pairing UI, enrollment challenge, credential rotation, remote revocation protocol, or durable request replay protection.


## Pairing ticket persistence

`AndroidKeystoreBridgePairingTicketStore` stores only the verifier key derived from the short-lived 256-bit pairing capability. The verifier key is encrypted with AES-GCM under a non-exportable Android Keystore key and persisted in SQLite. Ticket consumption is transactional and checks the expected verifier and expiry before deletion. The raw capability is returned only once to the owner-side caller and is not persisted.

This ticket store is separate from `AndroidKeystoreBridgeCredentialStore`: consuming a ticket does not create a device credential, grant scopes, or approve enrollment. Those actions remain behind a future explicit owner-approval flow.

## Remaining verification

In addition to the existing physical-device Keystore checks, verify the pairing ticket database across process restart, concurrent consume attempts, Keystore key invalidation and app-data restore. The Android HTTPS client has been added separately; no HTTPS server/listener, certificate provisioning UI, long-term credential issuance workflow, or remote revocation endpoint exists yet.
