package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

class AndroidGlafiraWindowsHubWorkspaceOutboxTest {
    @get:Rule val temporary = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val secret = ByteArray(32) { 0x5c }

    @Test
    fun encryptedStoreRetainsOldestDurableEntryWithoutPlaintext() {
        val directory = temporary.newFolder()
        val store = store(directory, listOf("operation-new", "operation-old"))
        store.enqueue("project-0001", "conversation-0001", "Новое", "7", now)
        val oldest = store.enqueue("project-0001", "conversation-0001", "Старое", "7", now - 1)

        val restored = store(directory).snapshot()

        assertEquals(2, restored.pendingCount)
        assertEquals(oldest.operationId, restored.oldest?.operationId)
        val raw = directory.resolve("windows_hub_workspace_outbox_v1.json").readText()
        assertTrue(raw.contains("GLAFIRA_WINDOWS_HUB_WORKSPACE_OUTBOX"))
        assertFalse(raw.contains("Новое"))
        assertFalse(raw.contains("Старое"))
    }

    @Test
    fun enqueueRequiresCanonicalVersionAndBlocksConversationWithUnresolvedConflict() {
        val store = store(temporary.newFolder(), listOf("operation-0001", "operation-0002"))
        assertThrows(IllegalArgumentException::class.java) {
            store.enqueue("project-0001", "conversation-0001", "Текст", "01", now)
        }
        val entry = store.enqueue("project-0001", "conversation-0001", "Текст", "7", now)
        store.requireOwnerResolution(entry.operationId, "9")
        assertThrows(IllegalArgumentException::class.java) {
            store.enqueue("project-0001", "conversation-0001", "Ещё", "9", now + 1)
        }
    }

    @Test
    fun acceptedAppendUsesImmutableIdempotencyAndExactAckThenRemovesEntry() {
        val store = store(temporary.newFolder(), listOf("operation-0001"))
        val entry = store.enqueue("project-0001", "conversation-0001", "Офлайн", "7", now - 1)
        val loaded = credential()
        val consumer = consumer(store, loaded) { supplied, request ->
            assertEquals("android-device-01", supplied.deviceId)
            assertEquals(GlafiraBridgeOperation.CONVERSATION_APPEND, request.operation)
            assertEquals(entry.operationId, request.idempotencyKey)
            assertEquals(mapOf(
                "conversationId" to "conversation-0001",
                "messageText" to "Офлайн",
                "baseRemoteVersion" to "7"
            ), request.payload)
            assertTrue(GlafiraBridgeProtocol.verifySignature(request, secret))
            accepted(request, entry, "8", duplicate = false)
        }

        val outcome = consumer.consumeNext()

        assertEquals(AndroidWorkspaceOutboxOutcome.Synchronized(entry, "8", false), outcome)
        assertEquals(0, store.snapshot().pendingCount)
        assertTrue(loaded.secretCopy().all { it == 0.toByte() })
    }

