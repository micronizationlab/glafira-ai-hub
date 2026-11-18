# GLAFIRA Bridge owner approval and device enrollment

Status: domain contract and unit-test implementation in progress. The Android durable adapter and owner UI are not implemented by this document or the domain contract alone.

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

The Android implementation should use SQLite transactions and Android Keystore-backed AES-GCM encryption. The iOS implementation should use an equivalent Keychain-backed design when the iOS client reaches this stage. No plaintext HMAC secret may be persisted, logged, included in model context, or placed in shared engineering memory.

## Scope rules

- Device-requested scopes are untrusted display hints only.
- Approval must require an authenticated owner session.
- The owner chooses the exact granted scope set; the request and LLM cannot expand it.
- Empty or malformed owner scope sets are rejected.
- Rejection never creates a credential.
- Pairing proof verification is not owner approval, and owner approval is not permission to bypass Core tool-risk gates.

## Current implementation boundary

The platform-neutral approval service and policy tests define the workflow and storage contract. They do not yet provide:
- Android SQLite/Keystore approval-store implementation;
- Android owner approval/rejection UI;
- trusted owner-authentication session issuer;
- actual pairing controller wiring from verified enrollment into the queue;
- end-to-end device credential delivery to a joining client;
- Desktop client enrollment or a live TLS server.

Do not claim device pairing is complete until those components and physical-device tests pass.
