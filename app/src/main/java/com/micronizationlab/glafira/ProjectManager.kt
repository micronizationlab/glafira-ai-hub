package com.micronizationlab.glafira

import android.content.Context
import java.util.Base64

suspend fun deleteProject(context: Context, project: Project): List<Project> =
    GlafiraCoreMaintenanceGate.withExclusive {
        PendingDeliveryRuntime.withPaused {
            GlafiraFileMaintenance.withExclusive {
                val current = loadProjects(context)
                require(current.any { it.id == project.id }) { "Проект уже удалён." }
                require(current.size > 1) { "Нельзя удалить последний проект." }
                GlafiraCoreRuntimeOwner.get(context).taskStore.deleteFinishedProject(project.id)
                PendingMessageStore(context.filesDir).deleteProject(project.id)
                deleteProjectHistory(context, project.id)
                val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
                val encodedId = Base64.getUrlEncoder().withoutPadding().encodeToString(project.id.toByteArray(Charsets.UTF_8))
                val editor = prefs.edit()
                prefs.all.keys.filter {
                    it.startsWith("draft_${project.id}_") || it.startsWith("draft_v2_$encodedId.") ||
                        it == "peer_consultation_enabled_${project.id}" || it == "peer_consultation_providers_${project.id}"
                }.forEach(editor::remove)
                check(editor.commit()) { "Не удалось удалить настройки проекта." }
                deletedProjectStore(context.filesDir).update({ emptySet() }) { it + project.id }
                updateProjects(context) { projects -> projects.filterNot { it.id == project.id } }
            }
        }
    }
