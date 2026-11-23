package com.micronizationlab.glafira

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraManualTaskFactoryTest {
    @Test
    fun createsEncryptedQueueCompatibleOwnerDraft() {
        val task = GlafiraManualTaskFactory.create(
            projectId = "project-1",
            title = "Проверить отчёт",
            instruction = "Собрать выводы и список следующих шагов.",
            priority = 8,
            nowMillis = 1_800_000_000_000L,
            taskId = "task-1",
            idempotencyKey = "idem-1"
        )

        assertEquals("project-1", task.projectId)
        assertEquals("content.draft", task.taskType)
        assertEquals(GlafiraTaskState.QUEUED, task.state)
        assertEquals(8, task.priority)
        assertEquals("owner.android.task_queue", task.origin)
        assertEquals("Проверить отчёт", JSONObject(task.payload).getString("title"))
        assertTrue(JSONObject(task.payload).getString("actionSummary").isNotBlank())
        GlafiraTaskPolicy.validate(task)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankTaskTitle() {
        GlafiraManualTaskFactory.create("project-1", " ", "Описание", 5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPriorityOutsideRange() {
        GlafiraManualTaskFactory.create("project-1", "Название", "Описание", 11)
    }
}
