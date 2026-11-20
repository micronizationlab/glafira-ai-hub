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
internal val builtInProjects = listOf(
    Project("default", "Личное"),
    Project("vodorodaktiv", "ВодородАктив"),
    Project("token", "Токен"),
    Project("chimera", "Химера")
)

internal fun defaultProjects() = builtInProjects

internal fun projectStore(directory: File) = DurableJsonFile(
    File(directory, "projects.json"),
    decode = { raw ->
        val array = JSONArray(raw)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Project(item.getString("id"), item.getString("name")).also {
                require(it.id.isNotBlank() && it.name.isNotBlank() && it.id.none { ch -> ch == '/' || ch == '\\' || ch.isISOControl() })
            }
        }.also { projects ->
            require(projects.isNotEmpty())
            require(projects.map { it.id }.distinct().size == projects.size)
        }
    },
    encode = { projects: List<Project> -> JSONArray().apply {
        projects.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
    }.toString() }
)

internal fun deletedProjectStore(directory: File) = DurableJsonFile(
    File(directory, "deleted_projects.json"),
    decode = { raw -> JSONArray(raw).let { array -> (0 until array.length()).map { array.getString(it) }.toSet() } },
    encode = { ids: Set<String> -> JSONArray(ids.sorted()).toString() }
)

fun saveProjects(context: Context, projects: List<Project>) =
    projectStore(context.filesDir).write(projects.ifEmpty { defaultProjects() })

fun loadProjects(context: Context): List<Project> = GlafiraFileMaintenance.withExclusive {
    val store = projectStore(context.filesDir)
    val projects = store.read(::defaultProjects)
    val deleted = deletedProjectStore(context.filesDir).read { emptySet() }
    val missing = builtInProjects.filter { candidate -> candidate.id !in deleted && projects.none { it.id == candidate.id } }
    if (missing.isNotEmpty() || !File(context.filesDir, "projects.json").exists()) {
        store.update(::defaultProjects) { current ->
            current + missing.filter { candidate -> current.none { it.id == candidate.id } }
        }
    } else projects
}

fun updateProjects(context: Context, transform: (List<Project>) -> List<Project>): List<Project> =
    projectStore(context.filesDir).update(::defaultProjects) { transform(it).ifEmpty { defaultProjects() } }
