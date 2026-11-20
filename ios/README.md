# GLAFIRA iOS

The iOS client is a native peer client for iPhone and iPad. It is not a separate
Core, memory authority, or privileged device.

## Current implementation status

- `GlafiraIOSUI`: adaptive SwiftUI navigation shell for projects, agents and settings on iPhone/iPad. It shows explicit not-connected/empty states and does not fabricate project data.
- `KeychainBridgeCredentialStore`: device-only Keychain storage for paired-device HMAC secrets, protected by `biometryCurrentSet` and `WhenUnlockedThisDeviceOnly`. Existing credentials cannot be overwritten implicitly.
- `OwnerBiometricAuthenticator`: explicit Face ID/Touch ID owner-presence check for sensitive actions. Cancellation, lockout, unavailable biometrics and errors fail closed.
- `OwnerBiometricGateView`: reusable SwiftUI confirmation surface; the protected callback runs only after successful biometric authentication.
- `GlafiraBridgeKit`: versioned Bridge request/response models, Kotlin-compatible canonical HMAC-SHA256 signing, signature verification and envelope validation.
- `PinnedBridgeHTTPSClient`: HTTPS-only transport for Android `/bridge/v1/requests` and `/bridge/v1/pairing`; checks the owner-supplied SHA-256 leaf pin, validates the certificate for the expected hostname, rejects redirects and bounds response bodies.
- `BridgePairing`: Android-compatible one-time pairing proof; a successful submission only enters the Android owner-approval queue and never grants scopes or creates credentials.
- `BridgeCredentialBundleImporter`: parses the exact `GLAFIRA_BRIDGE_CREDENTIAL_V1` bundle currently copied from Android's owner approval screen, verifies the expected HTTPS origin and certificate fingerprint, rejects unknown fields and scope escalation, and stores the 256-bit HMAC key only after fresh Face ID/Touch ID confirmation.
- `GlafiraBridgeKitTests`: protocol tests including a fixed HMAC golden vector
  shared with the Kotlin implementation.
- `.github/workflows/ios.yml`: macOS CI runs `swift test` for the package.

These are buildable Swift package modules, not yet an installable iPhone/iPad application.
There is no Xcode app target, app icon, signing profile, Apple account setup,
TestFlight build, or App Store release in this increment.

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
- Paired device HMAC credentials are stored only in Apple Keychain with device-only accessibility and current-biometric-set protection. Credential import must use the exact bundle shown by Android's owner approval screen after explicit approval. The current Android workflow transfers this bundle by a sensitive clipboard handoff; it is not yet a network grant endpoint.
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
4. SwiftUI app target with adaptive iPhone/iPad project and conversation shell.
5. Encrypted local cache, idempotent outbox and conflict handling after the
   shared Core memory/event contract is stable.


## Native Android pairing screen

The `GlafiraIOSUI` target now includes `BridgePairingView`, linked from the root navigation. The flow accepts the Android one-time invitation JSON, validates its HTTPS origin, certificate fingerprint and expiry, creates the cross-platform HMAC proof, and submits it to `/bridge/v1/pairing`. It displays the server-issued approval ID while the Android owner reviews the request.

After owner approval, paste the Android `GLAFIRA_BRIDGE_CREDENTIAL_V1` bundle into the import section. The importer requires the approval ID returned by this exact request and the original invitation ticket ID, in addition to matching server identity and requested scopes. It then requires local Face ID / Touch ID and stores the 256-bit secret in device-only Keychain. The secret is never sent back to Android or written to ordinary preferences.

The Android handoff schema now includes `approvalId` and `ticketId`. Android obtains these from the durable approval record/outbox relation; neither value is regenerated during credential delivery.
