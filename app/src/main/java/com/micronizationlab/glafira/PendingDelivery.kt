package com.micronizationlab.glafira

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Calls outlive a chat destination. Process death is recovered through the durable outbox. */
internal object PendingDeliveryRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatchLock = Mutex()

    fun dispatch(context: Context, pending: PendingMessage, prompt: String) {
        val app = context.applicationContext
        scope.launch { dispatchLock.withLock { executePendingMessage(app, pending, prompt) } }
    }

    suspend fun replay(context: Context) {
        val app = context.applicationContext
        scope.async { dispatchLock.withLock { replayPendingMessages(app) } }.await()
    }

    suspend fun <T> withPaused(block: () -> T): T = dispatchLock.withLock { block() }
}

internal fun appendPendingUserMessage(context: Context, message: PendingMessage): List<Msg> =
    historyStore(context.filesDir, "${message.projectId}_${message.agentName}").update({ emptyList() }) { current ->
        if (current.any { it.operationId == message.id && it.isUser }) current
        else {
            // The old queue already wrote its user message before enqueueing. Adopt that row.
            val legacyIndex = current.indexOfLast { it.isUser && it.operationId.isEmpty() && it.text == message.text && it.time == message.time }
            if (legacyIndex >= 0 && message.date.isEmpty()) current.mapIndexed { index, row -> if (index == legacyIndex) row.copy(operationId = message.id) else row }
            else current + Msg(message.text, true, message.time, message.date, message.id)
        }
    }

private fun deliverSavedResult(context: Context, message: PendingMessage) {
    val result = message.result ?: return
    appendPendingUserMessage(context, message)
    val now = Date()
    appendHistory(context, "${message.projectId}_${message.agentName}", listOf(Msg(
        result, false, SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now), message.id
    )))
    PendingMessageStore(context.filesDir).delivered(message.id)
}

/** Cancellation after a remote request starts is ambiguous; keep it quarantined until owner review. */
internal fun markPendingCancellationUncertain(store: PendingMessageStore, operationId: String) {
    if (store.all().firstOrNull { it.id == operationId }?.state == PendingDeliveryState.IN_FLIGHT) {
        store.failed(
            operationId,
            "Запрос прерван. Результат у провайдера неизвестен.",
            retryable = false,
            unknown = true
        )
    }
}

internal suspend fun executePendingMessage(context: Context, pending: PendingMessage, prompt: String) {
    if (isPendingDispatchBlocked(context)) return
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
    val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
    // A disconnected phone keeps the outbox durable without exhausting provider retry attempts.
    if (capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) != true ||
        !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
    val store = PendingMessageStore(context.filesDir)
    val claimed = withContext(Dispatchers.IO) { store.claim(pending.id) } ?: return
    try {
        val history = withContext(Dispatchers.IO) { appendPendingUserMessage(context, claimed) }
        val provider = runCatching { ProviderId.valueOf(claimed.providerId) }.getOrDefault(ProviderId.GIGACHAT)
        val reply = if (claimed.agentName == "ГЛАФИРА")
            GlafiraCoordinator.respondResult(context, provider, history, claimed.systemPrompt ?: prompt, claimed.projectId)
        else callAiProviderResult(context, provider, history, claimed.systemPrompt ?: prompt)
        // Once the response arrives, navigation/cancellation must not discard it.
        withContext(NonCancellable + Dispatchers.IO) {
            when (reply) {
                is AiProviderResult.Success -> {
                    store.result(claimed.id, reply.text)
                    deliverSavedResult(context, claimed.copy(state = PendingDeliveryState.RESULT_READY, result = reply.text))
                }
                is AiProviderResult.Failure -> {
                    val partial = reply.takeIf { it.error.partialResults.isNotEmpty() }?.userText()
                    store.failed(
                        claimed.id, partial ?: reply.error.message, reply.error.canRetryAutomatically,
                        reply.error.outcome == AiRequestOutcome.UNKNOWN, reply.error.retryAfterMillis, partial
                    )
                    if (partial != null) appendPartialResult(context, claimed.copy(result = partial))
                }
            }
        }
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable + Dispatchers.IO) {
            markPendingCancellationUncertain(store, claimed.id)
        }
        throw cancelled
    } catch (error: Exception) {
        withContext(NonCancellable + Dispatchers.IO) {
            // Do not downgrade a saved result if rendering/acknowledgement failed.
            if (store.all().firstOrNull { it.id == claimed.id }?.state == PendingDeliveryState.IN_FLIGHT)
                store.failed(claimed.id, "Не удалось подтвердить результат. Проверьте запрос перед повтором.", false, true)
            recordStorageDiagnostic(context.filesDir, "pending_queue.json", "delivery_${error.javaClass.simpleName}")
        }
    }
}

private fun appendPartialResult(context: Context, message: PendingMessage) {
    val partial = message.result ?: return
    appendHistory(context, "${message.projectId}_${message.agentName}", listOf(Msg(
        partial, false, "", message.date, "${message.id}:partial"
    )))
}

internal suspend fun replayPendingMessages(context: Context) {
    if (isPendingDispatchBlocked(context)) return
    val store = PendingMessageStore(context.filesDir)
    val queue = withContext(Dispatchers.IO) { store.all() }
    queue.forEach { message ->
        if (message.state == PendingDeliveryState.UNCERTAIN && message.result != null) {
            withContext(Dispatchers.IO) { appendPartialResult(context, message) }
        } else if (message.state == PendingDeliveryState.RESULT_READY) {
            withContext(Dispatchers.IO) { deliverSavedResult(context, message) }
        } else if (message.state == PendingDeliveryState.QUEUED && message.nextAttemptAt <= System.currentTimeMillis()) {
            val prompt = when (message.agentName) {
                "ГЛАФИРА" -> "Ты — Глафира, главный координатор проектов. Помогай структурировать задачи и управлять проектами."
                "АНАЛИТИК" -> "Ты — Аналитик. Глубоко анализируй данные, находи закономерности и давай объективные выводы."
                "ПРОГРАММИСТ" -> "Ты — Программист. Пиши код, находи ошибки и предлагай архитектурные решения."
                else -> "Ты — помощник. Отвечай по делу на русском языке."
            }
            executePendingMessage(context, message, prompt)
        }
    }
}

/** A process may have died between restore commit phases; no remote operation may use that snapshot. */
internal fun isPendingDispatchBlocked(context: Context): Boolean =
    File(context.noBackupFilesDir, "glafira-restore-journal/restore.pending").exists()
