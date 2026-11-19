# GLAFIRA Design System — Draft for Owner Visual Review

Status: direction approved; exact screen compositions and visual tokens remain subject to owner approval before UI implementation.
Date: 2026-11-19

## 1. Product identity

GLAFIRA should feel like the optimism and simplicity of ICQ 2003, refined with the restraint and finish of macOS Big Sur. It must feel light, welcoming, quick and polished — not like a dense enterprise dashboard or a futuristic control room.

### Brand mark

- The recognizable green five-petal ICQ flower is the approved visual anchor.
- Preserve the flower's recognizable silhouette and white/green center; do not replace it with a generic flower, lettermark or abstract G.
- Use the same mark in Android, iOS, Windows Desktop and Windows Agent.
- Produce platform-safe assets: adaptive Android icon foreground/background, iOS app icon, Windows .ico, tray icon and in-app vector/bitmap variants.
- The flower can have a carefully controlled glossy/raised finish, but silhouette and color identity take priority over effects.
- Asset licensing/provenance must be reviewed before distributing a copied historical ICQ artwork. If using a newly drawn interpretation, it must remain recognizably faithful without falsely claiming to be the original source file.

## 2. Visual principles

1. **Air before decoration.** Whitespace is a primary design element.
2. **Light surfaces.** Pearl white and soft neutral backgrounds; avoid large dark surfaces by default.
3. **Small moments of gloss.** Use highlights on the flower, selected buttons and a few controls only. Do not apply glass blur to every panel.
4. **Thin structure.** Prefer hairline silver separators and subtle depth over thick borders and heavy shadows.
5. **Friendly messenger rhythm.** Compact contact/project list, clear presence indicators and readable message history.
6. **Contained geometry.** Message bubbles are compact rectangular blocks with very small corner radii; text wraps inside the available width and never overflows the chat viewport.
7. **Calm color.** Green is reserved for online/healthy identity; pale icy blue for selection and active controls; amber/red for meaningful warnings/errors only.
8. **Consistency without cloning.** All platforms share tokens and semantics but use platform-appropriate layouts and controls.

## 3. Proposed palette (draft, not final)

| Token | Draft direction | Use |
|---|---|---|
| Pearl | near-white warm neutral | Main background |
| Snow | clean white | Chat and content surfaces |
| Ice | very pale blue | Selected/active areas |
| Blue accent | restrained clear blue | Links, focus, primary action |
| ICQ green | classic bright leaf green | Flower and online status |
| Silver | very light cool grey | Dividers, outlines, subtle chrome |
| Graphite | soft dark grey | Main text |
| Muted grey | medium cool grey | Secondary labels |
| Amber / red | restrained semantic colors | Attention/error states |

Exact hex values, gradients, shadows, radii and motion timings are deliberately not locked until the owner reviews the visual mockups.

## 4. Shared components and behavior

- **Flower mark:** brand identity and online/presence motif.
- **Presence indicator:** online, idle, busy, paused, offline, needs approval and error.
- **Conversation row:** small avatar/flower, title, last message, time and status.
- **Message block:** bounded rectangular bubble, sender/time metadata, attachment and delivery state.
- **Composer:** simple input, attachment affordance, send action and optional voice action; no clutter.
- **Task row/card:** task title, owner/Agent, state, progress and next required action.
- **Agent tile/row:** device label, connection, capabilities, current work and resource status where permission allows.
- **Approval item:** explicit action summary, risk, target, scope, expiry and approve/reject controls.
- **Notification:** concise and actionable; opening a notification never bypasses authorization.
- **Empty/loading/error states:** calm, helpful and consistent across platforms.

## 5. Platform screen map

### Android

Primary navigation:
- Чаты
- Задачи
- Моя сеть
- Настройки

Primary screens:
- Messenger-first home with the flower mark and clear connection status.
- Conversation view with bounded rectangular message blocks.
- Task inbox with status and dependency progress.
- Agent/network overview with connected, paused and offline counts.
- Approval review and device management.
- Settings for Bridge, privacy, notifications, local credentials and resource policy.

Mobile layout should prioritize one-column flow, readable touch targets and minimal persistent chrome.

### iOS

Mirror Android's information architecture and task semantics while using native SwiftUI navigation, sheets, accessibility and system conventions. Do not force an Android-style navigation bar onto iOS if native navigation is clearer.

### Windows Desktop

Primary workspace:
- Slim left rail for projects/chats and navigation.
- Main conversation/task workspace.
- Optional contextual right panel for task details, approvals or agent status.
- Dedicated **Моя сеть** view for the Agent fleet.
- Settings and diagnostics separated from everyday chat.

The Desktop should feel like a light ICQ-style messenger with a capable work area, not a wall of cards. Allow resizable panes and preserve a useful compact mode.

### Windows Agent

The Agent is a background worker, not a full chat application.
- Tray icon uses the GLAFIRA flower with a restrained state overlay.
- Small status window: connected Desktop host, device identity, connection, assigned task, resource limits, pause/resume and diagnostics.
- Avoid a full contact list, project dashboard or duplicate Desktop navigation.
- First-run enrollment clearly explains what the host can request and what local capabilities are granted.

## 6. Motion, sound and feedback

- Motion should be short, soft and purposeful: subtle hover/press response, gentle status transitions and unobtrusive notifications.
- Avoid spring-heavy or bouncy animation that makes a professional tool feel toy-like.
- Existing ICQ-inspired sounds remain optional and user-controllable, with per-event volume/mute settings.
- Respect OS reduced-motion, sound and notification preferences.
- Never use animation or color alone to communicate a critical state.

## 7. Accessibility and resilience

- Meet platform contrast and scalable text expectations.
- Support keyboard navigation and visible focus on Desktop.
- Preserve screen-reader labels for flower/status icons and controls.
- Message content must reflow without horizontal overflow.
- Layout must survive narrow windows, large fonts, localization and long filenames.
- Status always has a text/accessible label in addition to color.
- Loading, offline, reconnecting and error states must be distinguishable.

## 8. Visual approval workflow

1. Produce separate high-fidelity previews for Android, iOS, Desktop and Agent.
2. Show the same sample conversation and task states across all four previews to verify consistency.
3. Show Desktop's "Моя сеть" fleet screen as a separate composition.
4. Owner reviews the screens and asks for changes.
5. Record approval of each screen family.
6. Only then implement UI changes, incrementally, with screenshot/regression tests and no replacement of working business logic.

No screen in this draft is considered visually approved merely because the direction or flower mark has been approved.