    @Test
    fun duplicateAcknowledgementCompletesLostResponseRetryExactlyOnce() {
        val store = store(temporary.newFolder(), listOf("operation-0001"))
        val entry = store.enqueue("project-0001", "conversation-0001", "Офлайн", "7", now)
        val outcome = consumer(store, credential()) { _, request ->
            accepted(request, entry, "8", duplicate = true)
        }.consumeNext()

        assertEquals(AndroidWorkspaceOutboxOutcome.Synchronized(entry, "8", true), outcome)
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun versionConflictFencesExactEntryUntilOwnerExplicitlyRebases() {
        val store = store(temporary.newFolder(), listOf("operation-0001"))
        val entry = store.enqueue("project-0001", "conversation-0001", "Офлайн", "7", now)
        val outcome = consumer(store, credential()) { _, request ->
            GlafiraBridgeResponse(
                1,
                request.requestId,
                GlafiraBridgeStatus.FAILED,
                mapOf("currentVersion" to "9"),
                "WORKSPACE_VERSION_CONFLICT"
            )
        }.consumeNext()

        assertEquals(AndroidWorkspaceOutboxOutcome.RequiresOwnerResolution(entry, "9"), outcome)
        val conflicted = store.snapshot().oldest!!
        assertEquals(AndroidWorkspaceOutboxState.REQUIRES_OWNER_RESOLUTION, conflicted.state)
        assertEquals("7", conflicted.baseRemoteVersion)
        assertEquals("9", conflicted.currentRemoteVersion)

        store.rebase(entry.operationId, "9")
        assertEquals(AndroidWorkspaceOutboxState.PENDING, store.snapshot().oldest?.state)
        assertEquals("9", store.snapshot().oldest?.baseRemoteVersion)
    }

    @Test
    fun idempotencyConflictRequiresOwnerAndCanBeExplicitlyDiscarded() {
        val store = store(temporary.newFolder(), listOf("operation-0001"))
        val entry = store.enqueue("project-0001", "conversation-0001", "Офлайн", "7", now)
        val outcome = consumer(store, credential()) { _, request ->
            GlafiraBridgeResponse(
                1,
                request.requestId,
                GlafiraBridgeStatus.FAILED,
                emptyMap(),
                "WORKSPACE_IDEMPOTENCY_CONFLICT"
            )
        }.consumeNext()

        assertEquals(AndroidWorkspaceOutboxOutcome.RequiresOwnerResolution(entry, null), outcome)
        assertEquals(1, store.snapshot().conflictCount)
        store.discard(entry.operationId)
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun missingScopeAndTransportFailureKeepOriginalPendingEntry() {
        val directory = temporary.newFolder()
        val store = store(directory, listOf("operation-0001"))
        val entry = store.enqueue("project-0001", "conversation-0001", "Офлайн", "7", now)
        var calls = 0
        val missingScope = consumer(
            store,
            credential(scopes = setOf("ownership.read"))
        ) { _, _ ->
            calls += 1
            error("network must not run")
        }
        assertThrows(IllegalArgumentException::class.java) { missingScope.consumeNext() }
        assertEquals(0, calls)

        val offline = consumer(store, credential()) { _, _ -> throw Offline }
        assertThrows(Offline::class.java) { offline.consumeNext() }
        assertEquals(listOf(entry), store.entries())
    }

    @Test
    fun malformedSuccessAcknowledgementFailsClosedAndKeepsPending() {
        val store = store(temporary.newFolder(), listOf("operation-0001"))
        val entry = store.enqueue("project-0001", "conversation-0001", "Офлайн", "7", now)
        val consumer = consumer(store, credential()) { _, request ->
            GlafiraBridgeResponse(
                1,
                request.requestId,
                GlafiraBridgeStatus.ACCEPTED,
                mapOf(
                    "conversationId" to entry.conversationId,
                    "version" to "8",
                    "operationId" to "substituted-operation",
                    "duplicate" to "false"
                )
            )
        }

        assertThrows(IllegalArgumentException::class.java) { consumer.consumeNext() }
        assertEquals(listOf(entry), store.entries())
    }

    private fun store(
        directory: java.io.File,
        identifiers: List<String> = emptyList()
    ): AndroidWorkspaceOutboxStore {
        val iterator = identifiers.iterator()
        return AndroidWorkspaceOutboxStore(
            directory,
            AesGcmMemoryPayloadProtector(
                SecretKeySpec(ByteArray(32) { 0x33 }, "AES")
            ),
            operationIdentifier = {
                if (iterator.hasNext()) iterator.next() else "operation-fallback"
            }
        )
    }

    private fun consumer(
        store: AndroidWorkspaceOutboxStore,
        credential: GlafiraWindowsHubClientCredential,
        exchange: (GlafiraWindowsHubClientCredential, GlafiraBridgeRequest) -> GlafiraBridgeResponse
    ) = AndroidGlafiraWindowsHubWorkspaceOutboxConsumer(
        store = store,
        credentials = GlafiraWindowsHubClientCredentialProvider { credential },
        exchange = GlafiraWindowsHubBridgeExchange(exchange),
        clock = { now },
        requestIdentifier = { "android-workspace-request-0001" }
    )

    private fun accepted(
        request: GlafiraBridgeRequest,
        entry: AndroidWorkspaceOutboxEntry,
        version: String,
        duplicate: Boolean
    ) = GlafiraBridgeResponse(
        protocolVersion = 1,
        requestId = request.requestId,
        status = GlafiraBridgeStatus.ACCEPTED,
        result = mapOf(
            "conversationId" to entry.conversationId,
            "version" to version,
            "operationId" to entry.operationId,
            "duplicate" to duplicate.toString()
        )
    )

    private fun credential(
        scopes: Set<String> = setOf("ownership.read", "conversation.append")
    ) = GlafiraWindowsHubClientCredential(
        deviceId = "android-device-01",
        desktopHubId = "desktop-hub-01",
        endpoint = "https://192.168.1.20:8443/bridge/v1",
        tlsCertificateSha256 = "a".repeat(64),
        hmacSecret = secret.copyOf(),
        scopes = scopes,
        enrolledAtEpochMs = now - 1_000,
        expiresAtEpochMs = now + 60_000,
        ownershipAnchor = GlafiraOwnershipTrustAnchor(
            ownerCoreId = "desktop-core-01",
            signingKeyEpoch = 7,
            signingPublicKeySpkiBase64 = Base64.getEncoder().encodeToString(publicKey().encoded),
            signingPublicKeySha256 = MessageDigest.getInstance("SHA-256")
                .digest(publicKey().encoded).joinToString("") { "%02x".format(it) }
        )
    )

    private val pair by lazy {
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    }

    private fun publicKey(): PublicKey = pair.public

    private object Offline : RuntimeException()
}
