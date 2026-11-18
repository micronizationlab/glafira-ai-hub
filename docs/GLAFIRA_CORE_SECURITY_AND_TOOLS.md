# GLAFIRA Core: autonomy, tools and credential security

## Authority model

- The owner is the business and security authority.
- Glafira is the single coordinator and owner of task orchestration.
- OpenAI, DeepSeek, GigaChat and other LLMs are peer reasoning providers. No provider has authority over Glafira.
- Specialist agents are roles. Providers are replaceable model backends. A role must not be permanently coupled to one vendor.
- Glafira chooses whether to answer directly, consult peers, delegate a specialist task, or use an external tool. It owns the final synthesis and reports uncertainty.

## Model consultation

1. Glafira's configured primary provider receives the task and decides whether independent review is useful.
2. It may select up to two configured peer providers.
3. Peer calls run independently and concurrently.
4. Glafira compares their results, resolves disagreements, and produces the final answer.
5. Unconfigured providers are never silently substituted. A failed peer is reported and does not block Glafira from answering.

Credentials, API keys, bearer tokens and passwords must never be included in model prompts. The current client applies basic secret-pattern redaction before peer consultation; this is a defense-in-depth measure, not a complete data-loss-prevention system. A later policy layer must classify project data and enforce per-provider sharing permissions.

## Tool and account access model

Glafira should access external systems through a tool broker, not by handing credentials to an LLM.

Preferred order:
1. Official service API / OAuth integration.
2. Approved connector with scoped permissions.
3. Browser automation for tasks that have no supported API.
4. Android accessibility automation only when explicitly enabled for the device and app.

A tool request should contain a typed action, target service, project/task ID, and credential reference. The broker resolves the credential reference inside a protected boundary and injects secrets directly into the authenticated connector or browser session. Raw passwords and API tokens are never returned to the model, chat history, logs, analytics, screenshots or peer providers.

## Owner interaction and approvals

- If a required account is not connected and no credential exists, Glafira asks the owner for the missing access information and stores it through the protected vault UI.
- Prefer OAuth authorization flows where available; do not ask for a service password when OAuth is supported.
- CAPTCHA, MFA / one-time codes, biometric prompts, new-device challenges and other explicit identity checks must be completed by the owner. The system must not bypass them.
- Reading data and preparing drafts can be autonomous within granted scopes.
- Sending messages, publishing content, changing customer/business records, deleting data, making purchases, initiating payments or transferring money require explicit owner approval unless a narrowly scoped, revocable policy is configured.
- Every tool action is recorded in an audit log with actor, service, action, time, approval and outcome. Never log secret values.

## Desktop and browser execution

The Android app cannot automatically use the laptop's CPU, browser session or saved passwords. For desktop workflows, build a separately paired desktop companion / private execution endpoint. Pairing must be owner-approved and use authenticated, encrypted transport, device revocation, scoped tool permissions and an auditable command protocol. Browser automation should run in an isolated profile, not the owner's unrestricted personal browser session.

## Delivery phases

1. **Provider routing and Glafira coordinator** — in progress. Connect model providers to their own clients; let Glafira request peer reviews and synthesize.
2. **Role/provider separation and project context** — make agent roles independent from model vendors; add per-project context boundaries and provider data-sharing policy.
3. **Credential broker** — connect the encrypted API-key store and password vault through credential references; add access scopes, session locking and audit events.
4. **Tool registry** — define typed tools, permission scopes, approval requirements, idempotency and result contracts.
5. **Browser / desktop companion** — authenticated pairing and controlled browser automation for forms and sites.
6. **Business integrations** — CRM, Telegram, Instagram, Gmail and banking through official APIs/OAuth where available; browser fallback only when appropriate.
7. **Operational hardening** — tests, recovery, audit review, revocation, redaction tests and real-device acceptance testing.

## Current implementation boundary

The coordinator and provider-routing work does not yet provide browser automation, a desktop companion, CRM/banking connectors, or autonomous account login. Those are separate security-sensitive milestones and must not be represented as already working.
