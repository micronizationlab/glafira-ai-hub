package com.micronizationlab.glafira

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraToolBrokerTest {
    @Test
    fun readToolExecutesOnlyWhenScopeAndHandlerArePresent() = runBlocking {
        var called = false
        val result = GlafiraToolBroker.invoke(
            GlafiraToolRequest("web.search", "project-1", "Search public web", "query"),
            grantedScopes = setOf("web.search"),
            handlers = mapOf("web.search" to GlafiraToolHandler {
                called = true
                "search results"
            })
        )
        assertTrue(called)
        assertEquals(GlafiraToolCallStatus.SUCCEEDED, result.status)
        assertEquals("search results", result.output)
    }

    @Test
    fun missingScopeFailsBeforeHandlerRuns() = runBlocking {
        var called = false
        val result = GlafiraToolBroker.invoke(
            GlafiraToolRequest("web.search", "project-1", "Search public web", "query"),
            grantedScopes = emptySet(),
            handlers = mapOf("web.search" to GlafiraToolHandler {
                called = true
                "must not run"
            })
        )
        assertEquals(GlafiraToolCallStatus.MISSING_SCOPE, result.status)
        assertNull(result.output)
        assertEquals(false, called)
    }

    @Test
    fun externalWriteRequiresExactOwnerGrant() = runBlocking {
        var called = false
        val request = GlafiraToolRequest(
            "message.send", "project-1", "Send approved message to Alice", "hello"
        )
        val handler = mapOf("message.send" to GlafiraToolHandler {
            called = true
            "sent"
        })
        val scopes = setOf("communications.send")

        val denied = GlafiraToolBroker.invoke(request, scopes, handler)
        assertEquals(GlafiraToolCallStatus.OWNER_APPROVAL_REQUIRED, denied.status)
        assertEquals(false, called)

        val grant = GlafiraToolRegistry.issueGrantForOwnerApprovedRequest(
            "project-1", "message.send", request.actionSummary, 100, 200
        )
        val allowed = GlafiraToolBroker.invoke(request, scopes, handler, grant, now = 150)
        assertEquals(GlafiraToolCallStatus.SUCCEEDED, allowed.status)
        assertTrue(called)
    }

    @Test
    fun grantCannotAuthorizeDifferentAction() = runBlocking {
        val grant = GlafiraToolRegistry.issueGrantForOwnerApprovedRequest(
            "project-1", "message.send", "Send to Alice", 100, 200
        )
        val result = GlafiraToolBroker.invoke(
            GlafiraToolRequest("message.send", "project-1", "Send to Bob", "hello"),
            setOf("communications.send"),
            mapOf("message.send" to GlafiraToolHandler { "sent" }),
            grant,
            now = 150
        )
        assertEquals(GlafiraToolCallStatus.OWNER_APPROVAL_REQUIRED, result.status)
    }

    @Test
    fun missingHandlerFailsClosed() = runBlocking {
        val result = GlafiraToolBroker.invoke(
            GlafiraToolRequest("web.open", "project-1", "Read public page", "https://example.org"),
            setOf("web.open"),
            emptyMap()
        )
        assertEquals(GlafiraToolCallStatus.HANDLER_NOT_REGISTERED, result.status)
    }
}
