package com.micronizationlab.glafira

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotNull
import org.junit.Test

class GlafiraMobileOwnershipSyncAdapterTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private val fingerprint =
        GlafiraCoreOwnershipTransferCryptography.fingerprint(keyPair.public)

    @Test
    fun signedNextGenerationIsAppliedAndExactRetryIsIdempotent() {
        val store = MemoryStore()
        val adapter = adapter(store)
        val envelope = signed(payload())

        val applied = adapter.apply(envelope, Instant.parse("2026-11-21T12:01:00Z"))
        val duplicate = adapter.apply(envelope, Instant.parse("2026-11-21T12:02:00Z"))

        assertEquals(GlafiraOwnershipSyncDisposition.APPLIED, applied.disposition)
        assertEquals("desktop-core-01", applied.projection.ownerCoreId)
        assertEquals(8L, applied.projection.ownerGeneration)
        assertEquals(GlafiraOwnershipSyncDisposition.EXACT_DUPLICATE, duplicate.disposition)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun staleOrDivergentPreviousProjectionFailsWithoutMutation() {
        val store = MemoryStore()
        val adapter = adapter(store)
        adapter.apply(signed(payload()), Instant.parse("2026-11-21T12:01:00Z"))
        val before = store.value

        val divergent = payload().copy(
            transferId = "transfer-0002",
            nonce = "nonce-000002",
            newOwnerCoreId = "desktop-core-02",
            oldOwnerGeneration = 8,
            newOwnerGeneration = 9
        )
        assertThrows(IllegalArgumentException::class.java) {
            adapter.apply(signed(divergent), Instant.parse("2026-11-21T12:01:30Z"))
        }
        assertEquals(before, store.value)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun tamperedExpiredOrUntrustedTransferNeverSeedsProjection() {
        val original = signed(payload())
        listOf(
            original.copy(payload = original.payload.copy(stateVersion = 43)),
            original,
            original
        ).forEachIndexed { index, envelope ->
            val store = MemoryStore()
            val resolver = if (index == 2) {
                GlafiraOwnershipTrustedKeyResolver { _, _, _ -> null }
            } else trustedResolver()
            val adapter = GlafiraMobileOwnershipSyncAdapter(store, resolver)
            val now = if (index == 1) Instant.parse("2026-11-21T12:05:00Z")
                else Instant.parse("2026-11-21T12:01:00Z")
            assertThrows(IllegalArgumentException::class.java) { adapter.apply(envelope, now) }
            assertNull(store.value)
            assertEquals(0, store.writeCount)
        }
    }

    @Test
    fun strictWireDecoderRejectsUnknownFields() {
        val envelope = signed(payload())
        val json = wireJson(envelope)
        assertEquals(envelope, GlafiraOwnershipTransferEnvelope.parse(json.toString()))

        json.put("unreviewedField", true)
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraOwnershipTransferEnvelope.parse(json.toString())
        }
        json.remove("unreviewedField")
        json.put("oldOwnerGeneration", "7")
        assertThrows(IllegalArgumentException::class.java) {
            GlafiraOwnershipTransferEnvelope.parse(json.toString())
        }
    }

    @Test
    fun bridgeResponseIsCorrelatedProjectBoundAndUsesOutOfBandTrustKey() {
        val now = Instant.parse("2026-11-21T12:01:00Z")
        val envelope = signed(payload())
        val request = ownershipReadRequest(envelope.payload.transferId)
        val response = GlafiraBridgeResponse(
            protocolVersion = 1,
            requestId = request.requestId,
            status = GlafiraBridgeStatus.ACCEPTED,
            result = mapOf(
                "available" to "true",
                "envelope" to wireJson(envelope).toString()
            )
        )
        val store = MemoryStore()

        val applied = adapter(store).applyBridgeResponse(request, response, now)

        assertNotNull(applied)
        assertEquals("desktop-core-01", applied!!.projection.ownerCoreId)
        val rejectedStore = MemoryStore()
        assertThrows(IllegalArgumentException::class.java) {
            adapter(rejectedStore).applyBridgeResponse(
                request,
                response.copy(requestId = "request-owner-read-02"),
                now
            )
        }
        assertNull(rejectedStore.value)
    }

    @Test
    fun bridgeUnavailableResponseMustHaveExactFieldsAndDoesNotMutateProjection() {
        val now = Instant.parse("2026-11-21T12:01:00Z")
        val request = ownershipReadRequest("transfer-0001")
        val store = MemoryStore()
        val response = GlafiraBridgeResponse(
            1, request.requestId, GlafiraBridgeStatus.ACCEPTED,
            mapOf("available" to "false")
        )

        assertNull(adapter(store).applyBridgeResponse(request, response, now))
        assertNull(store.value)
        assertThrows(IllegalArgumentException::class.java) {
            adapter(store).applyBridgeResponse(
                request,
                response.copy(result = response.result + ("envelope" to "{}")),
                now
            )
        }
    }

    private fun adapter(store: MemoryStore) =
        GlafiraMobileOwnershipSyncAdapter(store, trustedResolver())

    private fun ownershipReadRequest(transferId: String) = GlafiraBridgeRequest(
        protocolVersion = 1,
        requestId = "request-owner-read-01",
        idempotencyKey = "idem-owner-read-01",
        deviceId = "device-0001",
        projectId = "project-0001",
        operation = GlafiraBridgeOperation.OWNERSHIP_TRANSFER_READ,
        issuedAtEpochMs = Instant.parse("2026-11-21T12:00:00Z").toEpochMilli(),
        expiresAtEpochMs = Instant.parse("2026-11-21T12:05:00Z").toEpochMilli(),
        payload = mapOf("transferId" to transferId),
        signatureHex = "0".repeat(64)
    )

    private fun trustedResolver() = GlafiraOwnershipTrustedKeyResolver { owner, epoch, digest ->
        keyPair.public.takeIf {
            owner == "android-core-01" && epoch == 3L && digest == fingerprint
        }
    }

    private fun signed(payload: GlafiraOwnershipTransferSigningPayload): GlafiraOwnershipTransferEnvelope {
        val signature = GlafiraCoreOwnershipTransferCryptography.sign(
            payload,
            keyPair.private,
            keyPair.public
        )
        return GlafiraOwnershipTransferEnvelope(
            payload,
            Base64.getEncoder().encodeToString(signature)
        )
    }

    private fun payload() = GlafiraOwnershipTransferSigningPayload(
        schemaVersion = 1,
        transferId = "transfer-0001",
        nonce = "nonce-000001",
        taskId = "task-000001",
        projectId = "project-0001",
        oldOwnerCoreId = "android-core-01",
        newOwnerCoreId = "desktop-core-01",
        oldOwnerGeneration = 7,
        newOwnerGeneration = 8,
        stateVersion = 42,
        payloadSha256 = "a".repeat(64),
        idempotencyKey = "idem-000001",
        effectRisk = "READ_ONLY",
        approvalRef = null,
        issuedAt = "2026-11-21T12:00:00Z",
        expiresAt = "2026-11-21T12:05:00Z",
        signingKeyEpoch = 3,
        signingPublicKeySha256 = fingerprint
    )

    private fun wireJson(envelope: GlafiraOwnershipTransferEnvelope): JSONObject {
        val payload = envelope.payload
        return JSONObject().apply {
            put("schemaVersion", payload.schemaVersion)
            put("transferId", payload.transferId)
            put("nonce", payload.nonce)
            put("taskId", payload.taskId)
            put("projectId", payload.projectId)
            put("oldOwnerCoreId", payload.oldOwnerCoreId)
            put("newOwnerCoreId", payload.newOwnerCoreId)
            put("oldOwnerGeneration", payload.oldOwnerGeneration)
            put("newOwnerGeneration", payload.newOwnerGeneration)
            put("stateVersion", payload.stateVersion)
            put("payloadSha256", payload.payloadSha256)
            put("idempotencyKey", payload.idempotencyKey)
            put("effectRisk", payload.effectRisk)
            put("approvalRef", payload.approvalRef ?: JSONObject.NULL)
            put("issuedAt", payload.issuedAt)
            put("expiresAt", payload.expiresAt)
            put("signingKeyEpoch", payload.signingKeyEpoch)
            put("signingPublicKeySha256", payload.signingPublicKeySha256)
            put("signatureAlgorithm", payload.signatureAlgorithm)
            put("signatureFormat", payload.signatureFormat)
            put("signature", envelope.signatureBase64)
        }
    }

    private class MemoryStore : GlafiraMobileOwnershipProjectionStore {
        var value: GlafiraMobileOwnershipProjection? = null
        var writeCount = 0

        override fun read(taskId: String, projectId: String) = value?.takeIf {
            it.taskId == taskId && it.projectId == projectId
        }

        override fun write(projection: GlafiraMobileOwnershipProjection) {
            value = projection
            writeCount++
        }
    }
}
