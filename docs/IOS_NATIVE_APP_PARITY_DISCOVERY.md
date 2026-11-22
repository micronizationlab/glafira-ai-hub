# iOS Native App Parity Discovery

**Status:** discovery baseline, not an installable iOS application.

## Existing foundation confirmed in the repository

The current Swift package is `ios/GlafiraBridgeKit` with two library targets:

- `GlafiraBridgeKit`: versioned Bridge models, canonical HMAC-SHA256, request validation, pinned HTTPS client, pairing invitation and credential-bundle import.
- `GlafiraIOSUI`: SwiftUI navigation shell, project/agent/settings surfaces, pairing UI and owner-biometric gates.
- `GlafiraBridgeKitTests`: protocol/security tests, including Kotlin-compatible HMAC golden vectors.
- `.github/workflows/ios.yml`: macOS Swift package test lane.

The existing implementation is materially beyond a blank iOS scaffold. Do not duplicate BridgeKit, Keychain, certificate pinning, pairing or biometric modules.

## Capability parity map

| Capability | Current evidence | Remaining work |
|---|---|---|
| SwiftUI navigation shell | `GlafiraIOSUI` package target | Wire into a signed native app target and lifecycle |
| Bridge request/response schema | `GlafiraBridgeKit` | Keep fixtures synchronized with Android/Desktop contracts |
| HMAC signing and verification | Shared Kotlin-compatible golden vector | Add broader cross-platform negative/fuzz vectors |
| HTTPS transport | Pinned HTTPS client implementation and tests exist for the historical Android-hosted route | Reconcile route and response contract with Windows Hub peer API (#392), then test against an owner-controlled endpoint |
| Pairing | Historical Android invitation proof/UI prototype; not end-to-end compatible with current Windows Hub | Define shared peer-vs-Agent enrollment contract in #392; align Android client #393 and test physical pairing/revocation |
| Credential storage | Device-only Keychain and biometric gate | Physical-device tests for biometric change, lockout and re-pair |
| Project/conversation data | UI shell has explicit empty/not-connected states | Bind to canonical Core memory/event contract; encrypted cache and outbox |
| Approvals and audit | Owner biometric UI building block | Action-bound approval list, audit history and status refresh |
| Voice | Consent-aware policy exists in shared documentation | Add optional provider-neutral plugin after client lifecycle contract |
| Packaging | Swift package CI only | Xcode app target, app metadata, simulator build and signed device build |

## Implementation sequence

### I0 — Preserve and validate existing foundation

1. Run the current Swift package tests on the exact branch head.
2. Inventory public APIs and tests in both Swift targets before changing them.
3. Add a contract-fixture parity check against `contracts/v1` where the schemas overlap.
4. Keep credential and pairing behavior fail-closed; no silent Keychain downgrade.

### I1 — Native app target

1. Add an Xcode project and a thin `@main` SwiftUI app target that imports `GlafiraIOSUI`.
2. Add launch configuration, app icon placeholder, display name, supported orientations and privacy usage descriptions only for permissions actually requested.
3. Add iPhone and iPad simulator build/test jobs; do not require signing secrets for simulator CI.
4. Keep the app a peer client. It must not become a second Core or privileged master.

### I2 — Client lifecycle and canonical memory

1. Introduce explicit connection state: unpaired, pairing pending, connected, disconnected, revoked and error.
2. Bind project/conversation screens to the versioned Core event model rather than mock or locally fabricated records.
3. Add encrypted local cache, idempotent outbox and explicit conflict state after the shared event/version contract is stable.
4. Enforce project isolation and provider data-sharing policy in the shared Core boundary.

### I3 — Device acceptance

- Install and launch on a current iPhone and iPad.
- Verify Face ID / Touch ID prompts, cancellation, lockout and changed-biometric re-pair.
- Pair to the canonical owner-approved peer endpoint after #392 establishes the route, server semantics and credential envelope; do not assume an Android HTTPS listener exists.
- Revoke the device and confirm the iOS client fails closed.
- Verify offline edits, reconnect idempotency and conflict visibility before enabling writes.

## CI and release gates

- Every change under `ios/**` runs `swift test --package-path ios/GlafiraBridgeKit`.
- Xcode simulator build and UI tests become required once the app target exists.
- Simulator success does not count as physical biometric or pairing acceptance.
- No signing, TestFlight, App Store publication, hosted endpoint or paid relay is part of this discovery slice.
- Never place HMAC secrets, provider keys or pairing tickets in logs, fixtures or screenshots.

## Immediate next slice

Create the unsigned native Xcode app target and connect the existing SwiftUI root view. Keep it independently buildable in simulator CI; defer project-data writes until the shared Core event contract is stable.
