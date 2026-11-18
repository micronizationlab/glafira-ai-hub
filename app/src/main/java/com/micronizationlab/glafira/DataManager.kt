package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Project(val id: String, val name: String)

fun saveProjects(context: Context, projects: List<Project>) {
    val file = File(context.filesDir, "projects.json")
    val jsonArray = JSONArray()
    projects.forEach {
        jsonArray.put(JSONObject().apply {
            put("id", it.id)
            put("name", it.name)
        })
    }
    file.writeText(jsonArray.toString())
}

fun loadProjects(context: Context): List<Project> {
    val file = File(context.filesDir, "projects.json")
    if (!file.exists()) {
        // Если файла нет, создаем дефолтный проект
        val defaultList = listOf(Project("default", "Личное"))
        saveProjects(context, defaultList)
        return defaultList
    }
    val list = mutableListOf<Project>()
    try {
        val jsonArray = JSONArray(file.readText())
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(Project(obj.getString("id"), obj.getString("name")))
        }
    } catch (e: Exception) { }
    return list
}
