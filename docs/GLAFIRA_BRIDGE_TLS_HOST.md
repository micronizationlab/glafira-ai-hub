# GLAFIRA Bridge LAN TLS Host

Status: implementation slice in progress. Android has a bounded HTTPS/1.1 host, explicit owner-started foreground service, separate pairing and paired-request routes, and owner approval wiring. Desktop has JVM APIs for pinned pairing and encrypted credential import. CI and physical-device validation remain open.

## Security invariants

- Listener is off by default; owner must explicitly start it from Settings → Security → Device pairing.
- Bind only to the owner-selected private IPv4 address. No wildcard/public bind, port forwarding, public relay, or hosted endpoint.
- Clients retain HTTPS hostname verification and verify the SHA-256 leaf certificate pin.
- Certificate contains the exact IP SAN and serverAuth EKU. The private EC key remains in Android Keystore.
- Pairing proof and paired-request admission are separate routes and state transitions.
- Requested scopes are untrusted hints. Only the owner approval screen grants scopes.
- All paired requests pass through credential lookup, HMAC verification, scope policy, device rate limit and replay/idempotency admission.
- No generic shell, arbitrary filesystem or unrestricted browser operations.

## Android runtime now wired

- AndroidKeystoreBridgeServerIdentityStore creates/persists a P-256 key in Android Keystore and an X.509 certificate with IP SAN, serverAuth EKU, digitalSignature usage and SHA-256 fingerprint.
- GlafiraBridgeTlsHttpServer: TLS 1.2/1.3, private IPv4 bind, bounded worker pool, handshake/read timeouts, fixed routes, strict request framing, bounded headers/body, duplicate-header rejection, Host validation, no keep-alive and generic errors.
- GlafiraBridgeHostService: non-exported foreground service, explicitly started/stopped by the owner, persistent notification, no boot auto-start.
- GlafiraBridgePairingJsonCodec and GlafiraBridgeRequestJsonCodec: strict UTF-8, exact fields, duplicate-key rejection and bounded JSON.
- /bridge/v1/pairing: verifies and atomically consumes the one-time proof, then enqueues the enrollment for owner approval. It does not issue credentials or grant scopes.
- /bridge/v1/requests: decodes the signed envelope and passes it to GlafiraBridgeRequestAdmission. Only a fully authenticated, scoped, non-replayed ACCEPTED request reaches GlafiraBridgeOperationDispatcher.
- Per-IP rate limit applies before parsing. Per-device rate limit applies only after the credential and HMAC have been verified. Decrypted HMAC bytes are cleared after admission.
- Owner approval screen can create a 120-second invitation, approve/reject, select scopes, issue credentials, and copy a TLS-pinned credential bundle. The screen is protected against screenshots while open.

## Desktop JVM runtime now wired

- Invitation-bundle parser validates format, expiry, HTTPS origin and certificate fingerprint.
- Pairing helper creates a fresh 256-bit nonce and HMAC proof without serializing the capability into the HTTP body.
- Pairing HTTP client posts to /bridge/v1/pairing with TLS hostname verification and the owner-supplied certificate pin.
- Owner credential bundle import uses a bounded strict JSON parser (duplicate keys, unknown/missing fields, excessive nesting and oversized collections are rejected) and an allowlist of registered Bridge scopes; it stores device ID, 256-bit HMAC secret, granted scopes, HTTPS origin and certificate pin in an AES-256-GCM encrypted vault using PBKDF2-HMAC-SHA256 (310,000 iterations).
- Vault writes are atomic and apply owner-only POSIX permissions where supported. Vault-backed request facade loads the credential, sends signed HTTPS requests and clears in-memory secret copies afterward.

The current Desktop vault is passphrase-protected application storage, **not yet native Windows Credential Manager or macOS Keychain integration**. The module is a JVM library; a user-facing Desktop installer/UI is still outstanding.

## Pairing sequence

1. Owner starts the Android server and confirms the active local IPv4 address in the foreground notification.
2. Owner authenticates with device credential/biometric and creates a 120-second invitation. It contains ticket ID, one-time capability, HTTPS origin and certificate fingerprint.
3. Desktop parses the invitation, creates nonce/proof, and sends the pairing request over pinned HTTPS.
4. Android consumes the ticket once and creates a pending owner approval. Requested scopes are only displayed as hints.
5. Owner selects actual scopes and approves or rejects. Approval atomically creates the encrypted device credential and encrypted delivery outbox.
6. Owner copies the TLS-pinned credential bundle. Desktop imports it into the encrypted vault with an owner passphrase.
7. Desktop sends signed requests to /bridge/v1/requests; Android verifies credential, signature, scope, device rate limit and idempotency.

## Remaining work before real-world test

- CI must pass Android unit tests, Desktop tests, Debug APK packaging and Desktop JAR build.
- Physical Android Keystore validation of certificate signing and TLS handshake on supported devices.
- Android 14/15 validation of foreground-service permissions, notification behavior, stop/restart lifecycle and Wi-Fi network changes.
- LAN end-to-end test with actual Android and Desktop devices, including wrong pin, wrong hostname, expired invitation, reused ticket, denied owner approval, revoked/unknown device, invalid HMAC and replayed request.
- Owner approval request persistence and action-bound grant execution; APPROVAL_REQUEST currently fails closed as not connected. External writes, financial and destructive actions are not dispatched.\n- Shared project/conversation dispatcher currently supports bounded PROJECT_READ, CONVERSATION_READ and CONVERSATION_APPEND only; it validates project membership, restricts conversation IDs to known agents under the selected project, rejects traversal/symlink paths, bounds file sizes and writes append operations atomically. No model/provider invocation is triggered by an append.
- Native Windows/macOS credential-store adapters and a Desktop user interface/installer.
- Owner-authenticated certificate rotation with client pin rollover acknowledgement.

No public endpoint, relay, paid infrastructure, merge or release has been created. APK is not marked ready for user testing until CI and physical LAN validation pass.
