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
- Remove the forced `.setProvider(ANDROID_KEY_STORE)` from `JcaContentSignerBuilder`; let JCA select the provider that supports the Android Keystore private-key type. On Android this is the purpose of the registered `AndroidKeyStoreBCWorkaround` provider.
- Keep the private key non-exportable; no TLS downgrade, protocol change, or key export.

Reference implementation rationale: Android Open Source Project documents the workaround provider as the provider for JCA crypto operations on Android Keystore keys. See:
https://android.googlesource.com/platform/frameworks/base/+/cb3bb3f/keystore/java/android/security/keystore/AndroidKeyStoreBCWorkaroundProvider.java

## Added regression test

File:
`app/src/androidTest/java/com/micronizationlab/glafira/AndroidKeystoreBridgeServerIdentityInstrumentedTest.kt`

The API 35 emulator instrumentation test now exercises the actual Android Keystore path:
- creates the EC identity for a private IPv4 host;
- asserts the private key remains non-exportable;
- validates IP SAN, serverAuth EKU, key usage, and self-signature verification;
- calls `getOrCreate()` a second time and verifies the certificate identity is reused;
- checks connection details are available;
- cleans up the test key alias and preferences before/after the test.

This test was added after the first CI run had already started. Run #982 therefore does NOT include it. Run #984 is based on the updated branch and includes the test in `connectedDebugAndroidTest`; its Android Unit Tests job passed (140 executed, 0 skipped), but the API 35 emulator job is still running. Do not mark the instrumentation test passed until its JUnit report confirms this exact class executed successfully.

## Validation status — NOT YET ACCEPTED

The source change and focused instrumentation regression test are committed to this isolated branch. Android JVM tests passed in run #984 (140 executed, 0 skipped); the API 35 emulator job remains in progress. No Bridge TLS listener or physical-device validation is accepted yet. Do not tell the owner the Bridge is fixed until the following pass:

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


## Parallel work index — 2026-11-19

The owner explicitly asked GLAFIRA to work on multiple independent project tasks in parallel while Kostya is inactive. Each task is isolated in its own branch and Draft PR:

| PR | Branch | Base | Scope | Current acceptance |
| --- | --- | --- | --- | --- |
| [#63](https://github.com/micronizationlab/glafira-ai-hub/pull/63) | `glafira/bridge-keystore-signer-fix` | `fix/android-bridge-settings-ui` | Android Keystore TLS signer and instrumentation regression test | Android JVM passed; API 35 emulator pending |
| [#64](https://github.com/micronizationlab/glafira-ai-hub/pull/64) | `glafira/desktop-shutdown-drain-guard` | `codex/p0-desktop-queue-persistence` | Desktop worker shutdown, OS queue lock and HMAC secret retention | JVM passed on intermediate heads; newest cross-process test pending CI; emulator/APK pending |
| [#65](https://github.com/micronizationlab/glafira-ai-hub/pull/65) | `glafira/dual-core-ownership-contract` | `codex/p1-result-delivery` | Dual-Core ownership, fencing, reconciliation and failover contract | Android JVM passed; emulator pending; documentation-only change |

Read the corresponding documents before touching each branch:
- Android: this file on branch `glafira/bridge-keystore-signer-fix`.
- Desktop: [handoff document on branch `glafira/desktop-shutdown-drain-guard`](https://github.com/micronizationlab/glafira-ai-hub/blob/glafira/desktop-shutdown-drain-guard/docs/GLAFIRA_DESKTOP_SHUTDOWN_HANDOFF.md).
- Dual-Core: [ownership contract on branch `glafira/dual-core-ownership-contract`](https://github.com/micronizationlab/glafira-ai-hub/blob/glafira/dual-core-ownership-contract/docs/contracts/DUAL_CORE_OWNERSHIP_AND_FAILOVER.md).

Do not merge these branches into one another or into `main` blindly. PR #64 depends on PR #62's Desktop queue branch; PR #65 depends on PR #58's result-delivery branch. PR #63 remains stacked on the Android Bridge settings branch. Preserve those dependencies when planning integration.


## Owner test APK packaging safety

The owner already has an installed GLAFIRA package. A CI-generated debug keystore is not stable across hosted runners, so a normal `assembleDebug` APK must not be presented as a safe in-place update.

A dedicated `ownerTest` build type was added:
- application ID suffix: `.ownertest`;
- separate Android app data and Keystore namespace;
- inherits the debug build type and remains debuggable;
- installs side-by-side with `com.micronizationlab.glafira`, avoiding replacement/uninstall of the owner's current app.

The owner APK workflow now builds `assembleOwnerTest` and uploads `app/build/outputs/apk/ownerTest/*.apk`. The artifact must still be built successfully and inspected before delivery. Pairing on this side-by-side test app will be a fresh pairing and will not reuse the existing app's Bridge identity.
