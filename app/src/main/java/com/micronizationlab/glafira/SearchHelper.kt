package com.micronizationlab.glafira

import android.content.Context
import android.widget.Toast
import java.io.File

fun searchInHistory(context: Context, historyKey: String, query: String): List<Msg> {
    val file = File(context.filesDir, "chat_$historyKey.json")
    if (!file.exists()) return emptyList()
    
    val allMessages = loadHistory(context, historyKey)
    if (query.isBlank()) return allMessages
    
    return allMessages.filter { it.text.contains(query, ignoreCase = true) }
}

fun copyFullHistory(context: Context, projectName: String, agentName: String, messages: List<Msg>) {
    val text = buildString {
        appendLine("История чата: $agentName (Проект: $projectName)")
        appendLine("=".repeat(40))
        messages.forEach { msg ->
            val author = if (msg.isUser) "Вы" else agentName
            appendLine("[${msg.time}] $author: ${msg.text}")
        }
    }
    copyToClipboard(context, text)
    Toast.makeText(context, "Вся история скопирована", Toast.LENGTH_SHORT).show()
}
