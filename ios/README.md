# GLAFIRA iOS

The iOS client is a native peer client for iPhone and iPad. It is not a separate
Core, memory authority, or privileged device.

## Current implementation status

- `GlafiraIOSUI`: adaptive SwiftUI navigation shell for projects, agents and settings on iPhone/iPad. It shows explicit not-connected/empty states and does not fabricate project data.
- `KeychainBridgeCredentialStore`: device-only Keychain storage for paired-device HMAC secrets, protected by `biometryCurrentSet` and `WhenUnlockedThisDeviceOnly`. Existing credentials cannot be overwritten implicitly.
- `OwnerBiometricAuthenticator`: explicit Face ID/Touch ID owner-presence check for sensitive actions. Cancellation, lockout, unavailable biometrics and errors fail closed.
- `OwnerBiometricGateView`: reusable SwiftUI confirmation surface; the protected callback runs only after successful biometric authentication.
- `GlafiraBridgeKit`: versioned Bridge request/response models, Kotlin-compatible canonical HMAC-SHA256 signing, signature verification and envelope validation.
- `PinnedBridgeHTTPSClient`: HTTPS-only transport prototype for the historical Android-hosted `/bridge/v1/requests` and `/bridge/v1/pairing` routes; it checks the owner-supplied SHA-256 leaf pin, hostname, redirects and response bounds. It is **not yet interoperable with the current Windows Desktop Hub**.
- `BridgePairing`: proof implementation for the historical Android owner-approval queue. Its route/status semantics do not match the current Windows Hub Agent enrollment endpoint; do not claim production peer pairing until #392 is resolved.
- `BridgeCredentialBundleImporter`: strict importer for the historical Android `GLAFIRA_BRIDGE_CREDENTIAL_V1` owner bundle. The current repository does not yet provide a matching end-to-end Android peer-client grant/delivery flow; keep this as a prototype and do not use Agent credentials as Core peer credentials.
- `GlafiraBridgeKitTests`: protocol tests including a fixed HMAC golden vector
  shared with the Kotlin implementation.
- `.github/workflows/ios.yml`: macOS CI runs `swift test` for the package.

The repository now includes an unsigned native Xcode application target at `ios/GLAFIRA.xcodeproj` which launches the existing `GlafiraRootView`. It is configured for iPhone and iPad and can be built for the simulator without signing. App icon assets, signing profile, Apple account setup, TestFlight build and App Store release are not included.

## Build and test

From this directory:

```sh
cd ios/GlafiraBridgeKit
swift test
```

Requires Swift 5.9 or later and the Apple CryptoKit framework.

## Security and compatibility boundaries

- Protocol version and typed operation names must match Android/Desktop exactly.
- HMAC canonicalization uses UTF-16 code-unit lengths to match Kotlin
  `String.length`; the golden test detects accidental serialization drift.
- Paired device HMAC credentials are stored only in Apple Keychain with device-only accessibility and current-biometric-set protection. The current importer expects a historical Android owner bundle; do not describe the clipboard flow as a live production pairing/grant endpoint. The current cross-platform peer protocol and server-side owner approval semantics are tracked by #392.
- Signature verification is not authorization. All operations remain subject
  to Gateway/Core admission, project scope, replay protection, revocation and
  owner approval.
- No arbitrary shell, filesystem, URL fetch, or generic code execution operation
  is defined.
- TLS hostname validation must remain enabled alongside certificate pinning.
- Voice capture must remain explicit and consent-gated; no always-listening mode.


## iOS biometric and Keychain requirements

- Paired HMAC secrets are stored as generic-password Keychain items scoped by
  service and device ID. Accessibility is `WhenUnlockedThisDeviceOnly`; access
  control is `biometryCurrentSet`. The secret is not synchronized to iCloud
  Keychain and cannot be restored onto another device.
- A changed biometric enrollment invalidates access to the paired secret.
  Re-pairing must be performed through the owner-approved Bridge flow; the app
  must never silently downgrade to an unprotected Keychain item or plaintext.
- Keychain reads show the system authentication prompt. Sensitive local actions
  use an explicit `OwnerBiometricAuthenticator` check and fail closed when
  biometrics are cancelled, unavailable, not enrolled, or locked out.
- The future iOS app target must add `NSFaceIDUsageDescription` with a clear
  user-facing explanation before Face ID is invoked. Touch ID uses the system
  prompt on supported devices.
- App integration must keep credential access behind the authenticated owner
  flow. Never log, serialize, export, or include HMAC secrets in diagnostics.
- Simulator CI can compile and unit-test policy/model behavior; actual Keychain
  biometric prompts and enrollment-change invalidation require physical-device
  acceptance testing.

## Next implementation gates

1. Strict JSON codec and cross-platform request/response fixtures.
2. Keychain credential store and LocalAuthentication adapter.
3. Pinned HTTPS transport and pairing invitation import.
4. Bind the native app target to authenticated project/conversation data and add UI tests.
5. Encrypted local cache, idempotent outbox and conflict handling after the
   shared Core memory/event contract is stable.


## Legacy Android-hosted pairing prototype — not end-to-end against current Hub

The `GlafiraIOSUI` target includes `BridgePairingView` and a pinned HTTPS client for the historical Android-hosted Bridge contract. It parses the old invitation format and submits to `POST /bridge/v1/pairing`, expecting `PENDING_OWNER_APPROVAL`.

The current Windows Desktop Hub exposes a different Agent enrollment route (`POST /bridge/v1/pair`) and different enrollment semantics. Agent enrollment is not Core peer enrollment. Therefore this iOS flow is **not currently end-to-end compatible** with the Windows Hub and must not be used as evidence of successful Android↔iOS↔Desktop pairing.

Issue #392 owns the canonical cross-platform peer contract and server implementation decision. After that contract is approved, update the Swift client, UI copy, Android client and Hub peer endpoint together; add shared fixtures and physical LAN pairing/revocation tests. Keychain import remains device-only and must never accept an Agent credential as a Core peer credential.
