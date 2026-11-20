package com.micronizationlab.glafira

import java.time.Instant

/**
 * Versioned role contract for the in-app HR Intelligence specialist.
 *
 * This first slice defines safe behavior and typed dossier/review models only.
 * It does not connect to mail, chats, calendars or CRM and does not persist contact data.
 * Connector access and encrypted persistence are separate, owner-authorized implementation steps.
 */
object HrIntelligenceAgent {
    const val ROLE_ID = "hr-intelligence"
    const val VERSION = 1
    const val DISPLAY_NAME = "КАДРОВИК"

    const val PROMPT = """
Ты — Кадровик GLAFIRA (HR Intelligence), специализированный помощник по организации
личных и деловых контактов владельца.

ТВОЯ ЗАДАЧА
Помогай структурировать сведения о людях и организациях, историю взаимодействий,
деловые и личные связи, явно данные предпочтения, договорённости и последующие действия.
Разделяй личный и деловой контекст. Формируй краткие карточки и хронологии только
на основании сведений, которые пользователь предоставил в этой беседе или к которым
приложение явно предоставило разрешённый доступ.

ПРАВИЛА ДОСТОВЕРНОСТИ
1. Для каждого существенного факта указывай источник и дату, если они известны.
2. Разделяй подтверждённые сведения, пересказ, предположение и неизвестное.
3. Не выдумывай должности, связи, даты, контакты или мотивы.
4. Не объединяй людей только по совпадению имени, фотографии или организации.
5. При противоречиях сохраняй обе версии и объясняй, что требует проверки.
6. Если данных недостаточно, прямо говори об этом и задавай один уточняющий вопрос.

ПРИВАТНОСТЬ И БЕЗОПАСНОСТЬ
- Не утверждай, что видишь почту, мессенджеры, файлы или CRM, если соответствующий
  источник реально не подключён и не выдал приложению разрешение.
- Не пытайся обходить авторизацию, права доступа, настройки приватности или ограничения источника.
- Не запрашивай и не раскрывай пароли, токены, ключи и коды подтверждения.
- Не делай выводов о здоровье, религии, политических взглядах, этнической принадлежности,
  сексуальной жизни, криминальной истории и иных чувствительных характеристиках.
- Не создавай скрытые психологические профили, рейтинги благонадёжности или оценки людей.
- Не отправляй сообщения, не публикуй и не изменяй записи во внешних системах без
  отдельного разрешения владельца.

РАБОТА С ОБЩЕЙ ПАМЯТЬЮ
Предлагай новые факты как кандидаты, а не как уже подтверждённую память.
Для кандидата указывай формулировку, источник, уверенность, область применения и
возможные противоречия. Перед добавлением в общую память требуется проверка всеми
доступными и уполномоченными агентами; недоступные агенты отмечаются как NOT_REVIEWED.
Высокозначимые и спорные сведения требуют решения владельца.

ФОРМАТ ОТВЕТА
Кратко: факт / источник / уверенность / что ещё проверить / следующий шаг.
Не выдавай проектируемые функции за уже работающие.
"""

    fun prompt(): String = PROMPT
}

enum class HrIdentityStatus { UNRESOLVED, PROVISIONAL, VERIFIED }

enum class HrRelationshipType {
    CUSTOMER, SUPPLIER, PARTNER, COLLEAGUE, FRIEND, FAMILY_CONTACT, PROSPECT, OTHER
}

enum class HrFactCategory {
    NAME, ORGANIZATION, ROLE, RELATIONSHIP, CONTACT_CHANNEL,
    INTERACTION_SUMMARY, COMMITMENT, EXPLICIT_PREFERENCE, OTHER_NON_SENSITIVE
}

enum class HrFactConfidence { CONFIRMED, STRONGLY_SUPPORTED, TENTATIVE, DISPUTED }

enum class HrFactSensitivity { INTERNAL, PERSONAL, CONFIDENTIAL }

data class HrSourceReference(
    val sourceType: String,
    val sourceId: String,
    val recordId: String,
    val observedAt: Instant,
    val eventAt: Instant? = null
) {
    init {
        require(sourceType.isNotBlank())
        require(sourceId.isNotBlank())
        require(recordId.isNotBlank())
    }
}

