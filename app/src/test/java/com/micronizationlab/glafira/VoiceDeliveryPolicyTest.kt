package com.micronizationlab.glafira

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDeliveryPolicyTest {
    private fun message(text: String, isUser: Boolean = false, operationId: String = "op-1") =
        Msg(text, isUser, "12:00", "2026-11-20", operationId)

    @Test fun disabledAutomaticSpeechNeverSpeaks() {
        assertFalse(shouldAutoSpeakAgentReply(message("old"), message("new", operationId = "op-2"), false))
    }

    @Test fun userMessagesAreNeverSpoken() {
        assertFalse(shouldAutoSpeakAgentReply(message("old"), message("question", true, "op-2"), true))
    }

    @Test fun messagesWithoutStableOperationIdentityAreNeverSpokenAfterAnAssistantReply() {
        assertFalse(shouldAutoSpeakAgentReply(message("old"), message("imported", operationId = ""), true))
    }

    @Test fun duplicateHistoryPollDoesNotReplayTheSameReply() {
        val reply = message("answer")
        assertFalse(shouldAutoSpeakAgentReply(reply, reply.copy(), true))
    }

    @Test fun sameAssistantOperationIsNotReplayedWhenSnapshotMetadataChanges() {
        val previous = message("answer", operationId = "op-1")
        val changedSnapshot = previous.copy(time = "12:01")
        assertFalse(shouldAutoSpeakAgentReply(previous, changedSnapshot, true))
    }

    @Test fun assistantReplySpeaksWhenItSharesOperationIdWithItsUserRequest() {
        val request = message("question", isUser = true, operationId = "request-42")
        val reply = message("answer", isUser = false, operationId = "request-42")
        assertTrue(shouldAutoSpeakAgentReply(request, reply, true))
    }

    @Test fun partialFailurePreviewIsNeverSpoken() {\n        val request = message("question", true, "request-7")\n        val partial = message("partial answer", operationId = "request-7:partial")\n        assertFalse(shouldAutoSpeakAgentReply(request, partial, true))\n    }\n\n    @Test fun finalAnswerAfterPartialPreviewIsSpokenOnce() {\n        val partial = message("partial answer", operationId = "request-7:partial")\n        val final = message("final answer", operationId = "request-7")\n        assertTrue(shouldAutoSpeakAgentReply(partial, final, true))\n    }\n\n    @Test fun assistantWithoutStableIdentityIsNeverSpokenEvenAsFirstMessage() {\n        assertFalse(shouldAutoSpeakAgentReply(null, message("imported", operationId = ""), true))\n    }\n\n    @Test fun newlyDeliveredAssistantReplyIsSpoken() {
        assertTrue(shouldAutoSpeakAgentReply(message("old answer"), message("new answer", operationId = "op-2"), true))
    }

    @Test fun firstAssistantReplyInAnEmptyChatCanBeSpoken() {
        assertTrue(shouldAutoSpeakAgentReply(null, message("answer"), true))
    }

    @Test fun identicalTextFromANewOperationIsStillASpeakableReply() {
        assertTrue(shouldAutoSpeakAgentReply(message("same answer", operationId = "op-1"),
            message("same answer", operationId = "op-2"), true))
    }
}
