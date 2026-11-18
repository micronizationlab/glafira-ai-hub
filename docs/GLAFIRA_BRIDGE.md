# Glafira Bridge — device and assistant connectivity specification

Status: architecture started; no live connection is deployed yet.

## Purpose

Glafira Bridge is the authenticated exchange layer between GLAFIRA AI HUB clients (Android and Desktop), the Glafira Core coordinator, configured model providers and approved external tool connectors.

"Direct connection to ChatGPT" has two distinct meanings and must not be conflated:

1. **OpenAI model access from Glafira:** Glafira calls the official OpenAI API through a provider adapter. This requires an API project/key and API billing/limits separate from a ChatGPT subscription. The key is stored in the protected local credential boundary and is never embedded in the APK, desktop installer, prompts or logs.
2. **ChatGPT product access to Glafira tools:** expose a remote, authenticated MCP server / ChatGPT connector endpoint where supported, so ChatGPT can call explicitly published Glafira tools. This requires a reachable HTTPS endpoint, connector setup and user-granted scopes. A local Android app cannot make the current ChatGPT conversation connect to itself merely by installing an APK. Connector availability and account support must be verified at setup time.

Neither path gives ChatGPT unrestricted control of the phone or PC. All operations pass through Glafira Core policy and the owner approval gate.

## Logical topology

- Android client and Desktop client establish outbound TLS connections to a Bridge endpoint.
- Bridge authenticates device identity and forwards typed requests to Glafira Core.
- Core assembles project-scoped context, selects an allowed provider/tool and checks policy.
- Results return to the originating client and are recorded in an audit trail.
- An optional MCP adapter exposes a narrow subset of the same typed tool registry to a connected ChatGPT client.
- Provider adapters (OpenAI, GigaChat and other explicitly configured providers) remain peer engines; Glafira Core owns routing and final synthesis.

## Transport and deployment options

- First development mode: local/LAN endpoint for Android and Windows clients on the same trusted network.
- Next: owner-operated endpoint on an always-on computer or other owner-controlled host, reachable over authenticated HTTPS. No rented server is assumed.
- A public ChatGPT connector requires a reachable HTTPS MCP endpoint. If the owner does not want any public endpoint, use OpenAI API access inside Glafira instead; do not pretend the ChatGPT product can reach a private LAN service.
- Do not add an unsolicited cloud relay, tunnel vendor or paid hosting dependency.

## Identity, pairing and sessions

- Pairing starts with a short-lived, single-use enrollment code shown by the owner-controlled Core host.
- The owner confirms device name and requested scopes before enrollment.
- Each device receives a revocable device credential; store it in Android Keystore or the desktop OS credential store.
- Use TLS, server authentication, short-lived access tokens, refresh-token rotation and replay protection.
- Support device listing, last-seen status, session expiry and immediate revocation.
- Never use a shared static secret embedded in the app.

## Message and command contract

Every request includes:
- protocol version;
- request ID / idempotency key;
- authenticated device ID;
- project ID and conversation ID where relevant;
- typed operation name and validated JSON payload;
- requested data scope;
- timestamp and expiry.

Every response includes:
- matching request ID;
- status: accepted, pending_approval, running, succeeded, failed or cancelled;
- typed result or sanitized error;
- timestamps and audit reference.

Commands are proposals until Core authorizes and the tool broker confirms execution. Retries must be idempotent. Unknown protocol versions and unknown operations fail closed.

## Security boundaries

- Owner remains final authority; Glafira Core coordinates; model providers have no tool authority.
- Secrets are resolved only inside the credential broker and are never returned to models, clients, MCP results or logs.
- Project data is filtered before provider or peer sharing; consultation is opt-in per project/provider.
- Read-only operations may proceed under granted scopes. External writes, sending/publishing, financial actions and destructive operations require a project/tool/action-bound, expiring owner approval.
- MCP tools expose narrow typed actions, not a generic shell, filesystem, browser or arbitrary code execution tool.
- Audit actor, device, project, action, approval, outcome and time; never audit secret values.
- Rate limits, payload limits, schema validation, origin checks and abuse monitoring are required before any public endpoint.

## GigaChat and other providers

GigaChat is a provider adapter selected by Core policy, not a controller of Glafira. It can receive only the task context allowed for that provider and project. Peer consultation remains disabled unless the owner opts in. Core compares responses and owns final synthesis. Provider failure must not silently route restricted data elsewhere.

## Delivery phases

1. Define protocol schemas, threat model and local mock transport.
2. Add device registry, one-time pairing, credential storage and revocation.
3. Implement Android and Desktop client transport with heartbeat, reconnect and idempotent queue.
4. Connect transport to Core coordinator and approval queue.
5. Add audit events, scopes, data classification and redaction tests.
6. Add official OpenAI API adapter for in-app model access (separate API credentials/billing).
7. Add optional MCP server adapter for ChatGPT product connectivity; validate real connector setup and remote HTTPS reachability.
8. Penetration/security review and multi-device acceptance.

## Acceptance criteria

- An unpaired or revoked device is rejected.
- Expired, replayed, malformed and cross-project requests fail closed.
- Retried requests do not duplicate external actions.
- No secret appears in model context, bridge payloads, client storage outside OS protection or logs.
- Approval grants are bound to one project, tool and exact action summary and expire.
- Provider data-sharing policy is enforced before every provider call.
- MCP exposes only explicitly allowlisted tools and reports pending approval rather than bypassing it.
- Android and Desktop can exchange project messages and task state over the approved transport.
