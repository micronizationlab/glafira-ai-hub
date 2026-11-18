# GLAFIRA Bridge LAN TLS Host — implementation plan

Status: design checkpoint. This document is not evidence that a listener, certificate issuer, or network endpoint exists.

## Security invariants

- Listener is disabled by default and requires an explicit owner action.
- Bind only to the owner-selected LAN interface. Never bind wildcard/public interfaces, perform port forwarding, or expose a hosted relay.
- Preserve TLS hostname verification on clients. A matching SHA-256 leaf pin is necessary but does not replace hostname verification.
- The certificate SAN must match the exact DNS name or IP address used by the client. Common Name alone is insufficient.
- The owner transfers the leaf-certificate SHA-256 fingerprint through the authenticated pairing/approval channel. Never accept a pin learned from the same unauthenticated network connection.
- Pairing proof ingress and already-paired request ingress are distinct routes and distinct state transitions.
- All authenticated Bridge requests pass through GlafiraBridgeRequestAdmission before dispatch.
- No generic shell, arbitrary filesystem, unrestricted browser, or direct model-to-device credential access.

## Proposed Android host shape

1. GlafiraBridgeHostController: owner-controlled start/stop state machine. Refuse start if the selected interface, certificate, credential store, or admission dependencies are unavailable.
2. GlafiraBridgeForegroundService: owns the listener while active, shows a persistent notification, stops on explicit owner request and network loss, and never auto-starts after reboot.
3. GlafiraBridgeServerIdentityStore: Android Keystore-backed private key plus certificate chain and explicit rotation metadata. Private key is never exported to app storage or logs.
4. GlafiraBridgeTlsServerFactory: creates an SSLServerSocket/TLS listener with TLS 1.2 minimum, TLS 1.3 where supported, strong platform cipher suites, client-auth policy explicitly defined, and bounded handshake timeout.
5. GlafiraBridgeHttpIngress: applies per-IP rate limiting before reading, strict method/path/content-type checks, bounded body reading, header limits, request timeout, and bounded concurrency.
6. GlafiraBridgeEnrollmentCoordinator: receives only pairing-proof route requests and places verified enrollments into the owner queue. It does not issue credentials.
7. GlafiraBridgeRequestAdmission: receives typed request envelopes for paired devices. Only after authentication, scope checks, expiry, replay/idempotency and Core policy checks may a typed dispatcher be called.
8. GlafiraBridgeOperationDispatcher: separate from HTTP/TLS. Dispatches only registered typed Core operations. External writes, finance and destructive actions remain owner-approved and action-bound.

## Certificate and naming decision

Use an owner-visible stable local DNS identity (for example, a device-specific .local hostname discovered through Android Network Service Discovery) only if the chosen resolver path is supported and verified on both Android and Desktop. Otherwise use an explicitly selected LAN IP and regenerate/rotate the certificate when the address changes.

The server certificate must contain the chosen hostname in subjectAltName:dNSName or the chosen address in subjectAltName:iPAddress, and extendedKeyUsage:serverAuth. The Android client currently keeps the platform hostname verifier enabled and pins the leaf certificate.

Certificate issuance must be implemented with a reviewed X.509 builder that supports SAN and EKU, with the signing private key kept in Android Keystore. Do not use internal JDK certificate classes, trust-all, disabled hostname verification, or a certificate that lacks SAN. Evaluate dependency size, maintenance and Android compatibility before adding an X.509 library.

Rotation must be owner initiated and authenticated: create new key/certificate, show the new fingerprint to the owner, transfer the new pin to clients through the already authenticated channel, verify client acknowledgement, then retire the previous identity. Never accept an unsolicited pin rollover.

## HTTP limits

- Fixed routes only: /bridge/v1/pairing for one-time proof submission and /bridge/v1/requests for paired requests.
- POST only; JSON with UTF-8 only.
- Apply remote-address rate limit before parsing or allocating request bodies.
- Use bounded header count/size, body size, request time, handshake time, active connection count and worker queue.
- Use GlafiraBridgeIngressPolicy for the current fixed request route and bounded body reader; extend route validation explicitly for pairing when its transport contract is ready.
- Use separate per-device rate limit after credential authentication.
- Reject duplicate/ambiguous Content-Length, unsupported Transfer-Encoding combinations, malformed request targets, path traversal, unknown content encodings and unexpected headers.
- Return generic errors; never include secrets, pairing capabilities, payload contents or HMAC material in logs.

## Android platform constraints to verify

- Foreground service type/permissions and notification requirements for the target SDK.
- Wi-Fi/LAN interface selection, address changes, network callbacks and service lifecycle.
- mDNS/NSD discovery and .local hostname resolution on supported Android versions.
- Keystore key algorithm/curve support, certificate signing compatibility, backup/restore behavior and hardware-backed availability.
- Whether a raw SSLServerSocket implementation provides sufficient HTTP framing, limits and cancellation; if a server library is considered, review dependency size, maintenance, TLS integration and attack surface.

## Required tests before physical pairing

- Unit tests for lifecycle transitions, interface selection, rate limits, bounded parsing, request timeout, concurrency and fail-closed behavior.
- TLS integration tests for SAN hostname match/mismatch, expired certificate, wrong pin, rotation, unsupported protocol/cipher and handshake timeout.
- Ingress tests for malformed request line, oversized headers/body, conflicting content length, transfer-encoding ambiguity, slowloris behavior and connection exhaustion.
- Admission tests for unknown/revoked device, invalid HMAC, expired request, duplicate idempotency key, denied scope and storage failure.
- LAN integration test between Android and Desktop on a private network.
- Verify the listener is unreachable from a public network and remains off after process restart/reboot.

## Current implementation status

Implemented separately:
- one-time pairing ticket/proof primitives and encrypted ticket storage;
- pinned HTTPS Android client;
- owner approval queue/UI and encrypted credential delivery outbox;
- verified-enrollment coordinator;
- bounded rate limiter and fixed-route bounded HTTP body policy.

Not implemented:
- TLS server certificate generation/rotation;
- foreground listener lifecycle and LAN binding;
- HTTP server implementation and transport-to-coordinator wiring;
- pairing route wire format;
- Desktop credential import/client transport;
- end-to-end LAN validation.

No listener or endpoint has been opened. No paid or hosted infrastructure has been created.