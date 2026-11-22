package com.micronizationlab.glafira

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

class AndroidGlafiraWindowsHubPairingClientTest {
    private val now = 1_800_000_000_000L
    private val ownershipKey = keyPair()

    @Test
    fun correlatedPinnedExchangeCommitsOnceAndClearsEnrollmentSecret() {
        val sink = RecordingSink()
        val client = AndroidGlafiraWindowsHubPairingClient(
            exchange = GlafiraWindowsHubPairingExchange { response() },
            store = sink,
            clock = { now }
        )

        assertEquals(
            GlafiraWindowsHubCredentialInstallResult.INSTALLED,
            client.enroll(invitation())
        )

        assertEquals(1, sink.installCalls)
        assertEquals("android-device-01", sink.deviceId)
        assertArrayEquals(ByteArray(32) { 0x5c }, sink.secretAtInstall)
        assertTrue(requireNotNull(sink.enrollment).secretCopy().all { it == 0.toByte() })
    }

    @Test
    fun responseSubstitutionOrExchangeFailureNeverCallsStore() {
        val substitutedSink = RecordingSink()
        val substituted = AndroidGlafiraWindowsHubPairingClient(
            exchange = GlafiraWindowsHubPairingExchange {
                JSONObject(response()).put("ownershipSigningKeyEpoch", 8).toString()
            },
            store = substitutedSink,
            clock = { now }
        )
        assertThrows(IllegalArgumentException::class.java) { substituted.enroll(invitation()) }
        assertEquals(0, substitutedSink.installCalls)

        val failedSink = RecordingSink()
        val failed = AndroidGlafiraWindowsHubPairingClient(
            exchange = GlafiraWindowsHubPairingExchange {
                throw IllegalStateException("network unavailable")
            },
            store = failedSink,
            clock = { now }
        )
        assertThrows(IllegalStateException::class.java) { failed.enroll(invitation()) }
        assertEquals(0, failedSink.installCalls)
    }

    @Test
    fun httpsExchangeChecksLeafPinBeforeWritingPairingCode() {
        val certificateBytes = ByteArray(128) { it.toByte() }
        val pin = fingerprint(certificateBytes)
        val connection = FakeHttpsConnection(
            URL("https://192.168.1.20:8443/bridge/v1/pair"),
            FakeCertificate(certificateBytes, ownershipKey.public),
            response(pin)
        )
        val exchange = AndroidGlafiraPinnedHttpsPairingExchange { connection }
        val parsed = GlafiraWindowsHubPairingTrustBinding.parseInvitation(
            invitation(pin),
            now
        )

        val responseText = exchange.redeem(parsed)

        assertEquals(response(pin), responseText)
        assertTrue(connection.connected)
        assertTrue(connection.certificateRead)
        assertTrue(connection.outputOpenedAfterPinRead)
        val request = JSONObject(connection.requestBytes.toString(Charsets.UTF_8))
        assertEquals(
            setOf("version", "ticketId", "pairingCode", "agentId"),
            request.keys().asSequence().toSet()
        )
        assertEquals("android-device-01", request.getString("agentId"))
    }

    @Test
    fun httpsExchangePinMismatchSendsNoPairingCode() {
        val connection = FakeHttpsConnection(
            URL("https://192.168.1.20:8443/bridge/v1/pair"),
            FakeCertificate(ByteArray(128) { 0x11 }, ownershipKey.public),
            response()
        )
        val exchange = AndroidGlafiraPinnedHttpsPairingExchange { connection }
        val parsed = GlafiraWindowsHubPairingTrustBinding.parseInvitation(invitation(), now)

        assertThrows(IllegalArgumentException::class.java) { exchange.redeem(parsed) }

        assertTrue(connection.connected)
        assertTrue(connection.certificateRead)
        assertFalse(connection.outputOpenedAfterPinRead)
        assertEquals(0, connection.requestBytes.size())
    }

    @Test
    fun ownerLifecycleErasesInvitationAfterSuccessAndFailure() {
        val lifecycle = RecordingLifecycle()
        val successBuffer = invitation().toCharArray()
        val success = AndroidGlafiraWindowsHubLifecycleController(
            redeemer = GlafiraWindowsHubInvitationRedeemer {
                assertTrue(it.contains("pairingCode"))
                GlafiraWindowsHubCredentialInstallResult.INSTALLED
            },
            localLifecycle = lifecycle,
            cancelOwnershipWork = { throw AssertionError("pair must not cancel work") }
        )
        assertEquals(GlafiraWindowsHubCredentialInstallResult.INSTALLED, success.pair(successBuffer))
        assertTrue(successBuffer.all { it == '\u0000' })

        val failureBuffer = invitation().toCharArray()
        val failure = AndroidGlafiraWindowsHubLifecycleController(
            redeemer = GlafiraWindowsHubInvitationRedeemer {
                throw IllegalStateException("exchange failed")
            },
            localLifecycle = lifecycle,
            cancelOwnershipWork = { throw AssertionError("pair must not cancel work") }
        )
        assertThrows(IllegalStateException::class.java) { failure.pair(failureBuffer) }
        assertTrue(failureBuffer.all { it == '\u0000' })
        assertEquals(0, lifecycle.unpairCalls)
        assertEquals(0, lifecycle.revokeCalls)
    }

