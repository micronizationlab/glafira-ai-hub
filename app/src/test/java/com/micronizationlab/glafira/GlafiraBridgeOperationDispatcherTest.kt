package com.micronizationlab.glafira

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraBridgeOperationDispatcherTest {
    @Test fun conversationIdsMustBeBoundToTheRequestedProjectAndKnownAgent() {
        assertTrue(
            GlafiraBridgeOperationDispatcher.isAllowedConversationId(
                "default", "default_ГЛАФИРА"
            )
        )
        assertTrue(
            GlafiraBridgeOperationDispatcher.isAllowedConversationId(
                "project-001", "project-001_OpenRouter Free"
            )
        )
        assertFalse(
            GlafiraBridgeOperationDispatcher.isAllowedConversationId(
                "project-001", "other-project_ГЛАФИРА"
            )
        )
        assertFalse(
            GlafiraBridgeOperationDispatcher.isAllowedConversationId(
                "project-001", "project-001_../../secrets"
            )
        )
        assertFalse(
            GlafiraBridgeOperationDispatcher.isAllowedConversationId(
                "project-001", "project-001_unknown-agent"
            )
        )
    }
}
