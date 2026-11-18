# GLAFIRA AI HUB — UI design system

## Product direction
A calm, capable workspace for an owner-led AI team. The interface should feel like a professional operations console, not a retro desktop imitation or a generic chat clone.

## Visual foundations
- Background: `#F5F6FA`
- Surfaces: white, with muted surface `#F1F3F8`
- Primary accent: indigo `#4656C8`; soft accent `#E9ECFF`
- Primary text: `#20243A`; secondary text: `#74798C`
- Success: `#23966B`; error: `#C74755`
- Cards: 16dp radius; controls: 12dp; dialogs: 20dp; chat bubbles: 16dp
- Prefer typography with clear hierarchy, comfortable line-height, and sentence case.
- Use simple letter/avatar marks and consistent vector icons; avoid emoji as primary navigation controls.

## Interaction principles
- Keep the chat header to back, search, and one overflow menu.
- Put secondary actions (pin, transfer, copy, export, TTS, clear) in the overflow menu.
- Integrate voice input and send controls into the composer.
- Use soft filled quick-action chips, not outlined micro-buttons.
- Agent cards show name, role, provider/readiness and a quiet status indicator.
- Status indicators communicate state but never dominate the agent name.

## UX principles
- Preserve existing project, chat, voice, search, pinning, export and settings behavior during visual refactors.
- Do not present configured providers as online unless runtime connectivity is actually established.
- Make touch targets comfortable and layouts responsive to narrow screens and keyboard insets.
- Accessibility: maintain readable contrast, scalable text, and non-color status cues.

## Delivery sequence
1. Shared tokens and Android system bars.
2. Agent hub and project selector.
3. Chat, message hierarchy, composer and secondary actions.
4. Settings, council, vault and bridge screens.
5. Device QA at compact and large screen widths; accessibility and regression pass.
