# GLAFIRA iOS

The iOS client is a native peer client for iPhone and iPad. It is not a separate
Core, memory authority, or privileged device.

## Current implementation status

- `GlafiraIOSUI`: adaptive SwiftUI navigation shell for projects, agents and settings on iPhone/iPad. It shows explicit not-connected/empty states and does not fabricate project data.
- `KeychainBridgeCredentialStore`: device-only Keychain storage for paired-device HMAC secrets, protected by `biometryCurrentSet` and `WhenUnlockedThisDeviceOnly`. Existing credentials cannot be overwritten implicitly.
- `OwnerBiometricAuthenticator`: explicit Face ID/Touch ID owner-presence check for sensitive actions. Cancellation, lockout, unavailable biometrics and errors fail closed.
- `OwnerBiometricGateView`: reusable SwiftUI confirmation surface; the protected callback runs only after successful biometric authentication.
- `GlafiraBridgeKit`: Swift Package foundation for the versioned Bridge request
  and response models, Kotlin-compatible canonical HMAC-SHA256 signing,
  signature verification, and envelope validation.
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
- The package does not store credentials. A future iOS adapter must store
  device credentials in Apple Keychain with device-only accessibility.
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
