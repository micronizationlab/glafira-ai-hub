# GLAFIRA DESIGN — shared visual tokens and component foundation

**Status:** proposal — not owner-approved for implementation  
**Umbrella:** issue #672 · **Work item:** GLAFIRA-DESIGN-W01 · **WBS:** 1.11.1.1.1  
**Identity:** ICQ 2003 visual character + Apple/macOS polish; retain the ICQ flower.

This file is the semantic source of truth for design proposals across Android, iOS, Windows Desktop and Windows Agent. It does not require identical native controls. Platform specifications may adapt tokens to native conventions while preserving meaning and hierarchy.

## 1. Design principles

1. **Recognizable GLAFIRA:** use the ICQ flower and a restrained green identity accent; do not imitate the old ICQ layout literally.
2. **Modern native polish:** layered surfaces, careful highlights, subtle depth, clear typography and restrained motion. Avoid heavy glass blur, ornamental gradients and excessive shadows.
3. **Information first:** chat, projects, task queue and agent state must remain readable before decoration.
4. **State clarity:** active, offline, waiting for owner, quota exhausted, error and recovery-required must never rely on color alone.
5. **Owner authority stays visible:** approval, revoke, pause and restore actions must be explicit. Design cannot hide or weaken security boundaries.
6. **Small-screen first, desktop-scalable:** preserve a usable Android layout around 360dp width, then expand into multi-pane desktop workspaces.

## 2. Semantic color tokens

The values below are **provisional proposals** for review. Components should consume semantic names, not hard-coded color literals. Keep status meaning consistent in light and dark themes.

| Token | Light proposal | Dark proposal | Use |
|---|---|---|---|
| brand.primary | #78B83A | #9AD45A | GLAFIRA/ICQ identity accent only; not a control boundary |
| brand.action | #3E6F20 | #9AD45A | Primary action fill/boundary; at least 3:1 against canvas |
| brand.onAction | #FFFFFF | #14220A | Text/icons on primary action; verify text contrast |
| brand.onPrimary | #14220A | #14220A | Text/icons on identity-brand surfaces |
| surface.canvas | #F3F6F0 | #151A17 | Main app background |
| surface.panel | #FFFFFF | #202722 | Cards and panels |
| surface.raised | #FFFFFF | #29332C | Dialogs, menus, popovers |
| text.primary | #182019 | #F1F5F0 | Main text |
| text.secondary | #59645B | #B5C0B6 | Metadata and helper text |
| border.subtle | #DCE4DA | #3A473D | Decorative dividers only; not an interactive control outline |
| control.outline | #657365 | #7E8D80 | Functional control boundary; 3:1 minimum against adjacent surface |
| focus.ring | #245EAA | #8DBDFF | Visible keyboard/input focus indicator |
| control.disabled | #A8B0A8 | #56625A | Disabled control surface/boundary; pair with text.disabled |
| text.disabled | #6B746C | #A0AAA1 | Disabled labels and values |
| status.neutral | #66736A | #A5B0A7 | Offline/inactive/unknown neutral state |
| status.success | #238343 | #62C77E | Completed/healthy |
| status.warning | #A96708 | #F0B34D | Waiting/attention |
| status.danger | #B4232C | #FF747C | Error/quota exhausted/destructive |
| status.info | #2868B2 | #79B6F5 | Informational/selected |

### Color rules

- Status must include text or an icon plus accessible label; never communicate status by hue alone.
- Quota exhausted and destructive actions use danger semantics; offline/inactive uses neutral semantics, not danger.
- Do not use brand green for success if it would make product identity and operational state ambiguous; use a distinct success tone.
- The provisional action, control.outline and focus.ring pairs above exceed 3:1 against their listed light/dark adjacent surfaces; verify again against actual component surfaces before owner approval.
- Keep border.subtle for decorative dividers only. Interactive outlines use control.outline; focus uses focus.ring; disabled uses control.disabled and text.disabled.
- Verify WCAG 2.2 AA contrast for every text/background pairing and non-text control boundary before approving exact values.

## 3. Typography

Use each platform's native system font while mapping to shared roles:

| Role | Android | iOS | Windows |
|---|---|---|---|
| Display / app title | Roboto | SF Pro Display | Segoe UI Variable Display |
| Body / controls | Roboto | SF Pro Text | Segoe UI Variable Text |
| Numeric / diagnostics | Roboto Mono | SF Mono | Cascadia Mono |

Shared roles: display, title, section, body, bodyStrong, label, caption, micro, numeric. Use sentence case, short labels and tabular numerals for quotas, timers and counts. Avoid tiny low-contrast metadata; captions should remain comfortably readable at normal zoom.

## 4. Spacing, shape and depth

