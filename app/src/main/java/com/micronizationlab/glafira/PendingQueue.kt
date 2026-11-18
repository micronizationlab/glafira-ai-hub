package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PendingMessage(
    val projectId: String,
    val agentName: String,
    val text: String,
    val time: String,
    val providerId: String = ProviderId.GIGACHAT.name
)

fun savePendingQueue(context: Context, queue: List<PendingMessage>) {
    val file = File(context.filesDir, "pending_queue.json")
    val arr = JSONArray()
    queue.forEach {
        arr.put(JSONObject().apply {
            put("projectId", it.projectId)
            put("agentName", it.agentName)
            put("text", it.text)
            put("time", it.time)
            put("providerId", it.providerId)
        })
    }
    file.writeText(arr.toString())
}

fun loadPendingQueue(context: Context): List<PendingMessage> {
    val file = File(context.filesDir, "pending_queue.json")
    if (!file.exists()) return emptyList()
    val list = mutableListOf<PendingMessage>()
    try {
        val arr = JSONArray(file.readText())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(PendingMessage(
                o.getString("projectId"),
                o.getString("agentName"),
                o.getString("text"),
                o.optString("time", ""),
                o.optString("providerId", ProviderId.GIGACHAT.name)
            ))
        }
    } catch (e: Exception) { }
    return list
}

fun addToPendingQueue(context: Context, msg: PendingMessage) {
    val queue = loadPendingQueue(context).toMutableList()
    queue.add(msg)
    savePendingQueue(context, queue)
}
