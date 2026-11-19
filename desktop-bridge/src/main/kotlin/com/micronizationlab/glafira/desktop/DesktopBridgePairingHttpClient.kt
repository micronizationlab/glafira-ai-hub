package com.micronizationlab.glafira.desktop

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

data class DesktopBridgePairingResponse(val approvalId: String, val status: String, val expiresAtEpochMs: Long?)

/** HTTPS pairing client. An owner-confirmed certificate pin is required before sending proof. */
class DesktopBridgePairingHttpClient(private val timeout: Duration = Duration.ofSeconds(15)) {
    fun submit(origin: String, serverCertificateSha256: String, enrollment: DesktopBridgeEnrollmentRequest): DesktopBridgePairingResponse {
        val uri = URI(origin)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        require(serverCertificateSha256.matches(Regex("[0-9a-fA-F]{64}")))
        val expectedPin = serverCertificateSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw java.security.cert.CertificateException("Unexpected client certificate.") }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Missing Bridge TLS certificate.")
                leaf.checkValidity()
                if (leaf.basicConstraints != -1 || leaf.keyUsage?.getOrNull(0) != true) {
                    throw java.security.cert.CertificateException("Bridge certificate key usage is invalid.")
                }
                if ("1.3.6.1.5.5.7.3.1" !in (leaf.extendedKeyUsage ?: emptyList())) throw java.security.cert.CertificateException("Bridge certificate is not server-authenticated.")
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                if (!MessageDigest.isEqual(actual, expectedPin)) throw java.security.cert.CertificateException("Bridge TLS certificate pin mismatch.")
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustManager), null) }
        val params = SSLParameters().apply { endpointIdentificationAlgorithm = "HTTPS"; protocols = arrayOf("TLSv1.3", "TLSv1.2") }
        val client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).sslContext(ssl).sslParameters(params).build()
        val request = HttpRequest.newBuilder(uri.resolve("/bridge/v1/pairing")).timeout(timeout)
            .header("Content-Type", "application/json; charset=utf-8").header("Accept", "application/json")
            .header("Cache-Control", "no-store").POST(HttpRequest.BodyPublishers.ofByteArray(enrollment.toJsonBytes())).build()
        val response = try { client.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
        catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Bridge pairing was interrupted.", error) }
        if (response.statusCode() != 202) {
            response.body().close()
            throw IOException("Bridge pairing was rejected with HTTP ${response.statusCode()}.")
        }
        val responseBytes = response.body().use { DesktopBridgeHttpBody.readBounded(it, MAX_PAIRING_RESPONSE_BYTES) }
        val json = JSONObject(Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(responseBytes)).toString())
        require(json.getString("status") == "PENDING_OWNER_APPROVAL")
        val approvalId = json.getString("approvalId")
        require(approvalId.length in 16..128 && approvalId.all { it.isLetterOrDigit() || it in "-_" })
        val expires = json.optLong("expiresAtEpochMs").takeIf { it > 0L }
        return DesktopBridgePairingResponse(approvalId, "PENDING_OWNER_APPROVAL", expires)
    }

    companion object { private const val MAX_PAIRING_RESPONSE_BYTES = 8 * 1024 }
}
