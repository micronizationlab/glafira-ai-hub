# Eleventh permanent workstream: helper intake and PR integration

**Status:** owner-approved operating rule, effective immediately.

## Purpose

The primary integration responsibility of the GLAFIRA coordinator is to collect completed work from assistants and contributors, verify that it is correct and compatible, and integrate suitable changes into the protected `main` branch as soon as the required gates are satisfied.

This is a permanent eleventh workstream. It runs at the beginning of every work session and remains active throughout the session. It does not replace the other ten workstreams; their order and effort are assigned by current owner priorities.

## Required start-of-session procedure

1. Inspect the current `main` SHA and all open PRs, not only the PRs mentioned in the previous handoff.
2. Inspect helper/contributor activity since the previous review: new commits, branches, PRs, issue updates, CI runs, review comments and requested changes.
3. Identify completed work that is not yet represented by a PR, and ask for or prepare a focused PR when the work is recoverable and authorized.
4. Build a dependency-aware integration queue. Detect duplicates, overlapping files, superseded implementations, stale base branches, oversized PRs and stacked PR dependencies.
5. Review candidate PR code and tests against the current `main`, architecture, security boundaries, compatibility and owner decisions.
6. Integrate suitable changes in small, dependency-ordered slices. Prefer already completed helper work over reimplementing it.
7. After every merge, verify the new `main` SHA, the merge commit, and post-merge CI. Record regressions and use the documented rollback/revert path when needed.
8. Report what was merged, what remains blocked, who owns each next action, and the exact evidence. Never report work as merged or tested without confirming it.

## Merge gates

A PR may be merged only when all of the following are true:

- It targets the current `main` and its exact head SHA has been reviewed.
- The change is within the repository PR-size policy and is one independently reviewable logical task.
- Required checks for the affected components passed on that exact head; skipped, cancelled, queued or stale checks are not passes.
- Relevant tests and security/architecture review have no unresolved blocking findings.
- Dependencies are already merged or are intentionally integrated in the correct order.
- Required owner/maintainer approval and repository branch protection are satisfied.

Do not merge merely because GitHub reports `mergeable=true`. Do not bypass protections, falsify checks, or merge a stale branch. Helpers must not merge their own PRs. The coordinator performs integration only when repository permissions and required approvals allow it.

## Priority rule

PR collection, review, consolidation and safe integration are the coordinator's primary continuous responsibility. The other ten workstreams remain active, but their implementation order and resource allocation are determined by the owner's latest priorities and by dependencies needed to unblock safe integration.

A large historical PR must be decomposed into unique, necessary, independently tested slices; never merge an oversized aggregate to make the backlog appear smaller.

## Session report

Keep a concise live register with:

- current `main` SHA;
- helper work discovered since the last session;
- PRs ready, blocked, stale, duplicate or obsolete;
- dependency order and next smallest action;
- exact-head CI outcomes and skipped coverage;
- merges and post-merge verification.

This rule is permanent unless the owner explicitly changes it.
