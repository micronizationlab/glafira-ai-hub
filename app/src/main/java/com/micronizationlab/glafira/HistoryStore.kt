package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

private const val HISTORY_FORMAT = "GLAFIRA_ENCRYPTED_HISTORY"
private const val HISTORY_FORMAT_VERSION = 1

/** Device-encrypted conversation history with an in-place legacy plaintext migration. */
internal class HistoryStore(
    directory: File,
    private val key: String,
    private val protector: MemoryPayloadProtector? = null
) {
    private val file = storageFile(directory, "chat_$key.json")
    private val backup = File(file.path + ".bak")
    private val payloadId = MemoryIdentifier("chat-history:$key")
    private val store = DurableJsonFile(file, decode = ::decodeStoredHistory, encode = ::encodeStoredHistory)

    fun read(default: () -> List<Msg>): List<Msg> {
        val corruptBefore = corruptGenerationCount()
        val migrationRequired = protector != null && listOf(file, backup).any(::isPlaintextHistory)
        val value = if (migrationRequired) store.update(default) { it } else store.read(default)
        check(corruptGenerationCount() == corruptBefore || file.exists() || backup.exists()) {
            "Conversation history cannot be decrypted with the active device key."
        }
        check(!migrationRequired || listOf(file, backup).none(::isPlaintextHistory)) {
            "Plaintext conversation history remained after migration."
        }
        return value
    }

    fun write(value: List<Msg>) = store.write(value)
    fun update(default: () -> List<Msg>, transform: (List<Msg>) -> List<Msg>): List<Msg> =
        store.update(default, transform)
    fun delete() = store.delete()

    private fun decodeStoredHistory(raw: String): List<Msg> {
        if (raw.trimStart().startsWith("[")) return decodeHistoryJson(raw)
        val activeProtector = requireNotNull(protector) { "Encrypted history requires a payload protector." }
        val envelope = JSONObject(raw)
        require(envelope.getString("format") == HISTORY_FORMAT) { "Unknown history envelope." }
        require(envelope.getInt("version") == HISTORY_FORMAT_VERSION) { "Unsupported history version." }
        val plaintext = activeProtector.unprotect(payloadId, envelope.getString("payload"))
        return try { decodeHistoryJson(String(plaintext, Charsets.UTF_8)) }
        finally { plaintext.fill(0) }
    }

    private fun encodeStoredHistory(history: List<Msg>): String {
        val raw = encodeHistoryJson(history)
        val activeProtector = protector ?: return raw
        val plaintext = raw.toByteArray(Charsets.UTF_8)
        return try {
            JSONObject().put("format", HISTORY_FORMAT).put("version", HISTORY_FORMAT_VERSION)
                .put("payload", activeProtector.protect(payloadId, plaintext)).toString()
        } finally { plaintext.fill(0) }
    }

    private fun corruptGenerationCount(): Int = file.parentFile!!.listFiles().orEmpty().count {
        it.name.startsWith(file.name + ".corrupt-") || it.name.startsWith(file.name + ".bak.corrupt-")
    }

    private fun isPlaintextHistory(candidate: File): Boolean {
        if (!candidate.exists()) return false
        return runCatching {
            candidate.bufferedReader().use { reader ->
                generateSequence { reader.read().takeIf { it >= 0 } }.firstOrNull { !it.toChar().isWhitespace() }
            } == '['.code
        }.getOrDefault(false)
    }
}

internal object HistoryCryptoRuntime {
    @Volatile private var testOverride: MemoryPayloadProtector? = null
    @Volatile private var production: MemoryPayloadProtector? = null

    fun protector(): MemoryPayloadProtector = testOverride ?: production ?: synchronized(this) {
        testOverride ?: production ?: AndroidKeystoreMemoryPayloadProtector("glafira.chat-history.v1")
            .also { production = it }
    }

    internal fun setProtectorForTesting(value: MemoryPayloadProtector?) { testOverride = value }
}

internal fun historyStore(directory: File, key: String) = HistoryStore(directory, key)
internal fun encryptedHistoryStore(directory: File, key: String, protector: MemoryPayloadProtector) =
    HistoryStore(directory, key, protector)
private fun protectedHistoryStore(context: Context, key: String) =
    encryptedHistoryStore(context.filesDir, key, HistoryCryptoRuntime.protector())

/** For deliberate whole-history replacement only; appending callers must use appendHistory. */
fun saveHistory(context: Context, key: String, history: List<Msg>) = protectedHistoryStore(context, key).write(history)
fun loadHistory(context: Context, key: String): List<Msg> = protectedHistoryStore(context, key).read { emptyList() }
fun appendHistory(context: Context, key: String, messages: List<Msg>): List<Msg> =
    appendHistory(protectedHistoryStore(context, key), messages)
internal fun updateHistory(context: Context, key: String, transform: (List<Msg>) -> List<Msg>): List<Msg> =
    protectedHistoryStore(context, key).update({ emptyList() }, transform)
internal fun deleteHistory(context: Context, key: String) = historyStore(context.filesDir, key).delete()

internal fun appendHistory(directory: File, key: String, messages: List<Msg>): List<Msg> =
    appendHistory(historyStore(directory, key), messages)

private fun appendHistory(store: HistoryStore, messages: List<Msg>): List<Msg> =
    store.update({ emptyList() }) { current ->
        val result = current.toMutableList()
        messages.forEach { message ->
            if (message.operationId.isEmpty() || result.none { it.operationId == message.operationId && it.isUser == message.isUser }) result.add(message)
        }
        result
    }

internal fun encodeHistoryJson(history: List<Msg>): String = JSONArray().apply {
    history.forEach { put(JSONObject().apply {
        put("text", it.text); put("isUser", it.isUser); put("time", it.time); put("date", it.date)
        if (it.operationId.isNotEmpty()) put("operationId", it.operationId)
    }) }
}.toString()

internal fun decodeHistoryJson(raw: String): List<Msg> {
    val array = JSONArray(raw)
    return List(array.length()) { index ->
        val value = array.getJSONObject(index)
        Msg(value.getString("text"), value.getBoolean("isUser"), value.optString("time", ""),
            value.optString("date", ""), value.optString("operationId", ""))
    }
}

internal fun portableHistoryEntryName(key: String): String =
    "portable-history/" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(key.toByteArray(Charsets.UTF_8)) + ".json"

internal fun historyKeyFromPortableEntry(path: String): String {
    require(path.startsWith("portable-history/") && path.endsWith(".json"))
    val key = String(Base64.getUrlDecoder().decode(
        path.removePrefix("portable-history/").removeSuffix(".json")
    ), Charsets.UTF_8)
    storageFile(File("."), "chat_$key.json")
    return key
}
