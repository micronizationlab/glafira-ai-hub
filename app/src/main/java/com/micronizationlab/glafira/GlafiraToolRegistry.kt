package com.micronizationlab.glafira

import java.security.MessageDigest

enum class ToolRiskLevel {
    READ_ONLY,
    DRAFT,
    EXTERNAL_WRITE,
    FINANCIAL,
    DESTRUCTIVE
}

data class GlafiraToolDefinition(
    val id: String,
    val title: String,
    val description: String,
    val risk: ToolRiskLevel,
    val requiredScopes: Set<String>
)

enum class ToolAuthorizationDecision {
    ALLOWED,
    OWNER_APPROVAL_REQUIRED
}

/**
 * A grant is issued by the app's owner-approval flow, never by an LLM response.
 * It is bound to one project, one tool and one exact action summary.
 */
data class ToolApprovalGrant internal constructor(
    val projectId: String,
    val toolId: String,
    val actionHash: String,
    val approvedAt: Long,
    val expiresAt: Long,
    val taskId: String? = null
)

object GlafiraToolRegistry {
    private val definitions = listOf(
        GlafiraToolDefinition(
            "project.read", "Чтение проекта", "Чтение разрешённых данных текущего проекта.",
            ToolRiskLevel.READ_ONLY, setOf("project.read")
        ),
        GlafiraToolDefinition(
            "conversation.read", "Чтение переписки", "Чтение разрешённой переписки проекта.",
            ToolRiskLevel.READ_ONLY, setOf("conversation.read")
        ),
        GlafiraToolDefinition(
            "conversation.append", "Добавление сообщения в переписку", "Добавление сообщения в историю проекта.",
            ToolRiskLevel.DRAFT, setOf("conversation.append")
        ),
        GlafiraToolDefinition(
            "approval.request", "Запрос подтверждения владельца", "Передача владельцу точного описания действия для решения.",
            ToolRiskLevel.DRAFT, setOf("approval.request")
        ),
        GlafiraToolDefinition(
            "desktop.execute", "Выполнение задания на Desktop", "Передача задания на доверенный Desktop-узел с возвратом результата в Core.",
            ToolRiskLevel.DRAFT, setOf("desktop.task.execute")
        ),
        GlafiraToolDefinition(
            "desktop.task.claim", "Получение задания Desktop", "Получение одного разрешённого задания из общей очереди Core.",
            ToolRiskLevel.READ_ONLY, setOf("desktop.task.claim")
        ),
        GlafiraToolDefinition(
            "desktop.task.result", "Результат Desktop-задания", "Обновление статуса и возврат результата выполнения в Core.",
            ToolRiskLevel.DRAFT, setOf("desktop.task.result")
        ),
        GlafiraToolDefinition(
            "content.draft", "Подготовка черновика", "Создание черновика текста без публикации.",
            ToolRiskLevel.DRAFT, setOf("content.draft")
        ),
        GlafiraToolDefinition(
            "message.send", "Отправка сообщения", "Отправка сообщения внешнему получателю.",
            ToolRiskLevel.EXTERNAL_WRITE, setOf("communications.send")
        ),
        GlafiraToolDefinition(
            "social.publish", "Публикация контента", "Публикация поста, истории или комментария.",
            ToolRiskLevel.EXTERNAL_WRITE, setOf("social.publish")
        ),
        GlafiraToolDefinition(
            "crm.update", "Изменение CRM", "Создание или изменение клиентской записи.",
            ToolRiskLevel.EXTERNAL_WRITE, setOf("crm.write")
        ),
        GlafiraToolDefinition(
            "payment.initiate", "Платёж", "Создание платёжной операции или перевода.",
            ToolRiskLevel.FINANCIAL, setOf("finance.payment")
        ),
        GlafiraToolDefinition(
            "record.delete", "Удаление данных", "Удаление записей или файлов.",
            ToolRiskLevel.DESTRUCTIVE, setOf("data.delete")
        )
    )

    fun all(): List<GlafiraToolDefinition> = definitions.toList()

    fun find(toolId: String): GlafiraToolDefinition? =
        definitions.firstOrNull { it.id == toolId }

    fun requiresOwnerApproval(risk: ToolRiskLevel): Boolean =
        risk == ToolRiskLevel.EXTERNAL_WRITE ||
            risk == ToolRiskLevel.FINANCIAL ||
            risk == ToolRiskLevel.DESTRUCTIVE

    fun authorize(
        definition: GlafiraToolDefinition,
        projectId: String,
        actionSummary: String,
        grant: ToolApprovalGrant?,
        now: Long = System.currentTimeMillis()
    ): ToolAuthorizationDecision {
        if (!requiresOwnerApproval(definition.risk)) return ToolAuthorizationDecision.ALLOWED
        if (grant == null) return ToolAuthorizationDecision.OWNER_APPROVAL_REQUIRED

        val expectedHash = actionHash(definition.id, projectId, actionSummary)
        val validGrant = grant.projectId == projectId &&
            grant.toolId == definition.id &&
            grant.actionHash == expectedHash &&
            grant.approvedAt <= now &&
            grant.expiresAt > now
        return if (validGrant) ToolAuthorizationDecision.ALLOWED
        else ToolAuthorizationDecision.OWNER_APPROVAL_REQUIRED
    }

    fun authorize(
        definition: GlafiraToolDefinition,
        task: GlafiraTask,
        actionSummary: String,
        grant: ToolApprovalGrant?,
        now: Long = System.currentTimeMillis()
    ): ToolAuthorizationDecision {
        if (!requiresOwnerApproval(definition.risk)) return ToolAuthorizationDecision.ALLOWED
        if (grant == null) return ToolAuthorizationDecision.OWNER_APPROVAL_REQUIRED
        val validGrant = grant.projectId == task.projectId &&
            grant.toolId == task.taskType &&
            grant.taskId == task.taskId &&
            grant.actionHash == actionHash(task, actionSummary) &&
            grant.approvedAt <= now &&
            grant.expiresAt > now
        return if (validGrant) ToolAuthorizationDecision.ALLOWED
        else ToolAuthorizationDecision.OWNER_APPROVAL_REQUIRED
    }

    internal fun actionHash(task: GlafiraTask, actionSummary: String): String {
        val fields = listOf(
            task.taskId, task.projectId, task.taskType, task.origin,
            task.payload, task.requiredScopes.sorted().joinToString("\\u0000"),
            actionSummary.trim()
        )
        val canonical = buildString {
            fields.forEach { append(it.length).append(':').append(it) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    internal fun issueGrantForOwnerApprovedRequest(
        projectId: String,
        toolId: String,
        actionSummary: String,
        approvedAt: Long,
        expiresAt: Long
    ): ToolApprovalGrant {
        require(find(toolId) != null) { "Unknown tool: $toolId" }
        require(requiresOwnerApproval(find(toolId)!!.risk)) { "This action does not require an approval grant." }
        require(expiresAt > approvedAt) { "Approval expiry must be after approval time." }
        return ToolApprovalGrant(
            projectId = projectId,
            toolId = toolId,
            actionHash = actionHash(toolId, projectId, actionSummary),
            approvedAt = approvedAt,
            expiresAt = expiresAt
        )
    }

    private fun actionHash(toolId: String, projectId: String, summary: String): String {
        val canonical = "$toolId\u0000$projectId\u0000${summary.trim()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
