package com.micronizationlab.glafira

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.io.File

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("GLAFIRA", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}

fun buildChatExportText(projectName: String, agentName: String, messages: List<Msg>): String = buildString {
    appendLine("Экспорт чата с $agentName (Проект: $projectName)")
    appendLine("=".repeat(40))
    messages.forEach { msg ->
        val author = if (msg.isUser) "Вы" else agentName
        appendLine("[${msg.date} ${msg.time}] $author: ${msg.text}")
    }
}

fun suggestedChatExportFileName(projectName: String, agentName: String): String {
    fun safe(value: String): String = value.trim()
        .replace(Regex("[^A-Za-z0-9А-Яа-яЁё._-]+"), "_")
        .trim('_', '.', '-')
        .take(40)
        .ifBlank { "chat" }
    return "GLAFIRA_${safe(projectName)}_${safe(agentName)}.txt"
}
