# GLAFIRA Android — operational screens UX

**Status:** proposal; owner approval required  
**Work item:** GLAFIRA-DESIGN-ANDROID-OPS-W01 · **WBS:** 1.11.1.2.2  
**Tracker:** #712 · **Umbrella:** #672  
**Dependencies:** shared tokens #679 remain provisional; model/provider selector detail #680 is separate.

## 1. Repository baseline and scope

Current Android TaskQueueScreen.kt includes a project-scoped queue, pending owner approvals, task priority, add-task flow, and an approval decision showing task type, action details, requested scopes, and the exact task ID/action hash. It also warns that adding a task creates a draft and does not execute external actions, publications or payments without separate owner confirmation.

Current SettingsScreen.kt is a long mixed page containing provider API-key configuration, primary coordinator model, project-scoped peer consultation grants, biometric entry, sound/voice controls, microphone permission education, password vault, project export/delete/history, app version and GitHub integration. This document proposes a clearer information architecture; it does not assert every item is available or functional in every build.

This package owns detailed UX for Projects, Tasks, Settings and permission education. Chat shell is #711; model/provider selection and disclosure is #680; shared tokens are #679; pairing is #677; backup/recovery flow is #678.

## 2. Navigation and page grouping

Suggested entry points:
- Projects — project list and selected project overview.
- Tasks — project-scoped task queue and owner approvals.
- Settings — grouped configuration and data controls.
- Chat and Assistants remain in the primary workspace defined by #711.

On compact screens, each destination is a single scrollable column with clear section headers. On wider layouts, project list/detail or settings navigation may become a two-pane layout. Do not add a permanent three-column layout to a 360dp phone.

## 3. Projects

### Project list

Each project row contains:
- Project name as the primary label.
- Short description or last-used context only when available.
- A selected marker for the current project.
- A secondary action menu for supported project actions.

The current project is visible in the global workspace header. Opening a project does not silently change a conversation's model/provider or grant new data-sharing permissions.

### Project overview

Suggested section order:
1. Project identity and current selection.
2. Related chats and task count, only when backed by real data.
3. Project-specific AI consultation grants summary.
4. Export/history actions.
5. Destructive actions in a separate danger section.

Project deletion/history deletion must be visually and semantically distinct. The current settings copy says deleting project history removes chats and pins but leaves the project. Keep this consequence explicit and do not label it as project deletion.

### Project states

| State | UX |
|---|---|
| No projects | Explain the empty state; show create action only if supported |
| Loading | Stable page title and progress indicator |
| Selected | Explicit selected label; project context visible |
| Error | Explain load failure; safe retry only if operation is idempotent |
| Long name | Wrap or truncate visually; expose full accessible name |

## 4. Task queue and owner approvals

### Queue hierarchy

1. Header: “Очередь задач” and current project.
2. Pending owner approvals section, if any.
3. Project task list.
4. Add-task action.
5. Empty state when no tasks exist.

The queue must always be scoped to the selected project. Show the project name near the header and repeat it in any approval sheet where ambiguity is possible.

### Task row

Show task title, concise status, priority and reason/result metadata where available. Keep raw IDs, hashes and diagnostic payloads out of the primary row; place them in a details view. Do not invent progress percentages or completion timestamps.

### Add task flow

Fields:
- Название — required, persistent label.
- Что нужно сделать — required, multiline.
- Приоритет — explicit numeric/labelled control with current value.
- Actions: “Добавить” and “Отмена”.

Before submission, state plainly: “Задача сохраняется как черновое поручение. Внешние действия, публикации и платежи не выполняются без отдельного подтверждения владельца.” Do not imply that creating a queue item executes it.

### Owner approval detail

Approval is a separate, high-attention review surface. It must show:
- Exact task ID and project.
- Tool/action type and human-readable action details.
- Requested permission scopes, individually listed.
- Exact action-hash binding and confirmation explanation.
- Separate explicit “Подтвердить” and “Отклонить” actions.

Do not compress scopes behind a generic “Разрешить” label. If task identity or action hash changes, the previous approval must not appear applicable. Biometric re-authentication wording must reflect the actual runtime requirement; do not promise a biometric prompt if it is not invoked.

### Queue states

| State | Visible wording / behavior |
|---|---|
| Empty | “Очередь пока пуста” with add-task action |
| Waiting for owner | “Требуется подтверждение владельца”; open exact approval detail |
| Running | Show confirmed execution state only; no invented percentage |
| Completed | Show success only after confirmed terminal result |
| Failed | Show known reason and safe next action |
| Unknown outcome | “Требуется сверка”; no generic retry or duplicate execution |
| Offline/stale | Label data freshness and connection state separately |
| Rejected | Record that owner declined; do not present as technical failure |

**Critical distinction:** WAITING_FOR_OWNER is an approval state. CORE_EXECUTION_OUTCOME_UNKNOWN is a reconciliation state. They must never share one generic “Ожидание” chip or a generic retry button.

