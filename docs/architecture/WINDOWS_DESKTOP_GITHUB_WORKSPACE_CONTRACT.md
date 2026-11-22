# Windows Desktop GitHub workspace integration contract

Status: implementation contract only. The current WPF Control Center does not yet provide a GitHub coding workspace.

## Product requirement

The Windows Desktop Hub is the second priority after Android GitHub integration. It must become a usable, visible GitHub workspace inside GLAFIRA Desktop, not merely a library of API methods. The first supported repository is the explicitly allowlisted `micronizationlab/glafira-ai-hub`.

## Required workspace

- Repository and branch selector showing the exact owner/repository and current base SHA.
- Bounded repository tree and text-file viewer; no arbitrary URL or shell access.
- Change editor with diff preview, file size/encoding checks, and protected-path explanation.
- Explicit feature-branch creation and commit preview.
- Draft PR creation and returned URL/SHA display.
- Operation history showing request ID, owner approval, branch, commit SHA, result, and reconciliation state.
- Clear read-only / write-enabled / authentication-expired state; no misleading enabled controls.

## Security and authority boundary

1. Reuse the typed branch-scoped GitHub client; do not add a second ad-hoc HTTP implementation.
2. Bind credentials to the Windows user with the existing protected credential storage. Never show or log token material.
3. Use a short-lived owner session, not a persistent UI boolean. Require re-authentication for every write.
4. Bind the approval to the exact repository, branch, path list, operation, and content preview hash.
5. Persist Core Trace before dispatch and a durable outcome/ACK after remote reconciliation.
6. Enforce repository allowlist and sensitive-path guard in the service layer. Hiding a button is not a security control.
7. Permit reads and feature-branch writes only. No shell, arbitrary URL, direct default-branch write, workflow/build-control edits, branch deletion, or PR merge.
8. Treat timeout-after-write as `OUTCOME_UNKNOWN`; reconcile the branch and blob SHA before retrying.

## Desktop/Core interaction

The WPF workspace must submit typed intents to the shared Core dispatcher. It must not call GitHub directly from code-behind or bypass owner-generation fencing. The UI should display Core's request and final result rather than maintain a second task authority.

## Minimum acceptance tests

- Workspace opens in read-only mode without a valid owner session.
- Expired or consumed owner proof blocks every mutation.
- Changing any preview field invalidates the prior approval.
- Disallowed repository, protected branch, and sensitive path are rejected by service tests.
- UI cannot invoke shell, arbitrary URLs, merge, delete branch, or default-branch writes.
- Trace is durable before network dispatch; ACK is durable after reconciliation.
- Simulated lost response after commit is reconciled without duplicate commits.
- Restarting Desktop does not resurrect an owner session or expose credentials.
- Exact returned branch, commit SHA, and PR URL are displayed.
- Windows Desktop build/tests and package CI pass on the exact PR SHA.

## Implementation sequence

1. Add a read-only workspace view-model and repository/branch/tree browsing.
2. Add diff/preview models with no write side effects.
3. Connect owner-session and preview-bound approval.
4. Connect the existing branch-scoped API for branch/file/draft-PR operations.
5. Route all operations through Core Trace, generation fencing, and durable ACK.
6. Add WPF interaction tests and Windows package CI, then test on a clean Windows profile.

Until the full sequence is implemented, the Desktop UI must not imply that GitHub coding is available merely because the API client exists.
