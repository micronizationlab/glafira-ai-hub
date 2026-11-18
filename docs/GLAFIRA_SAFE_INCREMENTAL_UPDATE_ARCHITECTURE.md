# GLAFIRA Safe Incremental Update Architecture

Status: architecture proposal; not yet implemented.
Priority: Core platform capability, planned before the first public release.
Branch: core/safe-incremental-updates

## 1. Product requirement

GLAFIRA AI HUB is expected to evolve continuously after its first release. Owners must be able to discover, review, download and install updates without repeatedly downloading the entire application when the delivery platform supports smaller update units.

The updater is a first-class Core subsystem, not an unrestricted self-modifying mechanism. It must preserve owner control, application integrity, user data and the ability to recover from failed updates.

## 2. Important Android delivery constraint

Android does not provide a general safe API for an installed app to silently replace arbitrary executable code or patch its own APK in place.

Use delivery mechanisms appropriate to the distribution channel:

- Google Play distribution: use Play In-App Updates for app-version updates; use Play Feature Delivery / Play Asset Delivery for eligible on-demand feature modules and large assets. Play remains responsible for installing app binaries and splits.
- Owner-managed / direct APK distribution: download a complete, signed APK (or supported installer package) and invoke the Android package installer with explicit user consent. A binary delta may reduce network transfer only if a trusted installer/updater reconstructs and verifies the complete signed target package before installation; do not invent an in-app APK patching mechanism.
- Downloadable non-executable content: deliver independently versioned, signed data/resource bundles (for example help content, templates, prompt packs, language assets and model metadata) without loading arbitrary DEX/JAR/native executable code from an untrusted remote source.
- Configuration and provider catalog changes: use schema-versioned, signed configuration documents with strict validation and rollback.

The release channel must be selected explicitly. The app must not mix Play-managed updates with a self-managed APK installation flow.

## 3. Core components

1. UpdateManifestClient
   - Fetches a versioned manifest over TLS from a configured update origin.
   - Supports stable, beta and owner-pinned channels.
   - Parses only a strict schema; rejects unknown critical fields and invalid version constraints.

2. UpdateManifestVerifier
   - Verifies a detached signature over canonical manifest bytes using a pinned public key.
   - Supports key rotation through an explicitly trusted root / cross-signing process.
   - Rejects unsigned manifests, unknown keys, expired metadata, downgrade attempts and replayed sequence numbers.

3. UpdatePlanner
   - Compares installed app version, Core schema version, device SDK, ABI, distribution channel and installed modules.
   - Produces a dependency-aware plan and explains size, changes, required restart and permissions.
   - Distinguishes app binary updates from data/configuration/module asset updates.

4. UpdateDownloader
   - Uses resumable downloads, bounded retries and WorkManager for deferrable transfers.
   - Enforces HTTPS, expected length, SHA-256 digest, available-storage checks and per-package size limits.
   - Downloads to a staging area; never overwrites active files during transfer.

5. UpdateInstaller
   - Delegates app binary installation to Google Play or Android PackageInstaller according to the selected distribution channel.
   - Installs data bundles transactionally into versioned directories, then atomically switches the active bundle pointer.
   - Does not use reflection, dynamic DEX/JAR loading, shell commands or arbitrary executable payloads.

6. UpdateHealthMonitor
   - Checks startup health, Core initialization, queue/database migration state and essential subsystem readiness after an update.
   - Marks a data/module update active only after validation.
   - For app binary updates, uses supported platform behavior and a recovery-compatible design; it must not promise automatic APK rollback where Android/package manager does not provide it.

7. UpdateCenter UI
   - Shows current version, available version, release notes, package size, channel, download progress and verification/install status.
   - Provides Check now, Download, Install/Restart, Pause and channel controls as appropriate.
   - Lets the owner defer or schedule downloads and installation within platform rules.

## 4. Update transaction and recovery

Required lifecycle:

CHECKING -> AVAILABLE -> DOWNLOADING -> VERIFYING -> STAGED -> WAITING_FOR_OWNER -> INSTALLING -> HEALTH_CHECK -> ACTIVE

Terminal or recoverable states include UP_TO_DATE, PAUSED, FAILED, REJECTED, INCOMPATIBLE and RECOVERY_REQUIRED.

