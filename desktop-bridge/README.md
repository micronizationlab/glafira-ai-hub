# GLAFIRA Desktop Bridge — legacy JVM client and task-worker foundation

This is a JVM 17 command-line validation client and task-worker foundation. It is **not** the Windows Desktop application or its authoritative network host. The current client still speaks to the legacy Android-hosted Bridge endpoint for compatibility; the target architecture makes Windows Desktop Hub the authoritative TLS listener and scheduler.

## Included

- Owner-approved Android pairing and one-time credential-bundle import.
- Encrypted local Desktop credential vault; passphrases and HMAC secrets are not printed.
- HTTPS Bridge client with bounded response handling, certificate pinning and scope checks.
- Task lease ID and fencing token are persisted with each remote task and echoed on every progress/result update.
- Durable Desktop task queue with expiry, idempotency, state transitions and interrupted-task recovery.
- Bounded worker runtime with an explicit task-handler registry.
- Local Ollama executor and DRY_RUN preview path.
- Unit tests for credential vault, HTTP body limits, pairing, dry-run preview, queue lifecycle and task runtime.

## Safety model

- The current legacy Android endpoint requires owner-approved pairing and granted scopes; Windows Desktop Hub enrollment is the target production flow.
- A pairing proof does not itself grant scopes.
- Credentials are not included in task envelopes or model prompts.
- Unknown task types fail closed; arbitrary shell execution is not exposed.
- The CLI refuses to run without a real terminal because secret entry must be hidden.
- DRY_RUN previews are not execution and must not be described as completed actions.
- LIVE external actions remain disabled until a separate policy and owner-approval implementation is reviewed.

## Build and test

From the repository root:

```bash
./gradlew :desktop-bridge:test :desktop-bridge:build
```

## Remaining work

- Replace the validation CLI with the Windows Desktop UI and persistent application host.
- Integrate the authoritative Core SQLite queue rather than a parallel local queue.
- Replace the legacy Android-hosted endpoint with the Windows Desktop HTTPS listener and authenticated enrollment.
- Add owner-visible lease/heartbeat state and result acknowledgements; reject stale results by lease ID and fencing token.
- Add Windows packaging/signing, installer/update flow and end-to-end pairing acceptance.
- Connect the Desktop host to the Windows Agent fleet and Android/iOS peers only after protocol compatibility tests pass.
