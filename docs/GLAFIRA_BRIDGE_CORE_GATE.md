# GLAFIRA Bridge → Core authorization boundary

This increment connects the typed Bridge envelope to the existing Core tool registry and policy gate.

## Request path

1. Validate protocol version, identifiers, TTL and operation payload.
2. Compare the request device ID with the device identity authenticated by the host.
3. Verify the HMAC-SHA256 signature using the device secret obtained from the trusted credential store.
4. Check that the authenticated device has the exact scope required by the mapped Core tool.
5. Apply the Core approval policy. An approval request can return `PENDING_APPROVAL`, but this response is not an approval grant and does not execute the requested action.

## Operation mapping

| Bridge operation | Core tool | Required scope |
|---|---|---|
| PROJECT_READ | project.read | project.read |
| CONVERSATION_READ | conversation.read | conversation.read |
| CONVERSATION_APPEND | conversation.append | conversation.append |
| APPROVAL_REQUEST | approval.request | approval.request |

## Trust boundary and remaining work

The gate is a pure authorization adapter. The authenticated device ID, device secret and granted scopes are supplied by its caller and MUST come from a trusted pairing/credential store. They must never be derived from Bridge request fields, an LLM response or user-controlled payload.

This code does not yet implement device pairing, credential persistence/rotation/revocation, durable replay/idempotency storage, a network listener, TLS hosting, an MCP server, a persistent approval inbox or operation execution. In particular, returning `PENDING_APPROVAL` is only a policy decision; durable owner review and grant issuance remain future work. No live ChatGPT connection or APK release is claimed.
