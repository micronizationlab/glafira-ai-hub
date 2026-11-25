# ADR-0003: Keep TraceService as the durable audit source; add OpenTelemetry separately

- **Status:** Accepted by the project owner, 2026-11-25
- **Decision owner:** Project owner
- **Scope:** Core audit history and technical observability
- **Related:** [Architecture and execution model](../ARCHITECTURE_AND_EXECUTION_MODEL.md), `GlafiraCoreContracts.kt`

## Context

GLAFIRA needs a durable, queryable record of product-domain events for task recovery, owner approvals, policy decisions, security investigations, review outcomes and dispute resolution. It also needs operational telemetry for latency, saturation, retries, provider timing and transport health.

These are related but different responsibilities. Replacing the domain TraceService with an observability vendor/collector would make essential audit and recovery history depend on exporter availability, telemetry retention and sampling. Conversely, using the domain audit store as a high-cardinality metrics/tracing backend would overload it with technical telemetry.

The current Core contracts already define `TraceService`, `TraceRepository`, typed `CoreTraceEvent`, project/task correlation and access-aware queries.

## Decision

1. Keep `TraceService` and `TraceRepository` as the durable product-domain audit interface and source of truth.
2. Add OpenTelemetry as a separate, additive technical observability layer for spans, metrics and operational logs.
3. Correlate the layers using stable trace/correlation IDs and task/project references; do not duplicate sensitive payloads.
4. Keep OTel exporters optional and failure-isolated. Exporter outage must not block safe local Core execution or durable local audit append.
5. Do not put prompts, credentials, private keys, raw provider responses, private files or restricted memory into telemetry attributes. Apply allowlists, redaction, bounded cardinality, sampling and retention.
6. Do not replace TraceService, its durable storage or owner-visible history with OTel. Do not make OTel the authorization or task-state authority.

## Consequences

- Product history remains locally queryable and useful during network/exporter outages.
- Operators gain standard technical telemetry without coupling task correctness to an external collector.
- Correlation and redaction policies must be tested across both layers.
- TraceService durability requirements remain explicit for security/authority transitions; telemetry is best-effort and must not silently substitute for an audit event.

## Acceptance criteria for implementation

- TraceService append/query behavior and existing event contracts remain compatible.
- OTel spans/metrics correlate to task lifecycle without containing sensitive payloads.
- A disabled or unavailable exporter does not break task execution or TraceService writes.
- Tests verify redaction, bounded attributes/cardinality, exporter failure isolation and project-scoped audit access.
