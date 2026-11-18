# GLAFIRA Shared Engineering Memory

## Status

Mandatory architectural requirement for GLAFIRA AI HUB. This document records the agreed requirement for the Bridge/Core roadmap; it does not claim that the exchange is already implemented.

## Goal

After the secure Bridge connects ChatGPT (GLAFIRA) with the application, ChatGPT and the application's **Programmer** role must exchange engineering knowledge in both directions and contribute to a shared, version-aware engineering memory.

## Required capabilities

1. **ChatGPT → Programmer role**
   - Share architecture decisions, implementation patterns, algorithms, debugging findings, test strategies, code-review findings, and relevant technical documentation.
2. **Programmer role → ChatGPT**
   - Share implementation summaries, changed-file references or diffs, build and test results, diagnostics, discovered defects, and validated technical solutions.
3. **Shared engineering memory**
   - Store reusable knowledge with project/module context, source, author/agent, timestamp, applicable version or commit, validation status, and links to evidence where available.
4. **Validation before promotion**
   - Distinguish proposals, observations, unverified findings, tested solutions, and accepted engineering decisions. Do not promote unverified suggestions as established facts.
5. **Traceability**
   - Preserve provenance and version context so that knowledge can be checked against the current repository state and superseded decisions can be identified.
6. **Continuous learning**
   - Feed verified outcomes, including failures and regressions, back into the shared memory so both participants can use them in later work.

## Safety and authority boundaries

- Neither participant blindly trusts or automatically copies the other's code or recommendations.
- Shared memory is not an execution authority. Code changes still require normal review, tests, and repository controls.
- The Core remains responsible for authorization, policy enforcement, tool permissions, and auditability.
- The LLM and request payloads must not be able to grant themselves permissions, approve actions, or rewrite trusted pairing credentials.
- Sensitive data, secrets, access tokens, and private credentials must never be stored in shared engineering memory.
- Every write and read path must be scoped to the relevant project and authorized role/device.
- Preserve an auditable history of accepted decisions and changes.

## Implementation direction

Treat this as a Core capability with platform-neutral contracts, not as an Android-only feature. Android and Windows Desktop clients are equal clients of the shared Core. The Bridge should expose narrowly scoped, typed operations for submitting, retrieving, reviewing, and promoting engineering knowledge. The first implementation should define the data model, provenance/validation states, access scopes, and audit events before adding automation.

## Acceptance criteria

- A knowledge item submitted by ChatGPT can be retrieved by the Programmer role, and vice versa, subject to authorization.
- Each item carries project/module, source, version/commit context, timestamp, and validation state.
- Proposed or unverified content is visibly distinguished from tested/accepted knowledge.
- Promotion to accepted knowledge is an explicit, auditable operation.
- Unauthorized devices/roles cannot read or write project engineering memory.
- Secrets and credentials are rejected or redacted.
- Android and Desktop use the same Core contracts.
- Automated tests cover authorization, provenance, validation-state transitions, version context, and rejection of unauthorized or malformed writes.

## Delivery status

**Not implemented yet.** This is an agreed mandatory requirement to be scheduled after the secure Bridge foundation is ready. It does not imply that a live ChatGPT-to-app connection or automatic knowledge exchange currently exists.
