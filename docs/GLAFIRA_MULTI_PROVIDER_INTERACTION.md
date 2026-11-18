# GLAFIRA Multi-Provider and Application Interaction

Status: approved architectural requirement; implementation not yet started.

## User requirement

GLAFIRA must act as an autonomous coordinator that can work with:
- AI services and assistants: ChatGPT, Yandex Alice and supported Yandex interfaces, DeepSeek, and additional providers added later;
- GLAFIRA's own project agents and specialized agents;
- connected applications and services (for example, CRM, email, cloud storage, messaging, and business tools);
- desktop software and browser-based applications;
- local device capabilities and files, subject to platform permissions.

GLAFIRA must be able to formulate questions and tasks, submit them under the user's authorized accounts, read results, ask follow-up questions, coordinate several agents or services, compare outputs, and return a consolidated result.

The requirement explicitly includes two interaction modes:
1. **API / official integration:** use documented APIs, connectors, MCP tools, or other supported integration surfaces where available.
2. **User-authorized graphical interaction ("manual" mode):** operate the visible browser or desktop interface by opening the application, entering information, clicking controls, reading results, and continuing the workflow as a human operator would, where technically and contractually supported.

The architecture must not assume every application exposes an API or permits UI automation. Capabilities must be verified per application and version.

## Architecture

1. **Unified capability-based adapter model:** define common task, identity/session, result, error, and audit contracts. Implement separate adapters for APIs/official integrations and graphical UI automation. Core selects a route based on verified capabilities, policy, reliability, cost, and user configuration.
2. **Application and agent registry:** maintain a registry of available providers, agents, apps, supported actions, permissions, interaction modes, limitations, and required confirmations. Discovering an app does not grant it permission.
3. **Orchestration:** Core decomposes a user-authorized goal into tasks, assigns work to appropriate agents/apps, manages dependencies, collects results, resolves conflicts, and produces a traceable synthesis. Agents may collaborate through typed messages and scoped task context.
4. **UI automation runtime:** desktop/browser automation for Windows and, where technically supported, Android. It must interact with the actual visible interface; do not assume direct access to private app internals. Prefer stable official interfaces over fragile screen automation when both can accomplish the task.
5. **Authorized sessions:** user signs in directly with each provider using its official login flow. GLAFIRA may use an authorized, user-approved logged-in session. Never ask the user to send passwords in chat, save passwords in project files, or expose session cookies/tokens to prompts, logs, or shared memory.
6. **Human-in-the-loop gates:** pause and ask the user when login, MFA, CAPTCHA, security challenge, consent, payment, account recovery, or other provider confirmation is required. Do not bypass these protections.
7. **Policy and attribution:** act only within the user's authorized account and the provider's supported rules. Do not impersonate another person or conceal automation where disclosure is required. Sending messages, publishing content, changing records, making purchases, deleting data, or other consequential external actions must follow explicit task-level authorization and confirmation rules.
8. **Task autonomy:** once a task and its limits are authorized, GLAFIRA can independently compose questions, submit them, read answers, ask follow-up questions, and coordinate work across providers and agents. It must retain a trace of the task, prompts, provider/app/agent, timestamps, results, and user approvals.
9. **Context boundaries:** each provider, app, and agent receives only the information needed for its task. Do not share private conversation history, files, credentials, or personal data across services or agents without explicit authorization.
10. **Shared engineering memory:** GLAFIRA and ChatGPT/other authorized engineering agents can exchange architecture decisions, code reviews, test reports, debugging findings, and validated solutions. Preserve provenance, version/commit context, and validation status; distinguish proposed, unverified, tested, and accepted knowledge. Core controls access; no blind promotion of suggestions to trusted knowledge.
11. **Fallback and resilience:** if UI layout changes, session expires, access is blocked, or a provider disallows the requested workflow, stop safely, report the exact blocker, and offer a supported API route or manual continuation. Never claim an interaction succeeded unless confirmed from the interface or provider response.
12. **Cost constraint:** prefer free routes and the user's existing subscriptions/accounts. API use may incur separate charges even when a web/app account is free; disclose this before enabling paid API usage. Do not incur charges or upgrade plans without explicit approval.
13. **Audit and control:** provide per-provider/app/agent enable/disable controls, visible activity history, task-level permissions, data-sharing settings, and an immediate stop control. Core remains the authority for access, policy, and audit.
14. **Platform parity:** Android and Windows Desktop are equal clients using shared Core contracts. Platform-specific automation capabilities must be declared rather than assumed.

## Acceptance criteria

- A capability matrix documents each integrated provider/app/agent, its API/official integration path, UI automation path, permissions, costs, and limitations.
- ChatGPT, Yandex Alice/Yandex interfaces, and DeepSeek are initial named AI integrations; the design supports adding other providers and ordinary applications without rewriting Core.
- At least one supported interaction path is tested for each initial provider; unsupported actions are explicitly documented.
- GLAFIRA can execute a bounded multi-step task across at least two providers or agents, collect results, compare them, and produce an auditable synthesis.
- A user-authorized question can be submitted through an authenticated UI session and its answer captured where the provider permits it.
- Login/MFA/CAPTCHA/security challenges pause for user action; no bypass.
- Provider sessions and credentials are not stored in Git, prompts, logs, or shared engineering memory.
- Cross-provider and cross-agent sharing is limited to task-authorized context.
- API billing and subscription requirements are disclosed before use.
- Consequential external actions follow the configured approval policy; the user can stop any active workflow.
- No claim of full integration until end-to-end tests pass on target desktop and mobile environments.

## Dependencies and implementation order

1. Finish backup/recovery validation and continue GLAFIRA Core hardening.
2. Complete secure Bridge foundations and authorized tool execution.
3. Implement the application/agent registry, permission model, audit trail, and task orchestration contracts in Core.
4. Implement desktop UI automation as the first graphical interaction target, then assess Android support.
5. Research current official capabilities and restrictions for ChatGPT, Yandex Alice/Yandex services, DeepSeek, and each additional app before choosing adapters.
6. Add provider and application adapters incrementally, with tests and user approval at each consequential boundary.
7. Expand shared engineering memory and agent-to-agent collaboration after secure authorization and audit foundations are in place.

## Current limitations

This document records the approved requirement only. It does not mean UI automation, provider sessions, API adapters, app integrations, or agent orchestration have already been implemented or tested. Availability depends on each provider's current interfaces, account settings, terms, and device environment.
