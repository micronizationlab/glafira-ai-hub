package com.micronizationlab.glafira

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

@Composable
fun HrContactsScreen(projectId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var contacts by remember { mutableStateOf<List<HrContactCard>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<HrContactCard?>(null) }

    LaunchedEffect(projectId, refreshKey) {
        val result = withContext(Dispatchers.IO) {
            runCatching { HrContactStore.list(context, projectId) }
        }
        result.onSuccess {
            contacts = it.sortedBy { card -> card.displayName.lowercase() }
            loadError = null
        }.onFailure {
            loadError = "Не удалось открыть зашифрованные карточки. Данные не изменялись."
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(12.dp)) {
        Row(
            Modifier.fillMaxWidth().background(XpBlue).padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(end = 14.dp))
            Text(
                "КАРТОЧКИ КОНТАКТОВ",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            Text("НАЗАД", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Проект: $projectId", color = Tx, fontSize = 10.sp)
            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Gr, contentColor = Color.White)
            ) { Text("+ ДОБАВИТЬ") }
        }
        loadError?.let {
            Text(it, color = Red, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (contacts.isEmpty() && loadError == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Пока нет карточек. Добавь первый контакт вручную.", color = Tx, fontSize = 13.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(contacts, key = { it.contactId }) { contact ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(contact.displayName, color = Color(0xFF202020), fontSize = 15.sp)
                                    val subtitle = listOfNotNull(contact.role, contact.organization).joinToString(" · ")
                                    if (subtitle.isNotBlank()) Text(subtitle, color = Tx, fontSize = 11.sp)
                                }
                                TextButton(onClick = { deleteTarget = contact }) {
                                    Text("УДАЛИТЬ", color = Red, fontSize = 10.sp)
                                }
                            }
                            contact.relationship?.let {
                                Text("Связь: ${it.label()}", color = Gr, fontSize = 11.sp)
                            }
                            Text(
                                "Фактов: ${contact.facts.size} · взаимодействий: ${contact.interactions.size} · договорённостей: ${contact.commitments.count { item -> item.status == HrCommitmentStatus.OPEN }}",
                                color = Tx,
                                fontSize = 10.sp
                            )
                            contact.interactions.lastOrNull()?.let {
                                Text("Последний контакт: ${it.summary}", color = Color(0xFF303030), fontSize = 11.sp)
                            }
                            contact.commitments.filter { it.status == HrCommitmentStatus.OPEN }.forEach {
                                Text("Договорённость: ${it.description}", color = Color(0xFF303030), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        HrAddContactDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, organization, role, channel, note, interaction, commitment, relationship ->
                val now = Instant.now()
                val source = HrSourceReference(
                    sourceType = "owner_input",
                    sourceId = projectId,
                    recordId = UUID.randomUUID().toString(),
                    observedAt = now,
                    eventAt = now
                )
                val facts = buildList {
                    add(HrContactFact(HrFactCategory.NAME, name, HrFactConfidence.CONFIRMED,
                        HrFactSensitivity.PERSONAL, listOf(source), true))
                    if (organization.isNotBlank()) add(HrContactFact(HrFactCategory.ORGANIZATION,
                        organization, HrFactConfidence.CONFIRMED, HrFactSensitivity.INTERNAL, listOf(source), true))
                    if (role.isNotBlank()) add(HrContactFact(HrFactCategory.ROLE,
                        role, HrFactConfidence.CONFIRMED, HrFactSensitivity.INTERNAL, listOf(source), true))
                    add(HrContactFact(HrFactCategory.RELATIONSHIP, relationship.name,
                        HrFactConfidence.CONFIRMED, HrFactSensitivity.PERSONAL, listOf(source), true))
                    if (channel.isNotBlank()) add(HrContactFact(HrFactCategory.CONTACT_CHANNEL,
                        channel, HrFactConfidence.CONFIRMED, HrFactSensitivity.CONFIDENTIAL, listOf(source), true))
                    if (note.isNotBlank()) add(HrContactFact(HrFactCategory.OTHER_NON_SENSITIVE,
                        note, HrFactConfidence.CONFIRMED, HrFactSensitivity.PERSONAL, listOf(source), true))
                }
                val card = HrContactCard(
                    contactId = UUID.randomUUID().toString(),
                    displayName = name,
                    organization = organization.ifBlank { null },
                    role = role.ifBlank { null },
                    relationship = relationship,
                    identityStatus = HrIdentityStatus.VERIFIED,
                    facts = facts,
                    interactions = if (interaction.isBlank()) emptyList() else listOf(
                        HrInteraction(UUID.randomUUID().toString(), now, "owner_input", interaction, listOf(source))
                    ),
                    commitments = if (commitment.isBlank()) emptyList() else listOf(
                        HrCommitment(UUID.randomUUID().toString(), commitment, sources = listOf(source))
                    ),
                    lastVerifiedAt = now
                )
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { HrContactStore.upsert(context, projectId, card) }
                    }
                    result.onSuccess {
                        refreshKey++
                        showAddDialog = false
                    }.onFailure {
                        loadError = "Не удалось сохранить карточку. Проверь состояние хранилища."
                    }
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить карточку?") },
            text = { Text("Карточка «${target.displayName}» будет удалена из локального зашифрованного хранилища этого проекта.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { HrContactStore.delete(context, projectId, target.contactId) }
                        }
                        result.onSuccess {
                            refreshKey++
                            deleteTarget = null
                        }.onFailure {
                            loadError = "Не удалось удалить карточку. Данные не изменялись."
                            deleteTarget = null
                        }
                    }
                }) { Text("УДАЛИТЬ", color = Red) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("ОТМЕНА") }
            }
        )
    }
}

@Composable
private fun HrAddContactDialog(
    onDismiss: () -> Unit,
    onSave: (
        String, String, String, String, String, String, String, HrRelationshipType
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var organization by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var interaction by remember { mutableStateOf("") }
    var commitment by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf(HrRelationshipType.CUSTOMER) }
    var menuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый контакт") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Имя и фамилия *") }, singleLine = true)
                OutlinedTextField(organization, { organization = it }, label = { Text("Организация") }, singleLine = true)
                OutlinedTextField(role, { role = it }, label = { Text("Должность / роль") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { menuExpanded = true }) {
                        Text("Связь: ${relationship.label()}")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        HrRelationshipType.values().forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.label()) },
                                onClick = { relationship = item; menuExpanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(channel, { channel = it }, label = { Text("Контактный канал (не пароль)") }, singleLine = true)
                OutlinedTextField(interaction, { interaction = it }, label = { Text("Последнее взаимодействие") })
                OutlinedTextField(commitment, { commitment = it }, label = { Text("Договорённость / следующий шаг") })
                OutlinedTextField(note, { note = it }, label = { Text("Заметка (без чувствительных сведений)") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(name.trim(), organization.trim(), role.trim(), channel.trim(),
                        note.trim(), interaction.trim(), commitment.trim(), relationship)
                }
            ) { Text("СОХРАНИТЬ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ОТМЕНА") } }
    )
}

private fun HrRelationshipType.label(): String = when (this) {
    HrRelationshipType.CUSTOMER -> "Клиент"
    HrRelationshipType.SUPPLIER -> "Поставщик"
    HrRelationshipType.PARTNER -> "Партнёр"
    HrRelationshipType.COLLEAGUE -> "Коллега"
    HrRelationshipType.FRIEND -> "Друг"
    HrRelationshipType.FAMILY_CONTACT -> "Личный контакт"
    HrRelationshipType.PROSPECT -> "Потенциальный клиент"
    HrRelationshipType.OTHER -> "Другое"
}
