# GLAFIRA Unified Interface Design System
**Owner-confirmed direction — 2026-11-21**

## 1. Design identity

One coherent visual language across Android, Windows Desktop and Web. The design is not three unrelated platform skins.

**Primary direction:** ICQ 2003 reimagined through contemporary iOS design architecture, with a restrained touch of glossy / lacquered glamour.

- ICQ 2003 supplies the emotional identity: recognizable flower mark, presence/status, contact-centric messaging, friendly compactness and the feeling of a living messenger.
- Current iOS supplies the structural and interaction reference: hierarchy, navigation, sheets, controls, spacing, typography, motion, focus and clarity.
- ChatGPT supplies the conversation workspace patterns: readable long-form conversation, clear user/assistant turns, composer, attachments, voice, contextual tools, message actions, conversation history and streaming response states.
- Gloss/lacquer is an accent, not the whole interface: polished highlights, subtle reflections, layered translucent surfaces and controlled depth. Keep text contrast and accessibility first.

If reliable ICQ 2003 visual references are unavailable, do not fabricate historical details. Use modern iOS as the architecture baseline and express ICQ character through brand, status, contact and messenger motifs.

## 2. Product principles

1. One GLAFIRA identity and one shared design token vocabulary.
2. Platform-native behavior where appropriate; shared hierarchy and semantics everywhere.
3. Conversation is a first-class workspace, not a small chat widget attached to a dashboard.
4. The owner can see what GLAFIRA is doing, what source/tool it is using, what requires approval and what has completed.
5. Security and financial actions receive explicit, unmistakable review surfaces.
6. Elegant restraint: lacquer accents never obscure content, status, focus or controls.
7. Accessibility, keyboard, touch, scaling and reduced-motion support are baseline requirements.

## 3. Information architecture

Shared top-level destinations:
- Chats / Home
- Contacts and GLAFIRA-to-GLAFIRA connections
- Projects and workspaces
- Tasks / activity
- AI models and capabilities
- Roles / specialist tools
- Approvals and security
- Files and knowledge
- Finance (permission-gated; read-only by default)
- Settings, devices and diagnostics

Android uses native tab/navigation patterns and modal sheets. Desktop uses a persistent navigation rail/sidebar plus a resizable conversation/work area and inspector. Web uses responsive sidebar + conversation canvas + contextual panel. Do not force identical pixel layouts across platforms.

## 4. Conversation workspace (ChatGPT-inspired)

- Persistent conversation history and search.
- Distinct user and GLAFIRA message blocks with readable width and line length.
- Streaming response state, stop/regenerate/retry controls where supported.
- Composer with text, attachment, voice and tool affordances; visible permission state.
- Message-level copy, edit/resend, quote/reply and source/reference actions as appropriate.
- Long outputs support headings, lists, code, tables, citations and collapsible details.
- Tool execution appears as an understandable activity card with requested action, status, output and approval gate.
- Approval cards are visually separate from ordinary chat messages.
- Empty, loading, offline, error, interrupted and reconnect states are designed explicitly.

## 5. Visual language

- Base: modern iOS-like material hierarchy, clean typography and generous spacing.
- Brand: ICQ flower as the recognizable anchor; status colors used consistently.
- Gloss: restrained lacquer-like highlights on selected primary surfaces, not every card.
- Surfaces: layered but quiet; use translucency only where contrast remains reliable.
- Motion: short, purposeful transitions; honor reduced-motion settings.
- Avoid: skeuomorphic overload, excessive glass blur, tiny text, dashboard clutter, terminal-like desktop UI.

## 6. Component catalogue to specify

Navigation bars/rails, contact rows, presence indicators, conversation bubbles, message composer, attachment tiles, tool activity cards, approval cards, model status chips, task rows, project switcher, modal sheets, dialogs, toasts, search, tables, empty/loading/error states, finance transaction rows and payment confirmation sheet.

For each component document default, hover, pressed, focused, disabled, loading, error and selected states, plus touch/keyboard behavior.

## 7. Finance-specific visual safety

Payment confirmation must show beneficiary, destination account (masked where appropriate), source account, amount, currency, fee, purpose and date in one review surface. Changes after approval must be obvious and invalidate prior approval. Never use the same visual treatment for a harmless navigation tap and an irreversible payment.

## 8. Deliverables and implementation mapping

Create shared tokens for color, typography, spacing, radius, elevation, icon size and motion. Map them to Jetpack Compose resources, WPF resources and Web CSS variables. Provide screen specs and responsive behavior before runtime implementation. This document is a design baseline; it does not authorize production UI edits.
