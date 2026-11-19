package com.micronizationlab.glafira.desktop

import org.junit.Assert.*
import org.junit.Test

class DesktopLogicalOperationIdTest {
    private val client = DesktopBridgeHttpsClient()
    private val credential = DesktopBridgeCredentials("device-001", ByteArray(32) { 3 },
        setOf("project.read", "conversation.read", "conversation.append"), "c".repeat(64))

    @Test
    fun retryKeepsLogicalIdentityInsideFreshSignedEnvelopes() {
        val payload = mapOf("conversationId" to "project-001_ГЛАФИРА", "messageText" to "Append once")
        val first = client.prepareRequest(credential, "project-001", DesktopBridgeOperation.CONVERSATION_APPEND, payload, "message-2026-001")
        val retry = client.prepareRequest(credential, "project-001", DesktopBridgeOperation.CONVERSATION_APPEND, payload, "message-2026-001")
        assertEquals("message-2026-001", first.idempotencyKey)
        assertEquals(first.idempotencyKey, retry.idempotencyKey)
        assertEquals(first.projectId, retry.projectId)
        assertEquals(first.operation, retry.operation)
        assertEquals(first.payload, retry.payload)
        assertNotEquals(first.requestId, retry.requestId)
        assertNotEquals(first.signatureHex, retry.signatureHex)
        assertTrue(first.issuedAtEpochMs > 0)
        assertTrue(retry.expiresAtEpochMs > retry.issuedAtEpochMs)
    }

    @Test
    fun explicitLogicalIdsAreLimitedToTheThreeDurableCoreOperations() {
        val durable = setOf(DesktopBridgeOperation.PROJECT_READ, DesktopBridgeOperation.CONVERSATION_READ,
            DesktopBridgeOperation.CONVERSATION_APPEND)
        for (operation in DesktopBridgeOperation.entries) {
            if (operation in durable) {
                assertEquals("operation-001", client.prepareRequest(credential, "project-001", operation,
                    emptyMap(), "operation-001").idempotencyKey)
            } else {
                assertThrows(IllegalArgumentException::class.java) {
                    client.prepareRequest(credential, "project-001", operation, emptyMap(), "operation-001")
                }
                val first = client.prepareRequest(credential, "project-001", operation, emptyMap())
                val next = client.prepareRequest(credential, "project-001", operation, emptyMap())
                assertNotEquals(first.idempotencyKey, next.idempotencyKey)
                assertNotEquals(first.requestId, next.requestId)
            }
        }
    }

    @Test
    fun omittedAppendIdFailsBeforeOpeningAnyNetworkConnectionWithMigrationHint() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            client.post("https://invalid.invalid", credential, "project-001",
                DesktopBridgeOperation.CONVERSATION_APPEND, mapOf("messageText" to "hello"))
        }
        assertTrue(error.message!!.contains("--operation-id message-2026-001"))
        val first = client.prepareRequest(credential, "project-001", DesktopBridgeOperation.PROJECT_READ, emptyMap())
        val next = client.prepareRequest(credential, "project-001", DesktopBridgeOperation.PROJECT_READ, emptyMap())
        assertNotEquals(first.idempotencyKey, next.idempotencyKey)
    }

    @Test
    fun validatesIngressCompatibleIdsWithoutNormalization() {
        for (invalid in listOf("", "short", "contains.dot", "has space", "кириллица", "x".repeat(129), "line\nbreak")) {
            assertThrows(IllegalArgumentException::class.java) {
                client.prepareRequest(credential, "project-001", DesktopBridgeOperation.PROJECT_READ, emptyMap(), invalid)
            }
        }
        for (valid in listOf("lower_UPPER-123", "a".repeat(8), "z".repeat(128))) {
            assertEquals(valid, client.prepareRequest(credential, "project-001", DesktopBridgeOperation.PROJECT_READ,
                emptyMap(), valid).idempotencyKey)
        }
    }

    @Test
    fun cliRequiresExplicitUnambiguousOperationIdFlag() {
        assertNull(parseDesktopOperationId(emptyArray()))
        assertEquals("message-001", parseDesktopOperationId(arrayOf("--operation-id", "message-001")))
        assertEquals("message-001", parseDesktopOperationId(arrayOf("--operation-id=message-001")))
        for (invalid in listOf(arrayOf("--operation-id"), arrayOf("--operation-id="),
            arrayOf("--operation-id", "first-001", "--operation-id", "second-002"), arrayOf("--unknown", "value"))) {
            assertThrows(IllegalArgumentException::class.java) { parseDesktopOperationId(invalid) }
        }
    }

    @Test
    fun existingJvmPostAndVaultFacadeSignaturesRemainAvailable() {
        assertNotNull(DesktopBridgeHttpsClient::class.java.getMethod("post", String::class.java,
            DesktopBridgeCredentials::class.java, String::class.java, DesktopBridgeOperation::class.java, Map::class.java))
        assertNotNull(DesktopBridgeEnrollmentFlow::class.java.getMethod("postFromVault", java.nio.file.Path::class.java,
            CharArray::class.java, String::class.java, DesktopBridgeOperation::class.java, Map::class.java))
    }
}
