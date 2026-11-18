# GLAFIRA Bridge owner approval and device enrollment

Status: platform-neutral domain contract, policy tests, and an Android SQLite/Keystore persistence adapter are implemented in the current Draft PR. An Android owner-facing approval screen and biometric/device-credential-gated short-lived owner session are now implemented. Verified-enrollment controller wiring and live Gateway transport remain outstanding. Physical-device security validation has not yet been performed.

## Trust boundary

1. The joining client proves possession of a short-lived one-time pairing capability.
2. The Bridge pairing protocol verifies and consumes the ticket.
3. A trusted transport/controller submits the resulting verified enrollment to the owner approval service. Raw unauthenticated requests must never be passed directly to this service.
4. The enrollment is added to a durable, owner-visible pending queue. This does not create a credential or grant scopes.
5. The owner authenticates through a platform-controlled short-lived owner session and explicitly selects scopes.
6. Approval atomically transitions the pending record and issues a new random device ID plus a 256-bit HMAC secret. The selected scopes come only from the owner UI.
7. The persistence adapter stores the credential encrypted and persists an encrypted one-time delivery outbox in the same transaction.
8. The owner retrieves the secret through a delivery claim bound to that authenticated owner session. Repeated retrieval is allowed only to that same session until the owner acknowledges safe receipt.
9. Acknowledgement destroys the encrypted delivery copy. Rejection creates no credential.

## Required persistence semantics

GlafiraBridgePairingApprovalStore is a platform-neutral port. Its production implementation must provide:

- durable pending requests across process restarts;
- bounded queue reads and input validation;
- transactional, compare-and-set approval/rejection so a request is decided once;
- atomic persistence of the approved device credential and encrypted delivery outbox;
- no secret returned by the approval transaction itself;
- session-bound delivery claim and acknowledgement;
- idempotent retries with no duplicate credentials or orphaned device records;
- fail-closed behavior on database/Keystore errors;
- audit metadata without secrets or pairing capabilities;
- cleanup/expiry policy for stale pending requests and unacknowledged deliveries.

The Android adapter, AndroidKeystoreBridgePairingApprovalStore, uses SQLite transactions and Android Keystore-backed AES-GCM encryption. Credential and delivery records are encrypted separately with identity-bound AAD. Delivery claims are leased to an owner session until its expiry, allowing a later authenticated session to recover an unacknowledged delivery. The iOS implementation should use an equivalent Keychain-backed design when the iOS client reaches this stage. No plaintext HMAC secret may be persisted, logged, included in model context, or placed in shared engineering memory.

## Scope rules

- Device-requested scopes are untrusted display hints only.
- Approval must require an authenticated owner session.
- The owner chooses the exact granted scope set; the request and LLM cannot expand it.
- Empty or malformed owner scope sets are rejected.
- Rejection never creates a credential.
- Pairing proof verification is not owner approval, and owner approval is not permission to bypass Core tool-risk gates.

## Current implementation boundary

The platform-neutral approval service, policy tests, Android encrypted persistence adapter, and owner-facing biometric approval screen are implemented in the current Draft PR. They do not yet provide:
- a TLS/network host that receives remote enrollment requests and calls the verified-enrollment coordinator;
- a joining-client credential import/handshake flow;
- Desktop client enrollment;
- a TLS server/listener, certificate provisioning or end-to-end LAN connection;
- physical-device validation of Android Keystore/database behavior.

The owner screen is available in Android Settings under Security. The platform-neutral enrollment coordinator now verifies and forwards one-time pairing proofs into the queue. Until a trusted TLS/network host calls that coordinator, the on-device queue will remain empty. The owner screen can recover an unacknowledged credential delivery after a previous owner-session lease expires. Do not claim device pairing or live ChatGPT connectivity is complete.

## Android physical-device acceptance checklist

- Verify AES key creation and encrypted database records on a supported Android device.
- Verify pending requests survive process death and device restart.
- Verify two concurrent approvals cannot issue two credentials for one request.
- Verify a database failure rolls back credential, delivery and approval-state changes together.
- Verify the HMAC secret is never present in SQLite as plaintext.
- Verify owner-session delivery can be recovered by a new authenticated session after the previous session lease expires.
- Verify acknowledgement removes the encrypted delivery copy while the credential remains available to the Bridge credential provider.
- Verify Keystore invalidation causes fail-closed behavior and requires controlled re-pairing.
- Verify app backup/restore does not restore ciphertext without its Keystore key and that this state is reported safely.
