# GLAFIRA AI HUB — Amazon Q project rules

These rules apply to all Amazon Q Developer tasks in this repository.

## 1. Work authorization and scope
- Implement only the concrete task described in the GitHub issue or pull-request request.
- Do not treat analysis, suggestions, or comments as authorization for unrelated architecture changes.
- Do not perform broad refactors, mass formatting, dependency upgrades, or file moves unless explicitly requested.
- Preserve existing public APIs, persisted data formats, database schemas, and migration compatibility unless the task explicitly changes them.
- If the issue is ambiguous or conflicts with repository architecture, explain the conflict and ask for clarification instead of guessing.

## 2. Branches and pull requests
- Never push directly to `main`.
- Work on an isolated feature/fix branch and open a pull request targeting `main`.
- Keep each PR focused and reasonably small; split independent work into separate PRs.
- Do not enable auto-merge and do not merge your own PR.
- Include a clear summary, changed-file list, tests executed, and known limitations in the PR description.

## 3. Project technology
- Treat this as an Android/Kotlin project. Inspect the current Gradle configuration before choosing APIs or dependencies.
- Respect the repository's configured Kotlin, Android Gradle Plugin, Gradle, Compose, and Java toolchain versions. Do not upgrade them opportunistically.
- Prefer existing libraries and established project patterns. Do not introduce Hilt, Room, Retrofit, Navigation, WorkManager, or other major frameworks unless the task explicitly authorizes them and the current project architecture supports them.
- Keep Android minimum SDK and target/compile SDK requirements consistent with the current project configuration.

## 4. Core architecture priorities
The current priority order is:
1. GLAFIRA Core: durable SQLite task queue, owner identity/generation, ACK, recovery, backup/restore, dispatcher.
2. Backup and Recovery.
3. Runtime, Bridge, and Desktop integration.
4. Agents and role-specific features.

Do not let lower-priority agent features displace Core, Backup/Recovery, or Runtime/Bridge/Desktop work. HR and other role agents remain a later-priority track.

## 5. Reliability and data safety
- Never log, commit, or expose passwords, tokens, private keys, credentials, personal vault contents, or other secrets.
- Do not weaken authentication, authorization, encryption, signature verification, or owner-approval checks to make tests pass.
- For persistent data changes, provide migration and rollback considerations.
- Handle failures explicitly; avoid swallowing exceptions or reporting success before durable completion.
- Preserve idempotency and recovery behavior for queue/dispatcher operations.

## 6. Testing and CI
- Run the narrowest relevant tests first, then all feasible relevant Gradle checks.
- Do not stop after the first failure. Collect the complete available test/build output and report every distinct failure.
- Never claim a test passed unless it actually ran and passed.
- If a test cannot run in the available environment, state exactly why and what remains unverified.
- Fix failures caused by your changes before requesting review. Do not hide or delete failing tests to obtain a green build.

## 7. Review handoff
- Make the implementation auditable: explain important decisions and trade-offs.
- Add or update tests for behavior changes.
- Mark unfinished work and risks clearly; do not present placeholders as completed functionality.
- Leave final merge approval to the repository owner after CI and independent review.
