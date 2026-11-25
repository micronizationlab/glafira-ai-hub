# GitHub ref compare-and-swap protocol for project leadership

**Status:** SUPERSEDED for runtime coordination by [ADR-0001](../adr/ADR-0001-runtime-coordination-local-core-not-github.md) (owner-approved 2026-11-25). Retained as historical protocol analysis; do not implement or enable GitHub-backed runtime election/claims.

## Authoritative ref

For each enrolled project, maintain one dedicated coordination ref outside `main`, for example:

`refs/heads/glafira-coordination/<project-id>`

The ref points to a commit whose tree contains the canonical coordinator lease, enrolled-node projection and bounded active task-claim index. The record is versioned and binds every mutable claim to a monotonic generation/state version. Never use a GitHub issue comment, local SQLite row, last-write-wins file upload or local device clock as the global authority.

## First-claim bootstrap

A newly enrolled project may not have a coordination ref yet. The initial election must still have exactly one winner:

1. Each eligible contender validates its owner-approved enrollment and creates a candidate root commit containing a generation-1 active lease and its signed node identity.
2. The contender attempts to create the one canonical project ref with GitHub's create-ref endpoint, targeting that root commit.
3. GitHub permits only one ref with that name. Exactly one concurrent create succeeds; all other contenders receive an already-exists/conflict response.
4. A losing contender discards its root proposal, fetches the winner's ref and joins as a contributor. It must not try to update the winner's lease or reuse its own root commit.
5. The winner's created ref is the authoritative initial election. All later renewals and successions use the exact-parent, non-force fast-forward update below.

The project ref name must be derived from a validated, normalized project ID and must not permit path traversal or ambiguous encodings. Bootstrap requires owner-approved node enrollment before any contender may attempt ref creation.

## Claim protocol

1. Fetch the exact current ref SHA and the canonical coordination record.
2. Obtain trusted current time from the HTTPS `Date` response header from GitHub's API. Do not trust the device wall clock or user-controlled Git commit author/committer timestamps.
3. Validate node enrollment, revocation, capabilities, credential epoch, current lease state and candidate eligibility.
4. If the current lease is active, reject a takeover unless a valid signed handoff names this candidate.
5. Build a new commit whose sole parent is the exact ref SHA observed in step 1. The tree contains generation N+1 and a fresh lease ID, heartbeat sequence, expiry and node signature.
6. Update the Git ref with a **non-force fast-forward-only** ref update. Two contenders based on the same parent create sibling commits: only one can advance the ref; the other update is non-fast-forward and must be rejected.
7. On rejection, discard the candidate commit as a losing proposal, refetch the winning ref and re-evaluate from step 1. Never retry the stale commit against a new parent without revalidating eligibility and lease expiry.
8. A successful ref update is the only event that makes the node coordinator. The successful response's resulting ref SHA is recorded as the committed election result.

The implementation must use the GitHub Git Database API to create blobs/trees/commits and the refs API to update the dedicated ref with `force=false`. If the GitHub API or repository policy cannot guarantee non-fast-forward rejection, stop: do not replace the protocol with read-then-write.

## Heartbeat and succession

- Coordinator heartbeat renews the same generation with a strictly increasing heartbeat sequence and a new lease expiry.
- Every renewal is another CAS update from the exact observed ref parent.
- Lease duration and renewal cadence are constants in the protocol version and tested with safety margins. Candidate takeover occurs only after the GitHub server-time estimate is beyond expiry plus the configured skew/grace margin.
- A signed handoff may nominate a target, but it still increments the generation and uses CAS.
- Reconnected former leaders read the newer generation and are fenced from all coordinator writes.
- A revoked node is rejected even if its local lease cache appears active.

## Task claims

Task claims are committed in the same project coordination record and use the same CAS ref. Each claim includes task ID, assigned node, coordinator generation, task state version, lease ID, fencing token, input digest, idempotency key and expiry. A node may claim only an unowned task with satisfied dependencies. A competing claim based on the same parent loses the CAS race. The winner's record is canonical; duplicate delivery is idempotent.

The record must be bounded. If task-claim volume exceeds safe Git object/manifest limits, move to per-task refs with the same exact-parent fast-forward CAS rule; do not weaken atomicity. Garbage collection of expired claims must itself be a CAS update and preserve audit references.

## Outage and partition behavior

If GitHub is unreachable, no new coordinator can be elected, no global lease can be renewed, no shared task can be claimed and no PR can be integrated. Nodes may continue only pre-authorized local work that cannot conflict or cause unapproved external effects. On reconnect, each node reconciles the authoritative ref before writing.

## Required concurrency acceptance

- Launch at least 20 contenders against an uninitialized project; exactly one create-ref succeeds and every loser becomes a contributor.
- Launch at least 20 contenders against one expired generation using the same observed parent; exactly one ref update succeeds.
- Verify all losing sibling commits are rejected as non-fast-forward.
- Verify a losing candidate refetches and cannot promote its stale commit.
- Verify renewal racing with takeover results in either a valid renewal or a single higher-generation takeover, never two leaders.
- Verify stale leader writes and stale task claims are rejected after generation increments.
- Verify revoked nodes cannot win even with a cached credential or lease.
- Verify outage prevents new global ownership and reconnect reconciles without replaying unknown external effects.
