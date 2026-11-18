package com.micronizationlab.glafira

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===== 10. Черновики сообщений =====
fun saveDraft(context: Context, projectId: String, agentName: String, text: String) {
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("draft_${projectId}_$agentName", text).apply()
}

fun loadDraft(context: Context, projectId: String, agentName: String): String {
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    return prefs.getString("draft_${projectId}_$agentName", "") ?: ""
}

fun clearDraft(context: Context, projectId: String, agentName: String) {
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    prefs.edit().remove("draft_${projectId}_$agentName").apply()
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
    var count = 0
    val files = context.filesDir.listFiles()
    files?.forEach { file ->
        if (file.name.startsWith("chat_${projectId}_") || file.name.startsWith("pinned_${projectId}_")) {
            if (file.delete()) count++
        }
    }
    return count
}

// ===== 6. Очистка чата =====
fun clearChat(context: Context, historyKey: String): Boolean {
    val chatFile = File(context.filesDir, "chat_$historyKey.json")
    val pinnedFile = File(context.filesDir, "pinned_$historyKey.json")
    var ok = true
    if (chatFile.exists()) ok = chatFile.delete() && ok
    if (pinnedFile.exists()) ok = pinnedFile.delete() && ok
    return ok
}

// ===== 13. Сворачивание длинных сообщений =====
const val COLLAPSE_THRESHOLD = 500

fun isLongMessage(text: String): Boolean = text.length > COLLAPSE_THRESHOLD

fun collapsedText(text: String): String = text.take(COLLAPSE_THRESHOLD) + "…"
