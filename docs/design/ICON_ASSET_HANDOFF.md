# GLAFIRA icon and asset handoff

**Tracker:** #686 · **Umbrella:** #672 · **WBS:** `1.11.1.1.3`

## Asset discovery status

The current repository Main tree was inspected before this handoff was written:

- No root-level `design/` directory exists.
- `app/src/main/res` currently contains `raw/`, `values/` and `xml/`; no `mipmap-*` or `drawable/` launcher artwork was found.
- Therefore the original GLAFIRA/ICQ flower source is **not present at the inspected paths**. The owner or designer must provide the original vector/source file. Do not redraw, replace or claim to have exported it without that source.

## Original application flower

After the original source is supplied and owner-confirmed:

- Preserve its recognizable silhouette and proportions.
- Use the semantic identity accent `brand.primary`; do not bake a runtime theme color into status glyphs.
- Export square raster assets at 1024×1024 (store/master), 512×512, 192×192 and 48×48 where the target platform needs them.
- Also retain a clean vector master (SVG or editable source) and document the export tool/version.
- Generate Android adaptive icon foreground/background layers only after confirming the existing app icon design and launcher requirements.
- Verify transparent padding, optical centering, edge quality and legibility at 48px.

## Semantic status SVG package

Deliver these six separate SVG files:

| File | Meaning | Required visual |
|---|---|---|
| `ic_status_success.svg` | Completed / healthy | Checkmark in a circle |
| `ic_status_warning.svg` | Waiting / attention | Exclamation in a triangle or circle |
| `ic_status_danger.svg` | Error / quota exhausted | X or octagon with exclamation |
| `ic_status_recovery.svg` | Recovery required / outcome reconciliation | Recovery/refresh arrow combined with a distinct alert marker; must not look like ordinary danger |
| `ic_status_neutral.svg` | Offline / inactive / unknown | Minus or empty circle |
| `ic_status_info.svg` | Informational | Lowercase “i” in a circle |

## SVG production rules

- Canvas: 24×24 with `viewBox="0 0 24 24"`.
- Nominal stroke: 2 units; use rounded joins/caps where appropriate.
- Use `stroke="currentColor"` and/or `fill="currentColor"`; no embedded brand/status hex colors.
- No raster image embeds, external references, scripts, filters or editor metadata.
- Optical center the visible glyph, not merely its geometric bounds.
- Preserve a clear silhouette at 16px and 20px as well as 24px.
- Provide an accessible semantic name in the consuming UI; SVG title metadata alone is not a substitute for platform accessibility labels.
- State is never color-only: always pair critical status with visible text and a screen-reader label.

## Required delivery from designer

1. Six optimized SVG files and the editable source/master.
2. A preview sheet on light and dark surfaces.
3. Export notes and confirmation that `currentColor` is preserved.
4. Contrast/legibility review at 16, 20 and 24px.
5. Owner review of the original flower before replacing any existing application branding.

## Acceptance checklist

- [ ] Original flower source supplied and owner-confirmed.
- [ ] All six status SVGs delivered with 24×24 viewBox and currentColor.
- [ ] Recovery icon is unambiguously different from ordinary error.
- [ ] No embedded colors or external dependencies in SVG.
- [ ] Light/dark preview and small-size legibility reviewed.
- [ ] Accessible labels specified for each state.
- [ ] Owner approves the final artwork before app packaging.
