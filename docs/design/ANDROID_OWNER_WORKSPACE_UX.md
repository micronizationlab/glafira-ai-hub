# GLAFIRA Android — owner workspace UX

**Status:** design proposal; not owner-approved for implementation  
**Work item:** GLAFIRA-DESIGN-ANDROID-UX-W01 · **WBS:** 1.11.1.2.1  
**Tracker:** #711 · **Umbrella:** #672  
**Visual dependency:** #679 is provisional. Use semantic token names; do not treat proposed color values as approved.

## 1. Purpose and scope

Define the Android owner's primary workspace: Home/Chat, current project context, and primary navigation. This package is a screen and interaction specification, not a claim that every described capability is already implemented.

The current Android MainActivity.kt contains the project selector, chat, agent/model list, settings, Council and task-queue entry points. Its current palette is still the legacy gray/blue Windows-XP-like palette. This proposal modernizes the information hierarchy while retaining the ICQ 2003 character and restrained Apple/macOS polish.

**Owned elsewhere:** detailed queue/settings screens (#712), provider/model selection details (#680), shared tokens (#679), pairing journey (#677), backup/recovery (#678), icon asset handoff (#689), cross-platform accessibility test method (#713).

## 2. Product principles

1. Chat is the primary work surface; navigation and status must not compete with the conversation.
2. Project context is persistent and visible. A message must not silently switch project.
3. The GLAFIRA flower is the identity mark. Use the existing source asset unchanged; do not redraw or substitute it.
4. Status is always text + icon + semantic tone, never color alone.
5. Owner decisions are explicit. Approval-required, offline, error and unknown outcome are different states.
6. A 360dp-wide phone is a first-class layout, not a compressed desktop.
7. Keep one primary action per screen; place secondary actions in overflow or contextual sheets.
8. Preserve native Android behavior for back, keyboard, system bars, touch and accessibility.

## 3. Primary navigation and information architecture

### Proposed top-level destinations

| Destination | Purpose | Detailed owner |
|---|---|---|
| Chat | Main conversation and current work context | #711 |
| Projects | Project list and project details | #712 |
| Tasks | Queue, approvals and task lifecycle | #712 |
| Assistants | Roles and connected AI models overview | #711 overview; #680 selector |
| Settings | Account, providers, voice, data and devices | #712 |

The bottom navigation on compact phones contains no more than five labelled destinations. Suggested Russian labels: **Чаты, Проекты, Задачи, Помощники, Ещё**. “Ещё” opens a sheet/list for Settings and less-frequent destinations if five full destinations become too dense. Do not hide a critical owner action behind an unlabeled icon.

On wider screens, replace the bottom bar with a narrow navigation rail/sidebar; keep the same destination order and names. The navigation mode changes, not the information architecture.

### Navigation map

    GLAFIRA workspace
    ├── Чаты (default)
    │   ├── Current conversation
    │   ├── Conversation list / search
    │   └── Context sheet (project, model, participants)
    ├── Проекты → project list / overview (#712)
    ├── Задачи → queue and approvals (#712)
    ├── Помощники
    │   ├── Role groups
    │   └── Model/provider selection (#680)
    └── Ещё
        ├── Настройки (#712)
        ├── Диагностика (only if available)
        └── О приложении

A destination should preserve its scroll position and draft text when the user moves away and returns, where the current application state supports it. Do not promise persistence across process death unless separately implemented and tested.

## 4. Global workspace shell

### Compact phone wireframe (360dp target)

    ┌──────────────────────────────────┐
    │ [flower] GLAFIRA       [profile] │
    │ Проект: ВодородАктив       [⌄]   │
    ├──────────────────────────────────┤
    │ Conversation title              │
    │ Model: selected model [change]  │
    │                                  │
    │ Message history                 │
    │ User / assistant message blocks │
    │ timestamps as secondary detail  │
    │                                  │
    ├──────────────────────────────────┤
    │ [+] Сообщение…       [mic] [➤]  │
    ├──────────────────────────────────┤
    │ Чаты Проекты Задачи Помощ. Ещё   │
    └──────────────────────────────────┘

The wireframe is structural, not pixel-approved. Header height, spacing and exact component sizes remain subject to token approval and device review.

### Global header

- Left: existing flower asset, then GLAFIRA wordmark. Do not let the logo become a large decorative banner.
- Right: owner/account affordance only if account identity is actually available; otherwise omit rather than show a fake avatar/status.
- Project context is a separate row/chip with selected project name and chevron. Long names truncate visually but remain fully available to screen readers and on opening the selector.
- Connection state, if shown, must include a readable label such as “Подключено”, “Нет соединения” or “Синхронизация”. Do not infer connectivity from a decorative green dot.

### Project context selector

Opening the selector presents a bottom sheet on phones and anchored menu/pane on larger screens.

Each row contains project name, optional short description and an explicit selected marker. Do not show sensitive project data in the selector. Search is useful only when the project list is long enough to warrant it.

Before switching, preserve the current conversation draft. If switching would discard unsaved content, explain the consequence and ask for confirmation. A project switch must never be inferred from the last message or assistant suggestion.

Empty state: “Проектов пока нет” with a single truthful next action only if project creation is supported by the current build. If unsupported, explain where project setup is available rather than showing a dead button.

## 5. Chat screen

### Hierarchy

1. Conversation title / current context.
2. Selected model/provider summary (compact; detailed selection belongs to #680).
3. Conversation history.
4. Composer anchored above system navigation/IME.
5. Optional context sheet for project, participants and conversation actions.

The selected model must not be presented as an agent identity. If a role/assistant is selected, show role and underlying model/provider as separate concepts when known.

### Message blocks

- Distinguish user and assistant messages through alignment and surface treatment, not color alone.
- Keep message text at the highest visual priority; timestamps, model labels and delivery metadata are secondary.
- Long messages wrap naturally; code, URLs and long tokens must not expand the screen horizontally.
- Actions such as copy or quote appear contextually and remain reachable with TalkBack.
- Do not show “delivered”, “saved” or “completed” unless the underlying event is confirmed.
- If a response is partial/streaming, label it as in progress and keep stop/cancel distinct from sending a new message.

### Composer

- Persistent labelled text field; placeholder “Сообщение…”.
- Attach, microphone and send controls are separate, labelled touch targets.
- Send is disabled only when sending is genuinely unavailable (for example, empty text and no attachment). If disabled for another reason, explain why.
- While sending, prevent accidental duplicate submission and expose progress accessibly.
- On IME open, composer remains visible immediately above the keyboard; message list receives the remaining height and scrolls to the latest message without covering the composer.
- On IME close, restore the previous conversation viewport. Do not jump to the top.
- Voice permission education and settings navigation are specified in #712; this screen only reserves a clear microphone entry point and does not change permission behavior.

### Chat states

| State | Visible treatment | Allowed interaction |
|---|---|---|
| Loading history | Stable header; unobtrusive progress in history | Back/navigation remain usable |
| Empty conversation | Brief explanation and composer | Start first message |
| Ready | Messages and composer | Normal actions |
| Sending | Progress attached to submitted message | Prevent duplicate send; allow safe cancel if supported |
| Offline | Explicit “Нет соединения”; retain draft | No false success; retry only when safe |
| Provider unavailable | Model summary explains unavailable state | Change model only through #680 |
| Error | Human-readable reason and safe next action | No generic retry for unknown execution outcome |
| Unknown execution outcome | “Требуется сверка” with distinct warning icon | No resend/retry until reconciled |
| Needs owner approval | “Требуется подтверждение владельца” | Open relevant approval flow; not the same as reconciliation |

## 6. Assistants overview

The Assistants destination is an overview, not the full provider selector.

- Use collapsible groups: **Роли GLAFIRA** and **Модели ИИ**.
- A role row shows role name, short purpose and role availability. Do not imply that every role is currently executing.
- A model row shows provider/model name and a short status label only when backed by current state.
- Status labels: “Активна”, “Не подключена”, “Недоступна”, “Лимит исчерпан”, “Ожидает подтверждения” as applicable. Do not invent quota reset time.
- Model choice and data-sharing disclosures open the #680 flow.
- Do not use a single green indicator for both brand identity and operational health.

## 7. Responsive behavior

| Available width | Layout |
|---|---|
| 360–599dp | Single column; bottom navigation; project chip; context via bottom sheet |
| 600–839dp | Single content column with optional navigation rail; sheets may become side panels |
| 840dp+ | Two-pane workspace may show conversation list and active chat; right context panel only when useful |
| Landscape / short height | Reduce nonessential header height; keep composer and primary navigation reachable; allow history to scroll |

Do not create a permanent three-column layout on a phone. At 360dp, the chat composer must not be crowded by a row of model, role, attachment, voice and send buttons. Secondary controls move into a sheet.

## 8. Accessibility and interaction acceptance

- All interactive controls have visible labels or meaningful accessible names, role and state.
- Minimum Android touch target: 48×48dp, including icon-only controls.
- Support TalkBack traversal in visual reading order; announce selected project and selected destination.
- Text scaling must not clip navigation labels, composer actions or status messages. Long Russian labels must wrap or use an accessible full label.
- Keyboard focus is visible and does not rely on selection fill alone.
- Contrast is measured against actual adjacent surfaces using #679; do not claim WCAG conformance from token proposals alone.
- Reduced-motion preference disables nonessential transitions; status and progress remain understandable without animation.
- Back behavior is predictable: dismiss an open sheet first, then return to prior destination, then leave workspace according to Android navigation conventions.

## 9. Owner review decisions

1. Should the compact navigation label be “Помощники” or “Агенты”? Recommendation: “Помощники” for user-facing Russian UI; retain “агент” in technical diagnostics.
2. Should the conversation list be a separate screen or a collapsible pane? Recommendation: separate screen at 360dp, pane on wide layouts.
3. Should the project selector stay in the global header or move into the chat title? Recommendation: global header row so context remains visible on every destination.
4. Should “Ещё” contain Settings and Diagnostics, or should Settings remain a direct fifth destination? Recommendation: keep five labelled items only if usability testing confirms legibility at 360dp; otherwise put Settings in “Ещё”.
5. Which existing flower asset is canonical? The design must use the owner-confirmed source unchanged; asset identity remains pending #689.

## 10. Implementation handoff checklist

- [ ] Owner approves navigation labels and destination grouping.
- [ ] Owner confirms canonical ICQ flower source; no substitute artwork.
- [ ] #679 token foundation is owner-accepted before exact colors/radii are wired.
- [ ] #712 owns detailed Projects/Tasks/Settings behavior; no duplicate implementation.
- [ ] #680 owns provider/model choice and data-sharing disclosure.
- [ ] Android implementation maps every state above to real domain state; no decorative/fabricated status.
- [ ] Verify 360dp portrait, landscape, keyboard open/close, long messages, long project names and text scaling on emulator and physical device.
- [ ] Run TalkBack and keyboard/focus checks; capture evidence separately from documentation review.

**Non-claims:** this document does not implement navigation, keyboard handling, project switching, chat persistence, provider selection, permissions or any Core/Bridge behavior. It does not approve provisional shared design tokens or complete physical-device acceptance.
