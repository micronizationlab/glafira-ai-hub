---
name: "Design system implementation"
about: "Implement or improve GLAFIRA shared design tokens and native UI components"
title: "feat(design): "
labels: "design-system"
assignees: ""
---

## Goal

Implement the approved GLAFIRA design-system work item **GLAFIRA-DESIGN-W01** (WBS **1.11.1.1.1**) using [the shared token specification](https://github.com/micronizationlab/glafira-ai-hub/blob/main/docs/design/DESIGN_TOKENS.md).

Umbrella tracking issue: #672.

## Platform

- [ ] Android — Jetpack Compose
- [ ] iOS — SwiftUI
- [ ] Windows Desktop — current WPF application
- [ ] Windows Agent — compact status/consent surface
- [ ] Shared documentation/assets

## Scope

- [ ] Semantic light/dark tokens for brand, surfaces, text, controls, focus and status.
- [ ] Spacing, shape, typography and motion roles.
- [ ] Reusable StatusChip with icon + visible text + accessible semantics.
- [ ] Minimum interactive target: Android 48dp; iOS 44pt; Windows 40 effective px (44px preferred for primary touch).
- [ ] Pending owner approval and `CORE_EXECUTION_OUTCOME_UNKNOWN` use distinct labels and icons; the latter is `Reconciliation required` with `ic_status_recovery.svg` and never offers generic retry.
- [ ] Recovery-required is visually and semantically distinct from generic error.
- [ ] Keyboard focus and predictable focus order on desktop.
- [ ] Respect Dynamic Type/font scaling, system appearance and reduced motion.
- [ ] Migrate hard-coded colors only within the explicitly scoped screens/components.

## Acceptance criteria

- [ ] Attach measured WCAG 2.2 AA text contrast ratios (at least 4.5:1) for each implemented component's actual foreground/background token pair.
- [ ] Attach measured non-text control-boundary and focus-indicator ratios (at least 3:1) against actual adjacent surfaces; identify each token pair and measured ratio in light and dark themes.
- [ ] No critical state is communicated by color alone.
- [ ] No new hard-coded HEX values in reusable UI components.
- [ ] Motion defaults to 120–220ms and respects reduced-motion settings.
- [ ] Android layout is checked at 360dp width.
- [ ] iOS previews cover Dynamic Type and light/dark appearance.
- [ ] Windows supports keyboard navigation and visible focus; every in-scope interactive target is at least 40×40 effective pixels (44×44px preferred for primary touch controls).
- [ ] Shared Swift package code using `UIColor` is guarded with `#if canImport(UIKit)` and has a `macOS`-compilable fallback.
- [ ] Tests/previews and before/after screenshots are attached.
- [ ] No changes to Core authority, permissions, cryptography, Bridge protocol, data schema or backup behavior unless separately approved.

## Implementation notes

Link the platform handoff: [PLATFORM_IMPLEMENTATION_HANDOFF.md](https://github.com/micronizationlab/glafira-ai-hub/blob/main/docs/design/PLATFORM_IMPLEMENTATION_HANDOFF.md).  
Icon requirements: [ICON_ASSET_SPEC.md](https://github.com/micronizationlab/glafira-ai-hub/blob/main/docs/design/ICON_ASSET_SPEC.md).

Keep the PR focused. Split Android, iOS and Windows implementations into separate PRs if the combined change would be difficult to review or validate.
