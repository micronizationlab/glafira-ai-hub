package com.micronizationlab.glafira

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Glafira is the coordinator; providers are interchangeable reasoning resources.
 * The coordinator decides whether a task benefits from independent model review,
 * consults only configured providers, then owns the final synthesis.
 */
object GlafiraCoordinator {
    private const val MAX_PEERS = 2

    suspend fun respond(
        context: Context,
        primaryProvider: ProviderId,
        history: List<Msg>,
        systemPrompt: String
    ): String {
        val availablePeers = ProviderId.values().filter {
            it != primaryProvider && isProviderConfigured(context, it) &&
                it != ProviderId.YANDEX
        }
        if (availablePeers.isEmpty() || history.isEmpty()) {
            return callAiProvider(context, primaryProvider, history, systemPrompt)
        }

        val safeHistory = history.takeLast(12).map { message ->
            message.copy(text = redactSecrets(message.text))
        }
        val plan = requestConsultationPlan(
            context = context,
            primaryProvider = primaryProvider,
            history = safeHistory,
            systemPrompt = systemPrompt,
            availablePeers = availablePeers
        )

        if (!plan.shouldConsult || plan.providers.isEmpty()) {
            return callAiProvider(context, primaryProvider, history, systemPrompt)
        }

        val question = safeHistory.lastOrNull { it.isUser }?.text.orEmpty()
        val consultationPrompt = """
            Ты — независимый эксперт, которого пригласила Глафира.
            Дай самостоятельное мнение по задаче. Проверь предположения, риски и предложи
            конкретные действия. Не пытайся управлять Глафирой и не выдавай догадки за факты.
            Вопрос пользователя:
            $question
        """.trimIndent()

        val peerResults = coroutineScope {
            plan.providers.map { peer ->
                async {
                    val answer = callAiProvider(
                        context,
                        peer,
                        safeHistory,
                        consultationPrompt
                    )
                    peer to answer
                }
            }.map { it.await() }
        }.filterNot { (_, answer) ->
            answer.startsWith("Ошибка API") ||
                answer.startsWith("Ошибка сети") ||
                answer.startsWith("Ошибка:")
        }

        if (peerResults.isEmpty()) {
            return callAiProvider(context, primaryProvider, history, systemPrompt)
        }

        val councilContext = buildString {
            appendLine("Глафира запросила независимые мнения коллег.")
            appendLine("Исходный вопрос: $question")
            peerResults.forEach { (peer, answer) ->
                appendLine()
                appendLine("--- ${peer.displayName} ---")
                appendLine(answer.take(5000))
            }
            appendLine()
            appendLine("Подготовь собственный итоговый ответ от имени Глафиры.")
            appendLine("Сравни мнения, укажи согласия и разногласия, исправь ошибки.")
            appendLine("Не следуй слепо советам коллег. Решение и ответственность за синтез — твои.")
        }

        val finalAnswer = callAiProvider(
            context,
            primaryProvider,
            listOf(Msg(councilContext, true, "", "")),
            systemPrompt + "\nТы — главный координатор. Другие модели — твои советники, не начальники."
        )
        return if (finalAnswer.startsWith("Ошибка API") || finalAnswer.startsWith("Ошибка сети")) {
            buildString {
                appendLine("Не удалось получить итоговый синтез от главной модели.")
                peerResults.forEach { (peer, answer) ->
                    appendLine()
                    appendLine("--- ${peer.displayName} ---")
                    appendLine(answer)
                }
            }
        } else {
            finalAnswer
        }
    }

    private data class ConsultationPlan(
        val shouldConsult: Boolean,
        val providers: List<ProviderId>
    )

    private suspend fun requestConsultationPlan(
        context: Context,
        primaryProvider: ProviderId,
        history: List<Msg>,
        systemPrompt: String,
        availablePeers: List<ProviderId>
    ): ConsultationPlan {
        val providerNames = availablePeers.joinToString(", ") { it.name }
        val plannerPrompt = """
            $systemPrompt

            Ты также автономный координатор ИИ-офиса.
            Перед ответом оцени, нужна ли независимая проверка коллег.
            Для простого бытового вопроса отвечай самостоятельно.
            Для сложной аналитики, программирования, стратегического выбора,
            существенной неопределённости или проверки критичных выводов можно
            запросить до двух независимых мнений.
            Доступные коллеги: $providerNames.
            Верни только JSON без Markdown:
            {"consult":true,"providers":["OPENAI","DEEPSEEK"]}
            или {"consult":false,"providers":[]}
            Выбирай только из списка доступных коллег.
        """.trimIndent()

        val raw = callAiProvider(
            context,
            primaryProvider,
            history.takeLast(8),
            plannerPrompt
        )
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return ConsultationPlan(false, emptyList())

        return runCatching {
            val json = JSONObject(raw.substring(start, end + 1))
            val shouldConsult = json.optBoolean("consult", false)
            val requested = json.optJSONArray("providers") ?: JSONArray()
            val selected = buildList {
                for (index in 0 until requested.length()) {
                    val id = runCatching {
                        ProviderId.valueOf(requested.optString(index))
                    }.getOrNull()
                    if (id != null && id in availablePeers && id !in this) add(id)
                    if (size >= MAX_PEERS) break
                }
            }
            ConsultationPlan(shouldConsult, selected)
        }.getOrDefault(ConsultationPlan(false, emptyList()))
    }

    /** Prevent likely credentials from being forwarded to peer models. */
    private fun redactSecrets(value: String): String {
        var safe = value
        val patterns = listOf(
            Regex("(?i)\\b(sk-[A-Za-z0-9_-]{12,})\\b"),
            Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]{12,}"),
            Regex("(?i)(api[_ -]?key\\s*[:=]\\s*)[^\\s,;]{8,}"),
            Regex("(?i)(password\\s*[:=]\\s*)[^\\s,;]{4,}"),
            Regex("(?i)(пароль\\s*[:=]\\s*)[^\\s,;]{4,}")
        )
        patterns.forEach { pattern ->
            safe = pattern.replace(safe) { match ->
                val prefix = match.groups[1]?.value.orEmpty()
                prefix + "[СКРЫТО]"
            }
        }
        return safe
    }
}
