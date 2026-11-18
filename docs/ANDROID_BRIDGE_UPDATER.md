# Android update compatibility

## Current state

The Android package ID is `com.micronizationlab.glafira`. CI now derives
`versionCode` from `GITHUB_RUN_NUMBER` and `versionName` from that same
number, so successive CI builds can be installed as newer package versions.

The debug build can use a stable CI signing key when the following GitHub
Actions repository secrets are configured:

- `GLAFIRA_DEBUG_KEYSTORE_BASE64`: base64-encoded JKS/PKCS12 keystore
- `GLAFIRA_DEBUG_STORE_PASSWORD`: keystore password
- `GLAFIRA_DEBUG_KEY_ALIAS`: key alias
- `GLAFIRA_DEBUG_KEY_PASSWORD`: key password

Without these secrets, GitHub Actions falls back to Android's runner-local
debug key. Such APKs are not guaranteed to update an APK from another runner.
The workflow emits a warning in that case.

## Create and configure the stable debug key

Create a dedicated debug-only keystore on a trusted machine. Do not reuse a
production/release signing key. Keep the keystore and passwords offline and
store only the base64 keystore and passwords in GitHub Actions Secrets.

Example (run locally; protect the resulting file and never commit it):

```sh
keytool -genkeypair -v -keystore glafira-debug.jks -storetype JKS \
  -alias glafira-debug -keyalg RSA -keysize 3072 -validity 10000
base64 -w 0 glafira-debug.jks
```

Add the four secrets above in repository Settings -> Secrets and variables ->
Actions. All future debug APKs signed with this key can be updated over an
installation signed with the same key, provided the package ID matches and
the new versionCode is higher.

## Not yet implemented

Stable signing and increasing version codes are prerequisites, not the full
Bridge updater. The remaining updater work is:

1. A versioned update manifest with package ID, versionCode, versionName,
   APK URL, SHA-256 digest, and signing-certificate fingerprint.
2. Authenticated manifest and APK delivery over the existing pinned Bridge.
3. Download to app-private storage, size limits, digest verification, package
   and signer verification before exposing the APK to the installer.
4. Android PackageInstaller handoff with explicit owner confirmation.
5. A verified backup/restore checkpoint and durable update state so a failed
   download or interrupted install never deletes user data.
6. Instrumented tests on physical Android devices, including upgrade from an
   older installed build.

Android's normal package update flow preserves app data when applicationId
and signing certificate match and versionCode increases. The app must not
uninstall itself or clear application data to update.
