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

fun exportChatToFile(context: Context, projectName: String, agentName: String, messages: List<Msg>) {
    val file = File(context.filesDir, "export_${projectName}_${agentName}.txt")
    val text = buildString {
        appendLine("Экспорт чата с $agentName (Проект: $projectName)")
        appendLine("=".repeat(40))
        messages.forEach { msg ->
            val author = if (msg.isUser) "Вы" else agentName
            appendLine("[${msg.time}] $author: ${msg.text}")
        }
    }
    file.writeText(text)
    Toast.makeText(context, "Сохранено: ${file.name}", Toast.LENGTH_LONG).show()
}

fun copyFullHistory(context: Context, projectName: String, agentName: String, messages: List<Msg>) {
    if (messages.isEmpty()) {
        Toast.makeText(context, "История пуста", Toast.LENGTH_SHORT).show()
        return
    }
    val text = buildString {
        appendLine("История чата с $agentName (Проект: $projectName)")
        appendLine("=".repeat(40))
        messages.forEach { msg ->
            val author = if (msg.isUser) "Вы" else agentName
            appendLine("[${msg.time}] $author: ${msg.text}")
        }
    }
    copyToClipboard(context, text)
    Toast.makeText(context, "История скопирована", Toast.LENGTH_SHORT).show()
}
