# Architecture Decision Records

Каждое существенное архитектурное решение фиксируется отдельным ADR.

## Жизненный цикл

- **Proposed** — предложение на обсуждение; реализация не разрешена самим фактом публикации.
- **Accepted** — решение явно утверждено владельцем.
- **Superseded** — заменено более новым ADR с указанием ссылки.
- **Rejected** — отклонено с кратким обоснованием.

## Текущие решения

| ADR | Решение | Статус |
|---|---|---|
| [ADR-0001](ADR-0001-runtime-coordination-local-core-not-github.md) | Runtime coordination остаётся внутри независимых Core; GitHub — development plane | Accepted |
| [ADR-0002](ADR-0002-kotlin-core-not-rust-rewrite.md) | Kotlin сохраняется для Android Core; Rust rewrite не выполняется; Windows остаётся .NET/C# | Accepted |
| [ADR-0003](ADR-0003-trace-service-and-opentelemetry.md) | TraceService — durable audit; OpenTelemetry — отдельная техническая телеметрия | Accepted |

## Структура ADR

1. Заголовок и номер.
2. Статус и дата.
3. Контекст и ограничения.
4. Решение.
5. Альтернативы.
6. Последствия, риски, миграция и откат.
7. Связанные issue, PR и спецификации.

Не присваивайте статус Accepted без подтверждения владельца. Не дублируйте существующие нормативные контракты: ADR должен фиксировать конкретное решение и ссылаться на них.