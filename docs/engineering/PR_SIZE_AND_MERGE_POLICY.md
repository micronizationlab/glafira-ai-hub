# PR Size and Merge Policy

**Status:** mandatory engineering rule  
**Applies to:** GLAFIRA Core, Android, Windows Desktop/Agent, shared modules, documentation, and every human or AI contributor.

## 1. Size limits

Measure PR size as the sum of additions and deletions shown by GitHub (the `changes` count), not the net line-count difference.

| Class | Changed lines | Required handling |
|---|---:|---|
| Small | 1–50 | Preferred for fixes and focused changes |
| Normal | 51–200 | Target range for a feature or coherent task |
| Large | 201–300 | Exception; explain why it cannot be split and request careful review |
| Oversized | More than 300 | Do not submit as one PR; split into independently reviewable PRs |

The normal target is **50–200 changed lines**. A PR should usually touch no more than **5 files**. These are reviewability targets, not a reason to omit necessary tests or documentation.

Generated files, lockfiles, snapshots, migrations, and vendored artifacts must be identified separately in the PR description. They do not make a large handwritten code change acceptable. If a generated artifact is unavoidable, explain how it was produced and how it was checked.

## 2. One PR, one logical change

- Each PR addresses one issue, defect, or tightly related implementation step.
- Avoid mixing refactoring, formatting, dependency upgrades, feature work, and unrelated fixes.
- Split broad work into a sequence of small PRs with explicit dependencies.
- Keep each intermediate PR buildable and testable whenever technically possible.
- Do not split changes merely to evade review or size limits; preserve coherent, reviewable units.

## 3. Required PR description

Every PR must state:
1. The problem and intended outcome.
2. The exact scope and files/components affected.
3. Tests run and their actual results (do not claim tests that were not run).
4. CI status or a clear note that CI is still pending/unavailable.
5. Risks, compatibility considerations, migration/rollback notes where relevant.
6. Links to prerequisite or follow-up PRs when the work is split.

## 4. Review and merge gate

The designated GLAFIRA integrator reviews every assistant/contributor PR before it enters `main`.

Required checks:
- Inspect the complete diff, not only the PR summary.
- Check architecture boundaries, security, data handling, backward compatibility, and scope.
- Run relevant unit, integration, build, and static checks where available.
- Inspect CI job results and logs; a green summary alone is not sufficient when a job has warnings or suspicious output.
- Request changes or split the PR if it is unclear, too broad, or difficult to validate.
- Merge only after required checks pass and no blocking review findings remain.
- Verify the resulting `main` state and record the merge commit; keep a practical rollback path.

A failed, missing, or inconclusive required check is not a pass. Do not merge on the assumption that CI will succeed later.

## 5. Contributor and assistant rules

- Contributors and AI assistants work on dedicated branches and open PRs; they must not push directly to `main`.
- Assistants must follow the same size limits and review gate as human contributors.
- The designated integrator owns final review and merge decisions, subject to repository permissions and branch protection.
- Never disable branch protection, bypass required reviews/checks, or weaken repository security to force a merge.
- If permissions prevent a safe merge, report the blocker and leave the PR open; do not work around the protection.

## 6. Splitting a large task

For work exceeding 300 changed lines:
1. Write a short implementation plan and identify independent slices.
2. Order PRs by dependency (foundations first, dependent behavior afterward).
3. Keep each PR within the normal 50–200-line target where practical.
4. Test each slice independently and run broader regression checks after the series is integrated.
5. Track the full task with an issue or parent PR description.

## 7. Exceptions

An exception above 200 lines requires a written reason in the PR description. An exception above 300 lines is not allowed for ordinary handwritten changes; split the work. A technically indivisible migration or generated artifact may be handled separately only with an explicit explanation, focused review, and suitable validation.

**Default decision:** when in doubt, make the PR smaller.
