# GLAFIRA AI HUB: единый реестр реализации и приёмки

Дата: 19 ноября 2026. Основание: согласованные владельцем приоритеты P0–P3 и параллельная работа. Основные ветки не изменяются; слияния выполняет владелец после проверки PR.

## Проверяемые исходные состояния

- main: `3d2aa8dbb9ed9ad42b76ef856d957133a4fd0b12`. Сборка до исправления нарушена отсутствующими Runtime Foundation типами.
- Функциональный стек: PR50 / `fix/android-bridge-settings-ui`, `17ffa3703d983b8ec9b1fe1253449deaa867f849`.
- PR51 восстанавливает минимальные зависимости main. Локально: 66 JVM-тестов и debug APK; GitHub Actions run44580427524 — success. Это восстановление компиляции с обязательным DRY_RUN.
- Согласованная функциональная сборка на базе PR50: 130 Android JVM-тестов, 0 failures/errors/skips; `assembleDebugAndroidTest` и `assembleDebug` успешны. Desktop: 39 JVM-тестов через Kotlin2.0.21/JVM17. Числа отдельных веток нельзя складывать: значительная часть тестов общая.
- Полный удалённый прогон согласованной сборки PR59 / `fd8cf227c2f5ffbbf4fdcbcfbd1ff74c066ab2d7`, run44583977488: Android/ Desktop JVM, 47 Android instrumentation tests и gated debug APK — success. Первый запуск обнаружил разные каталоги AVD; следующий реально выполнил22 теста и обнаружил старый дефект восстановления provider preferences. Он исправлен backup-пакетом, без исключения теста из CI.
- После этого добавлены точный model-settings backup policy (PR60), дополнительное P0 закрытие окна admission→enqueue (PR61) и persistent CLI session (отдельный P0 follow-up). В совмещённой локальной версии проходят140 Android JVM и50 Desktop JVM тестов; debug/instrumentation APK собираются. Для последующих исходных деревьев этот старый зелёный запуск не является доказательством: новые проверки запускаются отдельно.

## Независимые направления и границы файлов

| Направление | Приоритет / исполнитель | Изменяемые компоненты | Результат и критерий готовности |
|---|---|---|---|
| A. Core Runtime | P0 / p0_main | Runtime Foundation и минимальная зависимость main | PR51: компиляция восстановлена; DRY_RUN не вызывает live handlers. Требуется проверка владельцем и согласование со стеком PR50. |
| B. Android Core | P0/P1 / root + p0_queue | WorkManager, локальная очередь, recovery | Возврат ожидания сети, независимые wakeups, bounded recovery. Приёмка: процесс/сеть/перезапуск на устройстве; автономность 24/7 не подтверждена. |
| C. Desktop Core | P1 / p1_delivery | DesktopTaskRuntime, DesktopTaskQueue, DesktopResultOutbox | Сохранение фактического результата и доставка до ACK. Самостоятельная постановка/исполнение при выключенном Android ещё не завершены. |
| D. Bridge / синхронизация | P1 / p1_delivery | TLS/HTTPS client, ingress, protocol enums/validators, result journal, dispatcher | Project-scoped TASK_STATUS/история/части результата/ACK, dedup. Нужны UI-потребитель, крупные исходящие результаты и испытания разрывов связи. |
| E. Task Queue | P0 / p0_queue | TaskStore, Task, TaskQueue, DrainWorker, WorkScheduler | PR53: schema3→4, история, terminal guards, network recovery; 10 новых DB-тестов требуют эмулятора. |
| F. Backup / Storage | P0 / p0_storage; P1 / p1_backup | DataManager, DurableJsonFile, HistoryStore, PendingQueue/Delivery, MainActivity, ProjectManager; BackupArchive/Settings/Worker/Coordinator, portable snapshot | PR56: атомарные файлы и durable UI outbox. Backup: логическое повторное шифрование, проверки архива/назначения, rollback journal. Приёмка: новое устройство, авария во время restore, откат и реальное выполнение 6 новых instrumentation tests. |
| G. Security | P1 / root | Активный PairingApprovalStore, Bridge settings | PR55: отзыв с tombstone, удаление credentials/delivery, migration1→2. Четыре новых теста скомпилированы, требуется выполнение. Отдельные project-level grants и полноценный распределённый fencing остаются открытыми. |
| H. AI Providers | P0 safety / root; P2 функциональность | AiProviderResult/Client, Coordinator, ProviderRegistry, SettingsScreen | PR54: типизированный исход, безопасный retry, частичные ответы, настройка ID модели. Реальные ключи/квоты/доступность моделей не проверялись. Нельзя считать наличие ключа доступностью или бесплатным API. |
| I. Agents | P2 / следующий этап | ToolRegistry, dispatcher, реальные исполнители | Пока DRY_RUN. code.build должен запускать изолированную сборку с артефактами, web.research — реальный поисковый инструмент с источниками. Обычный LLM-текст не проходит приёмку как результат этих инструментов. |
| J. CI / QA | P0 / p0_ci + root integration | android.yml и CI helpers | PR52: ограниченные ожидания, KVM, отчёты действительно выполненных тестов; 16 host regressions. Полный PR59 прогон зелёный, включая47 instrumentation tests и Desktop. Новые P0 ingress/model-settings изменения требуют отдельного повторного прогона. |
| K. iOS | P2 / следующий этап | iOS project/signing/client | Наличие Swift package не является устанавливаемым приложением. Нужны Xcode target, credentials/signing владельца, TestFlight/device acceptance. |
| L. Desktop UX | P2 / следующий этап | Управление службой, настройки, очередь/результаты/ошибки/devices | Нужны понятный installer/launcher, service lifecycle, autostart и graceful recovery. Не объявлено готовым. |

