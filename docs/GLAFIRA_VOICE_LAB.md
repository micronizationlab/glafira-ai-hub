# GLAFIRA Voice Lab

Status: approved project direction; implementation not yet started.

## Purpose

Develop a dedicated voice research, training, evaluation, and integration track for GLAFIRA AI HUB. Voice Lab runs in parallel with GLAFIRA Core and must not delay or weaken Core security, backup, or stability work.

The intended outcome is a modular voice subsystem that can be used by the Android and Windows Desktop clients through shared, platform-neutral contracts.

## User goal

The user wants to research and test voice models that reproduce the characteristics of specific reference speakers, including timbre and manner of speech, for private experimentation and agent-quality evaluation.

Reference speakers discussed:
- Yuri Levitan
- Shura Karetny (the performer/persona must be identified precisely before selecting or processing a training corpus).

Private testing is a project context, not by itself proof of permission to clone or distribute a recognizable person's voice. Before training or generating a recognizable clone, record the applicable consent, license, rights-holder authorization, or other valid legal basis. Until that gate is satisfied, work may cover audio analysis, acoustic measurements, dataset quality, and non-impersonating voice-style research.

## Architecture principles

1. Voice Lab is a separate module/project track, not part of the security-critical Core implementation.
2. GLAFIRA Core remains the authority for agent policy, authorization, permissions, audit, and tool execution.
3. Android and Windows Desktop are equal clients; neither owns the voice logic.
4. Voice providers must be replaceable. Support both local inference where feasible and optional external providers, without requiring paid infrastructure by default.
5. Do not send recordings to external services without an explicit user decision and clear disclosure of provider/data handling.
6. Never place API keys, credentials, private recordings, or unlicensed training datasets in Git.
7. Keep source recordings, derived datasets, model weights, and evaluation reports separate, access-controlled, and traceable.
8. Do not represent a style approximation as an exact voice clone.
9. **Cloud-first file library:** user-uploaded source audio and other large files must live in a user-controlled cloud drive (Google Drive or Yandex Disk), not permanently in the phone's or desktop's internal storage. Local storage is limited to temporary cache/working files and must have configurable cleanup.
10. The cloud-drive integration must support both Google Drive and Yandex Disk through provider adapters. Both providers are part of the approved architecture; account connection and activation are deferred until after the Core and secure Bridge foundation is ready.
11. GLAFIRA must not silently upload files to a cloud provider. The user explicitly connects the account, chooses the provider and library folder, and can inspect/revoke access.
12. **Mandatory Yandex Alice integration:** GLAFIRA must be designed to work with Yandex Alice as an important voice-assistant integration, not merely as an optional future idea. The exact supported interaction path must be validated against currently available official Yandex interfaces and the user's account capabilities; do not assume Alice can directly ingest arbitrary audio files or control GLAFIRA without a supported integration surface.
13. **Free-tier-first constraint:** the initial design and implementation must target the user's existing ordinary Yandex account and available free features, without requiring paid Yandex subscriptions or paid cloud infrastructure. If a desired Alice or cloud feature requires a paid plan, clearly identify the limitation and propose a free alternative before asking the user to pay.
14. The user has an ordinary password for Yandex services. Never request that password in chat, store it in project files, or transmit it to ChatGPT. Account linking must use the official authorization flow (such as OAuth/device authorization where supported); store tokens only in secure platform storage and allow revocation.
15. Store only the minimum file metadata and references in GLAFIRA Core's index; keep OAuth tokens/credentials in secure platform storage and never in Git, logs, prompts, or shared engineering memory.
16. Access to cloud files must be mediated by Core authorization and audited. Use least-privilege scopes, short-lived access where supported, and avoid exposing permanent public links.
17. Upload, download, rename, delete, synchronization, quota errors, offline behavior, and account disconnection must be handled explicitly. Deleting a local cache must not delete the cloud original; cloud deletion requires a clear user action and confirmation.

## Work packages

### VL-0 — Inputs, rights, and requirements
- Identify the exact reference speaker/persona for each corpus.
- Inventory user-supplied recordings and their provenance.
- Record permission/license/rights status and allowed uses.
- Define whether the target is analysis only, authorized voice cloning, or both.
- Define language, sample rate, duration, audio quality, target devices, latency, and offline requirements.
- Defer connecting user accounts until the Core and secure Bridge foundation is ready.
- When connection is introduced, let the user connect either or both accounts, select dedicated library folders, and review requested permissions.

