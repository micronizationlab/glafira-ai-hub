# GLAFIRA Voice Runtime Contracts

This change adds provider-neutral Kotlin contracts only; it does not add a cloud provider, capture microphone audio, play audio, clone voices, or store recordings.

## Safety and lifecycle

- All providers are disabled unless the owner explicitly enables their provider ID.
- The app must request microphone permission only when the owner starts a push-to-talk/voice session. There is no always-listening contract or background microphone service.
- Audio payloads are bounded (25 MiB / 10 minutes), defensively copied, and passed to a provider only for the active request.
- Providers must not persist raw audio/transcripts or log request contents/credentials. Retention behavior is explicit in provider metadata.
- Text-to-speech always passes through a Core-owned authorization gate before a provider is called. The gate must enforce the existing voice-profile consent, expiry, revocation and permitted-use policy.
- Synthesis results carry user-facing AI-generated speech disclosure text.
- Acoustic voice profile IDs and delivery/persona profile IDs are separate fields.
- Provider adapters must use official APIs/SDKs, expose capability and locale metadata, and must not embed API keys. Secret retrieval belongs to the existing encrypted provider credential layer.

## Contract surface

- VoicePluginProvider: provider identity, capabilities and lifecycle.
- SpeechRecognitionProvider: bounded audio input to transcript result.
- SpeechSynthesisProvider: text/profile/use request to audio result.
- VoicePluginCatalog: duplicate IDs rejected; owner enablement is explicit and defaults to none.
- GlafiraVoiceRuntime: common call path; synthesis consent gate runs before provider invocation.

## Not implemented yet

- Microphone permission/session UI and Android AudioRecord capture.
- Audio playback lifecycle, interruption/focus handling and cancellation.
- Concrete STT/TTS adapters and provider comparison for Russian quality, latency, privacy, cost and free tiers.
- Provider settings UI, secure credential retrieval and physical-device tests.
- Streaming partial transcripts/speech and robust cancellation.
