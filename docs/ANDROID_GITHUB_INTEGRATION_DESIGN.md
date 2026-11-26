# Android GitHub Integration — implementation design

**Tracking issue:** #397  
**Status:** initial design on feature branch; not implemented or accepted as runtime behavior.  
**Owner priority (2026-11-22):** P0 — highest active implementation priority, alongside required bug fixes.

## 1. Goal

Make the installed Android GLAFIRA app an owner-authorized GitHub coding client for the explicitly selected repository, initially `micronizationlab/glafira-ai-hub`. The phone is the control plane; full builds and long-running validation may be delegated to GitHub Actions or an owner-controlled Desktop worker.

The existing ChatGPT-side GitHub connection is a separate integration and does not give the Android app any credentials or authority.

## 2. Authentication decision for the no-host MVP

Use a **GitHub App user access token**, not a personal access token, classic OAuth app token, installation private key, or GitHub password.

- Register a dedicated GitHub App named GLAFIRA AI HUB.
- Enable the GitHub App OAuth device flow for the initial native-client MVP.
- The owner starts the flow inside GLAFIRA. GLAFIRA obtains a short-lived device code, opens only the official `https://github.com/login/device` page, and shows the user code with clear instructions to verify the displayed app name and requested permissions.
- Poll only at GitHub's returned interval; handle `authorization_pending`, `slow_down`, expiry, denial, cancellation, and network loss.
- Request `offline_access` only if the app is configured to issue expiring user tokens and refresh tokens. Persist access/refresh expiry metadata and rotate refresh tokens atomically.
- The device flow is a deliberate MVP trade-off: GitHub recommends authorization code + PKCE where a confidential exchange can be safely provided and warns that device flow can be abused for phishing. Mitigations are mandatory: owner-initiated flow, one active request, short visible expiry, official-domain launch, app-name/permission verification, no unsolicited codes, cancel/revoke UI, and no collection of GitHub credentials.
- Re-evaluate Authorization Code + PKCE with a trusted token-exchange component if a no-secret native flow becomes officially supported for this app. Never ship a GitHub App private key or client secret in the APK.

The GitHub App Client ID is public configuration, not a secret. It must be supplied through a build configuration value after the owner registers the app; do not invent or commit a placeholder that can accidentally be released.

## 3. Minimum GitHub App permissions

Start with only:
- Repository metadata: read.
- Contents: read/write (branch-scoped writes are enforced by GLAFIRA policy; GitHub App permission itself is repository-level).
- Issues: read/write.
- Pull requests: read/write.
- Actions: read.
- Checks: read, only if required by the selected checks endpoints.

Do not request Administration, Secrets, Variables, Workflows write, Packages, Deployments, Organization administration, or account-wide permissions. Install the app only on the owner's explicitly selected repository. Verify actual permission names and endpoint requirements during app registration and integration tests.

Use a **user access token** for actions initiated on behalf of the owner. Do not generate installation tokens in the Android client: that requires the GitHub App private key, which must never ship in a native app.

## 4. Android architecture

Keep the integration in a dedicated package, separate from AI provider credentials and Bridge credentials:

- `GitHubConnectionStore`: encrypted access/refresh tokens, expiry, GitHub account identity, app client ID, selected repository IDs and connection state. Use Android Keystore-backed encryption; never expose raw credentials through UI state.
- `GitHubDeviceAuthorization`: device-code request, browser launch, bounded polling, cancellation, refresh and revoke.
- `GitHubApiClient`: HTTPS-only `api.github.com` client; fixed host allowlist, bounded response size/timeouts, GitHub API version header, safe pagination, rate-limit handling, redacted diagnostics and typed errors.
- `GitHubRepositoryAccess`: owner-approved repository allowlist and capability checks before every operation.
- `GitHubCodingService`: read tree/files/issues/PRs/checks/logs; create isolated branch; create/update files and commits; open/update Draft PRs and comments.
- `GitHubTaskHandler`: accepts durable Core task IDs and records repository, branch, commit SHA, operation result and ACK in Core/Trace. It never accepts arbitrary shell commands.
- `GitHubIntegrationScreen`: Connect, connected account, repository access, granted permissions, expiry, last successful sync, Test, Disconnect/Revoke.

No token, refresh token, device code, authorization header, private content or unredacted API response may enter an LLM prompt, log, crash report, backup, Trace event, clipboard or analytics.

## 5. Operation policy

Default capabilities:
- Read repository metadata, tree, files, issues, PRs, checks and supported workflow logs.
- Create isolated feature/fix branches.
- Create/update files and commits only on an authorized non-protected branch.
- Open/update Draft PRs and add task-scoped comments.

Require a fresh explicit owner confirmation for:
- Merge or close a PR.
- Push to `main` or any protected branch.
- Delete branches/files or rewrite history.
- Change repository settings, permissions, rulesets, secrets or variables.
- Trigger a workflow with external side effects.
- Any operation not covered by a granted capability.

Never bypass branch protection, required reviews, CI, rate limits or GitHub authorization. A denied/revoked/expired grant must fail closed.

## 6. Delivery slices

1. **Connection foundation:** app registration configuration, device authorization state machine, secure token store, revoke/disconnect and tests.
2. **Read-only API:** identity, repository allowlist, tree/file browsing, issues, PRs, checks and safe logs.
3. **Branch-write API:** create branch, create/update file, commit, open Draft PR, comment; conflict detection and idempotency.
4. **Core integration:** durable task queue, path conflict locks, Trace redaction, result ACK and exact-SHA CI evidence.
5. **Acceptance:** isolated test repository end-to-end read → branch → edit → commit → Draft PR → CI status/logs; denied/revoked access tests; clean process restart; Realme 8i APK installation and smoke test.

Each slice is a separate reviewed PR based on current `main`; no bulk changes and no direct merge.

## 7. Acceptance and evidence

- No credentials or device codes in source, prompts, logs, backups or test artifacts.
- Token refresh/revocation/expiry and process restart behave deterministically.
- The app cannot access a repository outside the owner's selected installation/repository grant.
- Writes are impossible after revoke, expiry, denied scope or owner-policy denial.
- Every write has a task ID, target branch, resulting commit SHA and auditable result.
- Exact-head CI proves the applicable tests ran; skipped jobs are not passes.
- Owner physically validates the APK on Realme 8i before the integration is called usable.

## 8. Current repository findings

- Current `main` baseline inspected: `7dda2bc6f7ca7ba506c0b59ddf6d97ca2b927193`.
- Android manifest already has Internet and network-state permissions.
- `EncryptedApiKeyStore` already provides AES-GCM storage backed by a non-exportable Android Keystore key; GitHub credentials still need a dedicated namespaced store and tests.
- Android app currently has provider-key settings but no GitHub API client, GitHub OAuth/device authorization, GitHub connection screen or GitHub repository integration.
- App uses Kotlin/Compose and Java 17; no HTTP client library is currently declared in `app/build.gradle.kts`. The first implementation must either use the existing bounded networking conventions or add a deliberately selected, reviewed dependency.
- Existing issue #397 is the implementation tracker. This document is a design proposal and does not claim any of the runtime features above are already implemented.
