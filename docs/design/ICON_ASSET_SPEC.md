# GLAFIRA icon asset specification

**Work item:** GLAFIRA-DESIGN-W01 · **WBS:** 1.11.1.1.1  
**Status:** handoff specification; artwork and exports are not yet delivered.

## 1. Existing app mark

Before changing or replacing the app mark, locate and review the current source asset in the repository. Preserve the recognizable GLAFIRA/ICQ flower identity. Do not fabricate a replacement or claim that an export exists until the source file is verified and reviewed by the owner.

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

The recovery icon must be visually distinct from ordinary danger/error. A recovery-required state is an operational condition that may require reconciliation or restore; it must not look like a generic failed task.

## 3. SVG production requirements

- Artboard: 24×24 viewBox.
- Nominal stroke: 2px, with consistent joins and caps.
- Optically center the visible shape, not merely its mathematical bounds.
- Use `currentColor` for strokes/fills wherever practical; do not bake a theme-specific fixed color into the source.
- No embedded raster images, editor metadata, hidden layers, unused defs, or unnecessary groups.
- Keep paths simple and deterministic; avoid filters and complex masks that are poorly supported by native renderers.
- Preserve legibility at 16px and 20px as well as the 24px master size.
- Use shape/symbol differences plus accessible text labels; never rely on color alone.
- Supply a light/dark preview sheet and a monochrome preview sheet.

## 4. Acceptance checklist

- [ ] Six status SVGs are present and parse as valid SVG.
- [ ] Recovery is distinguishable from danger at 16px in monochrome.
- [ ] Success, warning, danger, neutral and info are distinguishable without color.
- [ ] Stroke weight, optical alignment and baseline are consistent.
- [ ] No fixed theme colors are embedded in status source SVGs.
- [ ] Approved flower source is preserved and all requested raster exports are reproducible.
- [ ] Android, iOS and Windows render previews are reviewed at actual UI sizes.
- [ ] Accessibility labels and semantic status mapping are documented by consuming components.

## 5. Delivery layout

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
