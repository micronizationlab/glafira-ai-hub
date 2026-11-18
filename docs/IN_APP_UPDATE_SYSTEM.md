# GLAFIRA AI HUB — In-App Update System

## Owner requirement

The first owner-testable build must contain an update screen. The owner must not need to uninstall GLAFIRA or manually reinstall the complete application for every subsequent release.

## Android update model

- Native application updates are delivered as APK releases from the project's GitHub Releases.
- The installed package ID remains `com.micronizationlab.glafira`.
- Every release is signed with the same owner-controlled Android signing key.
- Android Package Installer installs the new APK over the existing app. App-private data, preferences and databases are preserved by Android when the package name and signing identity match.
- Android requires the user to confirm installation for ordinary sideloaded apps. The app may request the per-app "install unknown apps" permission, but must not attempt silent installation or bypass Android controls.
- In-app updater checks the latest non-draft, non-prerelease GitHub release, downloads its APK, verifies the release asset SHA-256 digest, package ID, version code and signing certificate before opening the system installer.
- Any missing digest, package mismatch, version mismatch, signature mismatch, HTTP error or interrupted download fails closed. No installer is opened for an unverified package.

## Release asset contract

- Release tag: human-readable version, for example `v0.2.0`.
- APK asset: `glafira-<versionCode>.apk`.
- The GitHub Release asset must expose its SHA-256 digest through the Releases API.
- Version code is monotonically increasing and is generated from the tagged source history by the release workflow.
- The update client only offers a release whose version code is greater than the installed version code.

## Signing key — mandatory one-time owner setup

A stable signing key is required before the first test build can be updated in place. A debug APK generated on a fresh CI runner is not suitable: its debug signing key may differ on the next run, and Android will reject an update signed by a different certificate.

Create the keystore once on the owner's trusted computer (keep the file in an encrypted backup location):
```bash
keytool -genkeypair -v -keystore glafira-release.jks -alias glafira -keyalg RSA -keysize 3072 -validity 10000
```

Encode the file for the GitHub secret (Linux/macOS):
```bash
base64 -w 0 glafira-release.jks
```
On macOS, use `base64 glafira-release.jks | tr -d '\\n'`. Paste the resulting value only into the repository secret field, never into chat, source code or an issue.

On Windows PowerShell, copy the encoded value directly to the clipboard with:
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("glafira-release.jks")) | Set-Clipboard
```
Then open GitHub repository **Settings → Secrets and variables → Actions → New repository secret** and add the four secrets below. Do not send the keystore or passwords in this chat.


The private keystore must never be committed to Git. Store these GitHub Actions repository secrets:

- `GLAFIRA_KEYSTORE_BASE64` — base64-encoded owner-controlled JKS/PKCS12 keystore.
- `GLAFIRA_KEYSTORE_PASSWORD`
- `GLAFIRA_KEY_ALIAS`
- `GLAFIRA_KEY_PASSWORD`

The release workflow refuses to publish an APK if any signing secret is missing. Keep an offline encrypted backup of the keystore and its passwords. Losing the signing key prevents in-place updates under the same Android package identity.

## Data and migration safety

- Updates must never clear app data, preferences, chats, owner configuration or Core memory as part of installation.
- Database changes require forward-compatible migrations and a verified pre-migration backup before destructive schema changes.
- Keystore keys and API credentials are not copied into release artifacts.
- Do not introduce an update that silently changes owner permissions, autonomous execution policy or approval requirements.
- Native code changes require a signed APK update. Provider configuration, prompts and independently validated skill packages may eventually use separately versioned content updates, but those must not be treated as a substitute for APK signature verification.

## Acceptance checks before the first test APK is called update-ready

1. First APK is release-signed with the stable owner key.
2. A second APK with a higher version code and the same signing key installs over the first one.
3. A test chat, project, settings and Core database survive the update.
4. A different signing certificate is rejected before installation.
5. A changed APK digest is rejected before installation.
6. A package-name mismatch and non-increasing version are rejected.
7. User cancellation leaves the installed version and app data intact.
8. Update checks and downloads fail safely when offline or when the release endpoint is unavailable.

The updater UI and verification flow are not considered production-ready until these end-to-end tests pass on a real Android device. The GitHub Release workflow also depends on the owner adding the signing secrets.
