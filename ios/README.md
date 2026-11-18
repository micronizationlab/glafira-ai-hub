# GLAFIRA iOS

The iOS client is a native peer client for iPhone and iPad. It is not a separate
Core, memory authority, or privileged device.

## Current implementation status

- `GlafiraBridgeKit`: Swift Package foundation for the versioned Bridge request
  and response models, Kotlin-compatible canonical HMAC-SHA256 signing,
  signature verification, and envelope validation.
- `GlafiraBridgeKitTests`: protocol tests including a fixed HMAC golden vector
  shared with the Kotlin implementation.
- `.github/workflows/ios.yml`: macOS CI runs `swift test` for the package.

This is a library foundation, not yet an installable iPhone/iPad application.
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

## Next implementation gates

1. Strict JSON codec and cross-platform request/response fixtures.
2. Keychain credential store and LocalAuthentication adapter.
3. Pinned HTTPS transport and pairing invitation import.
4. SwiftUI app target with adaptive iPhone/iPad project and conversation shell.
5. Encrypted local cache, idempotent outbox and conflict handling after the
   shared Core memory/event contract is stable.
