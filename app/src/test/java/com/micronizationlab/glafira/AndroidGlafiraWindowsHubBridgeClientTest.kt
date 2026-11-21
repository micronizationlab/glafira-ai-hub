package com.micronizationlab.glafira

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec
import java.util.ArrayDeque
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

class AndroidGlafiraWindowsHubBridgeClientTest {
    private val now = 1_800_000_000_000L
    private val secret = ByteArray(32) { 0x5c }

    @Test
    fun ownershipReadUsesCompositeDeviceSecretAndClearsLoadedCredential() {
        val loaded = credential()
        var exchangeCalls = 0
        var consumerCalls = 0
        val client = AndroidGlafiraWindowsHubOwnershipClient(
            credentials = GlafiraWindowsHubClientCredentialProvider { loaded },
            exchange = GlafiraWindowsHubBridgeExchange { supplied, request ->
                exchangeCalls += 1
                assertEquals("android-device-01", supplied.deviceId)
                assertEquals(GlafiraBridgeOperation.OWNERSHIP_TRANSFER_READ, request.operation)
                assertEquals(mapOf("transferId" to "transfer-0001"), request.payload)
                assertTrue(GlafiraBridgeProtocol.verifySignature(request, secret))
                GlafiraBridgeResponse(
                    protocolVersion = 1,
                    requestId = request.requestId,
                    status = GlafiraBridgeStatus.ACCEPTED,
                    result = mapOf("available" to "false")
                )
            },
            consumer = GlafiraOwnershipBridgeResponseConsumer { request, response, instant ->
                consumerCalls += 1
                assertEquals(request.requestId, response.requestId)
                assertEquals(now, instant.toEpochMilli())
                null
            },
            clock = { now },
            identifier = ArrayDeque(listOf("request-0001", "idempotency-0001"))::removeFirst
        )

        assertNull(client.read("project-0001", "transfer-0001"))
        assertEquals(1, exchangeCalls)
        assertEquals(1, consumerCalls)
        assertTrue(loaded.secretCopy().all { it == 0.toByte() })
    }

    @Test
    fun missingCredentialOrOwnershipScopeFailsBeforeNetwork() {
        var exchangeCalls = 0
        val exchange = GlafiraWindowsHubBridgeExchange { _, _ ->
            exchangeCalls += 1
            throw AssertionError("network must not be called")
        }
        val consumer = GlafiraOwnershipBridgeResponseConsumer { _, _, _ ->
            throw AssertionError("consumer must not be called")
        }
        val missing = AndroidGlafiraWindowsHubOwnershipClient(
            GlafiraWindowsHubClientCredentialProvider { null },
            exchange,
            consumer,
            clock = { now }
        )
        assertThrows(GlafiraWindowsHubCredentialUnavailableException::class.java) {
            missing.read("project-0001", "transfer-0001")
        }

        val scoped = AndroidGlafiraWindowsHubOwnershipClient(
            GlafiraWindowsHubClientCredentialProvider {
                credential(scopes = setOf("project.read"))
            },
            exchange,
            consumer,
            clock = { now }
        )
        assertThrows(IllegalArgumentException::class.java) {
            scoped.read("project-0001", "transfer-0001")
        }
        assertEquals(0, exchangeCalls)
    }

    @Test
    fun pinnedExchangeChecksLeafBeforeSendingSignedRequestAndDecodesResponse() {
        val certificateBytes = ByteArray(128) { it.toByte() }
        val pin = fingerprint(certificateBytes)
        val unsigned = request()
        val signed = GlafiraBridgeProtocol.sign(unsigned, secret)
        val response = JSONObject().apply {
            put("protocolVersion", 1)
            put("requestId", signed.requestId)
            put("status", "ACCEPTED")
            put("result", JSONObject(mapOf("available" to "false")))
            put("errorCode", JSONObject.NULL)
        }.toString()
        val connection = FakeHttpsConnection(
            URL("https://192.168.1.20:8443/bridge/v1"),
            FakeCertificate(certificateBytes, ownershipPublicKey()),
            response
        )

        val decoded = AndroidGlafiraPinnedHttpsBridgeExchange { connection }
            .send(credential(pin = pin), signed)

        assertEquals(signed.requestId, decoded.requestId)
        assertEquals(mapOf("available" to "false"), decoded.result)
        assertTrue(connection.connected)
        assertTrue(connection.certificateRead)
        assertTrue(connection.outputOpenedAfterPinRead)
        val transmitted = JSONObject(connection.requestBytes.toString(Charsets.UTF_8))
        assertEquals("android-device-01", transmitted.getString("deviceId"))
        assertEquals(signed.signatureHex, transmitted.getString("signatureHex"))
        assertEquals("OWNERSHIP_TRANSFER_READ", transmitted.getString("operation"))
    }

