package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun savePinned(context: Context, key: String, indices: Set<Int>) {
    val file = File(context.filesDir, "pinned_$key.json")
    val arr = JSONArray()
    indices.forEach { arr.put(it) }
    file.writeText(arr.toString())
}

fun loadPinned(context: Context, key: String): Set<Int> {
    val file = File(context.filesDir, "pinned_$key.json")
    if (!file.exists()) return emptySet()
    return try {
        val arr = JSONArray(file.readText())
        val set = mutableSetOf<Int>()
        for (i in 0 until arr.length()) set.add(arr.getInt(i))
        set
    } catch (e: Exception) { emptySet() }
}
