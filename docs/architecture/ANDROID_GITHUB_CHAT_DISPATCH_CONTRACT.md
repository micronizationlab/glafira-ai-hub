# Android GitHub chat-dispatch integration contract

Status: implementation contract; this document does not claim that chat dispatch is implemented.

## Current boundary

The branch-scoped Android GitHub operations are being developed separately. They must not be exposed to the chat dispatcher until this contract is satisfied. A successful biometric prompt or a connected GitHub account alone is not permission to execute an arbitrary instruction.

## Required request lifecycle

1. Parse the user's request into a typed GitHub intent: repository inspection, file read, feature-branch creation, file create/update, or draft pull-request creation.
2. Resolve the repository against the explicit allowlist. For the first release the only permitted repository is `micronizationlab/glafira-ai-hub`. Reject arbitrary hosts, URLs, owners, and repositories.
3. Build a deterministic preview containing repository, base ref, proposed branch, exact file paths, operation type, and a content diff or bounded read summary. Redact credentials before display and before Trace persistence.
4. Require a fresh owner-authentication proof for every mutating operation. The proof must be consumed once at the dispatch boundary; it must not be represented by a persistent Compose boolean or a remembered "unlocked" flag.
5. Require a separate explicit owner confirmation bound to the preview hash. If any field or content changes after confirmation, invalidate the approval and rebuild the preview.
6. Persist an immutable Core Trace event before dispatch, including request ID, owner Core ID/generation, repository, branch, operation, preview hash, and approval decision. Never persist access tokens or secret file contents.
7. Dispatch only through the typed branch-scoped repository API. No shell, arbitrary URL fetch, branch deletion, direct push to protected branches, merge, workflow/pipeline edits, or credential-file edits.
8. Persist the outcome and a durable acknowledgement after the remote result is reconciled. A timeout or lost response is `OUTCOME_UNKNOWN`, not success and not permission to blindly retry a write.
9. Show the resulting branch, commit SHA, and draft PR URL to the owner. A PR is not merged automatically.

## Fail-closed rules

- Missing/expired/consumed owner proof: reject before network dispatch.
- Repository or path outside the allowlist: reject before network dispatch.
- Missing preview-bound approval: reject before network dispatch.
- Core owner-generation mismatch: reject and record a stale-owner event.
- Missing durable Trace/ACK storage: disable mutating operations; read-only operations may proceed only if separately authorized.
- Uncertain write outcome: reconcile remote branch/file state before any retry.
- GitHub API errors and rate limits must be surfaced without converting them into success.

## Minimum acceptance tests

1. No owner proof means no mutating API call is made.
2. A consumed proof cannot authorize a second operation.
3. Approval for preview hash A cannot authorize changed preview hash B.
4. A repository other than the configured allowlist is rejected.
5. Protected branches and sensitive paths are rejected by the repository operation layer, not merely hidden in UI.
6. A stale owner generation cannot dispatch or acknowledge a result.
7. Trace is durable before the API call and the final outcome/ACK is durable after reconciliation.
8. Simulated timeout after remote commit produces `OUTCOME_UNKNOWN`; reconciliation finds the commit without creating a duplicate.
9. UI displays the exact branch, commit SHA, and draft PR URL returned by GitHub.
10. No path in the flow can merge a PR or modify the default branch.

## Integration order

1. Implement a typed dispatcher and preview model with no network side effects.
2. Add preview-hash-bound one-time owner approval.
3. Connect the dispatcher to the branch-scoped GitHub API.
4. Add Core Trace, owner-generation fencing, and durable ACK/reconciliation.
5. Add the chat UI and verify the full flow with mocked API tests.
6. Run Android JVM, shared-contract, APK, and emulator CI on the exact PR head; then perform physical-device acceptance.

Until all six stages and the acceptance tests pass, the Android chat must describe GitHub coding as unavailable rather than imply that an instruction has been executed.