data class HrContactFact(
    val category: HrFactCategory,
    val value: String,
    val confidence: HrFactConfidence,
    val sensitivity: HrFactSensitivity,
    val sources: List<HrSourceReference>,
    val isDirectlyStated: Boolean
) {
    init {
        require(value.isNotBlank())
        require(sources.isNotEmpty()) { "Every dossier fact must have at least one source." }
    }
}

data class HrInteraction(
    val interactionId: String,
    val occurredAt: Instant,
    val channel: String,
    val summary: String,
    val sources: List<HrSourceReference>
) {
    init {
        require(interactionId.isNotBlank() && channel.isNotBlank() && summary.isNotBlank())
        require(sources.isNotEmpty())
    }
}

enum class HrCommitmentStatus { OPEN, COMPLETED, CANCELLED }

data class HrCommitment(
    val commitmentId: String,
    val description: String,
    val dueAt: Instant? = null,
    val status: HrCommitmentStatus = HrCommitmentStatus.OPEN,
    val sources: List<HrSourceReference>
) {
    init {
        require(commitmentId.isNotBlank() && description.isNotBlank())
        require(sources.isNotEmpty())
    }
}

data class HrContactCard(
    val contactId: String,
    val displayName: String,
    val aliases: Set<String> = emptySet(),
    val organization: String? = null,
    val role: String? = null,
    val relationship: HrRelationshipType? = null,
    val identityStatus: HrIdentityStatus = HrIdentityStatus.UNRESOLVED,
    val facts: List<HrContactFact> = emptyList(),
    val interactions: List<HrInteraction> = emptyList(),
    val commitments: List<HrCommitment> = emptyList(),
    val lastVerifiedAt: Instant? = null
) {
    init {
        require(contactId.isNotBlank())
        require(displayName.isNotBlank())
        require(identityStatus != HrIdentityStatus.VERIFIED ||
            facts.any { it.category == HrFactCategory.NAME &&
                it.confidence == HrFactConfidence.CONFIRMED }) {
            "A verified identity requires a confirmed, source-backed name fact."
        }
    }
}

enum class HrReviewDecision {
    ACCEPT, CORRECT, CONFLICT, INSUFFICIENT_EVIDENCE, ABSTAIN, NOT_REVIEWED
}

data class HrAgentReview(
    val agentId: String,
    val decision: HrReviewDecision,
    val rationale: String,
    val sourceReferences: List<HrSourceReference> = emptyList()
) {
    init {
        require(agentId.isNotBlank())
        require(rationale.isNotBlank())
    }
}

data class HrMemoryReviewOutcome(
    val allEligibleAgentsReviewed: Boolean,
    val notReviewedAgentIds: Set<String>,
    val hasConflict: Boolean,
    val canPromote: Boolean
)

object HrMemoryReviewPolicy {
    fun evaluate(
        eligibleAgentIds: Set<String>,
        reviews: List<HrAgentReview>,
        requiresOwnerApproval: Boolean,
        ownerApproved: Boolean
    ): HrMemoryReviewOutcome {
        val groupedReviews = reviews.groupBy { it.agentId }
        val notReviewed = eligibleAgentIds.filterTo(mutableSetOf()) { id ->
            val entries = groupedReviews[id].orEmpty()
            entries.size != 1 || entries.singleOrNull()?.decision == HrReviewDecision.NOT_REVIEWED
        }
        val allReviewed = notReviewed.isEmpty()
        val duplicateReviews = groupedReviews.any { (id, entries) ->
            id in eligibleAgentIds && entries.size != 1
        }
        val hasConflict = duplicateReviews || eligibleAgentIds.any { id ->
            groupedReviews[id]?.singleOrNull()?.decision in setOf(
                HrReviewDecision.CONFLICT,
                HrReviewDecision.INSUFFICIENT_EVIDENCE,
                HrReviewDecision.ABSTAIN
            )
        }
        val unanimousEnough = eligibleAgentIds.all { id ->
            groupedReviews[id]?.singleOrNull()?.decision in
                setOf(HrReviewDecision.ACCEPT, HrReviewDecision.CORRECT)
        }
        val ownerGatePassed = !requiresOwnerApproval || ownerApproved
        return HrMemoryReviewOutcome(
            allEligibleAgentsReviewed = allReviewed,
            notReviewedAgentIds = notReviewed,
            hasConflict = hasConflict,
            canPromote = eligibleAgentIds.isNotEmpty() && allReviewed &&
                unanimousEnough && !hasConflict && ownerGatePassed
        )
    }
}
