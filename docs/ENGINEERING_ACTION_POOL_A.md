# Action Pool A — rolling 50-item GLAFIRA backlog

**Snapshot:** 2026-11-21 — priority labels reconciled to the current owner hierarchy. Linked PR/CI statuses remain snapshots and must be re-fetched against exact head SHA before assignment or acceptance.  
**Current owner priority:** P2 Core → P2 Backup & Recovery → P3 Runtime/Bridge/Android instrumentation/Windows Desktop/Windows Agent → P4 Android UX/voice/iOS/specialist agents → P5 optional commercial integrations.

**Current owner priority:** P1 Core → P2 Backup & Recovery → P3 Runtime/Bridge/Android instrumentation/Windows Desktop/Windows Agent → P4 Android UX/voice/iOS/specialist agents → P5 optional commercial integrations.

**Rule:** take the highest-priority item that is currently unblocked and non-conflicting. After each completion or newly available slot, rescan from item 1. Keep Pool A alive; prepare Pool B before Pool A runs out. Statuses are a planning snapshot, not claims of completion.

| # | Priority | Action / acceptance result | Existing reference | Current state / dependency |
|---:|:---:|---|---|---|
| 01 | P1 | Complete exact-head validation of dependency-aware DAG, including real Android SQLite instrumentation and migration tests | PR #159 | CI queued; runner config just added |
| 02 | P1 | Port remaining safe parallel-claim/recovery behavior from stale Core branch without overwriting current main | PR #71, #104 | Reconcile against current main |
| 03 | P1 | Integrate fair project-level dispatch with starvation bounds and deterministic tests | PR #119 | Stacked; requires Core reconciliation |
| 04 | P1 | Integrate durable AI task-plan graph creation and owner-runtime scheduling | PR #122 | Stacked; reconcile before port |
| 05 | P1 | Verify no duplicate task claims across independent SQLite connections under contention | PR #159, #71 | Instrumentation acceptance pending |
| 06 | P1 | Verify process-death recovery, expired leases and stale-worker fencing | PR #53, #163, #275, #284 | Partial: claim/history rollback, handler/retry recovery, result-write rollback and Desktop progress rollback are covered; #284 adds combined result+retry-history failure recovery and awaits exact-head CI. Full cross-platform failure matrix remains open |
| 07 | P1 | Complete provider outcome state machine: rejected vs unsent vs unknown | PR #264, #282, issue #262 | Partial: typed dispatch outcomes and partial-composite UNKNOWN/no-replay tests are merged; full provider coordinator checkpoints and durable reconciliation remain open |
| 08 | P1 | Complete durable result delivery/outbox retry until explicit ACK | Issue #266, prerequisite #258 | Blocked by real Core identity and ownership-generation foundation; durable outbox, exact recipient/digest ACK and restart-safe retry are not implemented |
| 09 | P2 | Complete portable Core backup and interrupted-restore recovery | PR #287, issue #260 | Partial: main has encrypted snapshot/restore and interrupted-restore handling; current-main PR #287 adds unknown-table export rejection and restore transaction rollback injection, exact-head CI pending |
| 10 | P1 | Verify Core maintenance lock is separate from per-task locks; crash/restart and data-integrity matrix | PR #71, #159 | Tests and code audit pending |
| 11 | P3 | Land Android instrumentation runner and initial AndroidJUnit4 suite in main | PR #159 | Exact-head CI pending |
| 12 | P3 | Land corrected change-aware selector so relevant Android/Core/Bridge changes run emulator acceptance | PR #161, #159 | Selector fix exists on PR branches |
| 13 | P3 | Preserve and verify historical TaskPlan instrumentation coverage from PR #122 in current main | PR #122, `GlafiraTaskPlanInstrumentedTest.kt` | The historical six cases plus four current-main acceptance additions are present (10 tests). Exact-head emulator run #44870182653 executed 77 instrumentation cases with 0 failures/errors/skips; current-main refresh PR #287 is rerunning the suite |
| 14 | P3 | Finish Bridge settings UI, owner approval and emulator regression | PR #140 | Android JVM passed; APK/emulator running |
| 15 | P2 | Complete backup manual action and user-visible failure/precondition states | Issue #137, PR #139/#148, PR #352/#353 | Open regression: emulator runs #44912132854, #44916097128 and #44915373464 each failed BackupSettingsSmokeTest; inspect exact logs and fix behavior, not assertions |
| 16 | P4 | Make Council action clickable when idle and show actionable validation/partial-failure states | Issue #137 | Await UI regression |
| 17 | P4 | Add user-facing task queue add/priority/project controls with safe task-type allowlist | Issue #137 | Not yet verified in current main |
| 18 | P2 | Verify Google Drive SAF write/restore and persisted URI permissions on physical Android device | Issue #138 | Physical device required |
| 19 | P3 | Verify two-device owner-approved private-LAN Bridge pairing and Keystore signing | Issues #73/#137 | Physical device pair required |
| 20 | P3 | Align Kotlin Bridge golden vectors with canonical schema/HMAC, including Unicode and sorted keys | Issue #73 | Contract suite work |
| 21 | P3 | Complete Bridge v1 task result lease/fencing contract on Android, Desktop client and Hub | PR #170, #283, issue #91 | Partial: lease ID/fencing fields, Android validation, Desktop queue persistence and Windows Hub admission validation are in main; #283 added Windows Hub C# regression tests and merged with exact-head checks |
| 22 | P3 | Validate replay rejection, revoked device rejection and stale fencing token rejection end-to-end | Issue #123, #91 | Open: protocol/admission tests exist, but live authoritative lease issuance, revocation-to-lease invalidation and end-to-end stale-result rejection still require the production Desktop Hub listener/store |
| 23 | P3 | Finish Bridge owner test APK signing/distribution and verify installed APK SHA | PR #69 | Stacked; inspect exact head |
| 24 | P3 | Reconcile Android Bridge Keystore signer fix with current owner pairing flow | PR #63 | Stacked on older Bridge UI |
| 25 | P3 | Close the release acceptance matrix across navigation, controls, permissions and screenshots | Issue #138 | Depends on #14–#20 |
| 26 | P3 | Complete Windows Desktop laptop-first control-center shell beyond initial navigation | PR #157 | Re-fetch current PR head and exact-head CI; historical shell pass is not current acceptance |
| 27 | P3 | Integrate Desktop Hub durable scheduler with mainline task store and lifecycle | PR #158, issue #123 | Active; inspect exact-head CI |
| 28 | P3 | Integrate HMAC admission, durable replay guard and DPAPI credential resolver on a clean main-based stack | PR #160/#147, issues #123 | Existing slices have stale stack dependencies |
| 29 | P3 | Make Windows Desktop the authoritative HTTPS Bridge host; remove Android-host assumption from production path | Issue #123 | Design/contract first |
| 30 | P3 | Implement Desktop enrollment, agent identity, scope grant and revocation lifecycle | Issue #123 | Blocked by host/authenticator |
| 31 | P3 | Implement multi-agent scheduler with configurable worker count and capability matching | Issue #123 | Depends on durable Hub scheduler |
| 32 | P3 | Implement Windows Agent secure enrollment and background worker service | Windows Agent issue W1/W2 | Not complete |
| 33 | P3 | Verify Desktop ↔ Windows Agent reconnect, heartbeat, lease expiry and offline recovery | PR #157, issue #123 | Requires runnable Desktop host + Agent |
| 34 | P3 | Finish Desktop task event journal, replay and audit visibility | PR #158 | Exact-head status to inspect |
| 35 | P3 | Verify DPAPI credential storage, rotation, revocation and no plaintext logs | PR #147 | Stale branch stack; port cleanly |
| 36 | P4 | Complete Android voice input permission UX and push-to-talk/toggle recording | Issue #99 | Existing runtime launcher; session work incomplete |
| 37 | P4 | Complete STT+TTS voice sessions with interruption, cancel, audio focus and headset handling | Issue #99, PR #156 | TTS rate/test button partial only |
| 38 | P4 | Finish Android home-section exclusivity and safe provider-key collapse/masking | Issue #82 | Old user feedback backlog |
| 39 | P4 | Fix cloud folder picker crash and test Drive/Yandex provider failure paths | PR #83, issue #82 | Stacked on old Core branch |
| 40 | P4 | Verify provider preference backup/restore and secret exclusion | PR #19, #60 | Old open work |
| 41 | P4 | Complete secure incremental in-app updater with signature, rollback and exact release channel | PR #42/#43 | Reconcile duplicate branches |
| 42 | P4 | Harden Password Manager CSV import with malformed/duplicate/large-file cases | PR #45 | Open older task |
| 43 | P4 | Finish model availability status and provider-specific health display | PR #10 | Open older task |
| 44 | P4 | Complete ProjectRepository and shared project workspace persistence | PR #7 | Open older task |
| 45 | P4 | Implement safe incoming chat-file handling with type/size/sandbox limits | PR #3 | Open older task |
| 46 | P4 | Finish Кадровик catalog/runtime and evidence dossier workflow under privacy boundaries | Issue #84, PR #98 | Queue 4 implementation |
| 47 | P4 | Reconcile professional agent catalog, role training and voice-plugin roadmap | PR #102 | Documentation/implementation split |
| 48 | P4 | Validate free-only connectors and source terms for HR, Doctor, Lawyer, Banker and Programmer | Issue #84 | Research before production adapters |
| 49 | P5 | Complete GCU/GLF metering, benchmark and owner-approved quote methodology | Issue #154, PR #153 | Specification exists; implementation deferred |
| 50 | P5 | Research Project Token/Clover: provider pricing (Yandex vs Grok etc.), VPN+GLAFIRA bundle, voluntary compute incentives, DePIN retention, reward economics and Russian legal/payment constraints | Token research issue / owner brief | External research workstream; parallel when engineering slots permit |

## Pool discipline

- An item is complete only with its acceptance evidence, not merely a commit or a green workflow summary.
- A PR that is stale, stacked on an unmerged base, or duplicates another slice must be reconciled rather than merged blindly.
- When a higher-priority item waits on CI or hardware, work proceeds on the next non-conflicting item below it.
- When any slot opens, rescan from item 01. Once all currently safe Pool A items are either completed or explicitly blocked, create Pool B with 50 additional items and continue alternating by rechecking Pool A after each Pool B completion.
- External research may proceed while CI runs, but does not authorize paid API calls, subscription spend or production token issuance.
