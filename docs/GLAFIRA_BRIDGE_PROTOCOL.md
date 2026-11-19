# GLAFIRA Bridge Protocol — implementation status

Status: protocol envelope and local validation implemented on `feature/glafira-bridge-protocol`; no listener, pairing service or ChatGPT connector is deployed yet.

## First protocol increment

The Android code now defines a versioned request/response envelope with:
- protocol version, request ID and idempotency key;
- device and project identity fields;
- typed operation enum (project read, conversation read/append, approval request);
- bounded issue/expiry timestamps;
- per-operation payload allowlists and required fields;
- an HMAC-SHA256 signing/verification primitive with constant-time signature comparison (the per-device secret storage and issuance layer is not implemented yet);
- explicit response states, including `PENDING_APPROVAL`.

The initial operation set deliberately excludes shell, arbitrary filesystem access, arbitrary URLs and generic code execution. Unknown payload fields, malformed identifiers, expired requests, oversized payloads and unsupported protocol versions fail validation.

## Security work still required before any live connection

1. Pair devices through a short-lived owner-confirmed enrollment code.
2. Issue per-device credentials and keep them in Android Keystore / desktop OS credential storage.
3. Authenticate every request over TLS; rotate and revoke credentials.
4. Add durable replay/idempotency storage and request audit records.
5. Route every operation through GLAFIRA Core scopes and the approval gate immediately before execution.
6. Add a transport host and the narrow MCP adapter for ChatGPT product connectivity.
7. Run unit, integration, reconnect, revocation, replay and Android device tests.

## Important deployment constraint

An APK installed on a phone cannot by itself make a private phone endpoint reachable by ChatGPT. A ChatGPT product connector needs a supported connector configuration and a reachable HTTPS endpoint. The free-first option is an owner-controlled always-on Windows host running the Bridge/MCP service, with a separately reviewed secure inbound connectivity method. No paid relay, rented server or public endpoint is enabled by this code.

The OpenAI API provider adapter is a separate route: it lets the app call an OpenAI model using an API key and separate API billing; it does not connect this ChatGPT conversation to the app.
