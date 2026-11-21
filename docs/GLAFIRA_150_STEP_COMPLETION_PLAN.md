# GLAFIRA — ordered 150-step completion plan

**Baseline main for this ledger revision:** `d14b59582544dabfcc83d8237b6dd0a8aa53710e` (verified 2026-11-21 05:35 UTC; refresh before the next integration).  
**Owner priority:** P0 Core → P1 Android instrumentation + Bridge → P2 Windows Desktop → P2 Windows Agent → P4 Android UX → P4 iOS → release gates.  
**Target:** fastest safe route to an integrated private owner MVP across four apps, not a calendar promise.

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