Параллельные исполнители работали в отдельных worktree/ветках. Критические файлы не редактировались одновременно: queue владеет TaskStore; delivery использует отдельный адаптер той же SQLite БД; backup экспортирует ту же рабочую БД логически. История файлов записывается через общий appendHistory. Контракты согласовывались до зависимых изменений.

## Контракты сохранности и повторов

1. Постоянный taskId/projectId/idempotencyKey идентифицирует операцию. Случайный идентификатор транспортного запроса не заменяет идентификатор операции. Для трёх enqueue-only Bridge операций durable Core task является атомарной квитанцией: HMAC/scopes/expiry/rate проверяются заново, а отдельный replay claim не создаётся до enqueue. Совпадающий повтор возвращает прежнюю задачу, включая terminal; другой device/payload/tool/scopes получает конфликт без раскрытия taskId. Control operations сохраняют прежний replay ledger.
2. Core result и terminal state фиксируются в одной SQL-транзакции. Desktop terminal record является durable outbox и не удаляется до подходящего ACK. Повтор доставки не запускает handler заново.
3. Потерянный ACK допускает повтор доставки результата. Истёкший lease не доказывает, что операция не выполнилась: другой узел не получает её автоматически; поздний результат исходного устройства сверяется.
4. NOT_SENT/REJECTED/UNKNOWN различаются. UNKNOWN, прерванная запись и legacy-запрос с недоказанным исходом требуют проверки. Автоматический повтор составной консультации после выполненных этапов запрещён без durable stage resume.
5. Android history использует operationId для идемпотентного отображения. Это не обещание exactly-once во внешнем сервисе. Такое обещание требует поддержки idempotency/reconciliation самим сервисом.
6. Восстановление переносит данные, но не полномочия: не копирует Android Keystore keys/credentials/grants/leases. Незавершённые задачи помещаются в owner review. Непереносимый старый архив отклоняется явно.
7. До замены файлов и SQL фиксируется rollback journal. Незавершённый restore блокирует Core и отправку из UI до безопасного восстановления с прежней passphrase.
8. Отзыв действует на новые admissions и повторные проверки прав. Он не отменяет уже выполненное внешнее действие и не делает безопасным его повтор на другом Core.

## Порядок review и интеграции

