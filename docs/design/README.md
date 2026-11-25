# GLAFIRA Design Package

This directory is the canonical design handoff for Android, iOS, Windows Desktop and Windows Agent.

## Source of truth

- [Shared semantic tokens and component contract](DESIGN_TOKENS.md) — approved direction and semantic names; check its current owner-review status before implementation.
- [Icon and asset handoff](ICON_ASSET_HANDOFF.md) — requirements for the retained GLAFIRA flower and semantic status SVGs.

## Implementation tracking

- Umbrella design project: [#672](https://github.com/micronizationlab/glafira-ai-hub/issues/672)
- Platform implementation and handoff tracker: [#686](https://github.com/micronizationlab/glafira-ai-hub/issues/686)
- Android Compose token implementation: [PR #688](https://github.com/micronizationlab/glafira-ai-hub/pull/688)

## Platform source locations

- Android production source: `app/src/main/java/com/micronizationlab/glafira/design/GlafiraDesignTokens.kt`.
- iOS SwiftUI and Windows XAML references are future-platform artifacts; this repository currently has no native iOS or Windows UI build target. They must not be reported as compiled application code until those targets exist.
- Keep native controls native. Shared token names and state semantics are the cross-platform contract.

## Asset status

The current Main tree does not contain a root `design/` directory or Android launcher icon resources under `app/src/main/res`. The original flower artwork must be supplied by the owner/designer before export. Do not substitute an invented flower.
