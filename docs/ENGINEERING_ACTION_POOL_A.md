# Action Pool A — rolling 50-item GLAFIRA backlog

**Snapshot:** 2026-11-20  
**Rule:** take the highest-priority item that is currently unblocked and non-conflicting. After each completion or newly available slot, rescan from item 1. Keep Pool A alive; prepare Pool B before Pool A runs out. Statuses are a planning snapshot, not claims of completion.

| # | Priority | Action / acceptance result | Existing reference | Current state / dependency |
|---:|:---:|---|---|---|
| 01 | P0 | Complete exact-head validation of dependency-aware DAG, including real Android SQLite instrumentation and migration tests | PR #159 | CI queued; runner config just added |
| 02 | P0 | Port remaining safe parallel-claim/recovery behavior from stale Core branch without overwriting current main | PR #71, #104 | Reconcile against current main |
| 03 | P0 | Integrate fair project-level dispatch with starvation bounds and deterministic tests | PR #119 | Stacked; requires Core reconciliation |
| 04 | P0 | Integrate durable AI task-plan graph creation and owner-runtime scheduling | PR #122 | Stacked; reconcile before port |
| 05 | P0 | Verify no duplicate task claims across independent SQLite connections under contention | PR #159, #71 | Instrumentation acceptance pending |
| 06 | P0 | Verify process-death recovery, expired leases and stale-worker fencing | PR #53, #163 | Partial; needs end-to-end Core tests |
| 07 | P0 | Complete provider outcome state machine: rejected vs unsent vs unknown | PR #54 | Old open implementation; reconcile |
| 08 | P0 | Complete durable result delivery/outbox retry until explicit ACK | PR #58 | Stacked; reconcile with main |
| 09 | P0 | Complete portable Core backup and interrupted-restore recovery | PR #57 | Stacked; reconcile with main |
| 10 | P0 | Verify Core maintenance lock is separate from per-task locks; crash/restart and data-integrity matrix | PR #71, #159 | Tests and code audit pending |
| 11 | P1 | Land Android instrumentation runner and initial AndroidJUnit4 suite in main | PR #159 | Exact-head CI pending |
| 12 | P1 | Land corrected change-aware selector so relevant Android/Core/Bridge changes run emulator acceptance | PR #161, #159 | Selector fix exists on PR branches |
| 13 | P1 | Verify exact-head emulator reports for the 9-test initial suite; reconcile and port the older 16-test TaskPlan instrumentation suite from PR #122 after its Core dependencies are integrated | PR #159, #122 | Initial 9 tests pass on emulator; older 16-test suite remains stacked/unmerged |
| 14 | P1 | Finish Bridge settings UI, owner approval and emulator regression | PR #140 | Android JVM passed; APK/emulator running |
| 15 | P1 | Complete backup manual action and user-visible failure/precondition states | Issue #137, PR #139/#148 | Emulator regression active |
| 16 | P1 | Make Council action clickable when idle and show actionable validation/partial-failure states | Issue #137 | Await UI regression |
| 17 | P1 | Add user-facing task queue add/priority/project controls with safe task-type allowlist | Issue #137 | Not yet verified in current main |
| 18 | P1 | Verify Google Drive SAF write/restore and persisted URI permissions on physical Android device | Issue #138 | Physical device required |
| 19 | P1 | Verify two-device owner-approved private-LAN Bridge pairing and Keystore signing | Issues #73/#137 | Physical device pair required |
| 20 | P1 | Align Kotlin Bridge golden vectors with canonical schema/HMAC, including Unicode and sorted keys | Issue #73 | Contract suite work |
| 21 | P1 | Complete Bridge v1 task result lease/fencing contract on Android, Desktop client and Hub | PR #163 | Stacked on #160; CI pending |
| 22 | P1 | Validate replay rejection, revoked device rejection and stale fencing token rejection end-to-end | PR #163, issue #123 | Requires durable Hub lease store |
| 23 | P1 | Finish Bridge owner test APK signing/distribution and verify installed APK SHA | PR #69 | Stacked; inspect exact head |
| 24 | P1 | Reconcile Android Bridge Keystore signer fix with current owner pairing flow | PR #63 | Stacked on older Bridge UI |
| 25 | P1 | Close the release acceptance matrix across navigation, controls, permissions and screenshots | Issue #138 | Depends on #14–#20 |
| 26 | P2 | Complete Windows Desktop laptop-first control-center shell beyond initial navigation | PR #157 | Shell CI passed; functional controls remain |
| 27 | P2 | Integrate Desktop Hub durable scheduler with mainline task store and lifecycle | PR #158, issue #123 | Active; inspect exact-head CI |
| 28 | P2 | Integrate HMAC admission, durable replay guard and DPAPI credential resolver on a clean main-based stack | PR #160/#147, issues #123 | Existing slices have stale stack dependencies |
| 29 | P2 | Make Windows Desktop the authoritative HTTPS Bridge host; remove Android-host assumption from production path | Issue #123 | Design/contract first |
| 30 | P2 | Implement Desktop enrollment, agent identity, scope grant and revocation lifecycle | Issue #123 | Blocked by host/authenticator |
| 31 | P2 | Implement multi-agent scheduler with configurable worker count and capability matching | Issue #123 | Depends on durable Hub scheduler |
| 32 | P2 | Implement Windows Agent secure enrollment and background worker service | Windows Agent issue W1/W2 | Not complete |
| 33 | P2 | Verify Desktop ↔ Windows Agent reconnect, heartbeat, lease expiry and offline recovery | PR #157, issue #123 | Requires runnable Desktop host + Agent |
| 34 | P2 | Finish Desktop task event journal, replay and audit visibility | PR #158 | Exact-head status to inspect |
| 35 | P2 | Verify DPAPI credential storage, rotation, revocation and no plaintext logs | PR #147 | Stale branch stack; port cleanly |
| 36 | P3 | Complete Android voice input permission UX and push-to-talk/toggle recording | Issue #99 | Existing runtime launcher; session work incomplete |
| 37 | P3 | Complete STT+TTS voice sessions with interruption, cancel, audio focus and headset handling | Issue #99, PR #156 | TTS rate/test button partial only |
| 38 | P3 | Finish Android home-section exclusivity and safe provider-key collapse/masking | Issue #82 | Old user feedback backlog |
| 39 | P3 | Fix cloud folder picker crash and test Drive/Yandex provider failure paths | PR #83, issue #82 | Stacked on old Core branch |
| 40 | P3 | Verify provider preference backup/restore and secret exclusion | PR #19, #60 | Old open work |
| 41 | P3 | Complete secure incremental in-app updater with signature, rollback and exact release channel | PR #42/#43 | Reconcile duplicate branches |
| 42 | P3 | Harden Password Manager CSV import with malformed/duplicate/large-file cases | PR #45 | Open older task |
| 43 | P3 | Finish model availability status and provider-specific health display | PR #10 | Open older task |
| 44 | P3 | Complete ProjectRepository and shared project workspace persistence | PR #7 | Open older task |
| 45 | P3 | Implement safe incoming chat-file handling with type/size/sandbox limits | PR #3 | Open older task |
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
