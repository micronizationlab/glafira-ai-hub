# GLAFIRA Native iOS Client Architecture

Status: P3 architecture baseline only. No iOS binary, signing identity, Apple developer account, hosted backend, or public endpoint is created by this change.

## Product boundary

The iOS app is a native equal-peer client for iPhone and iPad. It shares the versioned GLAFIRA Bridge protocol, typed Core operations, owner-approved device identity model, and project/conversation semantics with Android and Desktop. It does not become a separate AI brain or a privileged master that can bypass Core authorization.

## Proposed implementation

- SwiftUI application shell with iPhone and iPad adaptive navigation.
- Swift package GlafiraBridgeKit for protocol models, canonical request serialization, HMAC proof/signature helpers, strict size/type validation, and idempotency metadata. Keep test vectors byte-for-byte compatible with Kotlin/JVM.
- Network.framework HTTPS client using normal platform hostname validation plus explicit SHA-256 certificate pin validation. Never disable trust evaluation or accept user-supplied arbitrary certificates silently.
- Keychain storage for device ID, HMAC credential and pin; use device-only accessibility where feasible. Secret export is off by default.
- LocalAuthentication owner confirmation before approving pairing, changing scopes, exporting credentials, or revoking a device.
- Native QR/deep-link/manual invitation import; pairing uses the same 120-second one-time capability and proof as Android/Desktop. The iOS client never self-approves its own enrollment.
- SQLite/Core Data encrypted-at-rest cache only for data the owner chooses to keep; offline outbox uses idempotency keys and conflict-aware retries. Do not store secrets in project memory or transcript logs.
- Native microphone permission only after explicit voice-session start; AVAudioEngine capture and AVSpeechSynthesizer playback behind the shared provider-neutral voice contracts. No always-listening or background recording.
- iCloud/CloudKit synchronization is not a prerequisite and must not silently become a second project-memory authority. Shared project state remains Core-owned through authenticated Bridge operations.

## Pairing and operation lifecycle

1. Owner starts the Android LAN host explicitly and obtains a short-lived invitation.
2. iOS imports the invitation and verifies its format, expiry, HTTPS origin and certificate fingerprint.
3. iOS sends a fresh-nonce proof over pinned HTTPS to the Android pairing route.
4. Android creates a pending request. Owner confirms identity, chooses scopes and approves/rejects on the Android owner screen.
5. Owner transfers the issued credential bundle to iOS through a deliberate protected handoff; iOS imports into Keychain. No credential is inferred from pairing success.
6. iOS sends signed typed requests to the same /bridge/v1/requests route and honors admission, pending-approval, replay and revocation responses.
7. The client does not expose generic shell, arbitrary filesystem, or unrestricted tool execution.

## Build and validation sequence

1. Add an Xcode project/workspace and Swift package without changing Android/Desktop protocol semantics.
2. Port canonical protocol and HMAC test vectors; run shared cross-platform golden-vector tests.
3. Add Keychain and LocalAuthentication adapters with unit tests and simulator checks.
4. Add pinned Network.framework transport and TLS failure tests (wrong pin, wrong hostname, expired certificate, untrusted CA).
5. Add pairing import and owner-approval UX.
6. Add project/conversation views and encrypted offline cache/outbox.
7. Add native voice capture/playback only after the shared voice runtime and consent gate are stable.
8. Test on physical iPhone and iPad, including Wi-Fi changes, sleep/resume, revoked credentials, offline conflicts and memory retention controls.

## Explicit non-goals for the first iOS milestone

- No iOS-hosted public Bridge endpoint or cloud relay.
- No background microphone, always-on listening, or automatic voice capture.
- No autonomous external side effects without owner approval.
- No paid Apple developer services, TestFlight distribution, App Store submission, or release until the owner approves the account/cost and release process.
