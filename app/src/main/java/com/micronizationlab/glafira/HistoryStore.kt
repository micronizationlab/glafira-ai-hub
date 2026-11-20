package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun historyStore(directory: File, key: String) = DurableJsonFile(
    storageFile(directory, "chat_$key.json"),
    decode = { raw ->
        val array = JSONArray(raw)
        List(array.length()) { index ->
            val o = array.getJSONObject(index)
            Msg(o.getString("text"), o.getBoolean("isUser"), o.optString("time", ""), o.optString("date", ""), o.optString("operationId", ""))
        }
    },
    encode = { history: List<Msg> -> JSONArray().apply {
        history.forEach { put(JSONObject().apply {
            put("text", it.text); put("isUser", it.isUser); put("time", it.time); put("date", it.date)
            if (it.operationId.isNotEmpty()) put("operationId", it.operationId)
        }) }
    }.toString() }
)

/** For deliberate whole-history replacement only; appending callers must use appendHistory. */
fun saveHistory(context: Context, key: String, history: List<Msg>) = historyStore(context.filesDir, key).write(history)
fun loadHistory(context: Context, key: String): List<Msg> = historyStore(context.filesDir, key).read { emptyList() }
fun appendHistory(context: Context, key: String, messages: List<Msg>): List<Msg> =
    appendHistory(context.filesDir, key, messages)

internal fun appendHistory(directory: File, key: String, messages: List<Msg>): List<Msg> =
    historyStore(directory, key).update({ emptyList() }) { current ->
        val result = current.toMutableList()
        messages.forEach { message ->
            if (message.operationId.isEmpty() || result.none { it.operationId == message.operationId && it.isUser == message.isUser }) result.add(message)
        }
        result
    }