Rules:
- Preserve the currently active version until the staged package passes signature, digest, compatibility and schema checks.
- Never apply a partially downloaded package.
- Keep the previous data/config bundle until the new bundle passes health checks.
- Use expand/contract database migrations: a release must not irreversibly destroy data needed by the currently installed version before the new version is confirmed healthy.
- Back up critical local state before migrations; validate backup readability and record schema/version metadata.
- If validation fails, restore the previous data/config bundle and keep the app in a usable state.
- All update operations are idempotent and survive process death.
- Update events are auditable and visible to the owner.

## 5. Security requirements

- TLS transport plus cryptographic signature verification; TLS alone is not package authenticity.
- SHA-256 digest and declared size checked before activation.
- Signing key pinning and a documented rotation/revocation strategy.
- Anti-rollback sequence/version policy, with an explicit owner-authorized recovery path.
- Strict channel, package ID, app signing certificate, SDK, ABI and dependency checks.
- No credentials, tokens or private signing keys embedded in the app or update manifest.
- No execution of downloaded scripts or arbitrary code.
- Rate limits, disk-space limits, download timeouts and safe cleanup of abandoned staging files.
- Updates never grant new permissions, scopes or tool capabilities silently. Permission changes and new sensitive capabilities require owner-facing disclosure and Android permission flow.

## 6. Scheduling and owner policy

- Check for updates on app launch and on a configurable periodic schedule.
- Use WorkManager for deferrable checks/downloads; do not promise exact-time execution.
- Owner can choose Wi-Fi-only, charging-only, quiet hours, automatic checks, automatic download and manual/automatic install where the channel/platform allows it.
- Security-critical updates may be marked urgent, but still follow platform installation and owner-consent rules.
- Never interrupt an active external side effect or corrupt the persistent Core task queue during an update.
- Before Core/database changes, pause new task claims, finish or safely checkpoint current work, then resume after health validation.

## 7. Rollout and release engineering

- Publish immutable, signed manifests and immutable versioned artifacts.
- Support internal/canary, beta and stable channels.
- Use staged rollout and the ability to halt publication of a bad release.
- CI must verify manifest signatures, artifact hashes, compatibility metadata, migration tests and release notes.
- Keep a release record containing version, commit, channel, artifact digest, signing identity, schema changes and rollback/recovery notes.
- Never publish an update from an unreviewed branch or failed CI run.

## 8. Delivery phases

UP-0 — Design and distribution decision
- Confirm first-release distribution: Google Play, direct APK, or both with separate channels.
- Define manifest schema, trust root, version policy and artifact hosting.

UP-1 — Read-only update discovery
- Version/channel model, signed manifest verification, compatibility planner and Update Center status UI.
- No downloads or installation yet.

UP-2 — Safe data/config bundle delivery
- Resumable downloader, staging, digest verification, atomic activation and rollback.
- Only non-executable resources/configuration.

UP-3 — Platform app update integration
- Play In-App Updates / Play Feature Delivery for Play builds.
- Android PackageInstaller consent flow for direct APK builds.
- No silent self-installation.

UP-4 — Recovery and release automation
- Migration backups, health monitor, crash/recovery tests, staged rollout, signing-key rotation drills and physical-device tests.

## 9. Acceptance criteria

- A tampered, unsigned, expired, replayed, incompatible or downgraded package is rejected.
- Interrupted downloads resume or restart safely without affecting the active version.
- A failed data/config bundle activation returns to the previous active bundle.
- User data and Core task queue survive process death and supported upgrade paths.
- Owner can inspect release notes, package size, channel, progress and final outcome.
- The app uses the correct installer for its distribution channel and never claims silent APK replacement.
- CI verifies release metadata and package digests; no APK is built or distributed before the project's existing Bridge security/pairing release gate is satisfied.

## 10. Relationship to current priorities

This capability belongs to GLAFIRA Core because it controls the integrity and evolution of the whole application. Backup & Recovery remains the second priority and is a hard dependency for update transactions and migrations. The updater must not displace the current Core executor/Bridge security work; implement the design in staged increments after the current build/test failures are resolved.
