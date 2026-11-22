package com.micronizationlab.glafira

import java.time.Instant
import java.util.UUID

data class GlafiraOwnerMemoryFactDraft(
    val content: String,
    val sourceType: String,
    val sourceId: String,
    val classification: CoreDataClassification = CoreDataClassification.INTERNAL,
    val tags: Set<String> = emptySet()
)

/**
 * Owner-facing application service for project operational memory.
 *
 * Every operation derives its access context from the current project instead of accepting an
 * arbitrary scope. Owner-created facts are confirmed, source-backed records and remain
 * correctable/exportable through the same authorization path.
 */
class GlafiraOwnerOperationalMemory(
    private val repository: MemoryRepository,
    private val ownerId: String,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> MemoryIdentifier = {
        MemoryIdentifier(UUID.randomUUID().toString())
    }
) {
    init { require(ownerId.isNotBlank()) }

    suspend fun createFact(
        projectId: String,
        draft: GlafiraOwnerMemoryFactDraft
    ): MemoryWriteResult {
        val project = project(projectId)
        val content = draft.content.trim()
        val sourceType = draft.sourceType.trim()
        val sourceId = draft.sourceId.trim()
        if (content.isEmpty()) return MemoryWriteResult.Rejected("MEMORY_CONTENT_REQUIRED")
        if (sourceType.isEmpty() || sourceId.isEmpty()) {
            return MemoryWriteResult.Rejected("MEMORY_FACT_SOURCE_REQUIRED")
        }
        val timestamp = now()
        val record = CoreMemoryRecord(
            id = newId(),
            scopeType = MemoryScopeType.PROJECT,
            scopeId = project.value,
            kind = MemoryKind.FACT,
            content = content,
            classification = draft.classification,
            status = MemoryStatus.CONFIRMED,
            confidence = 1.0,
            confidenceBasis = MemoryConfidenceBasis.OWNER_CONFIRMED,
            provenance = MemoryProvenance(sourceType, sourceId, ownerId, timestamp),
            createdAt = timestamp,
            updatedAt = timestamp,
            createdBy = ownerId,
            lastModifiedBy = ownerId,
            tags = draft.tags.map(String::trim).filter(String::isNotEmpty).toSet()
        )
        return repository.put(record, access(project))
    }

    suspend fun list(projectId: String, includeDeleted: Boolean = false): List<CoreMemoryRecord> {
        val project = project(projectId)
        val statuses = if (includeDeleted) MemoryStatus.values().toSet()
        else MemoryStatus.values().filterTo(mutableSetOf()) { it != MemoryStatus.DELETED }
        return repository.search(
            MemoryQuery("", statuses = statuses, limit = 100, includeExpired = true),
            access(project)
        )
    }

    suspend fun correct(
        projectId: String,
        memoryId: String,
        content: String,
        tags: Set<String>
    ): MemoryWriteResult {
        val project = project(projectId)
        val corrected = content.trim()
        if (corrected.isEmpty()) return MemoryWriteResult.Rejected("MEMORY_CONTENT_REQUIRED")
        return repository.update(
            MemoryIdentifier(memoryId),
            MemoryChange(
                content = corrected,
                tags = tags.map(String::trim).filter(String::isNotEmpty).toSet()
            ),
            access(project)
        )
    }

    suspend fun delete(projectId: String, memoryId: String): MemoryDeleteResult {
        val project = project(projectId)
        return repository.delete(MemoryIdentifier(memoryId), access(project))
    }

    suspend fun export(projectId: String): MemoryExport {
        val project = project(projectId)
        return repository.export(
            MemoryQuery(
                "",
                statuses = MemoryStatus.values().toSet(),
                limit = 100,
                includeExpired = true
            ),
            access(project)
        )
    }

    private fun project(value: String): ProjectIdentifier = ProjectIdentifier(value.trim())

    private fun access(projectId: ProjectIdentifier) = MemoryAccessContext(
        actorId = ownerId,
        projectId = projectId,
        agentRoleId = null,
        allowedScopes = setOf(MemoryScopeType.OWNER, MemoryScopeType.PROJECT),
        maximumClassification = CoreDataClassification.RESTRICTED
    )
}
