package com.micronizationlab.glafira

import org.junit.Assert.*
import org.junit.Test

class GlafiraBridgeDurableTaskIdentityTest {
    private fun task() = GlafiraTask("task-001", "project-001", "conversation.append",
        """{"bridgeRequestId":"request-001","deviceId":"device-001","operation":"CONVERSATION_APPEND","actionSummary":"Append message","conversationId":"conversation-001","messageText":"hello"}""",
        "android-desktop-bridge:device-001", createdAtMillis = 1_000, updatedAtMillis = 1_000,
        idempotencyKey = "operation-001", requiredScopes = setOf("conversation.append"))

    @Test fun freshTransportEnvelopeAndJsonFieldOrderReturnTheSameAction() {
        val original = task()
        val retry = original.copy(taskId = "new-task-id", createdAtMillis = 3_000,
            updatedAtMillis = 3_000, deadlineAtMillis = 60_000,
            payload = """{ "messageText":"hello", "conversationId":"conversation-001", "operation":"CONVERSATION_APPEND", "actionSummary":"Append message", "deviceId":"device-001", "bridgeRequestId":"new-request-001" }""")
        assertTrue(GlafiraBridgeDurableTaskIdentity.equivalent(original, retry))
        assertTrue(GlafiraBridgeDurableTaskIdentity.equivalent(original.copy(state = GlafiraTaskState.SUCCEEDED), retry))
    }

    @Test fun aReceiptCannotBeReusedByAnotherDeviceProjectToolScopeOrAction() {
        val original = task()
        val conflicts = listOf(
            original.copy(projectId = "other-project"),
            original.copy(origin = "android-desktop-bridge:other-device"),
            original.copy(taskType = "conversation.read"),
            original.copy(requiredScopes = emptySet()),
            original.copy(idempotencyKey = "other-operation"),
            original.copy(payload = original.payload.replace("hello", "different message")),
            original.copy(payload = original.payload.replace("device-001", "other-device")),
            original.copy(payload = original.payload.replace("CONVERSATION_APPEND", "CONVERSATION_READ"))
        )
        conflicts.forEach { assertFalse(GlafiraBridgeDurableTaskIdentity.equivalent(original, it)) }
    }

    @Test fun malformedOrDifferentlyTypedStoredPayloadNeverMatches() {
        val original = task()
        listOf("not JSON", "[]", """{"messageText":1}""", """{"messageText":null}""").forEach {
            assertFalse(GlafiraBridgeDurableTaskIdentity.equivalent(original.copy(payload = it), original))
        }
    }
}
