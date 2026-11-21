# External AI contributor onboarding

**Purpose:** let friends and their own AI coding assistants contribute independently to GLAFIRA without mixing credentials, task scopes or unreviewed changes.

**Priority:** P4 collaboration enablement. This does not displace P1 Core, P2 Backup & Recovery or P3 Bridge/Desktop.

## 1. Two identities must not be confused

1. **Human/GitHub identity:** each friend uses their own GitHub account. The repository owner grants the minimum repository role needed for a specific collaboration.
2. **AI/tool identity:** the coding agent uses the friend's own provider account, subscription or explicitly configured API credential. No one shares the owner's ChatGPT/Copilot/Cursor credentials or personal tokens.

A model name (DeepSeek, GigaChat, Gemini, Claude, ChatGPT) is not proof that the product has GitHub write access. Before assigning a task, verify that the actual agent product can clone/read the repo, create a branch, commit/push, open a PR and report exact-head CI.

## 2. Verified contribution paths

| Tool / path | Verified GitHub contribution capability | Account / credential model | GLAFIRA use |
|---|---|---|---|
| Cursor Cloud Agents | Cloud agents can work on a GitHub-mirrored repository and open GitHub PRs. Cursor documents GitHub app connection and per-repository access. | Each contributor connects their own Cursor account and GitHub identity; repository installation permissions still apply. | Strong candidate for an independent design or bounded implementation lane. |
| OpenAI Codex Cloud | Cloud tasks can use GitHub repositories connected to the signed-in ChatGPT account; each task runs in an isolated workspace and results must be reviewed/committed. | Each contributor signs in with their own ChatGPT account and connects their own GitHub account. | Candidate for bounded implementation/review tasks; no sharing of the owner's ChatGPT session. |
| GitHub Agentic Workflows | GitHub documents workflows using supported agents including Claude Code, OpenAI Codex, Gemini CLI and Copilot CLI; safe PR output can propose changes for human review. | GitHub Actions plus the selected agent's own required credential/identity. | Useful for controlled automated review, documentation and narrowly scoped PR generation. |
| Gemini CLI + GitHub Actions | Google's maintained GitHub Action documents PR review, issue triage, code analysis and code modification from GitHub events/comments. | Requires a separately configured Gemini credential and GitHub Actions permissions. | Candidate after a small proof-of-capability run and free-quota/region check. |
| Claude Code in GitHub Agentic Workflows | GitHub's Agentic Workflows documentation describes Claude as a supported engine; setup requires Anthropic API credentials or supported workload identity. | Contributor/workspace-owned Anthropic credential; never copy another participant's secret. | Candidate only if the contributor has permitted access and a suitable quota. |
| GitHub Copilot coding agent | GitHub's current agent ecosystem supports issue/PR-based coding-agent sessions, but eligibility depends on the relevant Copilot plan and repository settings. | Each participant's own eligible GitHub/Copilot account. | Optional; do not assume a free entitlement. |

Official references:
- Cursor GitHub integration: https://cursor.com/docs/integrations/github
- Cursor Cloud Agents: https://cursor.com/docs/cloud-agent/overview
- Codex Cloud: https://help.openai.com/en/articles/20001545-using-codex-cloud
- GitHub Agentic Workflows: https://docs.github.com/en/actions/tutorials/develop-agentic-workflows-in-github-actions
- GitHub Agentic Workflows FAQ: https://github.github.com/gh-aw/reference/faq/
- Google Gemini CLI GitHub Action: https://github.com/google-github-actions/run-gemini-cli
- GitHub Claude/Codex coding agents announcement: https://github.blog/changelog/2026-02-04-claude-and-codex-are-now-available-in-public-preview-on-github/

## 3. Providers not yet approved as standalone GitHub coding agents

DeepSeek and GigaChat may be useful model providers, but this onboarding contract does not assume either account by itself can create GitHub branches or PRs. They may be considered only after a concrete, supported agent host/CLI or integration is demonstrated with the contributor's own credentials. Until then, classify them as **model consultation only / GitHub write path unverified**.

The same rule applies to Alice or any future provider. Do not give a model provider access to the repository merely because its chat interface can discuss code.

## 4. Mandatory repository safety gates

**Current blocker:** the repository's main branch was last verified as unprotected. See [Issue #370](https://github.com/micronizationlab/glafira-ai-hub/issues/370). Do not invite external accounts with write access until the owner/admin enables and verifies:

- PR-only changes to main, no direct pushes or force pushes;
- required owner-authorized review;
- exact-head CI checks selected for the changed components;
- no administrator bypass unless the owner explicitly chooses it;
- CODEOWNERS after real participant GitHub usernames and scopes are known.

Until these controls are active, a written instruction to "use a branch" is not a technical barrier against a collaborator pushing to main.

## 5. One participant = one reservation = one PR

Every participant must:

1. use their own GitHub account and own AI-tool account;
2. read GLAFIRA_COORDINATION.md and this onboarding contract;
3. create a participant record under docs/coordination/participants/;
4. reserve one exact issue, branch, path set and acceptance criterion;
5. avoid files owned by another lane unless central GLAFIRA approves a handoff;
6. work on a dedicated branch and open a PR; never merge their own work;
7. report the exact head SHA, CI run, executed tests, skipped jobs, logs and remaining risks;
8. stop and report a conflict rather than force-push, rewrite another participant's branch or silently change scope.

No credentials, tokens, provider cookies, private chat exports or local-only vault data may enter the repository, issues, PRs, logs or test artifacts.

## 6. Pilot before scaling

Start with one external participant and a documentation-only or isolated UI task. Confirm that the participant can complete the full loop: read assigned scope → create branch → make change → run checks → push → open PR → provide exact-head evidence. Then test a second participant on a non-overlapping lane.

The number of participants is intentionally unbounded by design. The control plane should support one or many participants through explicit reservations, file ownership and PR review; it must not assume a fixed friend count.

## 7. What this does not authorize

- No automatic merge to main.
- No permission to change Core ownership, authentication, backup encryption, Bridge pairing or provider credentials without a P1/P2/P3 issue and explicit review.
- No shared GitHub PAT, provider API key, ChatGPT login or other account secret.
- No claim that a model has native GitHub access until a real PR from its supported host has been verified.