### VL-1 — Cloud file library and audio corpus
- Implement and test adapters for both Google Drive and Yandex Disk through a provider-neutral cloud storage interface.
- Make the connected cloud folder the canonical library for original recordings and large derived assets.
- Build a local metadata index with file IDs, provider, folder, checksum, size, media type, provenance, rights status, and processing status; do not store the full audio in the index.
- Support resumable upload/download where available, integrity verification, duplicate detection, and safe retry behavior.
- Use bounded temporary local cache for processing; show cache usage and provide cleanup controls.
- Detect clipping, noise, silence, duplicates, and unsuitable segments.
- Measure speech rate, pauses, pitch range, intonation contours, articulation, and prosody.
- Produce a reproducible reference-speaker profile without exposing private source audio.
- Keep objective acoustic measurements separate from subjective style descriptions.

### VL-2 — Model/provider evaluation
- Compare candidate speech-to-text, speaker-analysis, and text-to-speech/voice-conversion approaches.
- Check model licenses, commercial-use restrictions, data retention, and local inference support.
- Benchmark intelligibility, speaker similarity (only for authorized cloning), prosody, latency, stability, and resource use.
- Select a baseline only after documented evaluation.

### VL-3 — Training and controlled experiments
- Prepare a reproducible dataset manifest and train/validation/test split.
- Run experiments only with data whose use is authorized for the selected purpose.
- Keep experiments isolated; version configs, model artifacts, and evaluation results.
- Store large model artifacts in the selected cloud library or another explicitly approved storage location, not in Git.
- Establish rollback and deletion procedures for datasets and checkpoints.

### VL-4 — GLAFIRA integration
- Define a provider-neutral Voice API in Core.
- Add speech input/output adapters for Android and Windows Desktop.
- Route voice actions through Core policy and authorization.
- Add user-visible controls for microphone, recording, playback, provider choice, cloud library, cache, and data handling.
- Preserve text interaction as a complete fallback.
- Provide an authorized Bridge file-transfer operation so ChatGPT can request selected files or derived excerpts from GLAFIRA; no automatic access to the entire cloud drive.
- Transfer only user-selected or explicitly authorized files, with size limits, audit records, and clear confirmation for sensitive/external transfers.

### VL-5 — Quality, safety, and device testing
- Test speech recognition and synthesis in Russian, including names and domain vocabulary.
- Measure latency, memory/CPU/GPU use, battery impact, and offline behavior.
- Test noisy environments, interruptions, long sessions, and recovery after errors.
- Test cloud account connection/disconnection, permission scope, upload/download integrity, sync conflicts, quota limits, network loss, cache cleanup, and deletion semantics.
- Test permissions, recording indicators, data deletion, and credential handling.
- Run regression tests on both client platforms.

## Acceptance criteria

- A documented, reproducible corpus workflow with provenance and rights status.
- No private audio or secrets committed to the repository.
- A cloud-backed library is the canonical store for uploaded source files; local storage is only a bounded cache.
- Both Google Drive and Yandex Disk are supported by tested provider adapters.
- The user can later connect either or both accounts, select library folders, and revoke access.
- A documented and tested Yandex Alice integration path works within the user's available free account capabilities, or clearly documents any unavoidable provider limitation and a no-cost alternative.
- Cloud file access is Core-authorized, least-privilege, and auditable.
- A documented comparison of candidate models and licenses.
- Repeatable quality and performance tests with baseline metrics.
- Core-mediated authorization and audit for voice-triggered agent actions.
- Android and Windows clients use the same provider-neutral contracts.
- Voice features can be disabled without breaking text-based operation.
- No claim of completion until device tests pass, defects are fixed, and regression tests pass.

## Dependencies and immediate next steps

1. Continue the existing backup, Core hardening, and secure Bridge work as the higher priority.
2. Design the cloud-library abstraction and secure Bridge file-transfer contract; do not hard-code a single provider.
3. Keep both Google Drive and Yandex Disk in scope; defer account connection until after Core and secure Bridge are ready.
4. Treat Yandex Alice integration as mandatory; research supported official interfaces and free-tier constraints before implementation.
5. Ask the user to identify reference recordings and confirm their provenance and permitted use.
6. Clarify whether the first experiment should be audio analysis only or authorized synthesis of new speech in the reference voice.
7. Inventory available local hardware and choose a no-paid-infrastructure-first evaluation path.
8. Create a separate implementation branch for Voice Lab only when the first technical work package is ready.

## Current limitations

This document records scope and requirements only. It does not mean a cloud drive is connected, files have been uploaded, a voice model has been trained, or voice cloning/integration is already implemented.
