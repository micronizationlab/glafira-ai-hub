# GLAFIRA AI HUB — Hybrid LLM Strategy

## Product decision

GLAFIRA is a thin, secure work client with a cloud-first model architecture. Most reasoning, coding, research, long-context work and multi-agent coordination are performed by external model APIs. The Android app is primarily the interface, secure local store and tool client; it must not require a large model to be bundled into the APK.

A small on-device model is an optional assistant for short, low-risk tasks and offline convenience. It is not the coordinator's primary brain and it is never required for the app to start.

## Runtime tiers

1. **Cloud primary** — user-selected external providers through a common provider interface. The coordinator can use a stronger cloud model for complex tasks and cheaper/free models for routine tasks.
2. **Cloud fallback** — a second configured provider can be selected after an explicit policy decision. Do not silently send private project data to an unapproved provider.
3. **On-device SLM** — optional, downloadable and capability-gated. Candidate runtime: LiteRT-LM Android Kotlin API. Candidate starting model: a quantized Gemma 3 1B or Gemma 3n E2B artifact, subject to device memory, thermal and performance checks. Do not bundle multi-hundred-megabyte weights in the APK.
4. **No-model fallback** — deterministic local features remain available: search, project navigation, templates, queueing and vault access.

## Workload routing

- Multi-agent council, code review, long documents, complex planning and external research: cloud.
- Short rewrite, short summary, simple classification or local draft cleanup: on-device SLM when installed and the device passes capability checks; otherwise cloud.
- Offline: queue cloud tasks, or offer the local SLM for supported short tasks.
- Passwords, API keys, authentication tokens and vault contents: never include in prompts, agent context, analytics, logs or crash reports. The password vault has no model integration.

## Privacy and provider policy

- Provider selection is explicit and visible in the UI.
- Each provider has metadata for cost class, free-tier status, data handling and whether private project context is allowed.
- Free API access is not automatically private. Before enabling a provider for sensitive project data, review its current retention/training policy.
- Never retry a failed request by forwarding the same prompt to another provider unless the user has allowed that provider for the project.
- API keys stay in encrypted local storage or a future backend secret manager. Never commit keys or place them in model prompts.
- The password vault is a separate local-only encrypted store. It is never synchronized to cloud by default.

## Device and performance policy

- Model weights are downloaded only after user consent and stored outside the APK in app-private storage.
- Check available memory, free disk, ABI, runtime support and thermal state before enabling local inference.
- Start with a small model and conservative context/output limits; do not keep the model resident continuously.
- Do not assume the user's laptop compute is available to the Android app. A future desktop companion or private inference endpoint would need an explicit authenticated connection.
- Keep provider/model identifiers in configuration, not scattered through UI code.

## Implementation sequence

1. Establish encrypted API-key storage and disable Android backup for secret-bearing app data.
2. Build the local password vault with a master password, authenticated encryption and explicit lock/unlock.
3. Stabilize the external provider interface and model routing, including provider-specific limits and errors.
4. Add provider privacy/cost metadata and project-level allowlists.
5. Add an optional LiteRT-LM runtime behind a feature flag, then benchmark on real devices before selecting a default model.
6. Add an optional desktop/private inference bridge only after authentication, transport encryption and permission boundaries are designed.

## Acceptance criteria

- The app launches and remains useful without downloading a local model.
- Complex and multi-agent tasks use cloud APIs by default.
- The local SLM is optional and never silently substitutes a cloud provider for a sensitive request.
- Vault data and provider secrets never enter model context.
- No secret-bearing data is included in Android backups.
- Model download, provider use and any data-sharing policy are visible to the user.
