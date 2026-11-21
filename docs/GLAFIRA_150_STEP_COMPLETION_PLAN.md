# GLAFIRA — ordered 150-step completion plan

**Baseline main:** `85fb70c16d900a8d4f66bd6fec59593de491da12` (refresh before each integration).  
**Owner priority:** P0 Core → P1 Android instrumentation + Bridge → P2 Windows Desktop → P2 Windows Agent → P4 Android UX → P4 iOS → release gates.  
**Target:** fastest safe route to an integrated private owner MVP across four apps, not a calendar promise.

## Reframed delivery strategy

- One Core authority and one versioned Bridge protocol; no platform-specific competing task queues or permission authorities.
- Windows Desktop is prioritized over iOS for the first full cross-device execution loop because it hosts Windows Agents. iOS remains first-class and progresses on non-conflicting work.
- Android is the instrumentation/conformance anchor and primary mobile owner test surface.
- Functional MVP first; defer visual polish, Chimera/avatar, public marketplace, wallet issuance/transfers, compute marketplace, VPN billing, macOS Desktop and specialist-agent expansion until the four-app foundation is accepted.
- No arbitrary shell execution, hidden compute, automatic external side effects, credential leakage or paid-backend requirement for the initial MVP.
- A step is complete only with code/config plus tests and relevant acceptance evidence. Skipped CI is not a pass; physical-device gates are explicitly owner-dependent.

## Ordered actions

### P0 — Core: единственная очередь и authority

1. [ ] Снять baseline main SHA, open PR/issues, CI runs и артефакты.
2. [ ] Составить dependency/file/commit map recovery stack из issue #91; не переносить ветку целиком.
3. [ ] Сверить PR #71/#104/#119/#122 с main и выделить только уникальные Core изменения.
4. [ ] Зафиксировать единый task state machine и допустимые переходы.
5. [ ] Проверить все SQLite migrations на upgrade с поддерживаемых версий.
6. [ ] Проверить concurrent claim из независимых SQLite connections.
7. [ ] Доказать отсутствие двойной выдачи task при конкуренции N workers.
8. [ ] Разделить maintenance gate и locks отдельных задач.
9. [ ] Добавить cancellation и тест cancel-vs-complete race.
10. [ ] Проверить DAG: cycles, missing nodes, cross-project edges, duplicate edges.
11. [ ] Проверить join/unblock только после успешных predecessor tasks.
12. [ ] Добавить fairness/starvation bounds между проектами.
13. [ ] Проверить bounded worker pool, backpressure и configurable concurrency.
14. [ ] Проверить stale worker fencing после lease expiry и повторной выдачи.
15. [ ] Добавить crash recovery для каждого non-terminal state.
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

26. [ ] Проверить AndroidJUnit4 suite в main и CI selection для Core/Bridge изменений.
27. [ ] Подтвердить, что managed emulator реально выполняет тесты и JUnit XML не пуст.
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
49. [ ] Связать Hub task journal с единым Core task authority; убрать дублирующие очереди.
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

71. [ ] Закончить типизированный lease envelope: type/idempotency/payload hash/risk/approval/expiry.
72. [ ] Проверить strict metadata validation и capability subset.
73. [ ] Добавить durable local lease journal с atomic persistence.
74. [ ] Подключить polling loop с bounded backoff и pause on disconnect/revoke.
75. [ ] Подключить AgentTaskHandlerRegistry к реальному worker lifecycle.
76. [ ] Добавить первый allowlisted read-only handler set; unknown type fails closed.
77. [ ] Сделать payload fetch allowlisted, hash-verified, size-bounded.
78. [ ] Добавить per-handler sandbox/time/resource limits.
79. [ ] Подключить lease renewal; остановить execution при невозможности продления.
80. [ ] Сохранять progress/result в durable outbox до ACK.
81. [ ] Проверять ACK по taskId/leaseId/fencing/result digest.
82. [ ] Разделить retryable, terminal и outcome-unknown; запретить blind replay.
83. [ ] Добавить crash recovery между handler completion и ACK.
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

111. [ ] Проверить Xcode project, scheme, deployment target и simulator build.
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
133. [ ] Проверить end-to-end task submission once → Core scheduling → Desktop routing → Agent handler.
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
3. Keep all four app directions active without duplicating Core/Bridge authority.
4. Branch from current main for clean slices. Stale stacked PRs are source material, not merge candidates.
5. Record exact head SHA, run IDs, executed tests/counts, skipped jobs, artifacts and unresolved risks.
6. Do not claim completion from docs, compilation or APK creation alone.
7. First owner beta remains private, uses approved read-only handlers, owner-gated actions and local/private-LAN transport.

## Initial execution wave

- Core: reconcile issue #91 and PRs #71/#104/#119/#122; validate concurrency, recovery, dependencies and result ACK.
- Bridge: finish canonical fixtures and authenticated lease/result delivery; validate #228 on its latest head.
- Windows: complete a typed read-only task cycle with durable lease and result ACK.
- Android: preserve emulator suite; close Bridge/queue/backup/voice release regressions after dependencies.
- iOS: preserve BridgeKit/native target; progress authenticated UI/task flows in parallel.
