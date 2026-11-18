package com.micronizationlab.glafira

import android.content.Context
import java.io.File

fun deleteProject(context: Context, project: Project, allProjects: List<Project>): List<Project> {
    // Удаляем файлы истории, связанные с этим проектом
    val files = context.filesDir.listFiles()
    files?.forEach { file ->
        if (file.name.startsWith("chat_${project.id}_")) {
            file.delete()
        }
    }
    
    // Возвращаем обновленный список проектов
    val updatedProjects = allProjects.filter { it.id != project.id }
    saveProjects(context, updatedProjects)
    
    return updatedProjects
}
