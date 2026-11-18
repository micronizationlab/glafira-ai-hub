package com.micronizationlab.glafira

import android.content.Context

data class Project(val id: String, val name: String)

/**
 * Compatibility facade. Existing call sites remain stable while persistence
 * is delegated to the repository implementation.
 */
fun saveProjects(context: Context, projects: List<Project>) {
    JsonProjectRepository(context).saveProjects(projects)
}

fun loadProjects(context: Context): List<Project> =
    JsonProjectRepository(context).loadProjects()
