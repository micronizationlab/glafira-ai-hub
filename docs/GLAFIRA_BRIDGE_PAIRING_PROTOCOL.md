# GLAFIRA Bridge trusted device pairing protocol

Status: pairing-ticket proof primitives and encrypted Android ticket persistence are implemented on the Gateway branch. Owner approval UI, long-term device credential issuance, joining-client storage, transport, HTTPS hosting, rate limiting, and live ChatGPT connectivity are NOT implemented yet.

## Security goals
- Only a person controlling the GLAFIRA owner device can authorize a new client.
- A pairing credential is single-use, short-lived, high-entropy, and bound to one enrollment attempt.
- Device identity and scopes are assigned by the owner-side Core, never accepted from the joining client or an LLM.
- Pairing secrets never enter project memory, prompts, analytics, crash reports, or ordinary logs.
- Revocation blocks new requests before any operation dispatch.

## Roles
- **Owner host:** trusted GLAFIRA Core instance that creates and approves pairing tickets.
- **Joining client:** an Android/Desktop client being paired.
- **Bridge transport:** authenticated HTTPS. No plaintext HTTP fallback is permitted outside local test fixtures.

## Pairing sequence
1. The owner explicitly opens Add device and chooses the device class and a least-privilege scope preset. The UI displays the exact scopes for confirmation.
2. The host creates a cryptographically random 32-byte (minimum) pairing capability using a platform CSPRNG. It assigns a random ticket ID, creation time, and expiry no later than 120 seconds.
3. The owner UI displays a QR code containing the HTTPS origin, ticket ID, and pairing capability. The capability is a bearer secret: show it only while the owner is pairing, never place it in a URL query string, and never log it.
4. The joining client connects only to the displayed HTTPS origin, validates the TLS certificate/hostname, generates its own random client nonce, and submits the ticket ID plus a proof bound to the ticket capability, ticket ID, and client nonce.
5. The host validates ticket existence, expiry, one-time state, proof (constant-time comparison), and TLS request limits. It does not trust a requested device ID, role, or scope list from the client.
6. The owner host shows a confirmation containing the joining device proposed display name, device class, and the exact owner-selected scopes. The owner must explicitly approve or reject. No model response can approve this step.
7. On approval, the host atomically consumes the ticket, creates an opaque random device ID and a fresh independent 32-byte HMAC secret, and returns the device ID and secret once over the already authenticated TLS channel. The joining client stores the secret in its platform secure storage; the owner host stores it through AndroidKeystoreBridgeCredentialStore or an equivalent desktop secure-store adapter.
8. Both sides erase transient pairing capability/proof buffers as soon as practical. The host marks the ticket consumed even if response delivery fails; the owner must start a new pairing attempt rather than reusing a ticket.
9. The client performs a signed read-only health check. The host verifies the new device credential and granted scope before returning a minimal success response.

## Ticket and proof requirements
- Pairing capability: at least 256 bits from a CSPRNG. Do not use a six-digit PIN, timestamp, device serial, or predictable random source.
- Ticket lifetime: at most 120 seconds; enforce expiry on the host using host time.
- Single-use: ticket state transition must be atomic (PENDING to CONSUMED or PENDING to REJECTED/EXPIRED). Concurrent submissions must not both succeed.
- Proof: HMAC-SHA-256 over a versioned, unambiguous canonical message containing protocol version, ticket ID, and client nonce. Compare MACs in constant time. Reject missing, malformed, replayed, or expired proofs.
- Bind the proof to the TLS session or a server-provided fresh challenge where the chosen transport supports it. Never accept a proof over an untrusted redirect or a different origin.
- The capability must not be reused as the long-term device HMAC secret. Generate a separate per-device secret after owner approval.
- Limit attempts per ticket and globally; fail closed on ticket-store errors.

## Scope and identity policy
- The host generates the device ID. The joining client cannot choose or overwrite it.
- The host chooses scopes from the Core tool registry and displays them to the owner before approval.
- Default new-device scopes are read-only and minimal. Conversation append, approval requests, external writes, and destructive actions are not granted by default.
- Owner approval of pairing is not approval of future external actions. Existing action-bound Core approval rules remain mandatory.
- Device display names are labels only and must never be used as authentication identifiers.

## Revocation and rotation
- Owner can revoke one device or all devices from a visible device-management screen.
- Local revocation first removes the credential record and destroys its wrapping key; if persistence fails, report failure and do not claim revocation succeeded.
- Every request re-loads or validates current credential state before Core authorization. A cached credential must not survive a revocation boundary.
- Remote clients receive a revocation result on their next connection, erase their local secret, and require a new owner pairing.
- Credential rotation is a separate owner-approved operation: issue a fresh secret, confirm receipt with a signed challenge, then retire the old secret. Never silently rotate or accept both secrets indefinitely.

## Storage and logging
- Owner-side secret storage uses non-exportable platform keystore keys and authenticated encryption.
- Joining-client secret storage uses Android Keystore or the desktop OS credential vault; never plaintext preferences, files, logs, or project memory.
- Pairing capabilities, proofs, HMAC secrets, QR payloads, and authorization headers are always redacted from logs and crash telemetry.
- Audit only non-secret metadata: ticket ID, device ID, owner decision, granted scopes, timestamps, and revocation outcome.
- Backup archives exclude live Bridge secrets by default. Restored devices must be re-paired unless a separate explicitly approved secret-recovery design exists.

## Required tests before enabling pairing
- Expired, malformed, altered, and wrong-ticket proofs are rejected.
- Replayed ticket and simultaneous duplicate enrollment produce at most one success.
- Owner rejection, timeout, and storage failure leave no usable device credential.
- Client-supplied scopes/device IDs cannot escalate privileges.
- Secret and capability values never appear in logs or error messages.
- Revocation immediately blocks the next request; revoked credentials cannot be restored by replay.
- Rotation rejects the old secret after the transition completes.
- Test ticket-store crash/restart behavior and concurrent access.
- Physical-device tests verify Android Keystore persistence, key invalidation, app-data clearing, and backup/restore behavior.

## Release gate
Pairing is not complete until the protocol, durable ticket store, owner confirmation UI, secure client storage, revocation path, automated tests, and physical-device tests all pass. A protocol document or a green APK build alone is not proof of a working Bridge connection.


## Implementation checkpoint — 2026-11-18

Implemented in the Gateway branch:
- 256-bit random one-time capability and random ticket identifier.
- Maximum ticket lifetime enforced at 120 seconds.
- Versioned HMAC-SHA-256 proof bound to ticket ID and a 256-bit client nonce.
- Constant-time proof comparison and atomic one-time consume contract.
- Android ticket store encrypts verifier keys with AES-GCM under a non-exportable Android Keystore key; the raw capability is not persisted.
- Unit tests cover capability size, expiry, altered/wrong-ticket proofs, replay rejection and the maximum lifetime.

Not yet implemented or accepted:
- An owner-facing approval/rejection queue and UI.
- Generation and one-time delivery of a separate long-term device ID/HMAC credential after owner approval.
- Secure storage on the joining Desktop client and pairing UI/QR scanner.
- Concrete HTTPS server/client transport, certificate lifecycle/pinning policy and rate limiting.
- End-to-end Android↔Desktop enrollment tests and physical-device Keystore validation.
- MCP server and ChatGPT connector endpoint.

A successful ticket proof only establishes possession of the short-lived pairing capability. It must never be interpreted as owner approval, device pairing completion, or permission to access project data.
