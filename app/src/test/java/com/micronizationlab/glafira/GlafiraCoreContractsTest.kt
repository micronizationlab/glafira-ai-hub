package com.micronizationlab.glafira

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraCoreContractsTest {
    private val now = Instant.parse("2026-11-18T12:00:00Z")

    @Test
    fun bridgeContentLengthAcceptsOnlyBoundedAsciiDecimalSyntax() {
        assertEquals(1L, GlafiraBridgeIngressPolicy.parseContentLength("1"))
        assertEquals(65536L, GlafiraBridgeIngressPolicy.parseContentLength("65536"))
        listOf(null, "", "+1", "-1", " 1", "1 ", "1.0", "١", "99999999999").forEach {
            assertEquals(null, GlafiraBridgeIngressPolicy.parseContentLength(it))
        }
    }

    @Test
    fun identifiersRejectBlankValues() {
        val error = runCatching { TaskIdentifier(" ") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun memoryConfidenceMustBeInRange() {
        val error = runCatching {
            CoreMemoryRecord(
                id = MemoryIdentifier("memory-1"),
                scopeType = MemoryScopeType.PROJECT,
                scopeId = "project-1",
                kind = MemoryKind.FACT,
                content = "A fact",
                classification = CoreDataClassification.INTERNAL,
                confidence = 1.1,
                confidenceBasis = MemoryConfidenceBasis.SOURCE_DERIVED,
                provenance = MemoryProvenance("test", "source-1", "agent-1", now),
                createdAt = now,
                updatedAt = now,
                createdBy = "agent-1",
                lastModifiedBy = "agent-1"
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun agentInferredMemoryCannotBeMarkedConfirmed() {
        val error = runCatching {
            CoreMemoryRecord(
                id = MemoryIdentifier("memory-2"),
                scopeType = MemoryScopeType.OWNER,
                scopeId = "owner",
                kind = MemoryKind.PREFERENCE,
                content = "Inferred preference",
                classification = CoreDataClassification.INTERNAL,
                status = MemoryStatus.CONFIRMED,
                confidence = 0.8,
                confidenceBasis = MemoryConfidenceBasis.AGENT_INFERRED,
                provenance = MemoryProvenance("conversation", "conversation-1", "agent-1", now),
                createdAt = now,
                updatedAt = now,
                createdBy = "agent-1",
                lastModifiedBy = "agent-1"
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun memoryQueryAndTracePaginationAreBounded() {
        assertTrue(runCatching { MemoryQuery("x", limit = 101) }.isFailure)
        assertTrue(runCatching { TraceQuery(limit = 501) }.isFailure)
        assertTrue(runCatching {
            TraceQuery(after = now, before = now.minusSeconds(1))
        }.isFailure)
    }

    @Test
    fun traceCannotDeclareSecretValuesWereStored() {
        val error = runCatching {
            CoreTraceEvent(
                eventId = TraceEventIdentifier("event-1"),
                occurredAt = now,
                recordedAt = now,
                eventType = CoreTraceEventType.TASK_STARTED,
                severity = CoreTraceSeverity.INFO,
                actor = CoreActor("agent-1", CoreActorType.AGENT),
                projectId = ProjectIdentifier("project-1"),
                taskId = TaskIdentifier("task-1"),
                parentTaskId = null,
                correlationId = CoreIdentifier("correlation-1"),
                causationId = null,
                component = "CoreCoordinator",
                action = "task.start",
                targetType = "TASK",
                targetId = "task-1",
                resultCode = "STARTED",
                approvalRef = null,
                redactionApplied = true,
                secretValuesStored = true
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun reviewerVerdictsAndIndependenceAreExplicitTypes() {
        assertEquals(4, ReviewVerdict.values().size)
        assertTrue(ReviewIndependence.LIMITED.name == "LIMITED")
        assertFalse(ReviewIndependence.DETERMINISTIC_ONLY == ReviewIndependence.INDEPENDENT_PROVIDER)
    }
}
