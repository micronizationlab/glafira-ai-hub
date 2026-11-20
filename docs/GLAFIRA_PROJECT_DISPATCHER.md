# GLAFIRA Project Dispatcher — v1

Status: implementation branch / draft PR pending CI.

## Purpose

The dispatcher prevents parallel engineering work from becoming siloed. Every reconciliation pass inventories all open pull requests, reads their current head SHA, and finds the newest Actions run that matches that exact SHA. It produces one cross-workstream report in the GitHub Actions job summary.

## Triggers

- Completion of the repository's main Android/Desktop validation workflow.
- Pull request opened, synchronized, reopened, or marked ready.
- Scheduled reconciliation every 15 minutes.
- Manual workflow dispatch.

The workflow is read-only: it has `contents: read`, `pull-requests: read`, and `actions: read`. It does not merge, close, retarget, push, or rerun workflows.

## Correctness rules

1. Inspect all open PRs, not only the PR that triggered the latest event.
2. Match CI to the exact current PR head SHA. A green run for an older SHA is stale and must not count as acceptance.
3. Report missing current-SHA runs explicitly.
4. Distinguish queued/in-progress from completed success/failure/cancelled.
5. Keep Draft PRs Draft. Human review and integration remain separate decisions.
6. Never alter branch dependency stacks or merge into `main`.
7. After a CI completion, the next dispatcher pass re-evaluates every active workstream, so another branch's newly completed run is not overlooked.

## Current operational workflow

When the report identifies a newly completed run:
- success on current SHA: inspect job-level results/artifacts and continue that workstream;
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
