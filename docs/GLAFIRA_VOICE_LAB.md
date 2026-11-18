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

## Work packages

### VL-0 — Inputs, rights, and requirements
- Identify the exact reference speaker/persona for each corpus.
- Inventory user-supplied recordings and their provenance.
- Record permission/license/rights status and allowed uses.
- Define whether the target is analysis only, authorized voice cloning, or both.
- Define language, sample rate, duration, audio quality, target devices, latency, and offline requirements.

### VL-1 — Audio corpus and analysis
- Build a local-only corpus workflow with metadata and checksums.
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
- Establish rollback and deletion procedures for datasets and checkpoints.

### VL-4 — GLAFIRA integration
- Define a provider-neutral Voice API in Core.
- Add speech input/output adapters for Android and Windows Desktop.
- Route voice actions through Core policy and authorization.
- Add user-visible controls for microphone, recording, playback, provider choice, and data handling.
- Preserve text interaction as a complete fallback.

### VL-5 — Quality, safety, and device testing
- Test speech recognition and synthesis in Russian, including names and domain vocabulary.
- Measure latency, memory/CPU/GPU use, battery impact, and offline behavior.
- Test noisy environments, interruptions, long sessions, and recovery after errors.
- Test permissions, recording indicators, data deletion, and credential handling.
- Run regression tests on both client platforms.

## Acceptance criteria

- A documented, reproducible corpus workflow with provenance and rights status.
- No private audio or secrets committed to the repository.
- A documented comparison of candidate models and licenses.
- Repeatable quality and performance tests with baseline metrics.
- Core-mediated authorization and audit for voice-triggered agent actions.
- Android and Windows clients use the same provider-neutral contracts.
- Voice features can be disabled without breaking text-based operation.
- No claim of completion until device tests pass, defects are fixed, and regression tests pass.

## Dependencies and immediate next steps

1. Continue the existing backup, Core hardening, and secure Bridge work as the higher priority.
2. Ask the user to provide or identify the reference recordings and confirm their provenance and permitted use.
3. Clarify whether the first experiment should be audio analysis only or authorized synthesis of new speech in the reference voice.
4. Inventory available local hardware and choose a no-paid-infrastructure-first evaluation path.
5. Create a separate implementation branch for Voice Lab only when the first technical work package is ready.

## Current limitations

This document records scope and requirements only. It does not mean a voice model has been trained, a dataset has been uploaded, or voice cloning/integration is already implemented.