    @Test
    fun pinMismatchSendsNoSignedRequest() {
        val connection = FakeHttpsConnection(
            URL("https://192.168.1.20:8443/bridge/v1"),
            FakeCertificate(ByteArray(128) { 0x11 }, ownershipPublicKey()),
            "{}"
        )
        val signed = GlafiraBridgeProtocol.sign(request(), secret)

        assertThrows(IllegalArgumentException::class.java) {
            AndroidGlafiraPinnedHttpsBridgeExchange { connection }
                .send(credential(), signed)
        }

        assertTrue(connection.connected)
        assertTrue(connection.certificateRead)
        assertFalse(connection.outputOpenedAfterPinRead)
        assertEquals(0, connection.requestBytes.size())
    }

    private fun request() = GlafiraBridgeRequest(
        protocolVersion = 1,
        requestId = "request-0001",
        idempotencyKey = "idempotency-0001",
        deviceId = "android-device-01",
        projectId = "project-0001",
        operation = GlafiraBridgeOperation.OWNERSHIP_TRANSFER_READ,
        issuedAtEpochMs = now,
        expiresAtEpochMs = now + 60_000,
        payload = mapOf("transferId" to "transfer-0001")
    )

    private fun credential(
        scopes: Set<String> = setOf("ownership.read", "project.read"),
        pin: String = "a".repeat(64)
    ) = GlafiraWindowsHubClientCredential(
        deviceId = "android-device-01",
        desktopHubId = "desktop-hub-01",
        endpoint = "https://192.168.1.20:8443/bridge/v1",
        tlsCertificateSha256 = pin,
        hmacSecret = secret.copyOf(),
        scopes = scopes,
        enrolledAtEpochMs = now - 1_000,
        expiresAtEpochMs = now + 60_000,
        ownershipAnchor = GlafiraOwnershipTrustAnchor(
            ownerCoreId = "desktop-core-01",
            signingKeyEpoch = 7,
            signingPublicKeySpkiBase64 = Base64.getEncoder()
                .encodeToString(ownershipPublicKey().encoded),
            signingPublicKeySha256 = fingerprint(ownershipPublicKey().encoded)
        )
    )

    private val pair by lazy {
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
    }

    private fun ownershipPublicKey(): PublicKey = pair.public

    private fun fingerprint(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it) }

    private class FakeCertificate(
        private val bytes: ByteArray,
        private val key: PublicKey
    ) : Certificate("X.509") {
        override fun getEncoded(): ByteArray = bytes.copyOf()
        override fun verify(key: PublicKey) = Unit
        override fun verify(key: PublicKey, sigProvider: String) = Unit
        override fun toString(): String = "FakeCertificate"
        override fun getPublicKey(): PublicKey = key
    }

    private class FakeHttpsConnection(
        url: URL,
        private val certificate: Certificate,
        response: String
    ) : HttpsURLConnection(url) {
        private val responseBytes = response.toByteArray(Charsets.UTF_8)
        val requestBytes = ByteArrayOutputStream()
        var connected = false
        var certificateRead = false
        var outputOpenedAfterPinRead = false

        override fun connect() {
            connected = true
        }

        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getCipherSuite(): String = "TLS_AES_128_GCM_SHA256"
        override fun getLocalCertificates(): Array<Certificate>? = null

        override fun getServerCertificates(): Array<Certificate> {
            check(connected)
            certificateRead = true
            return arrayOf(certificate)
        }

        override fun getOutputStream(): OutputStream {
            check(connected)
            outputOpenedAfterPinRead = certificateRead
            return requestBytes
        }

        override fun getInputStream(): InputStream = ByteArrayInputStream(responseBytes)
        override fun getResponseCode(): Int = HttpURLConnection.HTTP_OK
        override fun getContentType(): String = "application/json; charset=utf-8"
        override fun getContentLengthLong(): Long = responseBytes.size.toLong()
    }
}
