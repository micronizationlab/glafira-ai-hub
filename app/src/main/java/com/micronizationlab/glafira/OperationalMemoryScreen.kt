package com.micronizationlab.glafira

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
fun OperationalMemoryScreen(
    currentProject: Project,
    owner: GlafiraCoreRuntimeOwner,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<CoreMemoryRecord>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CoreMemoryRecord?>(null) }
    var deleting by remember { mutableStateOf<CoreMemoryRecord?>(null) }

    fun refresh() {
        scope.launch {
            runCatching { owner.operationalMemory(currentProject.id) }
                .onSuccess { records = it }
                .onFailure { message = it.message ?: "Не удалось прочитать operational memory." }
        }
    }

    LaunchedEffect(currentProject.id) { refresh() }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().background(XpBlue).padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("←  НАЗАД", color = Color.White, modifier = Modifier.clickable { onBack() }.padding(4.dp))
            Text("OPERATIONAL MEMORY", color = Color.White, fontWeight = FontWeight.Bold)
            Text("＋", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable { showAdd = true }.padding(horizontal = 8.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(currentProject.name, color = Gr, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Факты изолированы проектом и сохраняют источник.", color = Tx, fontSize = 10.sp)
            }
            TextButton(onClick = {
                scope.launch {
                    runCatching {
                        exportOperationalMemoryToFile(
                            context,
                            currentProject,
                            owner.exportOperationalMemory(currentProject.id)
                        )
                    }.onSuccess { message = "Экспорт сохранён в приватном хранилище: ${it.name}" }
                        .onFailure { message = it.message ?: "Не удалось экспортировать память." }
                }
            }) { Text("ЭКСПОРТ") }
            Button(onClick = { showAdd = true }) { Text("＋ ФАКТ") }
        }
        message?.let {
            Text(it, color = Tx, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
        }
        if (records.isEmpty()) {
            Text(
                "Подтверждённых фактов пока нет. Добавление требует явного источника.",
                color = Tx,
                modifier = Modifier.padding(24.dp)
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp)) {
                items(records, key = { it.id.value }) { record ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(record.content, color = Color(0xFF202020), fontSize = 12.sp)
                            Text(
                                "${record.kind.name} · ${record.status.name} · ${record.classification.name}",
                                color = Gr,
                                fontSize = 9.sp
                            )
                            Text(
                                "Источник: ${record.provenance.sourceType} / ${record.provenance.sourceId.orEmpty()}",
                                color = Tx,
                                fontSize = 9.sp
                            )
                            if (record.tags.isNotEmpty()) {
                                Text("Теги: ${record.tags.sorted().joinToString()}", color = Tx, fontSize = 9.sp)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { editing = record }) { Text("ИСПРАВИТЬ") }
                                TextButton(onClick = { deleting = record }) { Text("УДАЛИТЬ", color = Red) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddOperationalMemoryDialog(
            onDismiss = { showAdd = false },
            onSave = { draft ->
                scope.launch {
                    runCatching { owner.createOperationalMemoryFact(currentProject.id, draft) }
                        .onSuccess { result ->
                            message = result.message("Факт сохранён.")
                            if (result is MemoryWriteResult.Stored) {
                                showAdd = false
                                refresh()
                            }
                        }.onFailure { message = it.message ?: "Не удалось сохранить факт." }
                }
            }
        )
    }

    editing?.let { record ->
        EditOperationalMemoryDialog(
            record = record,
            onDismiss = { editing = null },
            onSave = { content, tags ->
                scope.launch {
                    runCatching {
                        owner.correctOperationalMemory(
                            currentProject.id,
                            record.id.value,
                            content,
                            tags
                        )
                    }.onSuccess { result ->
                        message = result.message("Факт исправлен.")
                        if (result is MemoryWriteResult.Stored) {
                            editing = null
                            refresh()
                        }
                    }.onFailure { message = it.message ?: "Не удалось исправить факт." }
                }
            }
        )
    }

    deleting?.let { record ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить факт?") },
            text = { Text("Содержимое будет заменено durable tombstone; запись не воскреснет после сбоя.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { owner.deleteOperationalMemory(currentProject.id, record.id.value) }
                            .onSuccess {
                                message = when (it) {
                                    MemoryDeleteResult.Deleted -> "Факт удалён."
                                    MemoryDeleteResult.NotFound -> "Факт уже отсутствует."
                                    is MemoryDeleteResult.Rejected -> "Удаление отклонено: ${it.reasonCode}"
                                }
                                deleting = null
                                refresh()
                            }.onFailure { message = it.message ?: "Не удалось удалить факт." }
                    }
                }) { Text("УДАЛИТЬ", color = Red) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("ОТМЕНА") } }
        )
    }
}

