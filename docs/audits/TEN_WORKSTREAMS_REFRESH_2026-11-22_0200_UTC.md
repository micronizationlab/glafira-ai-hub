# GLAFIRA ten-workstream live refresh — 2026-11-22 02:00 UTC

This is an evidence snapshot, not a completion declaration. Source baseline inspected: `main` at `7dda2bc6f7ca7ba506c0b59ddf6d97ca2b927193`.

## Exact-head CI state

- **PR #372 — Android backup/settings accessibility**, run `44973570586`: Android JVM, shared contract conformance and debug APK packaging passed; managed emulator failed 1 of 117 tests. The exact failure is `BackupSettingsSmokeTest.manualBackupActionRemainsEnabledAndExplainsMissingFolder`: `Settings action is not exposed as clickable`. The test stops at the Settings control, so the manual-backup/guidance assertions were not reached. This proves the latest Settings semantics change is still insufficient; do not weaken the assertion. Keep Draft and fix the Settings node on the exact PR head before rerunning the full emulator suite. iOS, Windows Hub, Windows Agent and workflow lint were skipped.
- **PR #390 — ten-workstream inventory**, run `44973679849`: Android JVM passed; shared contract conformance passed; iOS Swift package tests and simulator build passed; debug APK packaging passed; managed Android emulator passed 115/115 tests (0 failures, 0 errors, 0 skipped). Windows Hub, Windows Agent and workflow lint were skipped. The separate iOS workflow run `44973679857` completed successfully. Do not treat skipped lanes as passes.
- **Review gate:** PRs #347 and #380 have no submitted reviews in the current review-list query. They remain unmerged; no merge or branch-protection bypass was attempted.

## Ten workstreams — current source-backed status

1. **Core ownership/execution:** immutable Android task claim fencing by `ownerCoreId + ownerGeneration + stateVersion` is implemented in PR #347, with same-generation stale-reclaim regression coverage. Signed cross-Core ownership transfer remains separate in #366.
2. **Backup & Recovery:** PR #372 addresses Android Settings/manual-backup accessibility. Cloud-provider read-back, Google Drive/Yandex restore and clean-profile/physical-device acceptance remain open under #387.
3. **Trace/security/approvals:** PR #380 generation-fences owner approval mutations. No review is recorded yet. Cross-Core handoff must atomically invalidate old-generation grants and write Trace/receipt.
4. **Android UI/voice:** #372 emulator acceptance is the current UI gate. Existing one-shot microphone permission and basic TTS controls do not establish a complete voice session.
5. **Android↔Windows Bridge:** Android is the owner-facing HTTPS client target; do not revive the obsolete Android-hosted Agent server. Peer pairing remains distinct from Agent enrollment; #392/#393 track protocol and client work.
6. **Windows Desktop Hub:** authenticated HTTPS/TLS, pairing, durable Hub store and Agent lease dispatcher exist. Hub lease authority is not Desktop Core task ownership/approval authority; transactional Core↔Hub outbox/inbox and canonical result reconciliation remain open.
7. **Windows Agent:** fresh source inspection confirms `Program.cs` registers an `AgentTaskHandlerRegistry`, but supplies no concrete `IAgentTaskHandler`; the registry is therefore empty and polling exits before requesting work. `AgentTaskExecutor` fails closed on unknown task types and non-read-only risk. The 150-step ledger step 76 (first allowlisted read-only handler set) remains unchecked. Do not activate arbitrary command/shell execution. The bounded result artifact store/read-back, digest validation and exact durable ACK are also still missing under #384.
8. **iOS:** current Swift package tests and simulator build passed in PR #390 run `44973679849`; this validates selected iOS build/tests only, not cross-platform pairing acceptance. Legacy iOS pairing endpoint mismatch remains tracked by #392.
9. **AI providers/memory:** provider keys and primary-provider choice are not data-sharing authorization. Project/provider/purpose/data-classification policy and minimal context projection remain default-deny work under #394.
10. **CI/program 150:** preserve exact-head evidence; skipped jobs are not passes. The Android emulator jobs for #372 and #390 were still running at this snapshot. Continue from open dependency-aware steps; do not mark whole steps complete based on partial slices.

## Explicitly unverified

- Physical Android device acceptance.
- Google Drive/Yandex real provider read-back and restore to a clean profile.
- Two-Windows-PC Hub/Agent end-to-end acceptance.
- Production signed Core-to-Core ownership transfer and automatic failover.
- Production Agent task execution (no handler is registered).
- Full duplex voice session and real microphone/playback acceptance.

## Safety and merge discipline

No PR was merged in this refresh. Keep stacked PR dependencies intact. Do not merge #372 or documentation-only #390 as a substitute for the required runtime acceptance. Required approvals and branch protection remain mandatory.
