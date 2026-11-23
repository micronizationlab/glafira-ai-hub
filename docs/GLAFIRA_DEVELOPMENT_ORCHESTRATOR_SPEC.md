# GLAFIRA Development Orchestrator

**Status:** owner-approved architecture and Android experimental-build workstream.  
**Tracking:** issue [#446](https://github.com/micronizationlab/glafira-ai-hub/issues/446).  
**Related foundations:** #367 (Multi-AI providers), #397/#407/#408 (Android GitHub and owner security), #77 (Task/Result/Lease/Agent contracts), #394 (provider data-sharing policy).

## 1. Product decision

GLAFIRA can conduct several independent workstreams at once. Software development is one of its native work modes, not a separate product.

Within a software-development project, the priority order is:

1. **Coordinate connected AI providers first.** Discover real provider capabilities and availability; decompose work; dispatch suitable tasks; collect and compare outputs; coordinate integration.
2. **GLAFIRA actively develops second, in parallel.** GLAFIRA designs, codes, reviews, integrates, repairs and takes tasks that providers cannot or do not complete. It is a contributor, not only a dispatcher.
3. Build, test, bounded repair and evidence collection.
4. Publish reviewable branch commits and draft PRs through the owner-authorized GitHub path.

The owner remains the final authority. Providers and generated code never grant themselves permissions.

## 2. Android experimental MVP

Android is the first control-plane implementation target. The feature must be visibly marked experimental until the acceptance matrix passes.

MVP capabilities:
- Create/open an owner-approved project workspace and record its repository, base commit, language, build system, modules and test commands.
- Show connected providers and their verified capabilities, consent, data-sharing scope, free-tier/quota status, rate limits and health.
- Decompose a request into a bounded dependency DAG.
- Assign independent tasks to suitable providers in parallel; schedule dependent tasks only after prerequisites complete.
- Assign GLAFIRA its own coding/review/integration tasks concurrently with provider work.
- Accept plain text, fenced code and unified diffs; normalize them into typed artifacts.
- Show per-task ownership, status, output, conflict, approval and test evidence.
- Persist progress and recover after process death or network loss.

Heavy builds are not required to run on the Android phone. Android is the control plane; a paired Windows Desktop/Agent or another owner-approved free runner may perform bounded builds. A runner must be explicitly trusted and enrolled.

## 3. Core integration — one authority

Reuse the application-scoped `GlafiraCoreRuntimeOwner`, its durable queue, task-plan DAG, dependency scheduling, owner-generation fencing, Trace, ACK and recovery mechanisms.

Do not create:
- a second task queue;
- a parallel owner/permission system;
- provider-specific queues that bypass Core;
- a direct GitHub path outside the existing owner-authorized session and policy gates.

The Core remains responsible for authorization, project scope, context assembly, task lifecycle, idempotency, result reconciliation and audit.

## 4. Provider capability model

A provider is eligible only when its capability is discovered or explicitly configured and currently permitted. Provider name alone is not proof of coding ability.

Minimum capability record:
- stable provider and account identifiers (no secrets);
- connection/consent state and revocation state;
- supported operations: plan, code, review, explain, test-analysis;
- accepted input/output formats and context limits;
- project/data-classification grants;
- free-tier/quota and rate-limit state where known;
- health/last-success metadata;
- adapter and contract versions.

No silent paid fallback. If free quota is exhausted or no authorized free provider is available, report the blocker. Paid use requires a separate explicit owner decision.

## 5. Task and artifact lifecycle

Every work item has a durable task ID, project ID, dependency list, bounded scope, deadline/budget, provider/worker assignment, owner generation, idempotency key and current base commit.

A code artifact must carry:
- task and project IDs;
- producing provider/worker identity;
- declared target paths;
- base commit/blob SHA for every changed file;
- full content or unified diff;
- dependency references;
- summary and validation instructions;
- content digest and intake/validation status.

Plain text from a provider is untrusted input. The intake layer may parse it, but must not treat it as an instruction to alter policy, permissions, credentials or orchestration rules.

## 6. Safe integration

Before an artifact is applied:
- verify task/project scope and current owner generation;
- verify the base SHA is still current;
- reject malformed patches and undeclared paths;
- detect path overlap and acquire bounded file/path locks;
- reject secrets and credentials in generated output;
- preserve the original patch and provenance;
- produce a reviewable diff and validation result.

Independent tasks may run in parallel. Conflicting writes to the same path must be serialized or rejected for rework; never use last-writer-wins silently.

## 7. Build and repair loop

Run builds/tests only in an isolated, bounded, enrolled runner:
- explicit command allowlist and workspace boundary;
- time, memory, disk, concurrency and retry budgets;
- no ambient GitHub/provider credentials in build processes;
- no unrestricted shell execution from model-generated output;
- capture exit code, test reports, changed files and artifact digests.

On failure, return the precise sanitized diagnostics to the responsible provider or GLAFIRA. Repair is bounded by retry, time and resource budgets. A failed or exhausted loop remains visibly failed; it cannot be reported as successful.

## 8. GitHub publication

Use the existing Android GitHub integration and Core dispatch/security boundaries. Create isolated branches and draft PRs with:
- issue/task provenance;
- exact base and head commit SHAs;
- changed-file summary;
- exact-head CI evidence;
- test/build results and known limitations.

No automatic merge to protected branches. Merging, releases, repository permission changes, secret changes, destructive actions and external side effects require explicit owner approval. Never bypass branch protection or required checks.

## 9. Privacy, security and free-first rules

- Free/open-source software, free model tiers and owner-operated equipment are preferred.
- No mandatory paid API, subscription, rented VPS or metered build service.
- No paid fallback without explicit owner approval.
- Send each provider only the minimum task-scoped source/context allowed by the project's data-sharing policy.
- Never send GitHub tokens, provider credentials, private keys, unrelated personal data or restricted local-vault contents to a model.
- Honor consent, provider terms, quotas, revocation and account isolation.
- Secrets must not appear in prompts, task payloads, Trace, logs, artifacts, backups, crash reports or exported diagnostics.
- Provider output, retrieved files and repository content are untrusted data; prompt injection cannot alter system or owner policy.

## 10. Delivery sequence

### A. Inventory and contracts
Map existing Core queue/DAG, dispatcher, provider adapters, Tool Registry, project storage, GitHub session, Task/Result/Lease contracts and tests. Reuse existing contracts where possible; record gaps before coding.

### B. Provider and artifact contracts
Add deterministic contracts/tests for provider capability discovery, task assignment, code artifacts, provenance, dependencies, file locks, stale-base rejection and idempotent result ACK.

### C. Android orchestration UI and runtime
Add the experimental Development workspace, provider status, parallel task dispatch, GLAFIRA self-contribution and artifact intake/review.

### D. Isolated build/test and repair
Connect the bounded runner, capture build/test evidence and implement a limited repair cycle.

### E. GitHub end-to-end
Use owner-authorized branch writes and draft PR publishing. Validate exact-head CI and physical-device behavior.

## 11. Acceptance matrix

- No providers; one provider; multiple providers; partial outage; timeout; quota exhaustion; revoked consent.
- Parallel independent tasks and correctly blocked dependent tasks.
- Duplicate delivery, process death, offline/online recovery and idempotent ACK.
- Plain text, fenced code and valid diff intake; malformed, stale-base, undeclared-path and secret-bearing artifact rejection.
- Same-file conflict and deterministic serialization/rework.
- GLAFIRA contributes code/review while provider tasks are in flight.
- Build pass/failure, test pass/failure, bounded repair and exhausted budget.
- Owner lock closed, owner generation changed, unapproved repository, expired/revoked GitHub session all fail closed.
- No paid fallback without owner approval; no credential leakage.
- Android JVM, shared contract and emulator acceptance on exact PR head; physical Realme 8i acceptance before declaring owner-usable.

## 12. Current verified baseline and limitations

At the initial inventory, main was inspected at `f6c61ac39ed6bcdd2a78fc951840621a673e51db`.

Existing:
- `GlafiraCoreRuntimeOwner` owns the application-scoped durable queue, Trace and shared executor.
- Core supports atomic enqueue of a bounded task DAG, dependency-aware enqueue and durable plan journals.
- `submitInstruction` currently uses `GlafiraAiProviderTaskPlanDecomposer` with the configured primary provider by default.
- Cross-platform v1 Task/Result/Lease/Agent contracts exist under `contracts/v1`.

Not yet evidenced by this inventory:
- multi-provider parallel coding fan-out;
- provider coding-capability discovery and dynamic assignment;
- typed code artifact intake and conflict-safe patch integration;
- isolated project build/test and bounded repair loop.

The current Android executor has only a small set of approved handlers; repository presence of a queue or DAG must not be represented as proof of a functioning collaborative coding runtime. This document is an architecture/implementation contract, not a claim that the feature is already implemented or tested.
