package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GlafiraOwnerPlanRequestFactoryTest {
    @Test
    fun ownerFormBuildsBoundedProjectScopedRequestWithStableRetryKey() {
        val request = GlafiraOwnerPlanRequestFactory.create(
            projectId = " project-0001 ",
            title = "  Проверить источники ",
            instruction = "  Подготовить проверяемое резюме.  ",
            idempotencyKey = "owner-plan-session-0001",
            createdAtMillis = 1_000L,
            maxNodes = 4
        )

        assertEquals("project-0001", request.projectId)
        assertEquals("Проверить источники\nПодготовить проверяемое резюме.", request.instruction)
        assertEquals("owner-plan-session-0001", request.idempotencyKey)
        assertEquals(1_000L, request.createdAtMillis)
        assertEquals(4, request.maxNodes)
    }

    @Test
    fun invalidOwnerFormNeverReachesPlannerRequest() {
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraOwnerPlanRequestFactory.create(
                projectId = "project-0001",
                title = "Задача",
                instruction = " ",
                idempotencyKey = "owner-plan-session-0002",
                createdAtMillis = 1_000L
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraOwnerPlanRequestFactory.create(
                projectId = "project-0001",
                title = "Задача",
                instruction = "Описание",
                idempotencyKey = "short",
                createdAtMillis = 1_000L
            )
        }
    }
}
