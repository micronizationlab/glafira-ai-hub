# GLAFIRA design system — platform implementation handoff

**Umbrella:** issue #672 · **Work item:** GLAFIRA-DESIGN-W01 · **WBS:** 1.11.1.1.1  
**Source of truth:** [DESIGN_TOKENS.md](DESIGN_TOKENS.md)  
**Status:** implementation handoff based on the current token proposal in `DESIGN_TOKENS.md`; palette-dependent implementation remains pending owner approval and WBS acceptance.

## 1. Repository reality checked for this handoff

The repository currently contains:
- Android application module: `app`, Kotlin + Jetpack Compose, namespace `com.micronizationlab.glafira`.
- Native iOS project and Swift Package: `ios/GLAFIRA.xcodeproj` and `ios/GlafiraBridgeKit`.
- Windows Desktop application: `windows-desktop/GLAFIRA.Desktop.App`.
- Windows Agent project: `windows-agent`.

The Android UI currently defines legacy palette values directly in `MainActivity.kt` (`Bg`, `Gr`, `GrD`, `Tx`, `Line`, `Red`, `XpBlue`). Windows currently has application-level brush resources in `App.xaml` and additional literal colors in `MainWindow.xaml`. This handoff does not silently replace those values: migration must be deliberate, reviewed and visually tested.

## 2. Android — Jetpack Compose

Target package: `com.micronizationlab.glafira` (do not use the unrelated `ru.glafira.design` namespace).

Implementation sequence using the owner-approved baseline tokens:

1. Add a dedicated design-token source file under the existing app namespace or a dedicated design package within it.
2. Expose semantic light/dark color roles, spacing, corner radii and minimum touch target as typed Kotlin values.
3. Build a Material 3 `ColorScheme` adapter from those tokens; components must not copy HEX values.
4. Add reusable status-chip component with icon + visible label + semantics. Keep recovery-required separate from generic error.
5. Migrate the legacy palette and screens in small, testable slices; avoid a one-shot rewrite of `MainActivity.kt`.
6. Add Compose previews/tests for light/dark, status states, 360dp width and font scaling.

Minimum requirements:
- 48×48dp interactive targets.
- Verify layout at 360dp width and on Android 13 class hardware.
- Respect system dark theme and font scaling.
- Use semantic colors; no new hard-coded HEX literals in components.
- Motion defaults 120–220ms and respects system animator/reduced-motion settings.

## 3. iOS — SwiftUI

The repository already has a SwiftUI UI package at `ios/GlafiraBridgeKit/Sources/GlafiraIOSUI`.

Using the semantic roles in `DESIGN_TOKENS.md`; treat palette values as pending until the owner approves the token proposal:
1. Add a dedicated design-token source in the existing Swift package, using unambiguous names such as `GlafiraPalette` and `GlafiraSpacing`; avoid nesting a type named `Color` that shadows `SwiftUI.Color`.
2. Use SwiftUI `Color` with explicit light/dark asset or dynamic-provider implementation. If using `UIColor`, guard UIKit-specific code with `#if canImport(UIKit)` and provide a `macOS`-compilable fallback so the shared package continues to compile under `swift test` on macOS.
3. Add reusable `StatusChip` with SF Symbols/custom SVG fallback, text label and accessibility label/value.
4. Support Dynamic Type, safe areas, system appearance and 44×44pt minimum interactive targets.
5. Add previews for all semantic states and both appearances.

Do not copy a sample with a nested `Color` enum shadowing SwiftUI's `Color`; the compiler must resolve the intended type explicitly.

## 4. Windows Desktop and Agent

The repository contains a WPF desktop app and a Windows Agent project. The initial implementation should use the existing WPF resource system rather than introduce WinUI/XAML APIs that do not match the current project.

After owner approval of the token proposal:
1. Define shared semantic `Color`/`Brush` resources in the desktop application resource dictionary.
2. Move repeated literal colors from `MainWindow.xaml` to semantic resources.
3. Add visible keyboard focus styles and predictable tab order.
4. Add status presentation with text/icon and distinct recovery-required state.
5. For the Agent surface, keep status compact and expose Pause and Revoke only when authorized and supported by the existing command contract.
6. Test keyboard-only navigation, high contrast/system theme behavior and resizable layouts.

## 5. Shared component acceptance

- Text contrast: WCAG 2.2 AA, at least 4.5:1 for normal text.
- Non-text controls and meaningful boundaries: at least 3:1 against adjacent colors.
- Color is never the sole status signal.
- Status chips always include an icon or shape cue and a visible text label.
- Pending approval and unknown execution outcome use distinct labels and icons; `CORE_EXECUTION_OUTCOME_UNKNOWN` maps to `Reconciliation required` / `ic_status_recovery.svg` and never offers generic retry.
- Pending owner approval and unknown execution outcome use distinct labels and icons; `CORE_EXECUTION_OUTCOME_UNKNOWN` maps to `Reconciliation required` / `ic_status_recovery.svg` and never offers generic retry.
- Recovery-required is distinct from ordinary danger/error and waiting-for-owner.
- Focus is clearly visible for keyboard and accessibility navigation.
- Transitions default to 120–220ms; respect reduced-motion preferences.
- No new component hard-codes HEX values.
- For Windows, verify both width and height of every in-scope interactive target are at least 40×40 effective pixels; use 44×44px for primary touch controls.
- Verify narrow layouts, long localized strings and enlarged text.

## 6. Change control and rollout

This is an implementation handoff for the shared design foundation, not palette approval or permission to redesign all screens at once. Use semantic roles for planning; keep palette-dependent implementation pending until the owner approves the token proposal and its WBS leaf is accepted. Then migrate screens in small, reviewable slices. Implementation should be split into small platform PRs, each with screenshots/previews and tests. No design change may alter Core authority, permissions, Bridge protocol, encryption, backup/restore or owner-approval behavior.

Recommended order:
1. Complete owner review of the shared semantic token proposal and icon direction. Verify rendered component contrast and accessibility as part of each platform PR; do not infer palette approval from this handoff.
2. Android token adapter + status component.
3. Windows resource adapter + focus/status styles.
4. iOS SwiftUI adapter + Dynamic Type previews.
5. Incremental screen migration by user-visible priority.
