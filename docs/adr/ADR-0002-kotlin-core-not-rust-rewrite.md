# ADR-0002: Retain Kotlin for the Android Core; do not rewrite the Core in Rust

- **Status:** Accepted by the project owner, 2026-11-25
- **Decision owner:** Project owner
- **Scope:** Android Core and existing Kotlin/JVM components
- **Related:** [Architecture and execution model](../ARCHITECTURE_AND_EXECUTION_MODEL.md)

## Context

The Android Core is already implemented in Kotlin and integrated with Android lifecycle, platform APIs, Gradle, SQLite persistence, coroutine-based execution and the existing test/CI toolchain. Rust offers strong memory-safety properties and may be valuable for isolated low-level components, but a wholesale rewrite would add FFI, build, debugging, contributor and maintenance costs while delaying Core durability, recovery, Bridge and acceptance work.

The Windows Desktop Core/Hub is currently a separate .NET 8/C# implementation. The iOS application is Swift. The architecture uses language-neutral versioned contracts rather than one shared mutable cross-platform Core process.

## Decision

1. Retain Kotlin for Android Core and existing Kotlin/JVM modules.
2. Do not rewrite the Android Core or current Kotlin/JVM implementation in Rust solely to change languages.
3. Keep Windows Desktop Core/Hub on its current .NET 8/C# path and iOS on Swift. This ADR does not assert that Android and Windows share a Kotlin runtime or database.
4. Use versioned language-neutral contracts, canonical serialization and shared golden fixtures for Kotlin, C# and Swift interoperability.
5. Consider Rust only for a separately scoped component when profiling or a concrete security/isolation requirement demonstrates a benefit. Such a proposal requires a prototype, packaging/FFI/maintenance analysis, threat model and a new owner-approved ADR.
6. Tauri is not adopted as a platform rewrite by this decision.

## Consequences

- Existing Android code, tests, platform integrations and developer workflow remain usable.
- Near-term effort stays focused on task ownership, durable delivery, recovery, backup and Bridge acceptance rather than a language migration.
- Memory safety remains a concern to address through Kotlin/JVM safety practices, narrow interfaces, input validation, platform security APIs, tests and code review; Kotlin is not claimed to provide Rust's compile-time ownership guarantees.
- Cross-platform consistency is enforced at the protocol/schema boundary, not by pretending platform implementations are identical.

## Reconsideration triggers

Revisit this decision only if a measured bottleneck, critical unsafe boundary or independently deployable component justifies Rust's additional toolchain and maintenance cost. A benchmark or preference alone is insufficient.