@Composable
private fun AddOperationalMemoryDialog(
    onDismiss: () -> Unit,
    onSave: (GlafiraOwnerMemoryFactDraft) -> Unit
) {
    var content by remember { mutableStateOf("") }
    var sourceType by remember { mutableStateOf("owner") }
    var sourceId by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить подтверждённый факт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(content, { content = it }, label = { Text("Факт") }, minLines = 3)
                OutlinedTextField(sourceType, { sourceType = it }, label = { Text("Тип источника") }, singleLine = true)
                OutlinedTextField(sourceId, { sourceId = it }, label = { Text("Идентификатор источника") }, singleLine = true)
                OutlinedTextField(tags, { tags = it }, label = { Text("Теги через запятую") }, singleLine = true)
                Text("Без источника факт не будет сохранён.", color = Tx, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    GlafiraOwnerMemoryFactDraft(
                        content = content,
                        sourceType = sourceType,
                        sourceId = sourceId,
                        tags = tags.split(',').toSet()
                    )
                )
            }) { Text("СОХРАНИТЬ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ОТМЕНА") } }
    )
}

@Composable
private fun EditOperationalMemoryDialog(
    record: CoreMemoryRecord,
    onDismiss: () -> Unit,
    onSave: (String, Set<String>) -> Unit
) {
    var content by remember(record.id) { mutableStateOf(record.content) }
    var tags by remember(record.id) { mutableStateOf(record.tags.sorted().joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Исправить факт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(content, { content = it }, label = { Text("Факт") }, minLines = 3)
                OutlinedTextField(tags, { tags = it }, label = { Text("Теги через запятую") }, singleLine = true)
                Text("Источник и project scope сохраняются без изменений.", color = Tx, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(content, tags.split(',').toSet()) }) { Text("СОХРАНИТЬ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ОТМЕНА") } }
    )
}

private fun MemoryWriteResult.message(success: String): String = when (this) {
    is MemoryWriteResult.Stored -> success
    is MemoryWriteResult.Rejected -> "Операция отклонена: $reasonCode"
}

private fun exportOperationalMemoryToFile(
    context: Context,
    project: Project,
    export: MemoryExport
): File {
    val safeProject = project.id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
    val document = JSONObject().apply {
        put("schemaVersion", export.schemaVersion)
        put("projectId", project.id)
        put("records", JSONArray().apply {
            export.records.forEach { record ->
                put(JSONObject().apply {
                    put("id", record.id.value)
                    put("kind", record.kind.name)
                    put("content", record.content)
                    put("classification", record.classification.name)
                    put("status", record.status.name)
                    put("sourceType", record.provenance.sourceType)
                    put("sourceId", record.provenance.sourceId ?: JSONObject.NULL)
                    put("updatedAt", record.updatedAt.toString())
                    put("tags", JSONArray(record.tags.sorted()))
                })
            }
        })
    }
    return File(context.filesDir, "operational_memory_${safeProject}_${System.currentTimeMillis()}.json")
        .apply { writeText(document.toString(2)) }
}
