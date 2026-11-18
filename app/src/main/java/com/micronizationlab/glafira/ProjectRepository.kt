package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistence boundary for project metadata.
 * UI and project-management code can keep using the compatibility functions
 * while storage moves from JSON files to Room in a later migration.
 */
interface ProjectRepository {
    fun loadProjects(): List<Project>
    fun saveProjects(projects: List<Project>)
}

class JsonProjectRepository(context: Context) : ProjectRepository {
    private val projectsFile = File(context.filesDir, "projects.json")

    override fun saveProjects(projects: List<Project>) {
        val jsonArray = JSONArray()
        projects.forEach { project ->
            jsonArray.put(JSONObject().apply {
                put("id", project.id)
                put("name", project.name)
            })
        }
        projectsFile.writeText(jsonArray.toString())
    }

    override fun loadProjects(): List<Project> {
        if (!projectsFile.exists()) return createDefaultProjects()

        val loaded = try {
            val jsonArray = JSONArray(projectsFile.readText())
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(index)
                    add(Project(item.getString("id"), item.getString("name")))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        return if (loaded.isEmpty()) createDefaultProjects() else loaded
    }

    private fun createDefaultProjects(): List<Project> {
        val defaults = listOf(Project("default", "Личное"))
        saveProjects(defaults)
        return defaults
    }
}
