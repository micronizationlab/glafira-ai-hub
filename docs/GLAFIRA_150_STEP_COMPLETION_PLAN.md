# GLAFIRA — ordered 150-step completion plan

**Baseline main for this ledger revision:** 7d555c37b669a46f880639b4e01ef35792f29191 (verified after Core ownership-fencing PR #468 and Backup UI test PR #483 were merged on 2026-11-23).
**Owner priority (owner's latest instruction, 2026-11-23):** (1) GLAFIRA Android must be able to perform GitHub work; (2) Windows Desktop Agent must independently perform GitHub work; (3) resume the previously established Core, Backup & Recovery, Bridge and remaining priorities. Keep the ten workstreams active in parallel, but do not let lower-priority work displace these first two deliverables.  
**Target:** fastest safe route to an integrated private owner MVP across four apps, not a calendar promise.

**Latest verified mainline update (2026-11-23):** Current main is 7d555c37b669a46f880639b4e01ef35792f29191. PR #468 merged the focused Core execution-fencing slice as 5f07310d5d6854dedeaa42c7171aa4084bd5bc22 after Android JVM, shared-contract, APK and 127/127 managed-emulator checks passed; PR #483 then merged the test-only Backup button hit-target correction as 7d555c37b669a46f880639b4e01ef35792f29191 after Android JVM, shared-contract, APK and 123/123 emulator checks passed. These merges do not complete transactional ownership transfer (#366), durable Core result outbox/ACK (#266), or real-provider clean-device restore (#387). Program checkbox count remains unchanged at 27/150 (18%) and 27/200 (13.5%); no additional numbered step is closed by these focused merges.

**Current GitHub delivery gates (verified 2026-11-23):** Android PR #471 is Draft and now contains only the explicit read-command parser, Core task factory, MainActivity dispatch and focused tests; unrelated BackupSettingsSmokeTest changes were removed. Its prior feature head passed selected Android CI, but the cleaned head e88da9085adac59fc5f1927315bb0b201599a0fa still needs fresh exact-head validation, durable result-to-chat delivery/ACK, owner preview/approval and physical Realme 8i acceptance. Windows Desktop PR #472 remains Draft: selected Windows UI, Hub and package checks passed, but WPF still calls GitHub APIs directly rather than going through Desktop Core/Owner Unlock. PR #410 remains a mixed 156-file/138-commit Draft; do not merge wholesale. Its latest Windows Agent, Desktop, Hub, deployment and iOS native app jobs passed, while Android emulator and Swift DTO validation were still running/queued at the last check. Core execution fencing is merged (#468); transactional ownership transfer (#366), approval/direct-state audit, and durable result outbox/ACK (#266) remain open. Backup UI smoke-test correction #483 is merged; provider read-back and clean-device restore #387 remain open.

## Scope and denominator clarification

The original owner-requested Program 150 remains the first 150 numbered acceptance steps. The working ledger was subsequently expanded with 50 additional follow-on tasks (items 151–200); these are useful backlog extensions, not a silent change to the original Program 150 target. The current exact checkbox audit finds 200 unique numbered items, with 27 checked: all 27 belong to items 1–150, and none of items 151–200 is checked. Therefore report both figures: **Program 150 core = 27/150 (18%)**; **expanded 200-item ledger = 27/200 (13.5%)**. Do not describe the extended ledger as the original Program 150 or mix denominators.

## Reframed delivery strategy

- Two independently useful Core installations (Android and Windows Desktop), each authoritative only for tasks it owns. Every cross-Core task has exactly one owner Core and monotonic ownership generation; no global shared SQLite queue or dual-writable replica. Bridge is authenticated transport, not authority. See `docs/contracts/DUAL_CORE_OWNERSHIP_AND_FAILOVER.md`.
- Windows Desktop is prioritized over iOS for the first full cross-device execution loop because it hosts Windows Agents. Its Hub is a fleet/transport coordinator under Desktop Core, not another policy Core. iOS remains first-class and progresses on non-conflicting work.
- Android is the instrumentation/conformance anchor and primary mobile owner test surface; it must remain independently useful when Desktop is offline.
- Functional MVP first; defer visual polish, Chimera/avatar, public marketplace, wallet issuance/transfers, compute marketplace, VPN billing, macOS Desktop and specialist-agent expansion until the four-app foundation is accepted.
- No arbitrary shell execution, hidden compute, automatic external side effects, credential leakage or paid-backend requirement for the initial MVP.
- A step is complete only with code/config plus tests and relevant acceptance evidence. Skipped CI is not a pass; physical-device gates are explicitly owner-dependent.

## Ordered actions

### P0 — Core: ownership и queue kernels

1. [x] Снять baseline main SHA, open PR/issues, CI runs и артефакты.
2. [x] Составить dependency/file/commit map recovery stack из issue #91; не переносить ветку целиком.
3. [x] Сверить PR #71/#104/#119/#122 с main и выделить только уникальные Core изменения.
4. [x] Зафиксировать единый task state machine и допустимые переходы. PR #259 merged (`26ee94222811c5669d4f8d6d89b13f0cfb0be4dc`); exact-head CI run `44855763242` completed successfully, with exhaustive persisted-state transition and round-trip tests.
5. [x] Проверить все SQLite migrations на upgrade с поддерживаемых версий.
6. [x] Проверить concurrent claim из независимых SQLite connections.
7. [x] Доказать отсутствие двойной выдачи task при конкуренции N workers.
8. [x] Разделить maintenance gate и locks отдельных задач.
9. [x] Добавить cancellation и тест cancel-vs-complete race.
10. [x] Проверить DAG: cycles, missing nodes, cross-project edges, duplicate edges.
11. [x] Проверить join/unblock только после успешных predecessor tasks.
12. [x] Добавить fairness/starvation bounds между проектами.
13. [x] Проверить bounded worker pool, backpressure и configurable concurrency.
14. [x] Проверить stale worker fencing после lease expiry и повторной выдачи.
15. [x] Добавить crash recovery для каждого non-terminal state.
16. [ ] Сделать failure injection между claim, handler, progress, result и ACK.
17. [ ] Разделить provider outcomes: rejected, unsent, unknown; не replay-ить unknown blindly.
18. [ ] Завершить durable result outbox с retry/backoff и explicit ACK.
19. [ ] Проверить end-to-end idempotency для task, provider request, result и ACK.
20. [ ] Закрепить persistent owner grants, deny-by-default и approval binding к task hash/scope/expiry.
21. [ ] Добавить append-only Trace для task/policy/approval/provider events с secret redaction.
22. [ ] Обеспечить task/result/trace atomicity либо reconciliation outbox.
23. [ ] Добавить Reviewer ACCEPT/REVISE/REJECT/ESCALATE с bounded revision cycles.
24. [ ] Добавить scoped encrypted memory с provenance, confidence, expiry и owner correction/export/delete.
25. [ ] Пройти Core corruption, key-loss, migration, backup/restore и security acceptance; merge slices по одному.

### P1 — Android instrumentation и общий Bridge

26. [x] Проверить AndroidJUnit4 suite в main и CI selection для Core/Bridge изменений.
27. [x] Подтвердить, что managed emulator реально выполняет тесты и JUnit XML не пуст.
28. [ ] Сохранить/портировать исторические TaskPlan instrumentation cases, не заменять smoke-only.
29. [ ] Утвердить canonical versioned Task/Result/Lease/Agent schemas.
30. [ ] Создать общие golden fixtures для Kotlin, Swift и C#.
31. [ ] Проверить HMAC canonical bytes: Unicode, sorted keys, escaping, timestamps.
32. [ ] Утвердить strict payload fields, size/depth limits и unknown-field rejection.
33. [ ] Согласовать общую state/ACK семантику Android Core и Desktop Hub.
34. [ ] Закончить owner pairing: TLS pinning, Keystore, short-lived ticket, approval binding.
35. [ ] Проверить replay guard, idempotency retention, revocation и expired ticket.
36. [ ] Сделать durable Bridge ingress, переживающий process death.
37. [ ] Сделать durable response outbox с retry и ACK на обоих концах.
38. [ ] Добавить heartbeat freshness, offline transitions и endpoint rotation.
39. [ ] Проверить lease renewal, expiry, fencing и late-result rejection.
40. [ ] Проверить reconnect после network loss, Wi-Fi change и restart.
41. [ ] Закрыть Kotlin/Swift/C# conformance на одном наборе fixtures.
42. [ ] Добавить threat tests: wrong pin/host, replay, revoked identity, stale fencing.
43. [ ] Проверить что подпись не заменяет owner authorization.
44. [ ] Провести private-LAN pairing acceptance на реальных устройствах и сохранить evidence.
45. [ ] Зафиксировать Bridge v1 release gate; LIVE side effects остаются выключенными.

### P2 — Windows Desktop: основной control center и Hub

46. [ ] Сверить issue #95/#123 с фактическим WPF и Hub кодом.
47. [ ] Закрепить границу: Core — authority; Desktop Hub — transport/fleet host, не второй Core.
48. [ ] Сделать один lifecycle owner для HTTPS host, store, scheduler и shutdown.
49. [ ] Связать Hub task journal с Desktop Core как владельцем задач: Hub lease/fleet journal — транспортная проекция и журнал исполнения, Android Core queue остаётся независимой; cross-Core handoff только по dual-Core ownership contract.
50. [ ] Завершить persistent leases, monotonic fencing и stale-result rejection.
51. [ ] Интегрировать HMAC admission, replay guard и DPAPI resolver на чистой ветке main.
52. [ ] Завершить enroll/approve/revoke/rotate с signed short-lived descriptor.
53. [ ] Проверить TLS identity pinning и owner-approved endpoint rotation.
54. [ ] Добавить Agent inventory: capabilities, health, version, load, last heartbeat.
55. [ ] Реализовать bounded multi-agent scheduler с capacity matching.
56. [ ] Добавить global/per-agent parallelism и backpressure.
57. [ ] Поддержать DAG-aware placement и dependency joins.
58. [ ] Сделать durable event journal, replay и audit timeline.
59. [ ] Добавить Task Board: queued/running/waiting/failed/unknown/completed.
60. [ ] Добавить approval center с task/effect preview и deny-by-default.
61. [ ] Добавить Bridge diagnostics: listener, TLS, endpoint, device, error.
62. [ ] Проверить graceful shutdown: stop claims, drain/checkpoint active work.
63. [ ] Добавить Hub restart/lease reconciliation tests.
64. [ ] Закончить функциональные разделы Projects, Tasks, Agents, Approvals, Settings, Diagnostics.
65. [ ] Добавить project switcher и conversation entry с реальными Core данными.
66. [ ] Разделить model/provider catalog и connectors/tools registry.
67. [ ] Реализовать 3 Settings tabs и 6 role cards с 12 optional connector slots.
68. [ ] Добавить OS credential vault UX: connect/test/revoke/rotate/status.
69. [ ] Добавить safe File Intelligence parsing с limits/sandbox/no execution.
70. [ ] Проверить installer, upgrade, firewall Private/LAN-only, uninstall retention и rollback.

### P2 — Windows Agent: безопасный worker

71. [x] Закончить типизированный lease envelope: type/idempotency/payload hash/risk/approval/expiry.
72. [x] Проверить strict metadata validation и capability subset.
73. [x] Добавить durable local lease journal с atomic persistence.
74. [x] Подключить polling loop с bounded backoff и pause on disconnect/revoke.
75. [x] Подключить AgentTaskHandlerRegistry к реальному worker lifecycle.
76. [ ] Добавить первый allowlisted read-only handler set; unknown type fails closed.
77. [ ] Сделать payload fetch allowlisted, hash-verified, size-bounded.
78. [ ] Добавить per-handler sandbox/time/resource limits.
79. [x] Подключить lease renewal; остановить execution при невозможности продления.
80. [x] Сохранять progress/result в durable outbox до ACK.
81. [x] Проверять ACK по taskId/leaseId/fencing/result digest.
82. [ ] Разделить retryable, terminal и outcome-unknown; запретить blind replay.
83. [x] Добавить crash recovery между handler completion и ACK.
84. [ ] Добавить owner pause/resume, resource limits и owner-priority preemption.
85. [ ] Добавить tray green/red/gray с точной причиной состояния.
86. [ ] Проверить DPAPI, ACL, rotation и revocation.
87. [ ] Проверить service install/uninstall/start/stop/upgrade.
88. [ ] Создать 10-node scheduler harness без hard-coded fleet cap.
89. [ ] Проверить restart, LAN loss, duplicate result, stale lease и revoke.
90. [ ] Пройти двухкомпьютерное Windows acceptance и зафиксировать проверенные сценарии.

### P4 — Android: owner mobile app

91. [ ] Собрать regression matrix навигации, settings и критических controls.
92. [ ] Закрыть Bridge entry point, Start/Stop, pairing и owner approval из issue #137.
93. [ ] Закрыть task queue UI: create/project/priority/state/cancel/details.
94. [ ] Закрыть Council: provider readiness, empty input, partial failure, response.
95. [ ] Закрыть manual Backup: prerequisites, progress, verification и actionable errors.
96. [ ] Проверить Google Drive/Yandex SAF permissions, read-back и rotation на устройстве.
97. [ ] Проверить provider/model config backup и secret exclusion.
98. [ ] Завершить STT: permission lifecycle, explicit capture, cancel, transcript.
99. [ ] Завершить TTS: opt-in, dedup, focus, interruption, rate controls.
100. [ ] Добавить mic status и sound settings с actionable system settings shortcut.
101. [ ] Проверить Android task submit→Core→handler→result→history.
102. [ ] Проверить pending-message outbox после process death.
103. [ ] Проверить built-in workspaces migration и сохранность пользовательских данных.
104. [ ] Закрыть accessibility, orientation, small-screen и navigation tests.
105. [ ] Провести physical Android smoke и clean-profile restore acceptance.
106. [ ] Подготовить signed owner APK, version, checksum и install guide.
107. [ ] Проверить updater signature, rollback и release channel.
108. [ ] Провести privacy review Keystore, permissions, logs, clipboard handoff.
109. [ ] Собрать Android RC и complete regression report.
110. [ ] Получить owner acceptance на реальном телефоне.

### P4 — iOS: native peer app

111. [x] Проверить Xcode project, scheme, deployment target и simulator build.
112. [ ] Сверить Swift BridgeKit с canonical C1 fixtures и Kotlin/C# schema.
113. [ ] Подключить root UI к authenticated account/device state.
114. [ ] Добавить project/workspace switcher на реальных данных.
115. [ ] Добавить conversation UI с encrypted cache и safe pending outbox.
116. [ ] Добавить task board, result timeline и approval status.
117. [ ] Завершить pairing import и owner approval lifecycle.
118. [ ] Добавить reconnect/offline/retry/revocation behavior.
119. [ ] Подключить Keychain/biometric gate к sensitive actions.
120. [ ] Добавить provider/model catalog без второй registry.
121. [ ] Добавить explicit voice input permissions and cancellation.
122. [ ] Добавить voice output and delivery profile controls.
123. [ ] Добавить backup/restore controls with secret exclusion.
124. [ ] Добавить task/approval local notifications.
125. [ ] Пройти simulator UI tests и Swift package conformance.
126. [ ] Проверить Face ID/Touch ID, Keychain invalidation и pairing на iPhone.
127. [ ] Настроить signing, provisioning, app icons и privacy strings.
128. [ ] Собрать TestFlight-ready archive и release metadata.
129. [ ] Проверить clean install/upgrade/restore на чистом профиле.
130. [ ] Получить owner acceptance native iOS build и зафиксировать parity gaps.

### P4/P5 — Cross-platform release gates

131. [ ] Собрать feature parity matrix Android/iOS/Windows Desktop/Windows Agent.
132. [ ] Утвердить private MVP scope; wallet/token marketplace не включать в executable MVP.
133. [ ] Проверить end-to-end task submission once → owning Core → Desktop Hub routing → Agent handler → owning Core result ACK; при смене Core owner требуется signed transfer + generation fencing.
134. [ ] Проверить durable result return, ACK, trace и user-visible terminal state.
135. [ ] Проверить owner approval/revoke mid-flight и stale credential rejection.
136. [ ] Проверить cross-platform backup portability и restore без device secrets.
137. [ ] Проверить version compatibility old/new clients and Hub; fail safely.
138. [ ] Провести threat review: prompt injection, malicious files, replay, credential leakage.
139. [ ] Проверить data classification, project isolation и provider routing.
140. [ ] Добавить diagnostic bundle с redaction и support trace IDs.
141. [ ] Провести clean install/upgrade/uninstall matrix для четырёх приложений.
142. [ ] Проверить signing/notarization и reproducibility release artifacts.
143. [ ] Подготовить installation/first-pairing wizard и owner guide.
144. [ ] Пройти two-PC + Android system acceptance; iOS physical acceptance отдельно.
145. [ ] Зафиксировать release blockers, severity, mitigation и rollback owner.
146. [ ] Ограничить rollout private owner beta; LIVE actions остаются individually gated.
147. [ ] Добавить opt-in crash diagnostics с минимизацией данных.
148. [ ] Провести backup recovery drill и замер restore на clean device.
149. [ ] Выпустить owner beta packages с SHA-256 и compatibility matrix.
150. [ ] Закрыть MVP только evidence pack: exact SHAs, CI, test counts, artifacts и device acceptance.

## Dispatch protocol

1. Execute the lowest-numbered unblocked step; respect dependencies and avoid conflicting writers.
2. While CI/hardware/review waits, execute the next independent action, then rescan from step 1.
3. Keep all four apps active: two peer Cores, one owner Core per task, Bridge as transport only; no dual-writable replicas or global authority assumption.
4. Branch from current main for clean slices. Stale stacked PRs are source material, not merge candidates.
5. Record exact head SHA, run IDs, executed tests/counts, skipped jobs, artifacts and unresolved risks.
6. Do not claim completion from docs, compilation or APK creation alone.
7. First owner beta remains private, uses approved read-only handlers, owner-gated actions and local/private-LAN transport.

## Initial execution wave

- Core: reconcile issue #91 and PRs #71/#104/#119/#122; validate concurrency, recovery, dependencies and result ACK.
- Bridge: finish canonical fixtures and authenticated lease/result delivery; validate #228 on its latest head.
- Windows: complete the typed read-only Agent runtime, then register a real bounded handler and integrate Desktop Hub leases with Desktop Core ownership.
- Android: preserve emulator suite; close Bridge/queue/backup/voice release regressions after dependencies.
- iOS: preserve BridgeKit/native target; progress authenticated UI/task flows in parallel.

## Execution ledger — 2026-11-21

**Refreshed main after this wave:** `894a0ace84c045692c9fb21df6538cc4d6086d61`.

### Verified complete

- Step 8: dedicated `GlafiraCoreMaintenanceGate` keeps exclusive maintenance/snapshot work separate from task execution permits; `maintenanceWaitsForHandlersAndBlocksNewExecutionLeases` verifies drain-and-block semantics.
- Step 10: complete. PR #247 (`4d1ab98a334bc0fa60d17a2c8466512329a419b8`) added missing/duplicate dependency edge tests at persistent store, planner and durable-plan boundaries. Exact-head managed emulator: 70 test cases, 0 failures, 0 errors, 0 skipped; Android JVM, APK and shared contract checks passed.
- Step 13: bounded, dynamically configurable concurrency is implemented by `GlafiraCoreExecutionLimiter` and `GlafiraCoreDrainWorker`; `independentTasksRunConcurrentlyAndAreClaimedOnlyOnce` proves a configured limit of two is respected under five concurrent callers.
- Step 14: Hub lease expiry/reissue is fenced by monotonic fencing tokens; `StaleFencingTokenCannotCompleteReissuedLease` proves an old worker cannot complete a replacement lease. Exact-head Hub tests/build passed in PR #238.
- Step 5: SQLite upgrade coverage spans every supported predecessor version v1–v8 into v9. PR #245 exact-head emulator run `44851572341`: 68 test cases, 0 failures, 0 errors, 0 skipped.
- Steps 2–3: historical recovery-stack map and file/commit reconciliation are documented in `docs/CORE_RECOVERY_STACK_RECONCILIATION.md`. PRs #71/#104/#119/#122 are confirmed as stale/diverged stacks; their useful Core concurrency, dependency, fairness and task-plan features are already represented in mainline code. The obsolete Android-hosted Desktop task claim/result path is intentionally not transplanted because it conflicts with the current Windows Hub / dual-Core ownership architecture.
- Step 15: non-terminal restart behavior is covered across queued, running safe-read, uncertain-write, waiting-for-network, waiting-for-owner and retry-scheduled states; terminal states remain immutable after reopen.
- Step 1: baseline captured and refreshed at each integration; this ledger revision starts from main `195a45e430b271d3303a3cdca5bbf01dbb241485`.
- Steps 6–7, 10–12 and 15: current Core has independent-connection/concurrent-claim, duplicate-claim, DAG cycle/cross-project/dependency-join, project fairness and process-restart recovery instrumentation coverage. Exact Core acceptance remains dependent on the managed emulator and the remaining failure-injection/trace cases.
- Steps 26–27: AndroidJUnit4 suite is selected by CI; managed emulator executed instrumentation tests, JUnit reports were non-empty and diagnostics uploaded (PR #227 exact-head run `44841507909`).
- Step 9: complete. PR #240 (`a3a460caf7ab4b50d763ce87bf4e5f0f9ddd2421`) adds independent SQLite connection race coverage between result commit and cancellation recovery. Exact-head Android instrumentation: 66 tests, 0 failures/errors/skips; Android JVM, APK and shared contracts passed.
- Step 71: full typed lease metadata delivered to Agent (PR #228 merged as `0950bf33b2a7779e1b5addf64a56ea2a5c0fb2ce`).
- Step 72: strict lease validation and handler-aware claim filtering landed (PR #230 merged as `1a1b7cc5e9a5123362b6abe725302ecfc8fda46c`); PR #232 made the handler allowlist mandatory for every claim (merged as `60ba9b5a95eefaaed351c3f769a6c725a27b35d5`).
- Steps 73, 75, 79, 81 and 83: DPAPI lease/result journal, Worker lifecycle registration, server-confirmed lease renewal, cancellation on renewal failure, terminal result outbox, exact ACK identity/digest validation and restart recovery are merged in PR #238 (`b439177d6c71bebcbb8e4a6a17fd36ded39fdc26`). Exact-head Windows Agent tests: 67 passed, 0 failed; Windows Hub tests/build, shared contract conformance and x64 packages passed.
- Step 80: complete. PR #244 (`b007837cf0e87d843774316c3712c41c8f2c8074`) adds DPAPI-backed progress outbox, write-ahead progress persistence, exact progress ACK checks and restart flush before handler resume. Exact-head Windows Agent tests: 72 passed, 0 failed; shared contract and x64 packages passed.
- Step 83 / terminal result restart: PR #250 (`81e05f7ce7660350c584218a55f3cd62b3d9ef98`) reconstructs a new polling coordinator after terminal result persistence and lost ACK; the exact result is retried from the DPAPI journal without re-executing the handler. Exact-head Windows Agent tests: 72 passed, 0 failed; shared contracts and x64 packages passed.
- Step 74: bounded idle/failure polling backoff and pause-on-disconnect/revoke behavior merged in PR #242 (`195a45e430b271d3303a3cdca5bbf01dbb241485`). Exact-head Windows Agent tests: 68 passed, 0 failed; compile, shared contracts and x64 packages passed.
- Step 111: native iOS Xcode project/simulator target builds in CI (PR #237).

### In progress

- Step 4: state mapping is documented in `docs/GLAFIRA_TASK_STATE_MODEL.md`, but code consolidation remains open: Hub has no distinct `Cancelled` state, Core `WAITING_FOR_OWNER` carries multiple reason classes, and canonical result reconciliation is not yet wired.
- Step 80 compatibility: complete. PR #254 (`894a0ace84c045692c9fb21df6538cc4d6086d61`) proves old DPAPI journals without `PendingProgresses` still load and upgrade on write. Exact-head Windows Agent tests: 73 passed, 0 failed; compile, shared contract conformance and Windows packages passed.
- Step 5: complete. PR #240 added v7→v9 and v8→v9 migration cases; PR #245 added v1/v2. Existing v3–v6 cases plus the new v1/v2/v7/v8 cases all passed exact-head managed Android emulator acceptance. PR #245 merged as `b564771b8116b487bb6adceb3055c4192cc29d98`; its emulator report contained 68 cases, 0 failures, 0 errors and 0 skipped.
- Step 16: failure-injection coverage is improved by PR #244 progress-ACK restart tests and PR #250 terminal-result process-restart tests, but the full crash matrix across every Core/provider/trace boundary is still open.
- Step 9: complete. PR #240's independent-connection cancellation-vs-result-commit race passed on managed emulator.
- Step 15: complete. Current Core restart tests cover queued, running safe-read, uncertain-write, waiting-for-network, waiting-for-owner, retry-scheduled and terminal tasks across reopen/recovery; PR #240's emulator run executed the regression suite.
- Step 76: no production Windows Agent task handler is registered. The Agent remains connected but intentionally does not claim work. The next safe handler must match a real Core task type/capability and have a bounded, useful result contract; do not add a fake generic executor.
- Steps 77–78 and 82, 84–90: payload/handler isolation, sandbox/resource limits, unknown-outcome classification, owner pause/preemption, tray state, credential rotation/revocation, installer/service lifecycle, fleet harness and physical two-PC acceptance remain open.
- iOS step 113 is partial: PR #241 displays paired-device identifiers from Keychain metadata after import (`41d2ca035450a7b2709d70425401153f5fae1ea1`); it correctly says “key saved”, not “connected”. Projects, authenticated Core session and live data are still placeholders.

### Integration decisions

- Dual-Core authority is per task, not one global writable queue. PR #243 (this ledger's companion) adds the normative ownership/handoff/fencing contract; implementation and two-device acceptance remain open.
- PR #225 merged as `0c9c1f26f146e853b84bf3891387ac9ff67223b8`: opt-in automatic speech for newly delivered Agent replies, operationId-based duplicate suppression, and persistent missing-cloud-folder backup guidance. Exact-head Android JVM, debug APK and managed-emulator acceptance passed.
- PR #237 merged as `077579b818f3b2bf3179f0b3f9ea12b5f010f978`: macOS CI builds the actual native `ios/GLAFIRA.xcodeproj` Simulator app target in addition to Swift BridgeKit tests; exact-head iOS app and contract jobs passed. Physical iPhone acceptance remains open.
- PR #248 merged as `91b18fa9aec641edea1440413c092d6ea8678b30`: Windows Desktop Control Center footer now accurately reports implemented Agent transport and the remaining handler/Core handoff gaps.
- PR #227 merged as `14fba6f6a4e83dfba5a7177b4d25cea846d433c3`: restore regression proves RUNNING tasks return to owner review and source-device leases/nodes do not transfer.
- PR #229 merged as `d11afe70372f0dcf6215671ae317a2150711cad1`: this 150-step plan is the ordered execution ledger.
- Historical Core PRs #71/#104/#119/#122 are not merge candidates as stacks; current main contains newer versions of overlapping concurrency, maintenance, dependency, fairness and durable plan code. Issue #91 remains open for remaining Core acceptance gaps.


## New owner-directed workstream — identity, autonomy, continuity and updates (2026-11-21)

Normative specification: `docs/GLAFIRA_OWNER_IDENTITY_AUTONOMY_AND_UPDATES.md`. This workstream is added without displacing the existing P0/P1 order; execute its independent design/security slices in parallel and schedule implementation by dependency.

- [ ] Owner identity: trusted immutable-by-chat policy for Корчагин Валерий Вячеславович; only the authenticated owner receives the address `папа`; exact canonical answer; multi-account isolation and prompt-injection tests.
- [ ] Credential autonomy: OS-backed vault adapters, local credential broker, allowlisted browser/app automation, least-privilege grants, audit/revocation and owner confirmation for high-risk actions.
- [ ] Durable cross-device continuation: conversation branch/checkpoint IDs, authoritative history re-read before resume, task persistence independent of initiating UI, idempotent result/ACK reconciliation.
- [ ] Dynamic group membership: variable-size device registry, heartbeat freshness, lease-backed coordinator election, fencing, split-brain and offline-peer handling.
- [ ] Update trust: signed release manifest, owner signing authority, platform/product/version/hash checks, compatibility and rollback policy.
- [ ] Windows update chain: owner release → Windows Desktop → verified Windows Agent distribution; cached authorized source failover only.
- [ ] Mobile update UX: Android first Settings tab bottom action `Обновление` with small installed-version label; signed package verification and OS installer handoff. iOS uses App Store/TestFlight/MDM-supported update path, never silent self-install.
- [ ] Fleet and physical acceptance: variable node count, network partition, stale leader, revoked node, interrupted update, rollback and real-device installation tests.

**Important boundaries:** task owner Core, Windows fleet coordinator and update source are distinct roles. No device becomes authority solely because another is offline. Passwords never enter prompts/logs/Git/backups in plaintext. External side effects with unknown outcomes are never blindly replayed. A step is complete only after code plus exact-head tests and required acceptance evidence.


## Расширение программы — дополнительные шаги 151–200 (название программы сохраняется: «150 шагов GLAFIRA»)

Эти 50 шагов расширяют общий план до 200 внутренних пунктов, но не меняют его постоянное название. Нумерация непрерывная: новые пункты не заменяют, не перенумеровывают и не объявляют выполненными прежние шаги. Приоритеты и зависимости сохраняются; независимые работы допускаются параллельно. Все завершения подтверждаются кодом, exact-head CI и физическими испытаниями там, где они требуются.

### Надёжность Core, память и продолжение задач

151. [ ] Создать единый протокол durable checkpoint для длительных задач: версия схемы, task/branch IDs, owner Core, generation, состояние и контрольные суммы.
152. [ ] Реализовать восстановление диалога из авторитетной истории и checkpoint после закрытия приложения, перезапуска процесса и смены устройства.
153. [ ] Проверить согласованность ветвей диалога при одновременном поступлении сообщений с нескольких устройств; конфликтующие изменения не перетирать последней записью.
154. [ ] Добавить bounded replay журнала событий с обнаружением пропусков последовательности и безопасным snapshot repair.
155. [ ] Ввести политику хранения, архивирования и удаления истории, результатов, вложений и диагностик с проверкой проектных границ.
156. [ ] Реализовать сквозную трассировку задачи от пользовательского запроса до исполнителя, результата, ACK и показа пользователю без утечки секретов.
157. [ ] Провести длительный soak-тест Core: очередь, параллельные исполнители, перезапуски, рост БД, WAL/checkpoint и восстановление.
158. [ ] Испытать восстановление при нехватке диска, повреждённом/неполном backup, прерывании записи и недоступном хранилище.
159. [ ] Ввести проверяемые SLO для времени восстановления, потери подтверждённых результатов, задержки очереди и доступности локальных функций.
160. [ ] Подготовить автоматический диагностический отчёт о состоянии Core с безопасной редактировкой персональных данных и секретов.

### Четыре приложения и распределённая работа

161. [ ] Зафиксировать единый versioned protocol contract Android, iOS, Windows Desktop и Windows Agent с проверкой совместимости в CI.
162. [ ] Реализовать устойчивое сопряжение устройств: одноразовый enrollment, подтверждение владельца, отзыв, повторное сопряжение и защита от replay.
163. [ ] Проверить динамические группы устройств от одного узла до 10+ узлов с переменным составом и ограничениями ресурсов.
164. [ ] Завершить Desktop Hub ↔ Windows Agent handshake, взаимную аутентификацию, heartbeat и безопасное восстановление соединения.
165. [ ] Реализовать реальные ограниченные handler capabilities Agent с allowlist типов задач, лимитами времени/памяти и структурированным результатом.
166. [ ] Проверить, что Windows Agent не может сам назначать владельца задачи, расширять разрешения или выполнять незаявленные действия.
167. [ ] Довести Windows Desktop до полного управления группой: реестр устройств, очередь, прогресс, отмена, результат, ACK, диагностика и отзыв.
168. [ ] Довести Android до автономного полезного Core-режима при недоступности Desktop и безопасного согласования после восстановления связи.
169. [ ] Довести iOS до авторизованного подключения к Core, отображения проектов, очереди, прогресса и истории без ложного статуса «подключено».
170. [ ] Провести реальный тест двух Windows-компьютеров и мобильного устройства: pairing, dispatch, disconnect, reconnect, ACK, revoke и обновление.

### Голос, агенты и взаимодействие

171. [ ] Создать единый voice runtime contract: ввод речи, распознавание, ответ, синтез, отмена, прерывание и переключение текст/голос.
172. [ ] Реализовать диктовку во всех поддерживаемых приложениях с понятными разрешениями микрофона, диагностикой и fallback на текст.
173. [ ] Добавить пользовательские настройки голоса: включение/выключение, выбор доступного голоса, скорость, громкость и автоматическое чтение ответов.
174. [ ] Реализовать barge-in: пользователь может прервать говорящего агента, а система прекращает воспроизведение и принимает новую команду.
175. [ ] Ввести маршрутизацию голосового ответа к выбранному агенту и отображение, кто именно говорит, без подмены личности агента.
176. [ ] Проверить работу голосового контура при слабой сети, недоступном TTS/STT провайдере, отмене, повторе и смене аудиоустройства.
177. [ ] Создать реестр специализированных агентов с manifest, стабильным ID, версиями, инструментами, правами и проверяемыми тестами компетенций.
178. [ ] Реализовать безопасную передачу подзадач между агентами с ограниченным контекстом, явными результатами и ответственным Core.
179. [ ] Добавить тесты устойчивости к prompt injection из сообщений, файлов, сайтов, писем и результатов других агентов.
180. [ ] Провести сквозное испытание нескольких агентов: декомпозиция, параллельная работа, зависимости, объединение результатов и единый ответ владельцу.

### Память, знания и качество ответов

181. [ ] Разделить краткосрочный контекст, долговременную память, проектные знания, журнал событий и секреты; секреты не являются памятью модели.
182. [ ] Реализовать происхождение фактов: источник, время, уровень уверенности, область доступа и возможность исправления устаревших сведений.
183. [ ] Добавить проверку конфликтов памяти и запрос подтверждения для неоднозначных или потенциально опасных изменений важных фактов.
184. [ ] Ввести проектную изоляцию поиска и retrieval-тесты, доказывающие отсутствие утечки между аккаунтами и проектами.
185. [ ] Создать набор эталонных задач для оценки точности, полноты, цитирования источников, отказа при недостатке данных и устойчивости к выдумыванию.
186. [ ] Реализовать пользовательский экспорт и перенос знаний с сохранением происхождения данных и без переноса device-bound секретов.
187. [ ] Проверить жизненный цикл персональных данных: просмотр, исправление, экспорт, удаление по запросу и очистка производных индексов в пределах поддерживаемых гарантий.
188. [ ] Создать систему оценки качества специализированных агентов на закрытых тестовых наборах до выпуска новой версии.
189. [ ] Добавить контроль качества автоматических резюме и сжатия контекста: ключевые решения, ограничения и обязательства не должны теряться.
190. [ ] Подготовить аудит памяти и знаний: что известно, откуда получено, где используется и какие данные требуют повторной проверки.

### Безопасность, экономика, интеграции и эксплуатация

191. [ ] Завершить threat modeling всех четырёх приложений, Bridge, Hub, локального хранилища, обновлений и внешних интеграций.
192. [ ] Ввести централизованный permission broker с минимальными полномочиями, сроком действия грантов, подтверждением рискованных операций и журналом аудита.
193. [ ] Проверить ротацию ключей, отзыв устройств, восстановление после компрометации и безопасное обновление доверенных корневых ключей.
194. [ ] Создать формулу экономики токена/ресурсного вознаграждения: модель устройства, время доступности, фактическая нагрузка, энергия, VPN и AI-расходы; исключить обещания гарантированного дохода.
195. [ ] Реализовать калькулятор экономики устройств с параметрами CPU/GPU/RAM, энергопотребления, тарифов сети, времени работы и фактической производительности.
196. [ ] Добавить прозрачный учёт задач и ресурсов: измеренные CPU/GPU/RAM/время/трафик, происхождение метрик, проверка подмены и защита от двойного начисления.
197. [ ] Создать интеграционный SDK/API с версиями, scopes, rate limits, идемпотентностью, webhook ACK и тестовой средой; секреты хранить только в OS vault.
198. [ ] Провести нагрузочные и adversarial-тесты Bridge/Hub/API: replay, задержки, потеря пакетов, burst, отказ узла, неверные подписи и ограничение ресурсов.
199. [ ] Подготовить операционную готовность: release checklist, наблюдаемость, безопасные обновления, rollback, incident response и воспроизводимые сборки.
200. [ ] Провести итоговую независимую приёмку расширенной программы: матрица требований, exact SHAs, CI evidence, security review, backup/restore drill и реальные устройства; незакрытые пункты остаются явно открытыми.

**Правило исполнения расширения:** шаги 151–200 не образуют отдельную программу и не отменяют приоритеты исходных 150 шагов. Диспетчер выбирает следующие незаблокированные задачи по риску и зависимостям, продолжая параллельную работу. Постоянное имя всей программы — «150 шагов GLAFIRA».


## Execution ledger update — 2026-11-21 06:00 UTC

**Verified main baseline:** `c1ff8659109c106153e89d5b838d600128a0cb49`. This snapshot records verified merged commits and exact-head workflow evidence.

- **Steps 1–15:** retain previously recorded status; this refresh does not broaden their acceptance.
- **Step 16 — still open; multiple failure-injection slices merged.** PRs #261, #265, #275, #284 and #288 are merged. #284 covers recovery after both result persistence and retry-history persistence fail, followed by SQLite reopen. #288 covers fail-closed export for unknown Core tables and transactional portable-restore rollback when result insertion fails. Both exact-head Android JVM, shared-contract, APK and managed-emulator jobs passed. Remaining claim/handler/progress/result/ACK/cross-process failure matrix remains open under #260.
- **Step 17 — focused provider/outbox safeguards merged; overall step open.** PR #264 distinguishes NOT_SENT from UNKNOWN and prevents blind replay after ambiguous dispatch. PR #282 preserves completed peer results when a composite consultation becomes UNKNOWN. PR #290 verifies durable outbox quarantine for UNKNOWN, explicit owner retry, bounded 429 Retry-After, auth rejection and definitely-NOT_SENT retry. #290 exact-head Android JVM, shared contract, APK and summary passed; emulator was skipped for the unit-test-only change. Broader provider/coordinator reconciliation remains open under #262.
- **Step 18 — Android Core installation identity merged; full dual-Core ownership still open.** PR #292 merged as `543cc4aa2ce6bf097db81c3e6daf405f9c588505`. Exact-head Android JVM, shared-contract, APK and managed-emulator checks passed after rerunning one flaky UI smoke attempt. Android now has stable installation identity, Android Keystore signing, non-backup metadata and interrupted-rotation recovery. This is not Windows Desktop Core identity, owner/account identity, ownerGeneration, signed ownership transfer or task fencing. #258 and #266 remain open; do not bind ACK/outbox to placeholder identity.
- **Step 19 — partial only.** PR #271 and #282 are merged, but end-to-end idempotency across provider request, Core result, cross-Core delivery and durable ACK remains open.
- **Step 20 — owner authorization hardening partially merged; follow-up now merged.** PR #295 merged as `fb938b2a13e05491ef58784ce2974b8a66b12676`: Dry Run uses non-consuming `peekApprovalGrant()`, preserving the one-use grant for actual execution. PR #299 merged as `c1ff8659109c106153e89d5b838d600128a0cb49`: persistent grants require the exact registered scope set; unknown, malformed, duplicate or unexpected legacy scopes fail closed, and grant expiry is capped by the task deadline. Exact-head Android JVM, shared-contract, managed-emulator, APK and exact-head summary passed for both slices. This does not complete owner/account identity or cross-platform ownership generation.
- **Step 21 — metadata-only Trace contract and redaction foundation merged; persistence remains open.** PR #297 merged as `dd9d6b640d5324127838f15eae1d79c7e3ece53c`. Exact-head Android JVM, shared-contract, APK and managed-emulator checks passed. It defines typed event metadata, redaction, bounded queries and project/task read boundaries without payloads or provider responses. SQLite append-only persistence, producer integration, backup/restore continuity and transaction/reconciliation atomicity remain open under step 22.
- **Backup/Restore and step 25/136 — targeted evidence merged, full acceptance open.** PR #288 adds fail-closed unknown-table handling and rollback proof, but does not close full portable backup lifecycle, OS-kill matrix, cross-platform restore or device acceptance.
- **Mobile Personal Data Bridge — Android foundation slices merged; broader feature open.** PR #285 adds permission-gated read-only Contacts Provider access. PR #286 adds scoped AndroidX Photo Picker selection without broad gallery access or automatic upload. #273 remains open for owner review/merge UX, in-memory processing, safe image-to-agent transport, HR import integration, iOS parity and physical-device acceptance.
- **Windows Hub result fencing:** PR #283 merged current-main regression coverage. Old draft PR #163 was closed as superseded because its lease/fencing implementation and tests are already present on main in newer, safer forms.
- **Android smoke-test isolation:** PR #298 merged as `4bdad549e96c34a362fad305835e9fac36f9b33e`. Exact-head Android JVM, shared-contract, APK and managed-emulator jobs passed; UI smoke tests now explicitly disable persisted biometric lock state and clear the task stack before launch. Windows/iOS/workflow-lint jobs were skipped because this was Android-only.
- **Copilot collaboration:** PR #279 remains merged. No autonomous Copilot execution is assumed; all implementation/CI claims above come from GitHub branches, PRs and workflow evidence.

**Open work at this snapshot:** #260 (full Core failure-injection matrix), #262 (provider/coordinator reconciliation), #258/#266 (dual-Core identity and ownership-generation prerequisites), and step 22 Trace persistence/atomicity remain open. Platform jobs marked skipped are not passes. No complete program step is marked complete solely because a contract or one implementation slice has merged.


## Execution ledger update — 2026-11-21 06:50 UTC

**Verified merged baseline:** `11ad37168af605149d919e825cdbaf37ab489a76`. This update records only merged PRs and exact-head workflow evidence checked in GitHub.

- **Step 16 — still open; additional Windows Hub transaction-failure coverage merged.** PR #304 merged as `d035d3e17b1b047ebdedd3323f3b6b3792454cb2`: injected progress/ACK persistence failure rolls back lease renewal and does not acknowledge unpersisted progress. PR #305 merged as `763d58762f7c3ece41d7eed7a2532b622e8b9528`: terminal result journal failure rolls back the terminal transition atomically. Both PRs' exact-head workflow runs completed successfully. Combined with #283/#284/#288, this adds meaningful Hub and Core failure slices; the full crash/claim/handler/progress/result/ACK/cross-process matrix remains open under #260.
- **Step 17 — provider outcome classification coverage expanded; overall step open.** PR #309 merged as `11ad37168af605149d919e825cdbaf37ab489a76`. Exact-head Android JVM, shared-contract, APK and exact-head summary passed; emulator acceptance was not run for this JVM-only test change. The matrix covers explicit 4xx rejection classes, ambiguous 408/5xx outcomes, and pre-/post-dispatch NETWORK/TIMEOUT/TLS behavior. UNKNOWN remains non-auto-retryable. Full provider/coordinator reconciliation and durable result ACK remain open under #262.
- **Step 18 — still blocked on complete dual-Core ownership.** PR #292's Android installation identity slice remains merged; it is not Windows Desktop Core identity, owner/account identity, ownerGeneration, signed ownership transfer or task fencing. #258/#266 remain prerequisites; do not bind ACK/outbox to placeholder identity.
- **Step 19 — partial only.** #271/#282 and the new #309 classification matrix improve result idempotency and outcome safety, but end-to-end idempotency across provider request, Core result, cross-Core delivery and durable ACK remains open.
- **Step 20 — owner authorization hardening remains partial.** #295 and #299 remain merged for non-consuming Dry Run inspection and exact registered-scope validation. The next audit Trace producer is being developed separately; no claim is made that owner identity or cross-platform ownership is complete.
- **Step 21 — metadata-only Trace storage and portable Backup/Restore foundation merged.** PR #308 merged as `74bbcf1de935cc1ea8832a2ae53b828ce62ef979`. Exact-head Android JVM, shared-contract, APK, managed-emulator and exact-head summary passed. It provides append-only project/task-scoped Trace persistence, bounded access-filtered queries, metadata redaction, encrypted portable snapshot v3, v2 migration and rollback/trigger protections. This is a foundation, not complete end-to-end Trace acceptance.
- **Step 22 — lifecycle and approval producers remain under validation.** PR #317 adds SQLite-triggered TASK_CREATED/state-transition events plus transactional TASK_RESULT_COMMITTED; failure injection covers create, claim and result commit. Its first JVM run exposed a UUID/phone-redaction false positive; the fix and deterministic regression are in the current head. Android JVM and shared-contract jobs now pass; APK and emulator acceptance are still running. PR #318 adds approval request/grant/reject/consume/expiry events in the same SQLite transactions as approval decisions, with failure-injection tests; exact-head CI is pending. Neither PR is merged or counted as complete.
- **CI reliability:** PR #306 merged as `ea612750e30e8f5aa1e1dcbdbdf7c54814f59c69`; exact-head workflow runs passed. The test pipeline now rejects skipped JUnit test cases instead of silently treating them as successful.
- **Backup/Restore and step 25/136 — stronger targeted proof, full acceptance open.** #288 and #308 now cover unknown-table fail-closed export, metadata Trace continuity, portable v3/v2 migration and injected restore rollback. OS-kill matrix, cross-platform restore and physical-device acceptance remain open.
- **Mobile Personal Data Bridge — Android foundation slices merged; broader feature open.** #285/#286 remain scoped to permission-gated contacts and Photo Picker selection. #273 remains open for owner review/merge UX, in-memory processing, safe image-to-agent transport, HR import integration, iOS parity and physical-device acceptance.
- **Copilot collaboration:** #279 remains merged. No autonomous Copilot execution is assumed; implementation and CI claims are grounded in GitHub branches, PRs and workflow evidence.

**Open work at this snapshot:** #260 (full Core failure matrix), #262 (provider/coordinator reconciliation), #258/#266 (dual-Core identity and ownership-generation prerequisites), and step 22 (full Trace producer/atomicity/recovery coverage) remain open. Skipped platform jobs are not passes. No complete program step is marked complete solely because a contract or one implementation slice has merged.


## Execution ledger update — 2026-11-21 11:52 UTC

**Verified merged baseline:** `fc3448b1609a7c952dee819f38bd83457f30f8cb`. This update supersedes the earlier 06:50 snapshot where PR numbers and candidate branches were still in flight.

- **Step 18 — partially advanced; complete dual-Core ownership remains open.** PR #322 merged as `fc3448b1609a7c952dee819f38bd83457f30f8cb`. A separate `GLAFIRA.Desktop.Core` assembly now provides a persistent Windows Desktop installation `coreId`, `WINDOWS_DESKTOP` kind, non-exportable CNG ECDSA P-256 signing key, DPAPI CurrentUser-protected metadata, monotonic key epoch rotation, public-key consistency validation and fail-closed Desktop startup. Exact-head Windows Hub tests/build, WPF Desktop build, Windows deployment package workflow, Shared Contract conformance and exact-head CI summary passed. This is installation identity only; it does not implement account/owner identity, canonical task `ownerCoreId + ownerGeneration`, signed handoff, per-Core inbox/outbox or stale-owner fencing. Android↔Desktop takeover remains disabled.
- **Step 21 — Trace storage/portable Backup foundation remains merged.** PR #308 remains merged as `74bbcf1de935cc1ea8832a2ae53b828ce62ef979`.
- **Step 22 — transactional lifecycle Trace producers remain under validation.** Current-main PR #320 includes task-created/state-transition Trace triggers, transactional result/preview/review events, restore trigger suppression, safe task/project identifier validation, UUID-safe redaction and corrected managed-emulator assertions. Exact-head Android JVM and Shared Contracts passed; APK and managed-emulator acceptance are still running as of this update. It is not merged and step 22 remains open.
- **Owner approval Trace events — stacked candidate.** PR #321 is stacked on #320 and adds request/grant/reject/consume Trace events in the same transactions as approval state changes, with failure-injection coverage. A duplicate enum declaration caught by its first JVM run was corrected; exact-head JVM validation is running. Do not merge #321 before #320 and do not count skipped emulator/platform jobs as passes.
- **Superseded candidates:** PRs #315, #316 and #317 were closed without merge to avoid stale or duplicate branches. Their relevant deltas were consolidated into #320/#321; earlier failed emulator assertions are not acceptance evidence.

**Open work:** #260 (full Core failure matrix), #262 (provider/coordinator reconciliation), #258/#266 (owner-generation and signed cross-Core handoff), and step 22 (full Trace producer/atomicity/recovery coverage) remain open. No complete program step is marked complete solely because one implementation slice has merged.


## Execution ledger update — 2026-11-21 13:48 UTC

**Verified main baseline:** `b4a6a4d5bff305e768b20741030db614f6e6919b`.

- **Canonical task ownership metadata — merged, execution fencing still pending.** PR #342 supersedes stale PR #334 and is merged as `b4a6a4d5bff305e768b20741030db614f6e6919b`. It ports per-task `owner_core_id`, monotonic `owner_generation`, state-version tracking, payload digest/risk metadata, legacy-task quarantine and portable-restore authority reset onto current main. Exact-head run `44907702267`: Android JVM tests, Shared Contract conformance, managed Android emulator instrumentation, Debug APK packaging and exact-head CI summary all passed. Windows Hub, Windows Agent, iOS and workflow-lint jobs were skipped by change-aware selection and are not claimed as passes.
- **Stale-owner execution fence — NOT implemented by #342.** Audit after merge confirms the current Core queue still needs owner-aware claim, transition, recovery, network-resume, expiry/dependency mutation and result-commit guards. The executor currently claims a plain task and later mutates it using task ID/state only. The next implementation must carry an immutable `ownerCoreId + ownerGeneration` execution token from claim through every Core state/result mutation. Do not reuse Windows Hub/Agent `leaseId/fencingToken`; those remain a separate execution domain. Cross-Core failover remains disabled.
- **Step 16 / issue #260 — partial.** Existing merged slices include result/claim transactional failure injection, provider UNKNOWN quarantine and classification, Core result idempotency, Backup/Restore rollback, and Hub progress/result/claim/cancellation journal rollback tests. Remaining gaps include the complete Core handler/progress/result/outbox/ACK/restart matrix, exact receiver ACK reconciliation and stale-owner generation fencing. No full completion claim.
- **Step 17 / issue #262 — partial.** Merged coverage includes typed provider outcome classification, durable pending-delivery UNKNOWN quarantine, NOT_SENT retry distinction, REJECTED 429 Retry-After, auth rejection and partial-composite UNKNOWN preservation. Full provider coordinator checkpoint/reconciliation and no-replay acceptance remain open.
- **Step 18 / issues #258/#266 — partial.** Android and Windows installation identities and installation-backed ownership-transfer signing are present; PR #342 now supplies per-task owner metadata. Remote-key trust/revocation, signed handoff acceptance, replay inbox/outbox, atomic generation CAS and stale-owner execution fencing remain pending.
- **Step 21 — portable Backup/Restore foundation remains merged.** PR #308 and subsequent rollback/unknown-table tests remain the current baseline; cross-platform and physical-device restore acceptance remains open.
- **Step 22 — Trace remains partial.** PR #320 lifecycle producers and PR #335 approval events are merged. Full producer coverage, process-death reconciliation and end-to-end atomicity remain open.
- **PR #340 — accessibility blocker remains resolved.** Its exact-head run `44906440204` passed Android JVM, managed emulator, APK and exact-head summary.

**Next parallel work:** (1) implement Core execution-token fencing on top of #342 without touching the Hub lease model; (2) continue issue #260 failure-injection coverage in independent test-only slices; (3) continue issue #262 provider/coordinator reconciliation; (4) preserve the four-app and physical-device gates as explicitly unverified until tested.

**Acceptance discipline:** skipped platform jobs are not passes; metadata persistence is not execution fencing; no automatic cross-Core failover is enabled.


## Execution ledger update — 2026-11-21 14:40 UTC

**Verified main baseline:** `0649b3a24592e2ccd73b6997db04e2664f055be4` (PR #343; canonical ownership metadata is present, but runtime execution fencing remains under validation).

- **Owner-generation execution fence / PR #347 — not merged.** Exact-head run `44912132854`, attempt 2, on `be1d81586cd506176db712494ec77f6364faac83`: Android JVM, Debug APK and Shared Contract checks passed; managed Android emulator acceptance failed 1 of 117 instrumentation tests. The failure is `BackupSettingsSmokeTest.manualBackupActionRemainsEnabledAndExplainsMissingFolder`. No merge until corrected and exact-head CI passes.
- **Android accessibility follow-up / issue #350.** Diagnostics show UiAutomator found the Settings and backup action nodes with `clickable: false` and logged clicks on non-clickable nodes; the expected missing-folder guidance was not exposed. The existing UI handler appears to set guidance, so inspect Compose accessibility/action semantics and prove the handler executes. Do not weaken the regression assertion. Issue #138 and PR #347 contain the evidence and follow-up.
- **Communications autonomy / issues #348 and #349.** Owner explicitly approved autonomous routine commercial communications without per-message approval, with escalation for complaints, refunds, uncertain facts, legal/reputational matters, out-of-band discounts, financial commitments and destructive/high-impact actions. #348 covers Telegram Business/Managed Bots and Instagram Professional APIs; #349 adds the MAX business Bot API. All three connectors must remain official-API-only, least-privilege, bot/account scoped, auditable, revocable and subject to owner-dependent OAuth/verification/consent steps. These are requirements/issues, not completed integrations.
- **MAX onboarding constraints.** The MAX connector must verify current business-profile eligibility and platform bot limits at runtime; owner-only verification, bot moderation and consent steps cannot be bypassed. Never expose bot tokens in prompts, logs, telemetry, Git or plaintext backups.
- **Parallel P0 continuation.** Keep #260 failure-injection matrix, #266 durable result outbox/exact ACK, and #258 owner identity/credential autonomy/ownership handoff active. Do not count issue creation, documentation, skipped CI jobs or partial platform checks as implementation completion.

**Acceptance discipline:** PR #347 remains open and blocked by the emulator failure. The communications work is scoped but not implemented. Skipped platform jobs are not passes; no automatic cross-Core failover or unrestricted personal-account access is implied.


## Execution ledger — 2026-11-22 (live ten-workstream refresh)

**Verified repository baseline:** `7dda2bc6f7ca7ba506c0b59ddf6d97ca2b927193` (`main` HEAD).  
**Formal checklist count:** 27 of the original 150 numbered steps are currently marked `[x]` in this document; 123 remain `[ ]`. This is a literal checkbox count, not a claim that every other step has no implementation. The narrative evidence below records partial implementations separately. Several previously evidenced steps are not yet reflected consistently in the checkboxes; reconcile them only after exact-head evidence is refreshed.

### Ten workstreams — current verified snapshot

1. **Core / task dispatch:** Core has durable task-store, planning, ownership-generation fencing, approval and Trace foundations. Issue #407 confirms Android chat still does not route GitHub commands into Core; no GitHub handler is registered. Core acceptance remains open under #91 and #260; result outbox / exact ACK is tracked by #266 and depends on canonical Core identity/ownership prerequisites (#258).
2. **Android ↔ GitHub:** PR #404 is open Draft at `20d2fe778ee666dbc89142187f9a07a4d7765f56`. Exact-head run `44985860261`: Android JVM, shared contracts, emulator acceptance and debug APK passed. Other platform jobs were skipped. Physical Realme 8i acceptance remains outstanding. This is an authorization foundation, not the Program 150 command dispatcher (#407).
3. **Windows Desktop GitHub Agent:** PR #411 is open Draft at `c4216c61e48e8123cb03762a423c992c61111e7c`. Exact-head run `44989876926`: Windows Desktop UI, Hub tests/build, shared contracts and exact-head summary passed; Android, iOS and Agent jobs were skipped. The PR supplies Device Flow + DPAPI session foundation and read-only verification, not the full typed GitHub work loop. Owner unlock and typed operations remain open (#408/#409).
4. **Windows Hub / Agent / Bridge:** Existing Windows HTTPS Hub, TLS identity, pairing tickets, Agent registry, lease journal and fencing are real code, not future placeholders. However, issue #228 explicitly states typed lease metadata does not yet enable production Agent task claiming. First allowlisted read-only handler, payload isolation, unknown-outcome handling and physical two-PC acceptance remain open. Android is not currently an HTTPS Hub host; do not describe it as one.
5. **Android voice and microphone:** PR #402 is open Draft at `392e074190a664a6667efc62094716f66f72db10`. Exact-head run `44989948562`: Android JVM, shared contracts, debug APK and managed emulator acceptance passed; non-Android jobs were skipped. It fixes the same-operation user→assistant auto-speech policy and partial-preview suppression. It does not establish physical-device TTS behavior and does not fix the chat microphone crash. Issue #401 still requires Realme 8i Logcat and physical acceptance.
6. **Backup / restore:** PR #412 is open Draft at `85e880304bb645d0b56a855a152ea1c3f4a5a254`. Exact-head run `44988435228`: Android JVM, shared contracts, debug APK and emulator acceptance passed; other platform jobs were skipped. This is a focused visibility fix, not full backup/restore acceptance. Issue #398 still tracks the Realme folder-picker requestCode exception and device restore evidence.
7. **Android UX / settings / secrets:** PR #399 is open Draft and changes only `SettingsScreen.kt` to avoid hydrating saved provider secrets into visible fields, mask newly entered keys and clear UI state after save. Exact-head run `44983262126` passed Android JVM, shared contracts, managed emulator acceptance, debug APK and exact-head summary. iOS/Windows/workflow-lint lanes were skipped by change-aware selection. Physical Realme validation is outstanding. Other #398 defects (voice settings crash, image picker crash, sound playback, pairing/update controls) remain open.
8. **iOS:** Native Xcode project and Simulator target exist; BridgeKit tests/build have passed in prior exact-head CI evidence. The current audit identifies pairing and ownership as prototypes/foundations until canonical authenticated peer pairing and real Core-backed workspace/task flows are accepted. Physical iPhone acceptance remains open.
9. **Security / owner identity / recovery:** Android GitHub token storage uses Android Keystore AES-GCM and Desktop uses DPAPI, but encryption at rest is not equivalent to an owner-unlock/biometric gate. Issue #408 requires a consistent locked-state gate for settings, chat dispatch and token refresh. Dual-Core ownership, signed transfer and update trust remain separate gates (#258).
10. **CI / roadmap / integration control:** CI exact-head evidence is recorded per PR above; skipped jobs are not passes. PR #390 contains a 12-file ten-workstream inventory refresh but remains open, so its inventory is not yet on `main`. PR #410 is a 75-commit / 124-file Draft with a very broad cross-platform diff; it is not a safe integration unit and must be decomposed into bounded, independently tested slices. No PR in this snapshot is authorized for merge solely by this audit.

### Immediate execution order

- First complete the Android GLAFIRA GitHub work loop, including chat/Core dispatch and the owner-unlock gate (#407/#408).
- Second complete the independent Windows Desktop GitHub work loop and its owner-unlock gate (#409/#408).
- Then resume the previous order: Core ownership/failure-injection/result-ACK, Backup & Recovery, Bridge, Windows Agent production handler and remaining platform/release gates.
- Continue Windows Agent's first real read-only handler and Hub/Core ownership handoff; do not enable arbitrary execution.
- Finish Android voice policy integration and obtain physical-device Logcat for microphone and settings crashes (#401/#398).
- Reconcile focused backup and secret-field PRs with exact-head checks and Realme testing.
- Keep design work in a separate Android UI branch; do not mix cosmetic changes with Core, voice, credentials or Bridge behavior.
- Refresh this ledger after each accepted exact-head evidence update. Do not infer completion from an open PR, documentation, a successful compile or skipped CI jobs.


## Verification refresh — 2026-11-22 (latest exact-head audit)

- **PR #399 — provider secret fields:** exact head `3072120c784720664feac148bb38b373dac60bd8`, run `44983262126`. Android JVM, shared contracts, managed emulator acceptance, debug APK and exact-head summary all passed. iOS, Windows Agent, Windows Hub and workflow lint were skipped by change-aware selection. The code change keeps persisted provider credentials out of Compose text-field state, preserves an existing key when the field is blank, masks new input and clears plaintext UI state after successful save. This is strong automated evidence, not physical Realme acceptance.
- **PR #410 — broad cross-platform implementation stack:** exact head `807d4396af19569a028a9653738cf1b13610be41`, run `44992526034`. Android JVM, managed emulator, debug APK, shared contracts, iOS Swift package, Windows Hub, Windows Agent, Windows Desktop UI, Windows deployment packages and exact-head summary all passed. Workflow lint was skipped. The PR still spans 124 files, 75 commits, +14,577/−940 lines, across Android, iOS, Desktop Bridge, Hub and Agent. Passing CI does not make this a safe review/merge unit: it needs decomposition and security review before any integration. Source inspection confirms `windows-agent/GLAFIRA.Agent.Worker/Program.cs` registers an empty `AgentTaskHandlerRegistry`; the executor fails closed with `NO_AGENT_HANDLER`. Therefore production Agent task execution is still not enabled.
- **PR #390 — inventory refresh:** latest head `c12b68dc0c4e023d5b943c0f7d60a77872a34c23`, run `44974869469`. Android JVM, shared contracts, iOS package and APK passed; the managed emulator failed 1 of 115 tests: `BackupSettingsSmokeTest.manualBackupActionRemainsEnabledAndExplainsMissingFolder`, assertion: “Missing-folder guidance was not shown beside the manual backup action”. Windows Hub, Agent and workflow lint were skipped. Keep the inventory PR unmerged until the test failure is reconciled with the focused fix in #412 and the latest emulator run.
- **PR #413 — Program 150 baseline:** this document remains a documentation-only Draft PR. After the latest evidence refresh, exact-head run `44995542218` passed change detection and exact-head summary; Android, iOS, Windows, shared-contract and workflow-lint jobs were skipped by change-aware selection. This is documentation validation, not platform testing or implementation progress.
- **Windows Agent / typed lease note:** issue #228 is now closed: the Hub claim response carries task type, idempotency key, payload reference/hash, effect-risk, approval reference and lease expiry, and Agent mapping to a typed envelope is covered. Closing #228 closes the wire-metadata gap only; it explicitly does not enable polling/claim execution. The remaining result-artifact, durable payload and first bounded handler gate is tracked by open issue #384. Source inspection of PR #410 confirms `Program.cs` registers an `AgentTaskHandlerRegistry` but no concrete handler; the executor fails closed with `NO_AGENT_HANDLER`. Keep Agent Ready/production claims disabled until #384 and two-PC acceptance pass.
- **Core / ownership note from #410 source:** signed ownership-transfer issuance and transactional Hub-side ownership CAS/journaling tests are present in the broad stack, but this does not by itself establish canonical Android Core handoff, trusted old-owner key resolution, replay protection across process restart, or production cross-Core reconciliation. Keep #258/#266 acceptance open until the full Core path is proven.
- **Android ↔ Windows Bridge note from #410 source:** Android has a pinned-HTTPS client and read-side ownership sync worker in the stack. The source explicitly describes the Android client as read-only; it cannot sign a transfer, issue a lease or enqueue work. Treat this as transport/ownership-read groundwork, not a complete peer task channel.
- **Formal progress remains 27/150 core steps checked (18%); 27/200 across the expanded ledger (13.5%) in the original numbered checklist.** This is a literal checkbox count. The new exact-head evidence adds verified partial implementation detail but does not justify checking off whole steps.


- **Issue-state correction:** #228 is closed, not open. Its completion is the typed lease metadata contract only; #384 is the current explicit blocker for bounded result artifacts and first safe production Agent handler. Do not report #228 as an outstanding issue.


## Verification refresh — 2026-11-22 (Android/Desktop GitHub coding APIs and Core ownership audit)

- **Owner priority correction:** the latest explicit owner instruction supersedes the older P0-first ordering above: Android GLAFIRA GitHub work first, Windows Desktop GitHub-capable Agent second, then the previous priorities. Ten workstreams remain active in parallel.
- **PR #416 — Android branch-scoped GitHub coding API:** exact head `f035f031aa30f054a141f0aa2ee503a10eaa8150`, run `45003098387`. Shared contracts, Android JVM tests, debug APK and managed Android emulator acceptance passed; exact-head summary passed. Windows, iOS and Agent jobs were skipped. PR is Draft and stacked on #415/#404. Physical Realme 8i validation and owner-facing chat/Core/owner-unlock integration are still outstanding.
- **PR #417 — Windows Desktop branch-scoped GitHub coding API:** exact head `b5a3aa562617387c2fe64dbdf09a4bbad2a17dd9`, CI run `45003398881`: Windows Desktop Hub tests/build, shared contract conformance and exact-head summary passed. Windows Desktop x64 and Windows Agent x64 packages passed in run `45003398913`. Android, iOS and Agent test jobs were skipped in the change-aware run. PR remains Draft, stacked on #411; WPF owner-facing coding workspace, owner unlock and Core task/Trace/ACK integration remain open.
- **PR #418 — Windows Agent bounded result-artifact store:** exact head `b55fb551f5e0daab01e50d9468c058cdc6ab88e4`. Windows Agent tests/build, shared contracts and exact-head summary passed in run `45003345467`; Agent compile passed in `45003345452`; Agent and Desktop x64 package builds passed in `45003345456`. This is only the typed local artifact store. Production handler registration, durable Agent outbox/ACK acceptance and two-PC acceptance remain open under #384.
- **Core issue #381 — fresh source audit:** on current `main`, `GlafiraTaskStore.claimNext()` returns a plain task after updating by task ID/state; `transition()` and `persistCoreTaskResult()` likewise do not compare the immutable owner Core ID/generation/state-version captured before handler execution. `GlafiraCoreTaskExecutor` carries no claim tuple into handler completion. An audit comment was added to #381 with exact code findings and a focused implementation/test boundary. Do not treat the ownership table or #380 approval-generation fencing as a substitute for execution-result fencing.
- **Program 150 count:** remains 27/150 core steps checked (18%); 27/200 across the expanded ledger (13.5%). New PRs and audit comments are partial evidence, not completed checklist steps. No PR above is merged; no physical-device or production Agent readiness is claimed.


## Verification refresh — 2026-11-22 (Android and Windows GitHub integration contracts)

- **PR #421 — Android chat-to-GitHub dispatch contract:** exact head `cc3b52645d1f376a21ea94806d0c37fd06f6db49`. Workflow run `45006022195` (change-aware platform validation) and run `45006022203` (Project Dispatcher) both completed successfully. The document defines typed intents, repository allowlist, preview-hash-bound one-time owner approval, Core owner-generation checks, durable Trace/ACK, fail-closed behavior and reconciliation of uncertain writes. It is documentation only: Android chat dispatch is not implemented by this PR.
- **PR #422 — Windows Desktop GitHub workspace contract:** exact head `34775633573f9b0eee69f46c5493a54dbe88669e`. The PR was created as Draft; no workflow runs were returned at the time of this refresh. The document specifies the missing WPF workspace, read-only browsing, diff preview, owner session, preview-bound approval, Core Trace/ACK and Windows acceptance tests. It does not implement the workspace.
- **PR #419 Android sensitive-path guard:** exact-head runs `45004485858` (change-aware validation) and `45004485836` (Project Dispatcher) completed successfully.
- **PR #420 Windows sensitive-path guard:** exact-head runs `45004438257` (Windows Hub), `45004438235` (Windows Desktop UI), `45004438241` (Windows deployment packages), `45004438231` (change-aware validation) and `45004438205` (Project Dispatcher) completed successfully. Platform jobs skipped by change-aware selection are not claimed as passed.
- **Formal Program 150 count remains 27/150 core steps checked (18%); 27/200 across the expanded ledger (13.5%).** PRs #421 and #422 are documentation/contract artifacts, not completed numbered implementation steps. Their successful documentation CI is useful evidence but does not justify checking off an implementation milestone. No PR in this refresh was merged.


## Verification refresh — 2026-11-22 (latest helper contributions and exact-head checks)

- **Android GitHub Core dispatch — PR #424:** exact head `4c9a17cc6c37e9489560001d8e8266735468925e`. CI run `45012419029`: Android JVM, managed emulator acceptance, debug APK, shared contract conformance and exact-head summary passed. Windows/iOS/Agent/workflow-lint lanes were skipped by change-aware selection. This adds five registered Core GitHub handlers and owner-scoped authorization; ordinary chat still does not create these Core tasks, and the PR remains Draft with no submitted review.
- **Windows Desktop GitHub workspace — PR #425:** exact head `640760f3be4e019355a6a5739e17a6141062e376`. CI run `45012478059`, Windows Desktop Hub `45012478047`, Windows Desktop UI `45012478036`, deployment packages `45012478166`, dispatcher `45012478012`: all completed successfully. The WPF workspace currently reads the allowlisted `main` tree and selected files only. It does not write, create branches/PRs, or merge. The PR remains Draft with no submitted review.
- **Android intent preview — PR #423:** exact-head run `45007338718` failed Android compilation because the preview builder called the undefined `isWritableFeatureBranch` helper. The helper contribution was repaired in the PR branch by commit `be0bf7ffa47868e85de55767d0b7023529c9ad82`; it now delegates to the canonical `GitHubRepositoryOperations.requireWritableBranch` guard and rejects the base branch as a write target. New exact-head CI `45013577991` was still running at the time of this update; do not count the fix as validated until it completes.
- **Windows Agent artifact store — PR #418:** exact-head `b55fb551f5e0daab01e50d9468c058cdc6ab88e4`; Agent tests/build, shared contracts, deployment packages and dispatcher passed (runs `45003345467`, `45003345452`, `45003345456`, `45003345496`). This is a local bounded artifact store only. It does not register a production handler or implement durable Core ACK/outbox; issue #384 and two-PC acceptance remain open.
- **Voice and recovery slices:** PR #402 auto-speech policy CI `44989948562`, PR #414 recognizer startup guard CI `44997073730`, and PR #412 backup guidance CI `44988435228` all passed their Android change-aware suites. These results do not establish physical Realme 8i acceptance. Mic process-exit root cause and the backup folder-picker requestCode exception remain unresolved pending device Logcat.
- **Dependency / merge discipline:** Android chain is #404 → #415 → #416 → #419 → #424; Windows chain is #411 → #417 → #420 → #425. All listed dependency PRs have successful exact-head CI evidence, but they remain Draft and currently have no submitted GitHub reviews. Do not merge a downstream PR ahead of its base or mark these chains integrated on `main` until the review and ordered integration gates are satisfied.
- **Core ownership:** #347 and #380 remain open; the separate #381 audit still identifies missing immutable owner-generation fencing on claim completion/result persistence. Keep execution-fencing acceptance open.
- **Formal Program 150 count remains 27/150 core steps checked (18%); 27/200 across the expanded ledger (13.5%).** PRs, green builds, documentation and partial feature slices do not increment the original checklist unless a complete numbered acceptance step is met.


### Follow-up correction — PR #423 preview guard (2026-11-22)

The initial repair commit `be0bf7ffa47868e85de55767d0b7023529c9ad82` removed the missing helper but referenced `GitHubRepositoryOperations`, which is supplied by stacked PR #416 and is not present on PR #423's `main` base. The resulting base-branch compile failure was inspected from the exact CI job log. The preview model has now been made self-contained: it repeats the deterministic branch syntax/protected-branch checks locally, while the eventual API layer independently repeats the same policy at dispatch. CREATE_BRANCH previews now reject protected/default branches as well; tests cover main/master, protected prefixes, refs/, dot-segments and .lock names. Latest PR #423 head: `60c6156cf9116298a4c600cded2338010a5fe85a`; Android JVM CI run `45013775184` is in progress at this update. The earlier failing runs remain historical failures; do not count this repair as validated until the latest exact-head run completes.


## Live execution update — 2026-11-22 (ten directions + Program 150)

### Exact-head CI reconciliation

- **PR #423 Android GitHub intent preview:** exact head `60c6156cf9116298a4c600cded2338010a5fe85a`, workflow `45013775184`. Android JVM tests **passed**, debug APK package **passed**, shared contract conformance **passed**; managed Android emulator acceptance **failed**. The exact failing test is `BackupSettingsSmokeTest.manualBackupActionRemainsEnabledAndExplainsMissingFolder`: UiAutomator did not find the missing-folder guidance after tapping the manual backup action. This is a backup-settings UI/accessibility regression in the current main baseline, not a Kotlin compilation failure in the GitHub preview. Do not describe #423 as fully green.
- **Backup follow-up PR #426:** branch `fix/android-backup-guidance-visible-with-action`, exact head `a8840fcb475b65ca2362ae7de64ec94a7a6ceebe`. It exposes the active missing-folder prerequisite in the manual backup button's accessible description. Exact-head CI `45015827522` passed Android JVM, shared contract, debug APK and managed emulator acceptance; Windows/iOS/workflow-lint jobs were correctly skipped for this Android-only change. This does not prove cloud-folder picker, remote read-back, clean restore or physical Realme 8i acceptance.
- **PR #424 Android Core GitHub handlers:** exact head `4c9a17cc6c37e9489560001d8e8266735468925e`; CI `45012419029` passed Android JVM, emulator, APK, shared contract and exact-head summary. This remains a stacked Draft PR; ordinary chat-to-Core routing and owner-facing preview/approval/result UI are not included.
- **PR #425 Windows Desktop GitHub workspace:** exact head `640760f3be4e019355a6a5739e17a6141062e376`; CI `45012478059`, `45012478047`, `45012478036`, `45012478166` and dispatcher `45012478012` all passed. It is read-only (allowlisted main tree/file inspection), not autonomous branch/write/PR execution.
- **PR #413 ledger refresh:** exact head `20135534f5f45870634d7523c1792242e9e2d098`; change-aware documentation CI `45013895616` and dispatcher `45013895653` passed. Documentation validation does not increase implementation completion.
- **PR #410:** exact head `4bdaabb5e5882c371ef4cd5779e996ba4b41984d`; all reported platform and dispatcher jobs passed in run family `45010889753`–`45010889892`. It remains Draft and has known overlap with #418 in the Agent result artifact store files; do not merge both implementations unchanged.
- **PR #418 Windows Agent artifact store:** exact head `b55fb551f5e0daab01e50d9468c058cdc6ab88e4`; Agent, packages, change-aware CI and dispatcher passed. This is artifact persistence, not yet the production read-only handler registration / end-to-end ACK path.
- **Voice / restore:** #402, #414 and #412 retain their earlier successful change-aware checks, but no new physical Realme 8i acceptance evidence was obtained in this execution window. The microphone exit and folder-picker requestCode exception remain owner-device diagnostics, not declared fixed.

### Ten workstreams — current disposition

1. Android ↔ GitHub: API/authorization handlers exist in #424; chat-to-Core intent adapter, preview/approval UX and user-visible result routing remain.
2. Windows Desktop ↔ GitHub: read-only WPF repository workspace exists in #425; owner-session gate, approved write flow and independent two-PC acceptance remain.
3. Owner authentication: Compose biometric UI state is not yet a shared Core owner session consumed at the execution boundary.
4. Microphone: #414 startup guard passed CI; root cause and Realme 8i test remain.
5. Automatic TTS: #402 policy passed CI; physical test of automatic playback, interruptions and dedup remains.
6. Core: #347/#380 checks are green, but immutable owner-generation tuple must be carried through claim, handler completion and result persistence (#381).
7. Backup/Restore: #426 addresses the emulator-discovered accessibility failure; folder picker, remote read-back, clean restore and device acceptance remain.
8. Android ↔ Windows Bridge: pairing/transport exists in slices, but authenticated durable handoff, one-owner semantics, outbox/ACK and restart/replay acceptance remain.
9. Windows Agent: #418 bounded artifact store passed tests; first production allowlisted handler, ACK reconciliation and two-PC acceptance remain.
10. Coordination / Program 150: exact-head CI and issues were refreshed; no whole numbered milestone was newly completed in this window.

### Program 150 status

**Formal count remains 27/150 core steps checked (18%); 27/200 across the expanded ledger (13.5%).** This is the count of fully checked numbered milestones in the ordered checklist, not a count of PRs, commits, green CI jobs or partial feature slices. No new numbered step is checked by this update. The next unblocked evidence targets remain step 16 (full failure-injection matrix), step 17 (provider outcome/replay safeguards), step 18 (durable result outbox + explicit ACK), step 20 (persistent owner grants and approval binding), step 28 (TaskPlan instrumentation preservation), and steps 91–110 for Android acceptance. Step 16 has useful merged slices but remains open until the complete claim→handler→progress→result→ACK→restart and stale-owner matrix is demonstrated.


## Follow-up validation — 2026-11-22 (Backup emulator fix + owner approval inbox)

- **PR #426 backup guidance accessibility fix:** exact head `a8840fcb475b65ca2362ae7de64ec94a7a6ceebe`; run `45015827522` completed successfully. Android JVM, shared contract conformance, debug APK, managed emulator acceptance and exact-head summary all passed. This specifically closes the emulator assertion that failed in #423; it does not prove cloud-folder picker/remote restore or physical Realme 8i acceptance. #423's earlier failed run remains historical and must be rerun against main after #426 integration.
- **PR #427 Android Core owner approval inbox:** Draft PR stacked on #424, exact head `f433a0f791ae8a82d9295921e918f7525b8224f3`. It adds a Task Queue approval list, redacted action details and exact requested scopes, manual refresh, and fresh BiometricPrompt authentication to mint a one-use `GlafiraCoreOwnerSession` before approve/reject mutations. Exact-head CI `45016574315` passed Android JVM, managed emulator acceptance, debug APK, shared contract and exact-head summary; iOS, Windows Agent, Windows Desktop and workflow lint were skipped because this is an Android-only change. This remains only the approval-inbox slice, not the global owner-session gate or chat-to-Core routing.
- **PR #428 Android GitHub connect command:** new Draft PR stacked on #427, exact head `a1bc16cae00bae9793fad4e498a32dad144b5807`. It recognizes explicit Russian/English connect commands in the GLAFIRA chat and opens the existing GitHub Device Flow section in Settings without adding a chat/outbox item or granting access; unit tests cover accepted and rejected phrases. Dispatcher `45018987704` passed; change-aware CI `45018987717` was still running Android JVM tests at this update, while shared contract passed and unrelated platform jobs were skipped. This is navigation only: repository read/write task creation, Dry Run preview, approval binding and result presentation remain outstanding.
- **PR #413 ledger scope correction:** exact head `85e4834cdd3610edc62841ca6d4447eb3c1b4684`; documentation CI `45016120966` and dispatcher `45016120978` passed. The ledger now explicitly distinguishes the original 150-step target from the expanded 200-item backlog.
- **Formal count:** original Program 150 = **27/150 (18%)**; expanded 200-item ledger = **27/200 (13.5%)**. The 50 extension items (151–200) are all unchecked. No new numbered milestone is checked by PR #426 or #427; they are partial implementation evidence toward existing acceptance gates.


## Owner priority and verification update — 2026-11-22 (latest instruction)

The owner has since refined the execution order. This order supersedes earlier priority lines in this ledger:

1. **Integrate helper contributions first:** inspect helper PRs/files, reconcile overlaps against current `main`, integrate bounded slices, run exact-head validation, and notify the helper after every change. The helper must refresh from current `main` before starting another slice.
2. **Android app:** complete the ordinary-chat → intent → Core task → preview → fresh owner approval → execution → result/Trace loop, then validate voice, backup and device behavior.
3. **Windows Desktop:** complete the independent GitHub-capable Desktop workflow and owner-gated write path.
4. **Core, Backup & Recovery, Bridge:** continue ownership fencing, crash recovery, durable result outbox/ACK, portable restore and authenticated peer transport.
5. **Remaining work:** continue iOS, Windows Agent production handler, release gates and other backlog items without displacing the first four priorities.

Keep ten workstreams active in parallel; this is a merge/integration order, not an instruction to stop independent work.

### Latest integration evidence

- **Integration PR #429** now includes the focused Android backup-guidance accessibility fix from #426. Exact head `f6652b41ee0bedc3fff37884e43ffca148aadd3d` also adds UI-smoke test isolation in `AndroidUiSmokeTestSupport.kt`, `BackupSettingsSmokeTest.kt` and `MainActivitySmokeTest.kt`.
- Previous exact-head run `45071442557` completed 115 emulator tests with 3 failures. All three failed before reaching the expected home-screen accessibility elements; Logcat confirms `MainActivity` was displayed. The UI-smoke tests now clear only the app's no-backup restore journal before launch, because an interrupted-restore state routes the app to a recovery screen rather than the normal workspace. This is a test-isolation hypothesis/fix, not yet accepted evidence.
- New exact-head CI for `f6652b41ee0bedc3fff37884e43ffca148aadd3d` is run `45072600522`; at this ledger update Android JVM, Windows, iOS and contract jobs are still running. Do not mark the emulator gate passed until its executed-test report is green.
- PR #429 remains open and unmerged. Its overlapping Windows Desktop UI files are also in #425; resolve that overlap only after the #429 exact-head gate passes.
- PR #426's backup change has been copied into #429. Keep duplicate backup PRs #426/#412 open until the integrated exact-head emulator gate passes, then mark them superseded rather than merging duplicate edits.
- PR #418 overlaps #429 on the Windows Agent result-artifact store and tests. Do not merge both versions unchanged.
- Helper was notified in PR #410 and PR #429 of the new test-only changes and instructed not to duplicate the touched files.

**Formal Program 150 count remains 27/150 (18%); expanded ledger remains 27/200 (13.5%)** until a complete numbered acceptance step is evidenced. No new numbered milestone is checked by this update.


## Execution ledger — verified refresh after PR #428 merge (2026-11-22 22:45 UTC)

**Current main:** `4f97c19afa8f997c4977014398aa33b9dfae67d9`. **Formal checkbox count:** 27/150 checked (18.0%); this is the literal checklist count, not a claim that all unmarked steps have zero implementation. The expanded 151–200 backlog is reported separately and does not change the original 150-step denominator.

### Ten workstreams — current evidence

1. **Android GitHub/Core:** PR #435 head was rebased onto current main as `fdd4aa6153c72692e192fb04fd74c262bd5b5891`; fresh exact-head CI is pending. Prior head passed JVM, contracts, APK and emulator, but the shared owner-unlock gate in #408 remains a security blocker for production/autonomous credential use.
2. **Android owner approval inbox:** PR #437 head `ca957a6bd65658d9a5420f30900fc8dcd36f1bac` passed JVM, contracts, APK and emulator on its parent stack. Rebase and rerun after #435 integration; do not merge it independently from its dependency.
3. **Android chat GitHub command:** PR #428 merged as `4f97c19afa8f997c4977014398aa33b9dfae67d9`. Explicit connect commands navigate to Settings; this is not an authorization bypass or automatic login.
4. **Backup & Recovery:** #426 and #438 are in main. Physical Realme 8i backup/restore acceptance remains outstanding.
5. **Windows GitHub Workspace:** #436 is in main. Workspace remains first-stage; physical Windows validation, owner unlock and diff-preview/explicit approval before writes remain open.
6. **Windows Agent:** #433 is in main. The older #418 artifact-store extraction was closed as superseded after source comparison confirmed main has a newer superset with ProjectId binding and pending-artifact enumeration. One bounded read-only `diagnostics.snapshot` handler is now registered in Agent `Program.cs`, with the durable artifact store/outbox and Hub transport wired. Broader handler coverage, full crash/restart/ACK reconciliation evidence and two-PC acceptance remain open.
7. **Windows Desktop Hub:** #434 is in main; tests/build/package passed. Runtime validation on the owner's Windows machine remains outstanding.
8. **Shared contracts:** #431 is in main. Cross-platform end-to-end acceptance remains open.
9. **Android voice/auto-speech:** #432 is in main; physical Realme 8i validation remains outstanding. #414 is a separate older draft candidate and is not counted as merged.
10. **Android ↔ Windows ↔ Core integration:** still requires canonical peer pairing, one-owner task handoff, durable result/ACK reconciliation and two-device acceptance. Legacy CLI pairing is not production peer pairing.

### Active validation / blockers

- PR #435: rebased onto current main; awaiting fresh exact-head CI. Do not merge until the shared owner-unlock/session gate is implemented and tested at every GitHub token and network boundary.
- PR #437: exact-head checks passed on the previous stacked base; must be rebased and retested after #435.
- PR #423: side-effect-free GitHub intent preview model rebased onto current main; CI is running. The previous emulator failure was a stale BackupSettingsSmokeTest expectation (missing-folder guidance), not a failure in the preview model; rerun on current main is required before any promotion.
- PR #428: merged; physical-device test remains outstanding.
- PR #418: closed as superseded by the more complete implementation already in main.
- Skipped CI jobs and physical-device gates are not counted as passes.



## Correction — Windows Agent first handler source audit (2026-11-22)

Current `main` now registers `AgentDiagnosticsSnapshotTaskHandler` in `windows-agent/GLAFIRA.Agent.Worker/Program.cs`. `AgentWorker` polls only while the Agent is Ready and a typed handler is registered; `AgentTaskExecutor` rejects non-ReadOnly tasks. The first handler accepts only the pinned `diagnostics.snapshot` payload and reads a fixed set of local runtime metadata. `AgentResultArtifactStore`, `AgentResultArtifactOutbox` and the authenticated Hub artifact transport are registered. This resolves the earlier 'no handler registered' blocker for one narrow diagnostics task only. It does not establish general task execution, end-to-end Core acceptance/ACK recovery, or two-PC production readiness.


## Verification refresh — 2026-11-23 (provider policy and owner-session audit)

- **PR #458 provider capability policy:** merged to `main` at `642a0a3acb12a7eda15b03f622786bf5715792b9`. Exact-head run `45104054518`: Android JVM, shared contract conformance, debug APK, managed emulator acceptance and exact-head summary all passed. iOS, Windows Agent, Windows Desktop and workflow-lint jobs were skipped by change-aware selection and are not claimed as passed.
- **Backup UI regression recheck:** the exact test `BackupSettingsSmokeTest.manualBackupActionRemainsEnabledAndExplainsMissingFolder` passed in #458's clean current-main emulator run. This is evidence that the earlier failure did not reproduce; it is not a claim that cloud-folder selection, restore round-trip or physical-device backup acceptance is complete.
- **Windows Desktop GitHub owner-session audit:** current `MainWindow.xaml.cs` still decrypts the DPAPI token in `InitializeGitHubSessionState()` during window construction; `GetGitHubAccessTokenAsync()` loads/refreshes and returns a raw token, and GitHub workspace handlers pass that token to the coding client. A UI-only lock is insufficient; the next implementation must enforce a short-lived owner capability at the credential broker and every authenticated request. Finding recorded on issue #409.
- **Android GitHub owner-session audit:** `GitHubIntegrationSection` still obtains a raw token through `GitHubSession.accessTokenOrRefresh()` and uses it for authenticated user/installation/repository checks. Existing biometric approval UI is not an app-wide session gate. Finding recorded on issue #408.
- **Core coordinator runtime:** #451/#453/#455 provide architecture/contracts/bootstrap, not a complete persistent multi-node runtime. The next testable slice is durable node registry/heartbeat/lease state with trusted time and stale-generation fencing; issue #452 updated.
- **Voice/TTS source audit:** Settings persist the auto-speak preference, and `MainActivity` observes new assistant messages by operation ID before calling `TtsHelper.speak()`. The code path is present, but the user's Realme 8i behavior is not yet physically verified; the mic crash and real-device automatic playback remain open.
- **Formal Program 150 count remains 27/150 (18%); expanded ledger remains 27/200 (13.5%).** No numbered acceptance step is checked by PR #458 or this documentation refresh.


## Verification refresh — 2026-11-23 (Core fencing port, outbox and integration gates)

- **Core execution fencing source candidate — PR #347:** exact head `ea7b7878efe074ae2cb9a542efada25dd388c820` passed its selected exact-head CI run `44970287760`. Source inspection confirms atomic owned claim capture (`ownerCoreId + ownerGeneration + stateVersion`), immutable execution token propagation, owned transition/result persistence predicates, and instrumentation coverage for Core isolation, stale generation, and stale same-generation claim after retry/reclaim. The branch is 23 commits ahead / 31 behind current main and changes the executor, queue, store and ownership tests. **Port onto current main; do not merge/retarget blindly.** Add a blocked-handler test that performs a real transactional ownership transfer during execution and proves stale handler cannot mutate result/state/retry/history/Trace. Keep legacy executor fallback only for explicitly identified legacy/test stores; production owner-bound Core must fail closed if token capture is unavailable. Finding recorded on #381 (comment 7099186084).
- **Core result delivery / outbox:** current Core atomically persists encrypted result with terminal state/history, but the result table is not a complete delivery outbox. Windows Agent already validates artifact ACK identity including owner generation, lease, fencing token and digest before deleting its artifact; Desktop Bridge retains immutable terminal results across transport failure but its ACK contract is weaker. Reuse the Agent's exact-ACK model for Core delivery and bind recipient + originating conversation/message + result digest. Finding recorded on #266 (comment 7099110087). Do not rerun a handler to retry result delivery.
- **Android GitHub chat integration:** the side-effect-free `AndroidGitHubIntentPreviewBuilder` declaration exists, but a default-branch code search found no call site. The current dispatch path enqueues a task and shows a Toast; this does not establish an owner-visible preview or approval bound to the preview hash. Durable result correlation back to the originating chat is also still open. Findings recorded on #464 (comments 7099121758 and 7099058053).
- **Backup & Recovery:** current-main instrumented tests already cover wrong passphrase non-mutation, authenticated archive corruption/truncation, malformed snapshot rejection, rollback on restore failure, encrypted task result preservation, and resetting RUNNING tasks to owner review while dropping source-device leases. Remaining acceptance is process-death injection at durable boundaries, SAF/cloud-provider permission persistence, and physical-device backup/restore; see #387 (comment 7099191539). Do not duplicate existing test coverage or count it as physical-device acceptance.
- **Bridge:** current Android protocol unit tests validate desktop result envelope lease/fencing fields; desktop-bridge tests validate invitation parsing/proof generation. These are not an Android HTTPS client ↔ Windows Hub pairing/reconnect test. Current issue #392 still requires the owner-facing Android client, pinned TLS, one-time invitation, approval, persisted/revoked credentials and reconnect acceptance (comment 7099198882). iOS/Windows pairing route and semantics mismatch remains separately tracked in #393.
- **Windows Desktop GitHub / Owner Unlock:** PR #462 selected Windows build/tests pass, but WPF handlers still call GitHub APIs directly and UI MessageBox confirmations are not durable Core grants. Android and Desktop credential methods still return/use raw tokens without a shared short-lived owner-session capability at every authenticated request boundary; see #408/#409. Keep PR #462 Draft.
- **Provider PR #449 CI diagnosis:** old exact-head run `45096541638` passed Android JVM and APK packaging but failed managed emulator on `BackupSettingsSmokeTest.manualBackupActionRemainsEnabledAndExplainsMissingFolder` (one failure among 115 tests). The changed files are provider/UI files; the failure is a stale backup UI expectation later corrected in #450 and passed on a subsequent clean mainline run. PR #449 still needs fresh exact-head validation on current main; finding recorded in comment 7099209826.
- **Voice, Agent and multi-device coordination:** Agent's single bounded read-only `diagnostics.snapshot` handler and exact artifact ACK remain implemented; do not describe it as a general Windows coding agent. Android voice auto-speak/microphone acceptance still requires the owner's physical Realme 8i Android 13 test and Logcat evidence (#401). Coordination contracts remain normative documents, not production GitHub CAS election/lease runtime (#452).
- **Program count unchanged:** 27/150 primary acceptance steps (18%) and 27/200 expanded ledger items (13.5%). No numbered step is checked by this audit, comments, or documentation changes. Physical-device and end-to-end gates remain open.
