package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Project(val id: String, val name: String)

/**
 * Built-in GLAFIRA workspaces. Keep stable IDs so existing chat histories
 * remain attached to their projects across app upgrades.
 */
private val builtInProjects = listOf(
    Project("default", "Личное"),
    Project("vodorodaktiv", "ВодородАктив"),
    Project("token", "Токен"),
    Project("chimera", "Химера")
)

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
        // Fresh installs receive all standard workspaces.
        saveProjects(context, builtInProjects)
        return builtInProjects
    }

    val stored = mutableListOf<Project>()
    try {
        val jsonArray = JSONArray(file.readText())
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            stored.add(Project(obj.getString("id"), obj.getString("name")))
        }
    } catch (e: Exception) {
        // Preserve the existing recovery behavior: malformed project data
        // must not prevent the app from opening.
    }

    // Upgrade existing installations without deleting, renaming or reordering
    // user projects. Add only missing built-in IDs.
    val knownIds = stored.mapTo(mutableSetOf()) { it.id }
    val missingBuiltIns = builtInProjects.filter { it.id !in knownIds }
    if (missingBuiltIns.isNotEmpty()) {
        stored.addAll(missingBuiltIns)
        saveProjects(context, stored)
    }
    return stored
}
