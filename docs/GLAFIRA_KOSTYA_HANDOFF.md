# GLAFIRA AI HUB — engineering handoff

Last updated: 2026-11-19

## Current owner/developer handoff

The owner has explicitly authorized GLAFIRA to make repository changes while Kostya is inactive. Work is isolated on branch `glafira/bridge-keystore-signer-fix`. Do not assume that this branch is merged into `main` or included in the owner's installed APK.

Kostya should read this file first when resuming work, inspect the branch and its commits, then reconcile it with his active branches before continuing. Do not cherry-pick blindly or reset/rebase this branch without reviewing its base and diff.

## Change in this branch

### Android Bridge TLS signer provider

File:
`app/src/main/java/com/micronizationlab/glafira/AndroidKeystoreBridgeServerIdentityStore.kt`

Observed owner-device failure:
`OperatorCreationException: cannot create signer: no such algorithm: SHA256WITHECDSA for provider AndroidKeyStore`

Root cause:
- The EC private key is generated and retained in Android Keystore under provider `AndroidKeyStore`.
- That provider is the keystore/key-generation provider; it does not provide the JCA `Signature` implementation requested by Bouncy Castle's `JcaContentSignerBuilder`.
- Android registers `AndroidKeyStoreBCWorkaround` specifically for JCA signature operations using non-exportable Android Keystore keys; JCA provider selection should be allowed to choose it based on key compatibility.

Change made:
- Keep `ANDROID_KEY_STORE = "AndroidKeyStore"` for key generation and keystore access.
- Select `ANDROID_KEYSTORE_SIGNATURE_PROVIDER = "AndroidKeyStoreBCWorkaround"` for the `SHA256withECDSA` content signer.
- Keep the private key non-exportable; no TLS downgrade, protocol change, or key export.

Reference implementation rationale: Android Open Source Project documents the workaround provider as the provider for JCA crypto operations on Android Keystore keys. See:
https://android.googlesource.com/platform/frameworks/base/+/cb3bb3f/keystore/java/android/security/keystore/AndroidKeyStoreBCWorkaroundProvider.java

## Validation status — NOT YET ACCEPTED

The source change has been committed to this isolated branch, but no Android device/emulator validation is claimed yet. Do not tell the owner the Bridge is fixed until the following pass:

1. Android Keystore EC P-256 key generation succeeds.
2. `SHA256withECDSA` signer creation and certificate issuance succeed on API 35.
3. Certificate SAN contains the selected LAN IPv4 address and EKU contains serverAuth.
4. A second `getOrCreate()` reuses the existing key/certificate.
5. HTTPS server binds to the selected private IPv4 address.
6. TLS handshake succeeds from a paired/trusting client.
7. Stop/start cycle succeeds without regenerating or losing identity.
8. Run Android JVM and instrumentation suites; report exact counts and failures.
9. Build and install the APK over the owner's existing installation; verify app data and Bridge settings remain intact.

If the provider name is absent on a supported Android API/device, investigate provider registration and Android version behavior; do not silently fall back to exporting the key or a software private key. A fallback may only be considered if it continues to sign through the Android Keystore key and is covered by device tests.

## Existing project boundaries

- Do not merge this branch directly into `main` without checking the current Runtime Foundation/PR dependency stack.
- Do not weaken Bridge pairing, owner approval, scopes, replay protection, TLS pinning, or Core authorization.
- The user's architectural decision remains: Android and Desktop each need independent Core capability; neither is a permanent single point of failure.
- Kostya's existing PRs and branches must be reviewed before integration; this branch is an isolated focused fix, not a replacement for that work.
