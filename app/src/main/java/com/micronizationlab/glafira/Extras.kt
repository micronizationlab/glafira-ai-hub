package com.micronizationlab.glafira

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===== 10. Черновики сообщений =====
private fun draftKey(projectId: String, agentName: String): String {
    val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
    return "draft_v2_" + encoder.encodeToString(projectId.toByteArray(Charsets.UTF_8)) + "." + encoder.encodeToString(agentName.toByteArray(Charsets.UTF_8))
}

fun saveDraft(context: Context, projectId: String, agentName: String, text: String) = GlafiraFileMaintenance.withExclusive {
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    if (!prefs.edit().putString(draftKey(projectId, agentName), text).commit()) {
        recordStorageDiagnostic(context.filesDir, "glafira_prefs", "draft_commit_failed")
        ToastHelper.short(context, "Не удалось сохранить черновик: проверьте свободное место.")
    }
}

fun loadDraft(context: Context, projectId: String, agentName: String): String = GlafiraFileMaintenance.withExclusive {
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    val key = draftKey(projectId, agentName)
    if (prefs.contains(key)) prefs.getString(key, "") ?: ""
    else prefs.getString("draft_${projectId}_$agentName", "") ?: ""
}

fun clearDraft(context: Context, projectId: String, agentName: String) {
    // Empty value is a durable tombstone so a legacy key cannot resurrect a sent draft.
    saveDraft(context, projectId, agentName, "")
}

// ===== 8. Разделители дат =====
fun dateLabel(date: String): String {
    if (date.isBlank()) return "Ранее"
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(System.currentTimeMillis() - 86400000L))
    return when (date) {
        today -> "Сегодня"
        yesterday -> "Вчера"
        else -> {
            try {
                val d = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date)
                SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(d ?: Date())
            } catch (e: Exception) { date }
        }
    }
}

// ===== 11. Экспорт проекта целиком =====
fun exportProjectToFile(context: Context, project: Project, agentNames: List<String>): File {
    val sb = StringBuilder()
    sb.appendLine("ЭКСПОРТ ПРОЕКТА: ${project.name}")
    sb.appendLine("Дата: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())}")
    sb.appendLine("=".repeat(60))
    sb.appendLine()

    agentNames.forEach { agentName ->
        val key = "${project.id}_$agentName"
        val history = loadHistory(context, key)
        if (history.isNotEmpty()) {
            sb.appendLine("--- ЧАТ С $agentName (${history.size} сообщений) ---")
            history.forEach { msg ->
                val author = if (msg.isUser) "Вы" else agentName
                sb.appendLine("[${msg.time}] $author: ${msg.text}")
            }
            sb.appendLine()
            sb.appendLine("=".repeat(60))
            sb.appendLine()
        }
    }

    val file = File(context.filesDir, "project_${project.name}_${System.currentTimeMillis()}.txt")
    file.writeText(sb.toString())
    return file
}

// ===== 12. Массовое удаление истории проекта =====
fun deleteProjectHistory(context: Context, projectId: String): Int {
    val files = context.filesDir.listFiles().orEmpty()
    val historyKeys = files.map { it.name.substringBefore(".json") }
        .filter { it.startsWith("chat_${projectId}_") }.distinct().map { it.removePrefix("chat_") }
    historyKeys.forEach { deleteHistory(context, it) }
    val pinned = files.filter { it.name.startsWith("pinned_${projectId}_") }
    pinned.forEach { check(!it.exists() || it.delete()) { "Cannot delete ${it.name}" } }
    return historyKeys.size + pinned.size
}

// ===== 6. Очистка чата =====
fun clearChat(context: Context, historyKey: String): Boolean = try {
    deleteHistory(context, historyKey)
    context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("pinned_$historyKey.json") }.forEach {
        check(!it.exists() || it.delete()) { "Cannot delete ${it.name}" }
    }
    true
} catch (error: Exception) {
    recordStorageDiagnostic(context.filesDir, "chat_$historyKey.json", "clear_${error.javaClass.simpleName}")
    false
}

// ===== 13. Сворачивание длинных сообщений =====
const val COLLAPSE_THRESHOLD = 500

fun isLongMessage(text: String): Boolean = text.length > COLLAPSE_THRESHOLD

fun collapsedText(text: String): String = text.take(COLLAPSE_THRESHOLD) + "…"
