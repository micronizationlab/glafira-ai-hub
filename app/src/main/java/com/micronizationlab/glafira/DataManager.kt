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
    val deleted = deletedProjectStore(context.filesDir).read { emptySet() }
    val fallback = defaultProjects().filterNot { it.id in deleted }.ifEmpty {
        listOf(Project("recovered-${java.util.UUID.randomUUID()}", "Восстановленный проект"))
    }
    val stored = store.read { fallback }
    // If a built-in workspace was accidentally renamed to another built-in's name,
    // restore its canonical label while retaining its stable ID and all chat files.
    val normalizedStored = stored.map { project ->
        val canonical = builtInProjects.firstOrNull { it.id == project.id }
        val collidesWithAnotherBuiltIn = builtInProjects.any { candidate ->
            candidate.id != project.id && candidate.name.equals(project.name, ignoreCase = true)
        }
        if (canonical != null && collidesWithAnotherBuiltIn) project.copy(name = canonical.name) else project
    }
    val projects = normalizedStored.filterNot { it.id in deleted }.ifEmpty { fallback }
    val missing = builtInProjects.filter { candidate -> candidate.id !in deleted && projects.none { it.id == candidate.id } }
    if (normalizedStored != stored || missing.isNotEmpty() || projects != normalizedStored || !File(context.filesDir, "projects.json").exists()) {
        store.update({ fallback }) { current ->
            val repaired = current.map { project ->
                val canonical = builtInProjects.firstOrNull { it.id == project.id }
                val collidesWithAnotherBuiltIn = builtInProjects.any { candidate ->
                    candidate.id != project.id && candidate.name.equals(project.name, ignoreCase = true)
                }
                if (canonical != null && collidesWithAnotherBuiltIn) project.copy(name = canonical.name) else project
            }
            val kept = repaired.filterNot { it.id in deleted }.ifEmpty { fallback }
            kept + missing.filter { candidate -> kept.none { it.id == candidate.id } }
        }
    } else projects
}

fun updateProjects(context: Context, transform: (List<Project>) -> List<Project>): List<Project> =
    projectStore(context.filesDir).update(::defaultProjects) { transform(it).ifEmpty { defaultProjects() } }


/**
 * Creates a workspace only when its normalized display name is not already in use.
 * Returning the existing stable-ID workspace prevents a user-created duplicate of
 * built-in projects such as "Личное" and "ВодородАктив".
 */
fun createOrSelectProject(context: Context, name: String): Project {
    val normalizedName = name.trim()
    require(normalizedName.isNotBlank()) { "Укажите название проекта." }
    var selected: Project? = null
    updateProjects(context) { current ->
        val existing = current.firstOrNull { project ->
            project.id in builtInProjects.map { it.id } &&
                project.name.trim().equals(normalizedName, ignoreCase = true)
        } ?: current.firstOrNull {
            it.name.trim().equals(normalizedName, ignoreCase = true)
        }
        if (existing != null) {
            selected = existing
            current
        } else {
            Project(java.util.UUID.randomUUID().toString(), normalizedName).also {
                selected = it
            }.let { current + it }
        }
    }
    return checkNotNull(selected) { "Не удалось создать или выбрать проект." }
}
