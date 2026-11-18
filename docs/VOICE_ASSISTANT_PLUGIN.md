# Optional Voice Assistant Plugin

## Product intent

The Voice Assistant is an optional, separately enabled GLAFIRA AI HUB plugin. It adds spoken interaction and speech generation without making voice processing mandatory for ordinary text chats.

The plugin has two independent capabilities:
- **Speech input:** capture microphone audio, transcribe it, detect the active speaker when explicitly enabled, and pass the resulting user message to Glafira.
- **Speech output:** synthesize Glafira's response using a selected voice profile and a separate delivery/style profile.

Voice is a presentation and interaction layer. Glafira Core remains the sole coordinator; the voice plugin must not become a separate agent with its own authority.

## Voice profile versus delivery profile

Keep these concepts separate:

1. **Voice profile** — the acoustic identity: pitch range, timbre, texture, accent, pronunciation, vocal energy and other permitted vocal characteristics.
2. **Delivery profile** — how a line is performed: pace, pauses, emphasis, emotional range, conversational rhythm, degree of theatricality, humour timing and sentence length.
3. **Conversation persona** — the actual wording, knowledge, humour boundaries and conversational rules controlled by Glafira's system instructions.

A voice profile must not silently change the assistant's facts, authority, or business permissions. A delivery profile must not be treated as a license to impersonate a real person.

## Consent and likeness safeguards

- A recording's availability (including a long recording or public broadcast) is not proof of permission to clone the speaker.
- An identifiable real person's voice may be cloned only where the speaker has given explicit, documented authorization covering synthetic voice creation and the intended use.
- Preserve consent evidence, permitted use, duration, provider, model ID and revocation status with the voice asset.
- If the speaker's authorization is absent, do not train or generate a recognizable clone of that person.
- Offer a distinct, original voice with broad non-identifying characteristics instead. Do not label it as the named performer or imply endorsement.
- Clearly disclose AI-generated speech to listeners and preserve provider provenance/watermark metadata where available.
- Provide disable, delete and revoke controls. Deleting a profile should also remove plugin-managed samples and derived assets where provider terms permit.

## Recording and dataset pipeline

Do not treat "one hour of audio" as a universal training requirement. The amount, format and permitted use depend on the chosen provider/model.

For a consented speaker:
1. Confirm the speaker identity and scope of authorization.
2. Keep an original, immutable source recording and a separate working copy.
3. Check that the recording contains the intended speaker; detect music, overlap, noise, edits and other speakers.
4. Segment speech into clean utterances; preserve timestamps and source provenance.
5. Produce a transcript and manually review names, slang, punctuation, disfluencies and expressive delivery.
6. Create separate acoustic and delivery annotations. Do not collapse vocal identity and speaking manner into one opaque prompt.
7. Build a held-out evaluation set that is not used for adaptation.
8. Use a provider that supports the required consent workflow, language, quality and intended deployment.
9. Run listening tests and obtain the speaker's approval before enabling the profile for production.

For a non-cloned original voice, create a descriptive voice prompt and delivery profile from general attributes. Do not use a named person's recordings as a covert cloning dataset.

## Plugin controls

The plugin must be off by default and explicitly enabled by the owner. Keep the owner-facing settings deliberately minimal:
- Voice Assistant: Off / On
- Select / change the installed voice plugin

Do not expose manual controls for pitch, timbre, pace, pauses, accent, emotion or delivery style. The selected plugin/model is responsible for analyzing its permitted source material and producing its configured voice and delivery. Show a simple connection/availability status.

Microphone access is requested only when the owner starts a voice interaction. Do not add always-listening behavior to the minimal toggle. Audio retention remains off by default and must be clearly disclosed if a provider requires processing or storage.

## Runtime architecture

- `VoicePlugin`: lifecycle, opt-in settings and capability declaration.
- `AudioCapture`: microphone permission, capture and streaming.
- `SpeechToTextProvider`: provider adapter for transcription / speech input.
- `VoiceProfileStore`: metadata, consent evidence references and provider voice IDs; never store secrets in profile metadata.
- `DeliveryProfile`: structured delivery instructions independent of voice identity.
- `TextToSpeechProvider`: provider adapter for speech output.
- `AudioPlayback`: playback, interruption and lifecycle cleanup.
- `VoicePolicy`: checks profile authorization, permitted use, disclosure and retention before synthesis.
- `AuditEvents`: record profile creation, activation, deletion and provider operations without storing raw audio or secret values in logs.

All API credentials are resolved through the existing secure credential architecture. The voice plugin must never read the password vault directly or send credentials to an LLM.

## Privacy and security

- Microphone permission is requested only after the owner enables the feature and starts a relevant flow.
- Show an unmistakable listening state; never record silently.
- Do not upload or retain raw recordings unless the user explicitly enables the relevant workflow and is informed of the destination and retention.
- Encrypt stored audio and consent evidence; provide deletion controls.
- Do not send raw audio to unrelated peer models. Send transcripts only when project/provider data-sharing policy permits.
- Never use voice generation to impersonate, deceive, authenticate as, or falsely imply endorsement by another person.

## Delivery plan

1. Plugin contract, opt-in setting, voice/delivery profile data models and policy checks.
2. Speech input using a standard transcription provider.
3. Speech output using a stock or prompt-generated original voice.
4. Voice preview and delivery-profile tuning.
5. Consent-gated custom voice provider adapter for the speaker's own authorized voice.
6. Evaluation, privacy controls, retention/deletion and real-device tests.

## Initial target use case

The requested private target is a voice-and-delivery recreation of performer Shura Karetny, based on analysis of a substantial body of his recordings. Private use and an owner-only plugin toggle do not, by themselves, establish the performer's permission to create a recognizable synthetic replica. Therefore:
- Do not scrape, ingest or train a recognizable clone from publicly available recordings without documented authorization from the performer or applicable rights holder.
- If documented authorization is obtained, the plugin may use an appropriate provider workflow to analyze the authorized recordings and create the voice profile and delivery behavior, subject to provider terms.
- Until then, the plugin may offer only a clearly distinct, original voice using broad non-identifying characteristics; it must not claim to be Shura Karetny or reproduce his recognizable voice.
- The user-facing settings remain only enable/disable and change plugin; consent and provenance checks are enforced internally, not exposed as a collection of tuning controls.