- Base spacing unit: 4dp/pt; preferred scale: 4, 8, 12, 16, 20, 24, 32, 40, 48.
- Android touch targets: at least 48×48dp. iOS: at least 44×44pt. Windows Desktop: at least 40×40 effective pixels, with 44px preferred for primary touch controls.
- Corner roles: control 10, card 14, panel 18, dialog 20 (provisional).
- Use one subtle border and one restrained elevation layer before adding stronger shadow.
- Gloss is a small top-edge highlight or surface gradient, not a full-screen glass effect.
- Keep card padding and list-row alignment consistent; avoid arbitrary one-off spacing.

## 5. Component contracts

- **Primary button:** one clear action per view; disabled state explains why where possible.
- **Secondary button:** neutral outlined or tonal surface; never visually competes with primary.
- **Destructive button:** explicit label, danger semantics and confirmation for irreversible actions.
- **Status chip:** icon + text + semantic color; supports active, idle, offline, waiting, error and quota states. Default label color is text.primary on the current surface; status tone applies to the icon/accent only. Use a colored label only with a verified ≥4.5:1 text pairing.
- **Card/panel:** stable title, primary content, metadata and optional actions; avoid nested card-on-card unless hierarchy requires it.
- **Text field:** persistent label, visible focus/error state, clear validation text and platform-native input behavior.
- **Dialog/sheet:** concise consequence statement, explicit confirm/cancel actions; destructive confirmation defaults to cancel.
- **Navigation item:** consistent icon, label, selected state and accessibility name.

## 6. Shared state vocabulary

Every interactive screen documents: loading, empty, ready, disabled, waiting, offline, stale, error, success, confirmationRequired and recoveryRequired where applicable. Disabled and focus states use the shared semantic tokens above or a documented native equivalent.

Model/provider availability is distinct from connection state and quota. Task state is also reason-aware: WAITING_FOR_OWNER due to a pending approval is labelled “Needs owner approval”; CORE_EXECUTION_OUTCOME_UNKNOWN is labelled “Reconciliation required” with a distinct icon and must not offer generic retry. Do not collapse both into one generic waiting chip.

Model/provider availability is distinct from connection state and quota:
- Active/ready — green semantic state plus text.
- Inactive/offline — neutral state plus text.
- Waiting/limited — warning state plus reason.
- Quota exhausted — danger state plus explicit reset/renewal information when known.
- Error — danger state plus retry/recovery guidance only when safe.
- Recovery required — distinct visible text (“Recovery required”), a dedicated recovery/alert icon and an accessible label; never communicate it only by color or collapse it into ordinary error/waiting.
- Never invent quota reset times or provider availability.

## 7. Motion and interaction

- Typical transition proposal: 120–220ms; use shorter feedback for toggles and longer transitions only for navigation/context changes.
- Prefer opacity/position/scale changes that preserve spatial orientation; avoid decorative bouncing.
- Respect OS reduced-motion settings. Critical status changes must remain understandable without animation.
- Provide immediate pressed/focused feedback and prevent double submission while an action is in progress.

## 8. Accessibility and responsive behavior

- Target WCAG 2.2 AA contrast and visible focus; do not rely on color-only state.
- Support screen readers with meaningful names, roles, state and action descriptions. For every critical state, provide a visible text/icon cue plus an accessible label; include recoveryRequired and unknown-execution reconciliation.
- Preserve keyboard navigation and predictable focus order on desktop; touch targets remain usable on mobile.
- Test dynamic type/font scaling, 200% zoom where supported, landscape, narrow windows and long localized strings.
- For Android, validate on a 360dp-wide layout and the owner's Realme 8i class device. Do not assume desktop density or large-screen space.
- For Windows, support keyboard-first navigation and resizable panes. For iOS, use native navigation, safe areas and system text sizing.

## 9. Platform adaptation

- **Android:** Material 3 behavior may be used where it fits, but map it to these semantic tokens; keep compact owner-facing surfaces and touch-first actions.
- **iOS:** use native navigation, sheets, safe areas and SF typography; preserve shared color/state semantics.
- **Windows Desktop:** use multi-pane workspace patterns, keyboard focus, resizable navigation and diagnostics panels; keep macOS-inspired polish without copying macOS controls.
- **Windows Agent:** use a compact status-first surface with clear consent, capabilities, pause/revoke and task progress.

## 10. Approval and implementation gate

All colors, dimensions and component recommendations in this document remain proposals until owner review. Platform UX packages #674–#678 and #680 may define information architecture and wireframes while exact visual tokens are marked pending. No implementation lane may treat this file as an accepted UI contract until the owner approves it. This document changes no runtime code, Core authority, permissions, cryptography, Bridge protocol, database schema or backup behavior.
