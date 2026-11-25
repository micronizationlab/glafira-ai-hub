# Autonomous repair cycle — mandatory for every helper

**Applies to:** all eleven workstreams, every human/AI contributor, every platform and every task.
**Authority:** subordinate to `docs/GLAFIRA_EXECUTION_CONTROL.md`, owner decisions, security policy and PR/merge policy. It does not grant extra permissions.

## Required behavior when a defect is found

1. **Record evidence, not just a suspicion.** Capture the failing test/log, reproducible scenario, affected component and current commit SHA. Distinguish confirmed defect from hypothesis.
2. **Check ownership before editing.** Search the execution ledger, open PRs, branches and changed files. Reuse an existing Work Item/PR when it owns the same behavior. Never create overlapping implementation.
3. **Fix within assigned scope.** If the defect is in the executor's reserved files and permissions, diagnose the root cause and implement the smallest safe correction in the same work item. A journal entry alone does not satisfy an actionable assigned defect.
4. **Hand off outside scope.** If another component owns the defect, create/link a specific follow-up Work Item or issue with evidence, impact, owner, dependency and acceptance test. Notify that owner; do not edit their files or wait idly if independent work remains.
5. **Test the correction.** Add or preserve a regression test. Run the narrowest relevant test first, then required component/contract checks. Inspect actual logs.
6. **Repeat the loop.** If a test fails, classify the cause (source, test, environment, timeout, cancellation or infrastructure). For a source/test defect within scope, fix and rerun. Continue until the relevant acceptance tests pass or a concrete blocker/permission boundary is reached. Do not loop blindly on transient infrastructure failures.
7. **Check for regressions.** Review the final diff, related callers/contracts and relevant adjacent tests. Do not weaken assertions, remove security checks or broaden permissions to obtain a green result.
8. **Publish an evidence-based result.** Report commit SHA, files, root cause, correction, exact commands/run IDs, passed/failed/skipped checks, remaining gaps and next owner/action. Never describe an unrun, skipped, cancelled or stale check as passed.

## Stop conditions and safety boundaries

Stop implementation and escalate when a fix requires owner approval, secrets, destructive/external/financial side effects, security-policy changes, shared-file ownership conflict, an architectural decision, or scope beyond the approved task. Preserve the evidence and continue independent non-conflicting work.

The protocol does **not** authorize direct pushes to `main`, self-merge, bypassing branch protection/review, parallel CI builds, duplicate PRs, or reopening frozen PR #410. Follow the unique Work Item reservation, PR size limits, serialized build queue, exact-head validation, review and post-merge evidence requirements in the execution control.

## Definition of an actionable defect

A defect is closed only when the correction is implemented and its relevant regression/acceptance checks pass, or when it is explicitly transferred to a named owner with a linked work item and a documented blocker. “Found and logged” alone is not closure.
