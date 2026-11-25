# Architecture Decision Records

Each material architecture decision is recorded separately. The ADR describes the decision and its consequences; implementation status is tracked by code, tests and the roadmap.

## Lifecycle

- **Proposed** — a proposal; publication does not authorize implementation.
- **Accepted** — explicitly approved by the project owner.
- **Superseded** — replaced by a newer ADR with a link to the replacement.
- **Rejected** — declined with a short rationale.

Never mark a decision Accepted without owner approval. Preserve superseded documents for traceability and mark historical contracts inactive rather than deleting them without a migration plan.

## Current decisions

| ADR | Decision | Status |
|---|---|---|
| [ADR-0001](ADR-0001-runtime-coordination-local-core-not-github.md) | Runtime task coordination belongs to independent local Cores; GitHub is development-plane only | Accepted |
| [ADR-0002](ADR-0002-kotlin-core-not-rust-rewrite.md) | Retain Kotlin for Android Core; no wholesale Rust rewrite; preserve current Windows .NET/C# and iOS Swift boundaries | Accepted |
| [ADR-0003](ADR-0003-trace-service-and-opentelemetry.md) | TraceService remains durable audit; OpenTelemetry is additive technical telemetry | Accepted |

## Required structure

1. Title and number.
2. Status, date and decision owner.
3. Context and constraints.
4. Decision.
5. Alternatives considered.
6. Consequences, risks, migration and rollback.
7. Related issues, PRs, contracts and specifications.

Do not duplicate normative protocol schemas in ADRs. Link to the source contract and explain the decision boundary.
