# Android GitHub tools through GLAFIRA Core

Status: implementation slice on `feat/android-core-github-tool-dispatch`; not yet an end-to-end chat feature.

## Authority boundary

- GLAFIRA Core owns tool registration, scopes, approval state, task execution and result handling.
- LLM providers only help interpret a request and propose a typed Core task. A provider refusal is not an authorization decision and cannot veto an already-authorized Core tool.
- Provider output cannot grant scopes, create an owner approval, provide GitHub credentials or bypass task authorization.
- GitHub credentials are obtained by the GitHub Device Flow and resolved by `GitHubSession`; they are never included in task payloads or model prompts.

## Registered tools

| Tool | Scope | Risk |
|---|---|---|
| `github.repository.tree` | `github.repo.read` | READ_ONLY |
| `github.file.read` | `github.repo.read` | READ_ONLY |
| `github.branch.create` | `github.branch.write` | EXTERNAL_WRITE |
| `github.file.write` | `github.contents.write` | EXTERNAL_WRITE |
| `github.pull_request.draft` | `github.pull_request.write` | EXTERNAL_WRITE |

The handlers use the bounded, repository-allowlisted GitHub API client. Mutations remain limited to feature branches and Draft PRs. There is no merge, delete-branch, arbitrary URL, shell, repository-settings or direct default-branch write tool.

## Authorization and failure behavior

- Read operations require the owner-granted `github.repo.read` scope and the existing acknowledged Dry Run / fresh owner LIVE grant.
- Mutations require their narrow registered scope plus an exact, durable owner approval bound to the task ID, project, tool, payload and action summary.
- The Core task executor runs its authorizer immediately before invoking a handler.
- A network failure after a mutating request may mean GitHub accepted the operation. The handler moves that task to owner review rather than blindly retrying it.
- Missing/expired GitHub credentials return the task to owner attention; secrets are not exposed to a model.

## Remaining integration gates

This PR registers the handlers with the Android Core executor, but it does not yet make ordinary chat text automatically create GitHub tasks. The chat-to-Core intent adapter, owner-facing preview/approval screens, task-result presentation, physical Realme 8i acceptance and a full end-to-end GitHub session test remain separate gates. Do not describe the installed APK as already capable of autonomous GitHub coding until those gates pass.
