package com.micronizationlab.glafira

import android.content.Context

@Suppress("UNUSED_PARAMETER")
fun deleteProject(context: Context, project: Project, allProjects: List<Project>): List<Project> {
    val remaining = updateProjects(context) { projects -> projects.filter { it.id != project.id } }
    deleteProjectHistory(context, project.id)
    PendingMessageStore(context.filesDir).all().filter { it.projectId == project.id }.forEach {
        PendingMessageStore(context.filesDir).cancel(it.id)
    }
    return remaining
}
