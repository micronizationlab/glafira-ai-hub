# ADR-002: GLAFIRA Multi-AI Orchestrator and account-based provider connections

- **Status:** Proposed; owner-approved product direction, implementation gated by P1–P3 foundations and provider-specific authorization review
- **Date:** 2026-11-21
- **Related issue:** #367
- **Priority:** P4 design work; does not displace P1 Core, P2 Backup & Recovery, or P3 Runtime / Bridge / Desktop
- **Owner:** Валерий Корчагин

## Context

The owner wants GLAFIRA to coordinate multiple AI providers through the owner's ordinary provider accounts wherever an official, permitted integration exists. API keys are optional fallback connections, not the only connection model and not a mandatory prerequisite.

Examples of intended commands:

- "Спроси всех: <question>" — query every provider currently connected, eligible, available and permitted for the requested data.
- "Спроси ChatGPT: <question>" — query only the ChatGPT provider.
- "Спроси независимо всех, сравни ответы и проверь итог через ChatGPT."
- "Выполни цепочку: provider A → provider B → provider C", when every forwarding edge is allowed by the provider's terms and the owner has authorized that data flow.

GLAFIRA remains the single user-facing coordinator. Provider models are replaceable specialists, not independent owners of GLAFIRA state or permissions. Core controls task state, provider selection, data scope, approvals, audit and final delivery.

## Decision

Build a provider-independent orchestration layer with explicit account connections and two primary execution patterns:

1. **Parallel independent consultation (default):** each provider receives the original question and only the minimum authorized context. Responses are collected independently, attributed to their provider/account/model, compared, and synthesized. The final reviewer may receive the sanitized response set if that provider's rules and the user's consent permit it.
2. **Sequential chaining (opt-in per workflow):** each step receives the original task plus only the previous output explicitly authorized for forwarding. The orchestrator validates every source→destination edge against provider terms, data classification, user consent and project policy. A prohibited or unverified edge is blocked and reported; no silent substitution.

"Ask all" means all eligible connected providers, not every provider name in a hardcoded list. GLAFIRA must show which providers responded, were skipped, timed out, were rate-limited, require reauthorization, or are unavailable in the user's region.

## Provider connection registry

Each connection record should contain:

- stable provider ID and adapter version;
- local account/profile identifier and user-visible account label;
- connection method (official account OAuth, official free endpoint, user-supplied API key, or unavailable);
- granted scopes/capabilities and explicit data-sharing permissions;
- token/credential expiry and revocation state (never credential values in UI or Trace);
- model discovery and currently supported modalities;
- verified region, quota/rate-limit state, last health result and last successful use;
- per-provider usage ceiling and owner-controlled enable/disable state.

Connections are isolated by provider and account. A Google login to GLAFIRA is only GLAFIRA account authentication; it does not connect Google Gemini, ChatGPT, GigaChat or Alice by implication.

## OpenAI: Sign in with ChatGPT

The current official OpenAI open-source/local-app path is materially different from a ChatGPT browser session:

- The open-source flow uses dynamic client registration and OAuth Authorization Code + PKCE. The first registration uses `dynamic_agent_client`; OpenAI returns an issued `client_id` that must be retained for that account/host.
- A stable per-installation `ext_agent_host_id` is generated and persisted locally.
- The user authenticates in the system browser and separately authorizes the requested ChatGPT-plan usage scope.
- The app stores the issued client ID, verified identity, granted scopes and credentials in protected local storage under the user's control.
- Inference uses the public Responses API with the authorized OAuth access token. The app must wait for `response.completed` and handle revocation, expiry, admission failure and quota exhaustion.
- Successful identity sign-in alone does not grant inference permission. The connection is usable for model requests only when the required permission was actually granted.
- This does not expose the user's ChatGPT conversations, memory, files, API key or arbitrary ChatGPT web session.

The official cookbook describes open-source/local use and identifies eligible Plus/Pro users for plan-backed inference. Current Help Center guidance also describes plan sharing for eligible Go/Plus/Pro/Business use in participating apps. Eligibility must be checked from the live authorization result and current OpenAI policy; do not promise inference through a Free account. The owner currently uses the Free plan, so GLAFIRA must show "account signed in / plan inference not eligible or not granted" rather than pretending that ChatGPT is an active model provider.

### OpenAI terms and routing guard

The Sign in with ChatGPT terms require user-controlled local runtime, user authorization for requests, express consent for background use, secure local token storage, and prohibit password/cookie collection, usage pooling/resale, serving another user's requests, and exposing the plan as a general-purpose API for other tools or unrelated requests.

Therefore:

- Never build a generic proxy that exposes the owner's ChatGPT plan to GigaChat, Alice, other users, or third-party applications.
- Do not forward ChatGPT-generated output to another provider by default. Treat every cross-provider forwarding edge involving OpenAI as disabled until the exact intended use is confirmed to be permitted by current OpenAI terms and documentation.
- Independent fan-out can send the original user prompt separately to each permitted provider. A final ChatGPT review of sanitized peer outputs may be enabled only after verifying that this remains within the connected GLAFIRA app's allowed functionality and the granted scopes.
- Every request must originate from the owner's local GLAFIRA runtime or a remote runtime that the owner controls, and from the owner's action or expressly authorized automation.
- No other GLAFIRA account may consume the owner's ChatGPT plan.
- Usage limits and revocation must be visible; never switch to a paid API or credits automatically.