- PR51 — отдельный ремонт main; не объединять весь PR50 ради отсутствующих зависимостей.
- На фактической базе PR50 независимы PR52 (CI), PR53 (queue), PR54 (provider outcomes), PR55 (revocation).
- PR56 (storage) зависит от PR54. Queue и storage согласуют API с delivery/backup, но не редактируют их файлы.
- `codex/p0-reliability-foundation` — изолированная проверочная композиция перечисленных исправлений. Она не заменяет независимые PR и не предназначена для слепого merge в main.
- Portable backup PR базируется на этой композиции; delivery PR — на backup, поскольку admissions используют interrupted-restore guard. Их изменения проверяются отдельно по diff и совместно по итоговому дереву.
- PR60 дополнительно сохраняет валидированные model overrides без переноса API keys. Дополнительный P0 durable-ingress пакет закрывает crash gap до enqueue и потерю ответа с taskId; клиенты обязаны сохранять логический ключ операции при повторе.
- Реестр и приёмочные критерии идут отдельным маленьким PR поверх delivery. Его CI проверяет весь согласованный набор, не создавая огромный PR несвязанных изменений.
- Стек исходных PR30→31→32→35→37→47→48→50 требует отдельного согласования. Последние изменения PR30 и PR35 не включены в текущие дочерние tips; оба расхождения затрагивают workflow. Подробности: `.github/CI_INTEGRATION.md`.
- Перед переносом функционального стека в main нужно согласовать восстановленные Foundation файлы, активный pairing credential adapter и transport; повторить сборку и интеграционные проверки. Публикация PR не является разрешением на автоматическое слияние.

## Блокировки готовности и следующие работы

P0 не закрыт целиком до удалённого выполнения instrumentation и проверки реальной целостности после интеграции в целевую ветку. P1 не объявляется завершённым.

- Отображение фактических Core результатов в Android UI ещё не подключено к новому result API.
- Offline Desktop outbox не попадает в резервную копию Android. Desktop JSON ограничен правами владельца, но не зашифрован. Нужны отдельный защищённый Desktop backup/export и безопасное хранение.
- Полное распределённое ownership/fencing, автоматическая сверка после portable restore и миграция старых desktop result records остаются открытыми.
- Передача крупных Desktop→Android результатов требует chunked upload; данные сохраняются локально, но полная доставка всех размеров ещё не обеспечена.
- Восстановленные незавершённые Core задачи требуют полноценного UI review/reconciliation; нельзя автоматически изготовить им новые grants.
- Аудит production wiring выявил, что CLI worker выбирал inMemory queue, несмотря на поддержку persistent storage в библиотеке. P0 CLI пакет теперь явно подключает `~/.glafira-ai-hub/bridge/tasks.json`, валидирует файл до remote claim и держит отдельный `tasks.lock` на рабочую сессию. Повреждённый/null файл не превращается в пустую очередь. Пять новых тестов проверяют реальный CLI factory, рестарт/ACK и межпроцессную блокировку, включая child JVM; полный Desktop suite —50 тестов. Прежние library persistence tests сами по себе этот запуск не проверяли.
- Отдельный P0 ingress прогон run44587112823 выполнил53 instrumentation tests:51pass,2fail из-за тестового Application singleton после deleteDatabase в другом классе. Изоляция fixture исправлена: helper закрывается под maintenance gate, startup result schema переинициализируется, инъекция проверяет конкретный SQLiteConstraintException и marker ошибки. Тесты не исключаются. Новый полный прогон обязателен.
- Завершение неinterruptible Desktop handlers за пределами bounded shutdown требует отдельной проверки до LIVE: нельзя считать текущий CLI lock полной реализацией shutdown/fencing.
- В проекте не задан отдельный общий ключ подписи тестовых Android-сборок. Перед последовательными обновлениями на смартфоне требуется согласовать стабильную подпись и проверить совместимость; удаление установленного приложения ради смены подписи не является безопасным способом обновления пользовательских данных. Секреты подписи этим пакетом не меняются.
- Бюджеты выполнения и обработка ошибок preview требуют последующего аудита перед LIVE. Рабочий режим DRY_RUN сохраняется.
- UI pending queue живёт в приложении; WorkManager ограничен Android, а выключенный телефон не выполняет код. Для работы при выключенных пользовательских устройствах нужен постоянно работающий вычислительный узел. Облачное хранилище само задачи не исполняет.
- Проверки на реальных AI API, внешних сервисах, смартфоне и iOS не выполнялись. Необходимые платные ресурсы или учётные записи автоматически не включались.

Следующая интеграционная приёмка: network loss до/после remote commit, потеря ACK, kill/restart обоих узлов, late result после lease expiry, cross-project/device rejection, revoke offline/reconnect, corruption/rollback generation, fresh-device restore/key rotation и crash между файловым и SQL этапами. Любая обнаруженная потеря результата, повтор действия или обход прав остаётся P0/P1 блокировкой.
