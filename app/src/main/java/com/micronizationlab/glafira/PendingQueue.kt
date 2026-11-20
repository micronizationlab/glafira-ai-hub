package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class PendingDeliveryState { QUEUED, IN_FLIGHT, UNCERTAIN, RESULT_READY, COMPLETED, FAILED, CANCELLED }

data class PendingMessage(
    val projectId: String,
    val agentName: String,
    val text: String,
    val time: String,
    val providerId: String = ProviderId.GIGACHAT.name,
    val id: String = UUID.randomUUID().toString(),
    val date: String = "",
    val state: PendingDeliveryState = PendingDeliveryState.QUEUED,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val session: String = "",
    val result: String? = null,
    val error: String? = null
)

/** Durable outbox: a crashed request has an unknown outcome, never an automatic retry. */
internal class PendingMessageStore(directory: File, private val sessionId: String = processSession) {
    private val store = DurableJsonFile(File(directory, "pending_queue.json"), ::decode, ::encode) { previous ->
        previous.map { record ->
            // A previous generation can predate a successful remote call. Never blindly replay it.
            if (record.state == PendingDeliveryState.QUEUED || record.state == PendingDeliveryState.IN_FLIGHT)
                record.copy(state = PendingDeliveryState.UNCERTAIN, error = "Очередь восстановлена из резервной копии. Проверьте выполнение перед повтором.")
            else record
        }
    }

    fun recoverAfterRestore() = store.update({ emptyList() }) { queue -> queue.map {
        if (it.state == PendingDeliveryState.IN_FLIGHT || it.state == PendingDeliveryState.QUEUED)
            it.copy(state = PendingDeliveryState.UNCERTAIN, error = "Восстановленный запрос требует проверки: результат у провайдера неизвестен.")
        else it
    } }

    fun all(): List<PendingMessage> {
        val records = store.read { emptyList() }
        if (records.none { it.state == PendingDeliveryState.IN_FLIGHT && it.session != sessionId }) return records
        return store.update({ emptyList() }) { queue -> queue.map {
            if (it.state == PendingDeliveryState.IN_FLIGHT && it.session != sessionId)
                it.copy(state = PendingDeliveryState.UNCERTAIN, error = "Запрос прерван. Результат на стороне провайдера неизвестен; повторите только после проверки.")
            else it
        } }
    }

    fun add(message: PendingMessage): PendingMessage {
        val queue = store.update({ emptyList() }) { current ->
            if (current.any { it.id == message.id }) current else current + message
        }
        return queue.first { it.id == message.id }
    }

    fun claim(id: String, now: Long = System.currentTimeMillis()): PendingMessage? {
        var claimed: PendingMessage? = null
        store.update({ emptyList() }) { current -> current.map {
            if (it.id == id && it.state == PendingDeliveryState.QUEUED && it.nextAttemptAt <= now) {
                it.copy(state = PendingDeliveryState.IN_FLIGHT, attempts = it.attempts + 1, session = sessionId).also { value -> claimed = value }
            } else it
        } }
        return claimed
    }

