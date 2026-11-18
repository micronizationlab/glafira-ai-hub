# GLAFIRA Multi-Provider Interaction

Status: approved architectural requirement; implementation not yet started.

## User requirement

GLAFIRA must be able to independently formulate and submit questions, compare answers, and continue multi-step research under the user's authorized accounts in:
- ChatGPT (the ChatGPT web/app interface as well as supported API/connector routes);
- Yandex Alice and supported Yandex interfaces;
- DeepSeek (web/app interface as well as supported API routes).

The requirement explicitly includes both:
1. API/official integration, where available.
2. User-authorized graphical interaction ("manual" mode): GLAFIRA operates the visible web or desktop interface by opening the service, entering a prompt, submitting it, reading the response, and continuing the workflow as a human operator would.

## Architecture

1. **Provider adapters:** a common provider interface with separate adapters for API/official integrations and graphical UI automation. Core selects the route based on capability, policy, availability, and user configuration.
2. **UI automation runtime:** desktop/browser automation for Windows and, where technically supported, Android. It must interact with the actual visible interface; do not assume direct access to private app internals.
3. **Authorized sessions:** user signs in directly with each provider using its official login flow. GLAFIRA may use an authorized, user-approved logged-in session. Never ask the user to send passwords in chat, save passwords in project files, or expose session cookies/tokens to prompts, logs, or shared memory.
4. **Human-in-the-loop gates:** pause and ask the user when login, MFA, CAPTCHA, security challenge, consent, payment, account recovery, or other provider confirmation is required. Do not bypass these protections.
5. **Policy and attribution:** act only within the user's authorized account and the provider's supported rules. Do not impersonate another person, conceal automation where disclosure is required, or send messages/posts/purchases or other consequential external actions without the configured authorization policy.
6. **Task autonomy:** once a task and its limits are authorized, GLAFIRA can independently compose questions, submit them, read answers, ask follow-up questions, and compare outputs across providers. It must retain a trace of the task, prompts, provider, timestamps, results, and any user approvals.
7. **Context boundaries:** each provider receives only the information needed for the current task. Do not share private conversation history, files, credentials, or personal data across providers without explicit authorization.
8. **Fallback and resilience:** if UI layout changes, session expires, access is blocked, or a provider disallows the requested workflow, stop safely, report the exact blocker, and offer an official/API route or manual continuation. Never claim an interaction succeeded unless confirmed from the interface or provider response.
9. **Cost constraint:** prefer free routes and the user's existing subscriptions/accounts. API use may incur separate charges even when the web/app account is free; disclose this before enabling paid API usage. Do not incur charges or upgrade plans without explicit approval.
10. **Audit and control:** provide per-provider enable/disable controls, visible activity history, task-level permissions, and an immediate stop control. Core remains the authority for access, policy, and audit.

## Acceptance criteria

- ChatGPT, Yandex Alice/Yandex interfaces, and DeepSeek each have a documented capability matrix separating API, official integration, and UI automation.
- At least one supported interaction path is tested for each provider; unsupported actions are explicitly documented.
- GLAFIRA can compose and submit a user-authorized question through an authenticated UI session and capture the resulting answer where the provider permits it.
- Login/MFA/CAPTCHA/security challenges pause for user action; no bypass.
- Provider sessions and credentials are not stored in Git, prompts, logs, or shared engineering memory.
- Cross-provider sharing is limited to task-authorized context.
- API billing and subscription requirements are disclosed before use.
- Every task is auditable and can be stopped by the user.
- No claim of full provider integration until end-to-end tests pass on the target desktop and mobile environments.

## Dependencies and implementation order

1. Finish backup/recovery validation and continue GLAFIRA Core hardening.
2. Complete secure Bridge foundations and authorized tool execution.
3. Implement desktop UI automation as the first graphical interaction target, then assess Android support.
4. Research current official capabilities and restrictions for ChatGPT, Yandex Alice/Yandex services, and DeepSeek before choosing exact adapters.
5. Add provider adapters incrementally, with tests and user approval at each consequential boundary.

## Current limitations

This document records the approved requirement only. It does not mean UI automation, provider sessions, API adapters, or Alice/DeepSeek/ChatGPT integrations have already been implemented or tested. The availability of each route depends on the provider's current interfaces, account settings, terms, and device environment.
