# PR Size and Merge Policy

**Status:** mandatory engineering rule  
**Applies to:** GLAFIRA Core, Android, Windows Desktop/Agent, iOS, shared modules, documentation, tests, and every human or AI contributor.

## 1. Hard PR size limits

Measure handwritten change size as GitHub's total changed lines: additions + deletions (not the net difference).

| Class | Changed lines | Files changed | Rule |
|---|---:|---:|---|
| Tiny | 1–50 | 1–3 preferred | Preferred for isolated fixes |
| Standard | 51–150 | Up to 5 | Normal target for one focused task |
| Upper limit | 151–200 | Up to 5 | Allowed only when the change is still one coherent, independently testable unit |
| Exception | 201–300 | Up to 10 | Requires written justification and explicit owner/integrator approval before implementation begins |
| Oversized | More than 300 | Any | Prohibited as a single ordinary PR; split into smaller PRs |

**Default target: 50–150 changed lines and no more than 5 files. Absolute normal limit: 200 changed lines.** A PR must not exceed 200 lines merely because its author believes the change is convenient. If it does, split it. An exception from 201 to 300 lines is not automatic and must be agreed in advance; a retrospective explanation is not approval.

The file limit is independent of the line limit: a 100-line PR touching 15 unrelated files is still too broad. If a task genuinely needs more than 5 files, split it by dependency or obtain prior approval under the exception rule.

### What counts

- Count additions + deletions shown by GitHub for the PR.
- Do not hide size by squashing commits, moving code, or reporting net line counts.
- Tests and documentation required for the change count toward the limit. Do not omit them to make a PR smaller; split the work into coherent, testable slices instead.
- Generated code, lockfiles, snapshots, vendored files and binary artifacts must be listed separately with their generation/source rationale. They are not a loophole for oversized handwritten changes and still require review.
- A purely generated or mechanically migrated artifact may use a separate PR only with a reproducible generation procedure, provenance, validation and explicit reviewer agreement.

## 2. One PR = one logical change

- One PR addresses one defect, one issue, or one tightly bounded implementation step.
- Do not combine Android + iOS + Windows + Core + Backup + Bridge changes merely because they belong to one feature.
- Do not mix feature work with unrelated refactoring, formatting, dependency upgrades or documentation cleanup.
- Split cross-platform work into dependency-ordered PRs. Keep each PR buildable and testable whenever possible.
- A parent issue or tracking PR may describe the whole initiative; it must not become a container for an unreviewable implementation batch.
- Before editing, inspect current main, open PRs and changed-file overlaps. Never duplicate an active or already merged implementation.

## 3. Required PR description

Every PR must state:
1. Problem, intended outcome and acceptance criteria.
2. Exact scope: what is included and explicitly excluded.
3. Changed-line count and changed-file count; explain any exception or generated artifacts.
4. Exact files/components affected.
5. Tests and commands actually run, with real results.
6. CI status per relevant job: passed, failed, skipped, cancelled or pending.
7. Risks, security/privacy implications, compatibility, migration and rollback notes.
8. Dependency, predecessor and follow-up PR links.
9. Evidence that the branch is based on a current, appropriate base.

Never claim a test passed if it was skipped, cancelled, not run or run on a different commit.

## 4. Review and merge gate

- The designated GLAFIRA integrator reviews the complete diff before merge.
- Inspect architecture boundaries, security, data handling, backward compatibility, scope and file overlap.
- Inspect CI job results and logs, not just a green summary.
- Re-run relevant checks after the final commit/rebase; checks from an older SHA do not validate a new head.
- A failed, missing, cancelled or inconclusive required check is not a pass.
- Keep broad or unclear PRs in Draft and request a split.
- Merge only after required checks pass, blocking findings are resolved and the owner/authorized maintainer approves.
- Verify the resulting main SHA and preserve a practical rollback path.

## 5. Contributor and assistant rules

- Work on a dedicated feature branch and open a focused PR. Never push directly to main.
- These size limits apply equally to Berestovkon and every other human/AI helper.
- Do not create a second PR for files or behavior already owned by another active PR without coordination.
- Do not merge, force-push, rewrite shared history, bypass branch protection, weaken checks or change repository security controls.
- If permissions prevent a safe merge, report the blocker and leave the PR open.
- If the task cannot fit the limits, stop and propose a split plan before writing a large batch.

## 6. How to split a large task

For work exceeding 200 changed lines:
1. List the independently reviewable slices and their exact file ownership.
2. Identify dependencies and order the slices from foundations to dependent behavior.
3. Create one PR per slice, each targeting 50–150 changed lines and at most 5 files where practical.
4. If an indivisible slice is 201–300 lines, obtain prior owner/integrator approval and document why it cannot be split.
5. Validate each exact PR head independently; run broader regression tests after integration.
6. Keep a parent issue/register with links and status for all child PRs.

For a historical oversized PR (including #410), freeze the source branch. Extract only unique, unmerged changes onto fresh branches from current main; do not rebase or merge the aggregate wholesale.

## 7. Exceptions

An exception is valid only when the owner/integrator approves it in writing before implementation begins. Approval at PR submission is retrospective and invalid. The PR must explain why the change is indivisible, identify every changed file, provide focused tests and state the review risk.

- 201–300 changed lines: exceptional approval required.
- More than 300 changed lines: no ordinary implementation exception; split the work.
- Security, credential, ownership, backup/restore and migration changes should be smaller than the general limits whenever possible, not larger.

**Default decision: split first, ask before exceeding the limit, and prefer the smallest independently testable PR.**


## 8. File-count-only exception and approval timing

The file limit is independent of the line limit. A PR with 1–200 changed lines that needs 6–10 files is not automatically allowed by the 201–300-line exception row.

- A **file-count-only exception** may be approved only for one coherent, independently testable change with no more than 200 changed lines and 6–10 explicitly listed files.
- Written owner/integrator approval must be recorded **before implementation begins**. The request must state why the files cannot be split, list every path, name the executor/reviewer, and identify the focused validation.
- This exception does not permit 201–300 lines unless the separate line-count exception is also approved before implementation. If both limits are exceeded, both conditions must be satisfied in one written approval.
- More than 10 files or more than 300 changed lines must be split; no exception is available under this policy.
- Approval at PR submission, after implementation, or after review is retrospective and invalid. Execution-control, WBS, branch, helper and design-fallback documents must repeat this same pre-implementation deadline without alternative wording.