Official references:
- https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt
- https://developers.openai.com/siwc/token-sharing-open-source
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery
- https://openai.com/policies/sign-in-with-chatgpt-terms/
- https://help.openai.com/en/articles/20001542-using-your-chatgpt-plan-in-other-apps-and-sites

## Android feasibility gate

The published OpenAI open-source example uses a local runtime (Electron main process and `@siwc/local`) and a loopback callback listener on `127.0.0.1`. It is not proof that the SDK is directly usable from the existing Kotlin Android application.

Before implementation, run a bounded Android-native feasibility spike:

1. Verify that the current official open-source OAuth registration/callback contract permits a native Android local loopback callback or another documented native redirect.
2. Verify exact PKCE, state, nonce, client registration, token refresh and revocation requirements.
3. Verify that tokens can be stored only in Android Keystore-backed protected local storage and never copied to Desktop/cloud backup.
4. Verify that the app can make authorized Responses API requests directly from the owner-controlled Android runtime without a proxy server or paid infrastructure.
5. Test revoked access, denied plan scope, Free/ineligible account, unsupported region, quota exhaustion, expired access token and refresh-token rotation.
6. If Android-native integration is not documented or fails policy review, do not emulate ChatGPT's web UI or scrape browser sessions. Keep the adapter unavailable on Android until an official compliant route exists; a separately paired owner-controlled Desktop runtime may be evaluated as an optional local host, not assumed to solve mobile-only use.

## Provider adapters

Each adapter implements a common capability contract:

- `connect()`, `refresh()`, `disconnect()`, `health()`;
- `discoverModels()` and supported modality/capability reporting;
- `generate()` with structured completion/error states;
- quota/rate-limit and region availability reporting;
- safe cancellation and bounded retry semantics.

Adapters must use documented official endpoints and account flows. No password capture, cookie scraping, undocumented private endpoints, UI automation against consumer websites, or session impersonation.

Candidate providers include ChatGPT, GigaChat, Alice, Gemini and DeepSeek. Their account-based integration is not assumed to exist: investigate each provider separately and classify it as supported, restricted, API-only, unavailable or unverified.

## Orchestration contract

A durable Core task records:

- original user request and correlation ID;
- selected providers and why each was eligible or skipped;
- provider/account/model identity (non-secret identifiers);
- per-provider prompt/context digest and data classification;
- execution mode (parallel or sequential);
- each response, completion state, timestamps, timeout/retry/quota result;
- source-to-destination forwarding edges and the policy decision for each;
- conflict matrix, evidence/provenance links and synthesis/reviewer outcome;
- final result delivery and explicit ACK.

Provider responses are untrusted data, not instructions. They cannot change GLAFIRA policy, permissions, provider routing or owner identity. Tool calls and external side effects remain behind Core's existing authorization and owner-approval gates.

## Privacy and safety

- Apply project and data-classification filters before every provider call.
- Do not send local-only vault data, passwords, API keys, authentication tokens, private files or unrelated project memory to any model.
- Redact secrets before persistence and before cross-provider transmission.
- Encrypt provider credentials at rest using platform-protected storage; never put them in Git, logs, Trace, crash reports or backups.
- Store only the minimum response/provenance required by the user's configured retention policy.
- User can inspect and revoke each provider independently.
- Background multi-provider requests require explicit opt-in and a visible stop/pause control.

## Free-only resource policy

No mandatory paid API, paid subscription, metered proxy, rented VPS, or paid relay. Verify current free quota, rate limits, region availability, data retention, privacy terms and withdrawal risk for every candidate. A provider with no viable free official access is shown as unavailable or API-key optional; the app must not silently incur charges.

## Acceptance criteria

- Common provider contract and connection registry have unit tests.
- Parallel fan-out and sequential workflows have deterministic tests.
- Tests cover partial failures, timeout, quota exhaustion, region denial, expired/revoked credentials, missing provider, contradictory answers, cancellation and final-review failure.
- Tests prove no paid fallback, credit purchase, or provider-to-provider forwarding occurs without the required explicit policy and owner consent.
- Tests prove no credential or local-only data appears in prompts, logs, Trace, diagnostics or backup archives.
- Android feasibility gate is completed with documented evidence before enabling the ChatGPT provider in Android.
- The UI distinguishes account identity sign-in from actual model-inference capability.
- "Спроси всех" accurately reports reached, skipped and failed providers.
- Every synthesized claim can be traced to its provider response or marked as GLAFIRA synthesis.
- Exact-head CI and relevant Android/Windows acceptance pass before the feature is called implemented.

## Consequences

This architecture allows GLAFIRA to orchestrate multiple user-owned AI accounts without assuming every provider supports account OAuth or a free endpoint. It preserves the owner's desired "ask all → compare → final review" experience while respecting provider-specific permissions and avoiding hidden paid dependencies.

The orchestration contract and UI can be designed independently of any one provider. The ChatGPT adapter remains gated by the native Android feasibility check, actual account eligibility, current official authorization and terms review.