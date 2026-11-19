package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Project(val id: String, val name: String)

internal fun projectStore(directory: File) = DurableJsonFile(
    File(directory, "projects.json"),
    decode = { raw ->
        val array = JSONArray(raw)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Project(item.getString("id"), item.getString("name")).also {
                require(it.id.isNotBlank() && it.name.isNotBlank() && it.id.none { char -> char == '/' || char == '\\' || char.isISOControl() })
            }
        }.also { projects ->
            require(projects.isNotEmpty()) { "Projects must contain a default project" }
            require(projects.map { it.id }.distinct().size == projects.size)
        }
    },
    encode = { projects: List<Project> -> JSONArray().apply {
        projects.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
    }.toString() }
)

internal fun defaultProjects() = listOf(Project("default", "Личное"))
fun saveProjects(context: Context, projects: List<Project>) = projectStore(context.filesDir).write(projects.ifEmpty { defaultProjects() })
fun loadProjects(context: Context): List<Project> = projectStore(context.filesDir).read { defaultProjects() }
fun updateProjects(context: Context, transform: (List<Project>) -> List<Project>): List<Project> =
    projectStore(context.filesDir).update(::defaultProjects) { transform(it).ifEmpty { defaultProjects() } }
