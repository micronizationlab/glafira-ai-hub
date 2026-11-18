# SMM Specialist Role

## Purpose

Add **SMM Specialist** as a first-class, assignable AI employee in GLAFIRA AI HUB. The role owns social-media and messaging communications for the projects/brands to which it is assigned. It is a role/persona with scoped capabilities, not a model provider and not an independent authority outside Glafira Core.

Glafira remains the coordinator and owner-facing assistant. The SMM Specialist may use configured LLM providers through the existing orchestration layer; its role instructions and permissions remain separate from the selected model.

## Responsibilities

- Monitor and organize incoming messages, comments, mentions and customer questions on connected channels.
- Draft and, where explicitly authorized, send replies.
- Prepare posts, short-form content, stories, announcements, campaign copy and content calendars.
- Adapt approved brand content to each platform's format and audience without changing factual claims.
- Triage leads and support requests; create or update CRM records only through approved integrations and permissions.
- Escalate complaints, refund requests, legal/medical/financial claims, threats, sensitive personal data and uncertain cases to the owner or a designated human.
- Track channel activity and prepare performance summaries using available platform analytics.
- Maintain a consistent, project-specific brand voice and response knowledge base.

## Channel coverage

Design a connector-based architecture intended to support, where official access is available:
- Telegram
- VKontakte
- Instagram
- Facebook
- MAX
- Other social networks, messaging services and community platforms added later.

Do not promise universal access. Each channel is enabled only when a supported official API, approved integration, or permitted connection method exists for the user's account and region. Platform permissions, account type, review/approval, rate limits, regional availability and API policy changes may limit particular operations.

Prefer official APIs and approved integrations. Do not bypass platform protections, scrape private content, evade rate limits, or automate interactions in ways prohibited by the platform. If an official API cannot perform an operation, explain the limitation and offer a safe manual handoff rather than pretending the action succeeded.

## Architecture

- `SmmSpecialistRole`: role prompt, responsibility boundaries and escalation rules.
- `SocialChannelConnector`: common interface for channel adapters.
- `TelegramConnector`, `VkConnector`, `InstagramConnector`, `FacebookConnector`, `MaxConnector`: independent adapters, implemented only against supported APIs and capabilities.
- `ChannelCapabilityRegistry`: declares per-account capabilities such as read messages, read comments, draft reply, send reply, publish post, upload media, read analytics.
- `SocialInbox`: normalized conversation and comment events, preserving source IDs and project association.
- `ContentCalendar`: scheduled drafts and approved publishing jobs.
- `BrandProfile`: project-scoped tone, product facts, prohibited claims, FAQ and escalation rules.
- `ApprovalPolicy`: owner confirmation and scoped auto-reply rules.
- `AuditLog`: records who/what initiated external actions, target channel, content hash/reference, approval and result; do not log tokens or secrets.

All channel credentials must be managed by the secure credential/OAuth broker. Never expose access tokens, passwords or refresh tokens to an LLM or include them in prompts, chat history, logs or analytics. Prefer OAuth and least-privilege scopes; support token revocation and account disconnection.

## Authorization and safety

- The owner explicitly connects each account and grants channel-specific permissions.
- Default to read and draft capabilities; sending messages, publishing, deleting, spending on ads, changing account settings and modifying business records require owner approval until the owner configures narrow, revocable automation rules.
- Auto-replies may be enabled only per channel and per project, with explicit rules, confidence thresholds, rate limits, quiet hours and escalation conditions.
- Never impersonate a customer, staff member or public figure. The assistant should identify itself as an automated assistant when required by law, platform policy or the owner's disclosure policy.
- Do not invent stock, prices, delivery terms, product properties, certifications, refunds or promises. Retrieve current facts from the approved project knowledge base or ask.
- Do not send sensitive customer information to unrelated models or channels.
- Human review is required for legal threats, disputes, safety concerns, medical claims, chargebacks, account bans and other high-impact cases.

## Owner experience

The SMM Specialist appears as a selectable role in the project's employee/agent list. The owner can assign it to a project and connect one or more social accounts. The interface should show each account's connection state and actual available capabilities; unavailable actions must not be displayed as active.

The role can be assigned a configured provider/model by Glafira's model-routing policy. Changing the provider must not change the role's permissions, brand profile or conversation history.

## Implementation sequence

1. Add the role definition and role registry entry, without external actions.
2. Implement normalized Social Inbox and draft-only workflow with test fixtures.
3. Add Telegram and VK adapters where the connected account/API permissions support them.
4. Add Instagram and Facebook via supported Meta APIs and account permissions.
5. Investigate MAX official developer access and implement only when a supported API is available.
6. Add publishing queue, owner approval, audit trail and safe per-channel auto-reply rules.
7. Add CRM integration and analytics after the channel foundation is stable.

Each adapter must have automated tests for permission denial, expired credentials, rate limiting, duplicate events, retries, webhook verification and safe failure. Never report a message or post as sent until the connector confirms success.
