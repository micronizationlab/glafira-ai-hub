# GLAFIRA Project Dispatcher — v1

Status: implementation branch / draft PR pending CI.

## Purpose

The dispatcher prevents parallel engineering work from becoming siloed. Every reconciliation pass inventories all open pull requests, reads their current head SHA, and finds the newest Actions run that matches that exact SHA. It produces one cross-workstream report in the GitHub Actions job summary.

## Triggers

- Completion of the repository's main Android/Desktop validation workflow.
- Completion of the Windows Agent workflow.
- Completion of the GLAFIRA Java Format Check workflow, including validation after a PR base retarget.
- Pull request opened, synchronized, reopened, or marked ready.
- Scheduled reconciliation every 15 minutes.
- Manual workflow dispatch.

The workflow is read-only: it has `contents: read`, `pull-requests: read`, and `actions: read`. It does not merge, close, retarget, push, or rerun workflows.

## Correctness rules

1. Inspect all open PRs, not only the PR that triggered the latest event.
2. For fallback Actions evidence, require exact PR number, exact current head SHA and a non-empty exact current base SHA. Older-head/base runs are stale.
3. Re-read PR refs after collecting runs. If refs moved, render against latest refs; if the second inventory read fails, fallback status is UNKNOWN.
4. Exclude METADATA_ONLY only for GLAFIRA Java Format Check; similarly named commit titles in other workflows remain valid CI evidence.
5. Distinguish queued/in-progress from completed success/failure/cancelled.
6. Rollup checks are head-associated only and must be labelled BASE NOT VERIFIED.
7. Paginate open-PR inventory. Cap workflow history at the newest 1,000 runs and fetch it only when fallback evidence is needed.
8. Keep Draft PRs Draft. Human review and integration remain separate decisions.
9. Never alter branch dependency stacks or merge into main.
10. Escape contributor-controlled PR titles and branch names before inserting them into Markdown.
11. PR-triggered renderer tests use per-PR concurrency; each workflow_run completion gets its own run-ID group; scheduled/manual reconciliations use unique run IDs.

## Current operational workflow

When the report identifies a newly completed run:
- success on exact PR/head/base fallback match: inspect job-level results/artifacts and continue that workstream;
- rollup success: treat as head-only evidence until the merge-base identity is independently verified;
- failure on current SHA: inspect failed job logs and fix the cause;
- in progress: leave it running and continue another ready workstream;
- no run for current SHA: inspect workflow triggers and decide whether a validation run is required.

## Boundaries / known limitations

- This v1 is a status reconciler and dashboard, not an autonomous code-writing agent.
- It does not poll continuously; the schedule is every 15 minutes and workflow completion events provide faster updates.
- GitHub Actions scheduling can be delayed during platform load.
- It does not download or inspect test artifacts automatically; a later version can add focused JUnit/artifact parsing after the basic status loop is validated.
- It cannot monitor local laptop processes or the owner's physical Android device.
- It must not expose secrets or copy repository contents into public logs.

## Next implementation steps

1. Validate the workflow syntax and confirm it triggers on the repository's actual CI workflow name.
2. Confirm it lists every active PR and matches run head SHAs correctly.
3. Add job-level status and artifact summaries.
4. Add explicit workstream ownership/priority metadata in a small manifest.
5. Consider safe automated failed-job reruns only after retry classification and owner approval rules are defined.
