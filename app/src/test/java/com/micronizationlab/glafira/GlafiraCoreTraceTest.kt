package com.micronizationlab.glafira

import org.junit.Assert.*
import org.junit.Test

class GlafiraCoreTraceTest {
    private fun draft(
        projectId: String = "project-001",
        actorId: String = "owner-001",
        actionCode: String = "task.started",
        targetId: String? = "task-001",
        evidenceRefs: List<String> = listOf("artifact-001")
    ) = GlafiraCoreTraceDraft(
        projectId = projectId,
        taskId = "task-001",
        correlationId = "correlation-001",
        occurredAtMillis = 1_800_000_000_000L,
        actorType = GlafiraCoreTraceActorType.OWNER,
        actorId = actorId,
        component = "CoreCoordinator",
        eventType = GlafiraCoreTraceEventType.TASK_STARTED,
        actionCode = actionCode,
        targetType = "TASK",
        targetId = targetId,
        resultStatus = "STARTED",
        evidenceRefs = evidenceRefs
    )

    @Test
    fun redactionProducesMetadataOnlyEventAndNeverPersistsCredentialOrPersonalValues() {
        val event = GlafiraCoreTraceRedactor.redact(draft(
            actorId = "owner@example.com",
            actionCode = "Bearer abcdefghijklmnopqrstuvwxyz123456",
            targetId = "https://user:password@example.com/private",
            evidenceRefs = listOf("ghp_12345678901234567890")
        ))

        assertEquals("redacted", event.actorId)
        assertEquals("redacted", event.actionCode)
        assertEquals("redacted", event.targetId)
        assertEquals(listOf("redacted"), event.evidenceRefs)
        assertTrue(event.redactionApplied)
        assertFalse(event.secretValuesStored)
    }

    @Test
    fun invalidProjectOrTaskIdentityIsRejectedRatherThanRedactedIntoAnotherScope() {
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreTraceRedactor.redact(draft(projectId = "owner@example.com"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreTraceRedactor.redact(draft().copy(taskId = "https://example.com/task"))
        }
    }

    @Test
    fun safeCodesAndOpaqueReferencesSurviveRedaction() {
        val event = GlafiraCoreTraceRedactor.redact(draft())
        assertEquals("task.started", event.actionCode)
        assertEquals("task-001", event.targetId)
        assertEquals(listOf("artifact-001"), event.evidenceRefs)
    }

    @Test
    fun eventRejectsUnredactedOrOversizedEvidenceAtConstruction() {
        val safe = GlafiraCoreTraceRedactor.redact(draft())
        assertThrows(IllegalArgumentException::class.java) {
            safe.copy(redactionApplied = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreTraceRedactor.redact(draft(evidenceRefs = (1..33).map { "artifact-$it" }))
        }
    }

    @Test
    fun queryBoundsPaginationAndRequiresValidTimeWindow() {
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreTraceQuery(projectId = "project-001", limit = 101)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraCoreTraceQuery(projectId = "project-001", fromMillis = 2_000L, toMillis = 1_000L)
        }
        assertEquals(50, GlafiraCoreTraceQuery(projectId = "project-001").limit)
    }

    @Test
    fun taskScopedAccessCannotReadSiblingTasksOrAnotherProject() {
        val allowed = GlafiraCoreTraceRedactor.redact(draft())
        val sibling = GlafiraCoreTraceRedactor.redact(draft().copy(taskId = "task-002"))
        val otherProject = GlafiraCoreTraceRedactor.redact(draft().copy(projectId = "project-002"))
        val access = GlafiraCoreTraceAccessContext("project-001", setOf("task-001"))

        assertTrue(access.canRead(allowed))
        assertFalse(access.canRead(sibling))
        assertFalse(access.canRead(otherProject))
    }

    @Test
    fun uuidIdentifiersAreNotMistakenForPhoneNumbers() {
        val numericUuid = "12345678-1234-4234-9234-123456789012"
        val event = GlafiraCoreTraceRedactor.redact(draft().copy(
            eventId = numericUuid,
            projectId = numericUuid,
            taskId = numericUuid,
            correlationId = numericUuid
        ))
        assertEquals(numericUuid, event.eventId)
        assertEquals(numericUuid, event.projectId)
        assertEquals(numericUuid, event.taskId)
        assertEquals(numericUuid, event.correlationId)
    }

    @Test
    fun projectScopedAccessCanOptIntoProjectLevelEventsOnlyExplicitly() {
        val projectEvent = GlafiraCoreTraceRedactor.redact(draft().copy(taskId = null))
        assertFalse(GlafiraCoreTraceAccessContext("project-001", setOf("task-001")).canRead(projectEvent))
        assertTrue(GlafiraCoreTraceAccessContext(
            "project-001", setOf("task-001"), allowProjectEvents = true
        ).canRead(projectEvent))
    }
}
