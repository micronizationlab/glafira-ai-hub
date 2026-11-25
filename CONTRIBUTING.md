# Contributing to GLAFIRA

Thank you for considering a contribution to GLAFIRA. The project is developed in the open, and voluntary contributions are welcome. There is currently no promise of payment, employment, or guaranteed acceptance for contributions.

## Start here

1. Read the root [README](README.md) and the [collaborator handoff](docs/GLAFIRA_COLLABORATOR_HANDOFF.md).
2. Read the issue you want to work on completely, including its acceptance criteria and dependencies.
3. Before coding, leave a comment describing your proposed approach. Wait for maintainer confirmation when the issue asks for it or touches security, ownership, persistence, credentials, or cross-device execution.
4. Work in your own fork and branch. Open a pull request against `main`; do not ask for direct write access.
5. Explain what changed, why, how it was tested, and what remains unverified.

## Good first contribution areas

Look for issues labeled `help wanted` or `good first issue`. Small documentation improvements, focused tests, isolated UI fixes, and reproducible bug reports are preferred starting points. Large architecture issues are not automatically beginner-friendly.

Current community contribution candidates:

- [Yandex Cloud provider adapter](https://github.com/micronizationlab/glafira-ai-hub/issues/375)
- [Windows Agent bounded result artifacts](https://github.com/micronizationlab/glafira-ai-hub/issues/384)
- [Backup provider read-back and clean-device restore acceptance](https://github.com/micronizationlab/glafira-ai-hub/issues/387)

## Project priorities and boundaries

GLAFIRA is an owner-controlled personal AI platform. Contributions must preserve these invariants:

- The Core and its durable task state remain authoritative; transport/Hub components must not become a second Core.
- Owner identity and owner-generation fencing must fail closed. A lost heartbeat is not permission to take ownership.
- No stale owner generation or expired claim may execute work or commit results.
- Credentials, tokens, recovery secrets, and private user data must never be placed in prompts, logs, telemetry, Git, issues, or ordinary backups.
- Never add shell execution, arbitrary command execution, unrestricted filesystem access, or unrestricted network fetch to an Agent handler.
- Do not weaken approval gates, branch protection, CI, or security checks to make a change pass.
- Do not claim a feature is production-ready based only on compilation or a mocked test.

If a change conflicts with an invariant, stop and ask before implementing it.

## Development and verification

Use the toolchain and commands documented by the affected module and workflow. At minimum:

- Run the narrowest relevant unit tests.
- Run shared contract tests when changing a cross-platform contract.
- Run the applicable Android or Windows build/test workflow.
- Report skipped tests explicitly; skipped is not passed.
- Include the exact commit SHA tested when reporting CI results.

Do not invent test results. If you cannot run a required test locally, explain why and identify the CI or device acceptance still needed.

## Security and data handling

- Never commit API keys, passwords, personal data, certificates, keystores, recovery phrases, or real account exports.
- Use synthetic fixtures and secret canaries in tests.
- Keep examples and logs redacted.
- Do not request production credentials from maintainers. Live provider smoke tests must be opt-in and use maintainer-controlled secrets in CI.
- Report a suspected vulnerability privately through the repository's GitHub security reporting mechanism rather than publishing exploit details in an issue.

## Pull request expectations

A useful PR includes:

- A concise problem statement and implementation summary.
- The issue number it addresses.
- Files/modules changed and any contract or migration impact.
- Tests run, exact results, and tests skipped.
- Screenshots only when a UI change needs them; never include private data.
- Known limitations and rollback considerations.

All contributions are reviewed. A PR is not accepted until the maintainer has reviewed the code and required checks have passed. The project owner retains final authority over architecture, releases, and merges.

## Communication

Please be respectful, specific, and transparent. If you are unsure about scope, ask before spending time on an implementation. A small, reviewable PR is preferred over a broad rewrite.