    @Test
    fun ownerDisconnectAndLocalRevokeCancelOnlyScopedOwnershipWork() {
        val lifecycle = RecordingLifecycle()
        var cancellations = 0
        val controller = AndroidGlafiraWindowsHubLifecycleController(
            redeemer = GlafiraWindowsHubInvitationRedeemer {
                throw AssertionError("disconnect must not redeem")
            },
            localLifecycle = lifecycle,
            cancelOwnershipWork = { cancellations += 1 }
        )

        controller.unpair()
        controller.revokeLocalTrust()

        assertEquals(1, lifecycle.unpairCalls)
        assertEquals(1, lifecycle.revokeCalls)
        assertEquals(2, cancellations)
    }

    @Test
    fun ownershipWorkIsCancelledEvenWhenLocalCleanupFailsClosed() {
        var cancellations = 0
        val controller = AndroidGlafiraWindowsHubLifecycleController(
            redeemer = GlafiraWindowsHubInvitationRedeemer {
                GlafiraWindowsHubCredentialInstallResult.UNCHANGED
            },
            localLifecycle = object : GlafiraWindowsHubLocalPairingLifecycle {
                override fun unpair() = throw IllegalStateException("cleanup failed")
                override fun revokeAll() = throw IllegalStateException("cleanup failed")
            },
            cancelOwnershipWork = { cancellations += 1 }
        )

        assertThrows(IllegalStateException::class.java) { controller.unpair() }
        assertThrows(IllegalStateException::class.java) { controller.revokeLocalTrust() }
        assertEquals(2, cancellations)
    }

    private inner class RecordingSink : GlafiraPinnedPairingEnrollmentSink {
        var installCalls = 0
        var deviceId: String? = null
        var secretAtInstall = ByteArray(0)
        var enrollment: GlafiraPinnedPairingEnrollment? = null

        override fun install(
            enrollment: GlafiraPinnedPairingEnrollment
        ): GlafiraWindowsHubCredentialInstallResult {
            installCalls += 1
            deviceId = enrollment.deviceId
            secretAtInstall = enrollment.secretCopy()
            this.enrollment = enrollment
            return GlafiraWindowsHubCredentialInstallResult.INSTALLED
        }
    }

    private class RecordingLifecycle : GlafiraWindowsHubLocalPairingLifecycle {
        var unpairCalls = 0
        var revokeCalls = 0

        override fun unpair() {
            unpairCalls += 1
        }

        override fun revokeAll() {
            revokeCalls += 1
        }
    }

    private fun invitation(pin: String = "a".repeat(64)) = JSONObject().apply {
        put("version", 1)
        put("agentId", "android-device-01")
        put("endpoint", "https://192.168.1.20:8443/bridge/v1")
        put("tlsCertificateSha256", pin)
        put("ownerCoreId", "desktop-core-01")
        put("ownershipSigningKeyEpoch", 7)
        put(
            "ownershipSigningPublicKeySpkiBase64",
            Base64.getEncoder().encodeToString(ownershipKey.public.encoded)
        )
        put("ownershipSigningPublicKeySha256", fingerprint(ownershipKey.public.encoded))
        put("ticketId", "0123456789abcdef0123456789abcdef")
        put(
            "pairingCode",
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0x2a })
        )
        put("scopes", JSONArray(listOf("ownership.read", "project.read")))
        put("expiresAtEpochMs", now + 120_000)
    }.toString()

    private fun response(pin: String = "a".repeat(64)) = JSONObject().apply {
        put("version", 1)
        put("status", "ENROLLED")
        put("errorCode", JSONObject.NULL)
        put("agentId", "android-device-01")
        put("enrollmentId", "0123456789abcdef0123456789abcdef")
        put("desktopHubId", "desktop-hub-01")
        put("endpoint", "https://192.168.1.20:8443/bridge/v1")
        put("tlsCertificateSha256", pin)
        put("ownerCoreId", "desktop-core-01")
        put("ownershipSigningKeyEpoch", 7)
        put(
            "ownershipSigningPublicKeySpkiBase64",
            Base64.getEncoder().encodeToString(ownershipKey.public.encoded)
        )
        put("ownershipSigningPublicKeySha256", fingerprint(ownershipKey.public.encoded))
        put("sharedSecretBase64", Base64.getEncoder().encodeToString(ByteArray(32) { 0x5c }))
        put("scopes", JSONArray(listOf("ownership.read", "project.read")))
        put("enrolledAtEpochMs", now - 1_000)
        put("expiresAtEpochMs", now + 30 * 24 * 60 * 60 * 1_000L)
    }.toString()

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun fingerprint(value: ByteArray) = MessageDigest.getInstance("SHA-256")
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