    fun result(id: String, text: String) = change(id) { it.copy(state = PendingDeliveryState.RESULT_READY, result = text, error = null) }
    fun delivered(id: String) = change(id) { it.copy(state = PendingDeliveryState.COMPLETED) }
    fun deleteProject(projectId: String) = store.update({ emptyList() }) { queue ->
        check(queue.none { it.projectId == projectId && it.state == PendingDeliveryState.IN_FLIGHT }) {
            "Дождитесь завершения запросов проекта."
        }
        queue.filterNot { it.projectId == projectId }
    }
    fun failed(id: String, message: String, retryable: Boolean, unknown: Boolean, retryAfterMillis: Long? = null, partialResult: String? = null) = change(id) {
        val retry = retryable && !unknown && it.attempts < MAX_ATTEMPTS
        it.copy(
            state = if (unknown) PendingDeliveryState.UNCERTAIN else if (retry) PendingDeliveryState.QUEUED else PendingDeliveryState.FAILED,
            error = message,
            result = partialResult ?: it.result,
            nextAttemptAt = if (retry) System.currentTimeMillis() + (retryAfterMillis ?: (1000L shl it.attempts.coerceAtMost(8))) else 0
        )
    }
    fun cancel(id: String) = change(id) {
        if (it.state == PendingDeliveryState.IN_FLIGHT || it.state == PendingDeliveryState.COMPLETED || it.state == PendingDeliveryState.RESULT_READY) it
        else it.copy(state = PendingDeliveryState.CANCELLED)
    }
    /** Only an explicit user action should call this: the previous remote request may have succeeded. */
    fun retry(id: String) = change(id) {
        if (it.state == PendingDeliveryState.UNCERTAIN || it.state == PendingDeliveryState.FAILED)
            it.copy(state = PendingDeliveryState.QUEUED, attempts = 0, nextAttemptAt = 0, session = "", error = null, result = null)
        else it
    }
    private fun change(id: String, transform: (PendingMessage) -> PendingMessage) =
        store.update({ emptyList() }) { current -> current.map { if (it.id == id) transform(it) else it } }

    companion object {
        const val MAX_ATTEMPTS = 5
        private val processSession = UUID.randomUUID().toString()
        private fun decode(raw: String): List<PendingMessage> {
            val array = JSONArray(raw)
            return List(array.length()) { index ->
                val o = array.getJSONObject(index)
                // Deterministic migration preserves operation identity until the first durable update.
                val id = o.optString("id").ifBlank { UUID.nameUUIDFromBytes((index.toString() + ":" + o.toString()).toByteArray()).toString() }
                PendingMessage(
                    o.getString("projectId"), o.getString("agentName"), o.getString("text"),
                    o.optString("time", ""), o.optString("providerId", ProviderId.GIGACHAT.name), id,
                    o.optString("date", ""), PendingDeliveryState.valueOf(o.optString("state", "UNCERTAIN")),
                    o.optInt("attempts", 0), o.optLong("nextAttemptAt", 0), o.optString("session", ""),
                    if (o.isNull("result")) null else o.getString("result"),
                    if (!o.has("state")) "Запрос из старой очереди: проверьте выполнение перед повтором." else if (o.isNull("error")) null else o.getString("error")
                ).also { require(it.id.isNotBlank() && it.projectId.isNotBlank() && it.agentName.isNotBlank() && it.attempts >= 0)
                    require((it.projectId + it.agentName).none { char -> char == '/' || char == '\\' || char.isISOControl() }) }
            }.also { require(it.map { record -> record.id }.distinct().size == it.size) }
        }
        private fun encode(queue: List<PendingMessage>): String = JSONArray().apply {
            queue.forEach { message -> put(JSONObject().apply {
                put("projectId", message.projectId); put("agentName", message.agentName); put("text", message.text)
                put("time", message.time); put("providerId", message.providerId); put("id", message.id); put("date", message.date)
                put("state", message.state.name); put("attempts", message.attempts); put("nextAttemptAt", message.nextAttemptAt)
                put("session", message.session); put("result", message.result ?: JSONObject.NULL); put("error", message.error ?: JSONObject.NULL)
            }) }
        }.toString()
    }
}

fun loadPendingQueue(context: Context): List<PendingMessage> = PendingMessageStore(context.filesDir).all()
fun addToPendingQueue(context: Context, msg: PendingMessage) { PendingMessageStore(context.filesDir).add(msg) }

/** Compatibility API merges by identity so a stale snapshot cannot erase concurrent enqueues. */
fun savePendingQueue(context: Context, queue: List<PendingMessage>) {
    val store = PendingMessageStore(context.filesDir)
    queue.forEach { store.add(it) }
}

/** A historical archive cannot prove that a queued operation has not since completed. */
fun recoverPendingAfterRestore(context: Context) { PendingMessageStore(context.filesDir).recoverAfterRestore() }
