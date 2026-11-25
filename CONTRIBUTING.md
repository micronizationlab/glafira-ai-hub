# Contributing to GLAFIRA

This repository contains a multi-platform, owner-controlled system. Correctness, recoverability and explicit authority boundaries take precedence over change volume.

## Before starting

1. Read [the architecture and execution model](docs/ARCHITECTURE_AND_EXECUTION_MODEL.md) and the applicable normative contracts.
2. Read [coding standards](docs/CODING_STANDARDS.md), [onboarding](docs/ONBOARDING.md) and [the current roadmap](docs/IMPLEMENTATION_ROADMAP.md).
3. Check the current `main` SHA, open issues, active PRs, workflow runs and the execution ledger. Documentation snapshots are not live project state.
4. Confirm scope, dependencies, acceptance criteria and the decision owner before changing shared contracts.

## Work units and branches

- One PR must represent one independently reviewable task.
- Branch from the latest `main`; use a descriptive name such as `fix/core-durable-ack` or `docs/architecture-map`.
- Keep changes focused. The normal target is at most 400 changed lines; split larger work into dependency-ordered PRs.
- Do not edit the same shared contract or source file concurrently from multiple workstreams. Assign one owner and integrate dependent changes in sequence.
- Do not commit directly to `main`, force-push shared branches or rewrite another contributor's history.

## Required implementation evidence

Every behavior change must include the smallest relevant regression test and, where appropriate, integration, fault-injection or platform acceptance tests. The PR must state:

- the reproduced problem and expected behavior;
- exact files/modules and public contracts changed;
- commands and exact-head CI results;
- tests that were skipped, cancelled or unavailable;
- security/privacy implications and migration/rollback plan;
- remaining physical-device or end-to-end acceptance.

A build, a documentation entry, a green historical run or a skipped check is not proof that a feature works. Validate the exact PR HEAD and revalidate after rebasing or changing code.

## Authority and safety

- The owner retains final authority over architecture, credentials, approvals, releases and external side effects.
- Models and agents are untrusted planners/executors, not permission authorities.
- Runtime task ownership belongs to the owning GLAFIRA Core; GitHub is not a runtime queue, lease service or global coordinator.
- External, financial, publishing and destructive actions require the exact owner grant/approval defined by the relevant policy.
- Default to DRY_RUN until the live path has explicit authorization and acceptance evidence.
- Never commit secrets, tokens, passwords, private keys, personal data or real customer data. Use fixtures with synthetic values.
- Never weaken tests or remove a safety gate merely to obtain a green workflow.

## Review and merge

- Self-review the complete diff before requesting review.
- At least one independent approval and all required exact-head checks are expected before merge.
- Resolve review comments explicitly; a `COMMENTED` review is not an approval.
- Merge only when the PR is current with its base and the required checks have completed successfully.
- After merge, record the new `main` SHA and verify post-merge CI. Update the execution ledger only for work actually integrated and accepted.

## Questions and architecture changes

Use the GitHub issue forms for bugs, feature proposals and architecture discussions. A material architecture change requires an ADR under `docs/adr/`; a proposal is not an accepted decision until the owner approves it. Preserve superseded documents for traceability and mark their status clearly.
