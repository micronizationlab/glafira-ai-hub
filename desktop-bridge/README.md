# GLAFIRA Desktop Bridge — first Windows Desktop runtime slice

This is a JVM 17 command-line validation client and task-worker foundation. It is **not** the final Windows Desktop graphical application.

## Included

- Owner-approved Android pairing and one-time credential-bundle import.
- Encrypted local Desktop credential vault; passphrases and HMAC secrets are not printed.
- HTTPS Bridge client with bounded response handling and scope checks.
- Durable Desktop task queue with expiry, idempotency, state transitions and interrupted-task recovery.
- Bounded worker runtime with an explicit task-handler registry.
- Local Ollama executor and DRY_RUN preview path.
- Unit tests for credential vault, HTTP body limits, pairing, dry-run preview, queue lifecycle and task runtime.

## Safety model

- The Android owner must approve pairing and the actual granted scopes.
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
- Add authenticated Desktop enrollment, task leases, heartbeats, result acknowledgements and owner-visible status.
- Add Windows packaging/signing, installer/update flow and end-to-end pairing acceptance.
- Connect the Desktop host to the Windows Agent fleet and Android/iOS peers only after protocol compatibility tests pass.
