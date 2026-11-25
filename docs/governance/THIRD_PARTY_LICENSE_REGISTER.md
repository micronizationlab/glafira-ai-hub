# GLAFIRA third-party component and license register

**Status:** initial manifest inventory; license/legal review is NOT complete.  
**Sprint:** #634 · **Work item:** #636 · **WBS:** 1.7.3 / 1.11.1  
**Inventory snapshot:** Main code and manifests inspected 2026-11-25.  
**Policy:** no commercial compatibility is inferred from package popularity or a top-level license badge. Verify SPDX metadata and the full resolved transitive graph before release.

## Direct dependency inventory

| Ecosystem / manifest | Component | Declared version | License / commercial status | Next verification |
|---|---|---:|---|---|
| Android / `gradle/libs.versions.toml` | Android Gradle Plugin | 8.7.2 | PENDING | Official license, redistribution terms, plugin runtime. |
| Android / version catalog | Kotlin Gradle plugins | 2.0.21 | PENDING | Kotlin compiler/plugin license and bundled components. |
| Android / version catalog | Compose BOM | 2024.09.03 | PENDING | Resolve every BOM-managed artifact and version. |
| Android / `app/build.gradle.kts` | AndroidX Activity Compose | 1.9.2 | PENDING | Resolve exact artifact license and notices. |
| Android / app Gradle | AndroidX Compose UI, UI Graphics, Material 3 | BOM-managed | PENDING | Resolve exact artifacts and transitive graph. |
| Android / app Gradle | AndroidX Biometric | 1.1.0 | PENDING | Verify license and platform dependencies. |
| Android / app Gradle | AndroidX Work Runtime KTX | 2.10.0 | PENDING | Verify license and transitive dependencies. |
| Android / app Gradle | AndroidX DocumentFile | 1.0.1 | PENDING | Verify license and transitive dependencies. |
| Android tests | JUnit | 4.13.2 | PENDING | Verify test/runtime scope and notices. |
| Android tests | org.json | 20240303 | PENDING | Verify license and dependency graph. |
| Android tests | AndroidX Test Runner | 1.6.2 | PENDING | Verify license and transitive dependencies. |
| Android tests | AndroidX Test Ext JUnit | 1.2.1 | PENDING | Verify license and transitive dependencies. |
| Android tests | UI Automator | 2.3.0 | PENDING | Verify license and transitive dependencies. |
| Desktop Bridge / `desktop-bridge/build.gradle.kts` | Gson | 2.11.0 | PENDING | Verify license and transitive dependencies. |
| Windows Hub / `GLAFIRA.Desktop.Hub.Core.csproj` | Microsoft.Data.Sqlite | 8.0.12 | PENDING | Resolve NuGet graph, bundled SQLite/native components. |
| Windows Hub / Core csproj | System.Security.Cryptography.ProtectedData | 8.0.0 | PENDING | Verify license and platform-specific behavior. |
| Windows tests / Hub + Agent test csproj | Microsoft.NET.Test.Sdk | 17.12.0 | PENDING | Test-only scope, license and transitive graph. |
| Windows tests | xunit | 2.9.2 | PENDING | Test-only scope, license and transitive graph. |
| Windows tests | xunit.runner.visualstudio | 2.8.2 | PENDING | Test-only scope, license and transitive graph. |
| Windows Hub | Microsoft.AspNetCore.App framework reference | net8.0 | PENDING | Verify .NET runtime distribution and hosting terms. |

## Manifests inspected

- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `desktop-bridge/build.gradle.kts`
- `windows-desktop/GLAFIRA.Desktop.Hub.Core/GLAFIRA.Desktop.Hub.Core.csproj`
- `windows-desktop/GLAFIRA.Desktop.Hub.Tests/GLAFIRA.Desktop.Hub.Tests.csproj`
- `windows-agent/GLAFIRA.Agent.Worker.Tests/GLAFIRA.Agent.Worker.Tests.csproj`

## Incomplete inventory — do not infer zero dependencies

- Resolve all Gradle dependencies and plugins from lock/dependency reports; current version catalog alone is not a complete graph.
- Inspect all Windows `.csproj` files, central package files, lockfiles and publish outputs, including the production Agent Worker and Desktop Core.
- Locate and inspect iOS/Xcode project dependency declarations, Swift Package.resolved files, embedded frameworks and binary SDKs. A first-pass code search did not establish a complete iOS dependency inventory.
- Inspect JavaScript/tooling manifests, GitHub Actions, container/build images, native binaries, fonts, icons and bundled assets.
- Capture SPDX identifiers, copyright notices, source-offer obligations, attribution, patent terms, redistribution/modification restrictions and copyleft scope.
- Separate library licenses from hosted provider/API terms, model weights, datasets, pricing, quotas and telemetry.
- Candidate components not yet adopted: Playwright, LiteLLM, mDNS/discovery libraries, OpenTelemetry exporters, Restic and Borg. They remain RESEARCH ONLY until separately reviewed.

## Release gate

Every resolved direct and transitive component must have a recorded license source, version, platform, distribution mode, commercial-use assessment and notice obligations. Unknown or ambiguous terms remain REVIEW/BLOCKED and require legal review; they are not treated as permissive. Re-run the inventory on dependency changes and retain a machine-readable SBOM as a release artifact.