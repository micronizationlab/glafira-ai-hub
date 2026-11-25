# GLAFIRA icon asset specification

**Work item:** GLAFIRA-DESIGN-W01 · **WBS:** 1.11.1.1.1  
**Status:** handoff specification. The app flower is already owner-approved; preserve it unchanged. Status icon artwork remains outstanding.

## 1. Existing app mark

The canonical GLAFIRA flower already exists and is owner-approved. It is an ICQ-style flower with five green petals, a white center and green core, finished with a glossy/lacquered dimensional highlight. This is a preservation requirement, not a redesign brief. Locate the exact approved source in the active project history/branch before making exports. Do not redraw, replace, recolor or simplify the approved mark. The `main` branch tree scan did not reveal a flower-named SVG/PNG/WebP/ICO asset, so the canonical source location still needs to be resolved.

Required app-mark exports once the source artwork is approved:

| Export | Intended use |
|---|---|
| 1024×1024 | Master/high-resolution source export |
| 512×512 | Store and desktop artwork |
| 192×192 | Android launcher asset source |
| 48×48 | Compact UI / legacy-scale preview |

Keep the editable vector source alongside generated raster exports. Raster exports must be generated from the approved vector master, not independently redrawn.

## 2. Status icon set

Required source SVGs:

- `ic_status_success.svg`
- `ic_status_warning.svg`
- `ic_status_danger.svg`
- `ic_status_recovery.svg`
- `ic_status_neutral.svg`
- `ic_status_info.svg`

The recovery icon must be visually distinct from ordinary danger/error. Map `CORE_EXECUTION_OUTCOME_UNKNOWN` explicitly to `ic_status_recovery.svg` and the visible label `Reconciliation required`; do not map it to owner approval or generic failure. A recovery-required state is an operational condition that may require reconciliation or restore; it must not look like a generic failed task.

## 3. Native platform delivery paths

Keep the six canonical SVG files as editable design masters under `design/icons/`; do not add a runtime SVG-renderer dependency solely for these status assets. Generate and review native outputs from the same SVG source:

- **Android Compose:** convert each SVG to a VectorDrawable XML resource under `app/src/main/res/drawable/` (Android Studio Vector Asset import or a deterministic reviewed conversion). Bind `currentColor` to the semantic Compose tint at runtime; do not bake light/dark colors into generated paths.
- **Windows WPF:** convert SVG paths to `DrawingImage` / `Geometry` resources in the existing `windows-desktop/GLAFIRA.Desktop.App` resource dictionary. Bind fills/strokes to semantic WPF brushes; do not introduce WinUI-only APIs or an SVG runtime library.
- **iOS SwiftUI:** deliver vector PDF assets in the existing asset catalog with Preserve Vector Data enabled, or a reviewed SwiftUI `Path` representation generated from the same source. Keep semantic tint dynamic and verify both appearances.

Generated platform assets are build inputs, not replacements for the canonical SVG masters. Each consuming PR must record the source-to-output mapping and attach light/dark and monochrome previews.

## 4. SVG production requirements

- Artboard: 24×24 viewBox.
- Nominal stroke: 2px, with consistent joins and caps.
- Optically center the visible shape, not merely its mathematical bounds.
- Use `currentColor` for strokes/fills wherever practical; do not bake a theme-specific fixed color into the source.
- No embedded raster images, editor metadata, hidden layers, unused defs, or unnecessary groups.
- Keep paths simple and deterministic; avoid filters and complex masks that are poorly supported by native renderers.
- Preserve legibility at 16px and 20px as well as the 24px master size.
- Use shape/symbol differences plus accessible text labels; never rely on color alone.
- Supply a light/dark preview sheet and a monochrome preview sheet.

## 5. Acceptance checklist

- [ ] Six status SVGs are present and parse as valid SVG.
- [ ] Recovery is distinguishable from danger at 16px in monochrome.
- [ ] Success, warning, danger, neutral and info are distinguishable without color.
- [ ] Stroke weight, optical alignment and baseline are consistent.
- [ ] No fixed theme colors are embedded in status source SVGs.
- [ ] Existing approved flower source is located and preserved unchanged; requested raster exports are reproducible.
- [ ] Android, iOS and Windows render previews are reviewed at actual UI sizes.
- [ ] Accessibility labels and semantic status mapping are documented by consuming components.

## 6. Delivery layout

Recommended final layout after artwork review:

```text
design/
  app-mark/
    glafira-flower.svg
    exports/
      glafira-flower-1024.png
      glafira-flower-512.png
      glafira-flower-192.png
      glafira-flower-48.png
  icons/
    ic_status_success.svg
    ic_status_warning.svg
    ic_status_danger.svg
    ic_status_recovery.svg
    ic_status_neutral.svg
    ic_status_info.svg
  previews/
    status-icons-light.png
    status-icons-dark.png
    status-icons-monochrome.png
```

This is a target handoff layout, not a claim that these binary assets have already been produced.
