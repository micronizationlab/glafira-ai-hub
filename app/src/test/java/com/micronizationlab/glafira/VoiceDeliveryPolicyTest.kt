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

    @Test fun messagesWithoutStableOperationIdentityAreNeverSpoken() {
        assertFalse(shouldAutoSpeakAgentReply(message("old"), message("imported", operationId = ""), true))
    }

    @Test fun duplicateHistoryPollDoesNotReplayTheSameReply() {
        val reply = message("answer")
        assertFalse(shouldAutoSpeakAgentReply(reply, reply.copy(), true))
    }

    @Test fun newlyDeliveredAssistantReplyIsSpoken() {
        assertTrue(shouldAutoSpeakAgentReply(message("question", true), message("answer", operationId = "op-2"), true))
    }

    @Test fun identicalTextFromANewOperationIsStillASpeakableReply() {
        assertTrue(shouldAutoSpeakAgentReply(message("same answer", operationId = "op-1"),
            message("same answer", operationId = "op-2"), true))
    }
}
