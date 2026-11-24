# GLAFIRA CI — durable single-worker build queue design

**Status:** Proposed; not implemented. **Work Item:** `GLAFIRA-CI-W01`. **Baseline:** `148b427bab6e98cb59d68327f4c220d29bbd12c7`.

## Findings

The eight current workflow files have independent event triggers. Android and iOS use `cancel-in-progress: true`; Windows Agent/Desktop, packages and contracts do not share a build-slot policy. iOS package/app validation appears in Android change-aware CI, standalone iOS CI and contracts CI. Windows Desktop Hub tests appear in both Hub and Desktop UI workflows. The package workflow can build Agent and Desktop packages in parallel. A single static concurrency group across all current workflows is unsafe: GitHub retains one running and one pending run per group, and a newer pending run may replace an older pending run.

The specific cancellations of old iOS runs were not proven to have been caused by concurrency. Treat the workflow settings as a concrete risk, not a retroactive root-cause claim.

## Decision

Use a **durable PR-label queue** as source of truth and a **single worker workflow** as the only scheduler of platform validation. The worker uses one static concurrency group with `cancel-in-progress: false`. Worker wake-up events may coalesce because queue records persist independently as labels; the event is only a wake-up signal, not the queue item.

Labels:
- `ci-queued`: awaiting validation.
- `ci-running`: claimed; worker run ID, PR number, base SHA and head SHA recorded in a bot comment.
- `ci-validated`: all required lanes passed for that exact head/base pair.
- `ci-validation-failed`: a required lane failed; failure class/log links recorded.
- `ci-retry-approved`: owner-authorized retry after a documented diagnosis.

A small enqueue workflow adds `ci-queued` on opened/reopened/synchronize/ready-for-review events. It must not checkout or execute PR code. A privileged PR-target event may only label the PR; no scripts or files from the PR may be run in that context.

## Worker algorithm

1. Wake on enqueue events and a periodic schedule; use one global worker concurrency group, `cancel-in-progress: false`.
2. Enumerate open, non-draft PRs labelled `ci-queued`; select deterministic FIFO order (enqueue time, then PR number).
3. Read current base and head SHAs from GitHub immediately before claiming; never trust a stale webhook payload.
4. Claim one PR and record worker run ID, PR number, base SHA and head SHA.
5. Dispatch one canonical validation workflow and wait for its terminal result before selecting another PR.
6. Run selected lanes sequentially: change detection → Android JVM → APK → emulator when required → iOS → Windows Agent → Desktop Hub/UI → contracts → exact-head summary. Change-aware skipped lanes are not passes.
7. Re-read the PR after validation. If closed, draft, or base/head changed, do not mark the new revision validated; enqueue the current revision.
8. On success, record lane results and artifact digests, then replace `ci-running` with `ci-validated`.
9. On failure, preserve logs and failure class, mark `ci-validation-failed`, and stop automatic retries. Retry only on a new SHA or owner-approved, diagnosed infrastructure retry.
10. If the worker crashes/cancels, keep durable queue state. A scheduled recovery pass reconciles stale `ci-running` claims against Actions run state and requeues only after proving the prior worker is terminal.

The durable labels make coalesced wake-up events safe: if several events arrive while a worker is running, the pending wake-up can be replaced without erasing any PR queue record. A scheduled poll recovers a missed wake-up.

## Validation workflow contract

Inputs: `pr_number`, `expected_base_sha`, `expected_head_sha`, `queue_worker_run_id`.

The validation workflow must checkout `refs/pull/<number>/merge`, verify its base/head parents match the supplied SHAs, and run with read-only permissions/no write-capable secrets. It publishes a machine-readable per-lane result (`success|failure|skipped|cancelled`), exact SHAs, test reports, artifact SHA-256 digests, and fails if a required lane fails/cancels or an unexpected lane is skipped. The worker never executes PR-controlled scripts. Manual dispatch/direct push triggers on platform workflows must be removed or restricted so they cannot bypass the queue.

## Migration

**A — inventory (current):** map every trigger, job, test, artifact and platform owner; choose one canonical iOS, Desktop Hub and contracts lane; leave workflows unchanged until coverage is mapped.

**B — canonical validation:** consolidate lane ownership into one validation workflow; preserve commands/artifacts first; verify merge-ref base/head; chain jobs sequentially.

**C — durable worker:** add enqueue labels, one non-cancelling worker, claim/lease recovery and exact-head revalidation. Pilot with one PR only. Do not bulk-enqueue the historical open-PR backlog.

**D — remove bypasses:** remove direct PR/push triggers only after the queue path is proven; remove duplicate iOS/Desktop Hub jobs only after canonical coverage is demonstrated; keep the project dispatcher separate because it inventories work and does not build artifacts.

## Acceptance tests

- At most one worker and one PR's platform validation active repository-wide.
- Three PRs enqueued rapidly are all processed; no work is lost when wake-ups coalesce.
- A PR updated while waiting is validated only at its latest base/head SHA.
- A worker cancellation leaves recoverable queue state.
- An infrastructure failure is not retried without owner authorization/diagnosis.
- Each required platform lane has one canonical path; no duplicate iOS/Desktop Hub/package lane.
- Failed, cancelled or skipped checks are never reported as full pass.

## Explicit non-decisions

Do not apply one static global group to all current workflows, rely on Actions concurrency as the durable queue, bulk-enqueue old PRs, blindly rerun cancelled iOS checks, or claim the queue is already implemented. This is a design proposal only.