## 5. Settings information architecture

Replace the current long mixed page with grouped sections. Keep one page with anchored sections on compact screens or a navigation list + detail pane on wider screens.

### Proposed sections

| Section | Contains | UX guardrail |
|---|---|---|
| Аккаунт и безопасность | Owner identity, biometric entry, security status | Never display secrets |
| Провайдеры ИИ | Provider key setup and configured state | Mask keys; blank input must not erase saved value |
| Главная модель | Primary coordinator selection | Explain that this is the coordinator model, not an agent role |
| Консультации ИИ | Project-scoped providers allowed to receive context | Show current project and exact sharing scope |
| Звук и голос | App sounds, chat notifications, spoken replies, voice input | Separate microphone permission from voice preference |
| Данные проекта | Export, copy history, delete history | State exact scope and irreversible consequence |
| Устройства и интеграции | GitHub connection and device links where supported | Show requested access and revoke path |
| О приложении | Version and build information | Diagnostics must not expose secrets |

### Provider key entry

- Show provider name and configured/not configured state.
- Never echo a stored key back into the field.
- Use masked entry; a blank field means “keep existing” only if that is the actual runtime behavior.
- Save feedback must distinguish saved, rejected and not attempted.
- Do not show a green success label until secure storage confirms the write.

### Project-scoped consultation grants

This setting controls which providers may receive project context. Present the current project name prominently, then list each provider with a clear enabled/disabled state and concise data-sharing explanation. Changing the grant is an explicit owner action. Do not combine this with the primary model selector.

### Voice and microphone

Separate three concepts:
1. Voice input preference.
2. Android microphone permission state.
3. Spoken assistant response preference.

If permission is denied, explain that the operating system controls access and provide an “Открыть настройки Android” route only when appropriate. Do not imply that toggling voice preference itself grants OS permission. Never suggest background recording; current settings copy states microphone use begins only after the user's voice-input action.

### Export and destructive history actions

Export must show destination, progress, completion/failure and resulting file location only when known. History deletion must name the exact project and state that chats/pins are deleted while the project remains. Confirmation defaults to cancel. Do not combine export and delete into one menu item.

### GitHub integration

Display connection state, connected account and requested repository access only when confirmed by the integration. The confirmation page must tell the owner to verify the application name and requested scopes. Keep GitHub token handling separate from AI provider keys. Provide a clear disconnect/revoke path if supported.

## 6. Responsive and keyboard behavior

- 360–599dp: one column; sticky section title only where it does not consume too much vertical space.
- 600–839dp: one content column with optional navigation rail.
- 840dp+: settings section list and detail pane; queue can show task list and selected task detail.
- Forms scroll as a whole when the keyboard opens; focused field remains visible.
- Primary submit/cancel actions must not be hidden behind the IME. If pinned action bars are used, ensure they do not cover the last field.
- Dialogs on small screens should become full-height or bottom-sheet forms when content is long.
- Long Russian labels and provider names wrap without horizontal scrolling.

## 7. Accessibility and status language

- Every control has a visible label or accessible name, role, state and action.
- Android touch targets are at least 48×48dp.
- Approval scope lists are read in logical order; confirm/reject actions have distinct accessible labels.
- Error, waiting, quota and recovery states use text + icon + semantic tone.
- Focus is visible; TalkBack announces current project and task status.
- Text scaling must not clip approval details, permission explanations or destructive consequences.
- Exact contrast values remain subject to #679 owner approval and measurement on actual component surfaces.

## 8. Owner decisions

1. Should Projects and Tasks be direct bottom-navigation destinations? Recommendation: yes, both are frequent owner workflows.
2. Should Settings use section anchors or a nested category list? Recommendation: section anchors on phone; list/detail on wide screens.
3. Should approval detail be a bottom sheet or full screen? Recommendation: full screen for multi-scope/high-impact approvals; sheet only for short, low-risk details.
4. Should task priority use a numeric 0–10 control or named levels? Existing UI exposes 0–10; retain it until owner changes the product semantics.
5. Should the current project label appear in every settings section? Recommendation: show it in all project-scoped consultation/data sections, not unrelated account sections.

## 9. Implementation handoff checklist

- [ ] Owner approves navigation and section grouping.
- [ ] Approval screen displays exact task identity, action hash and all requested scopes.
- [ ] Waiting-for-owner and reconciliation-required states remain distinct.
- [ ] Provider keys remain masked and secure-storage feedback is truthful.
- [ ] Voice preference and Android microphone permission are separate.
- [ ] Export/delete consequences and project scope are explicit.
- [ ] Verify 360dp, keyboard open/close, large text, TalkBack and landscape.
- [ ] Run functional/security acceptance separately; this UX document is not proof of behavior.

**Non-claims:** no queue semantics, approvals, biometric flow, microphone permission, provider storage, export, deletion, GitHub access or settings behavior is changed by this document.
