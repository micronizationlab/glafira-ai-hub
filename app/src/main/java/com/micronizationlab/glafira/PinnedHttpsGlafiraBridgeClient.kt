package com.micronizationlab.glafira

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

/**
 * HTTPS-only Bridge client with an owner-supplied SHA-256 leaf-certificate pin.
 *
 * Hostname verification remains enabled in addition to certificate pinning. The caller
 * must obtain the fingerprint through a trusted owner-controlled pairing channel, never
 * from an unauthenticated network response. Invoke from a worker/IO thread.
 */
class PinnedHttpsGlafiraBridgeClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES
) {
    init {
        require(connectTimeoutMs in 1..60_000)
        require(readTimeoutMs in 1..120_000)
        require(maxResponseBytes in 1..MAX_RESPONSE_BYTES)
    }

    @Throws(IOException::class)
    fun post(
        origin: String,
        expectedCertificateSha256: String,
        request: GlafiraBridgeRequest
    ): GlafiraBridgeResponse {
        val endpoint = BridgeHttpsEndpoint.parse(origin)
        val pin = BridgeHttpsEndpoint.parseFingerprint(expectedCertificateSha256)
        val sslContext = pinnedSslContext(pin)
        val url = endpoint.resolveRequestUrl().toURL()
        val body = encodeRequest(request).toByteArray(Charsets.UTF_8)
        if (body.size > MAX_REQUEST_BYTES) throw IOException("Bridge request exceeds the size limit.")

        val connection = (url.openConnection() as? HttpsURLConnection)
            ?: throw IOException("Bridge endpoint did not create an HTTPS connection.")
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Connection", "close")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            if (status != HttpsURLConnection.HTTP_OK && status != HTTP_ACCEPTED) {
                throw IOException("Bridge HTTPS request failed with status $status.")
            }
            val responseBytes = connection.inputStream.use { it.readLimited(maxResponseBytes) }
            return decodeResponse(String(responseBytes, Charsets.UTF_8), request.requestId)
        } finally {
            connection.disconnect()
        }
    }

    private fun encodeRequest(request: GlafiraBridgeRequest): String =
        JSONObject().apply {
            put("protocolVersion", request.protocolVersion)
            put("requestId", request.requestId)
            put("idempotencyKey", request.idempotencyKey)
            put("deviceId", request.deviceId)
            put("projectId", request.projectId)
            put("operation", request.operation.name)
            put("issuedAtEpochMs", request.issuedAtEpochMs)
            put("expiresAtEpochMs", request.expiresAtEpochMs)
            put("payload", JSONObject(request.payload))
            put("signatureHex", request.signatureHex)
        }.toString()

    private fun decodeResponse(json: String, expectedRequestId: String): GlafiraBridgeResponse {
        val value = JSONObject(json)
        val requestId = value.getString("requestId")
        if (requestId != expectedRequestId) throw IOException("Bridge response request ID mismatch.")
        val version = value.getInt("protocolVersion")
        if (version != GlafiraBridgeProtocol.CURRENT_VERSION) {
            throw IOException("Bridge response protocol version is unsupported.")
        }
        val status = runCatching {
            GlafiraBridgeStatus.valueOf(value.getString("status"))
        }.getOrElse { throw IOException("Bridge response status is invalid.") }
        val resultJson = value.optJSONObject("result") ?: JSONObject()
        val result = buildMap {
            val keys = resultJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = resultJson.opt(key)
                if (item is String) put(key, item)
            }
        }
        return GlafiraBridgeResponse(
            protocolVersion = version,
            requestId = requestId,
            status = status,
            result = result,
            errorCode = value.optString("errorCode").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    private fun pinnedSslContext(expectedPin: ByteArray): SSLContext {
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificates are not accepted by the Bridge client.")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull()
                    ?: throw CertificateException("Bridge server did not provide a certificate.")
                if (authType.isNullOrBlank()) throw CertificateException("TLS authentication type is missing.")
                leaf.checkValidity()
                if (leaf.basicConstraints != -1 || leaf.keyUsage?.getOrNull(0) != true) {
                    throw CertificateException("Pinned Bridge certificate key usage is invalid.")
                }
                val extendedUsage = leaf.extendedKeyUsage
                    ?: throw CertificateException("Pinned certificate must declare TLS serverAuth EKU.")
                if (SERVER_AUTH_OID !in extendedUsage) {
                    throw CertificateException("Pinned certificate is not valid for TLS server authentication.")
                }
                if (!leaf.publicKey.algorithm.equals("EC", ignoreCase = true)) {
                    throw CertificateException("Pinned Bridge certificate must use an EC public key.")
                }
                val actualPin = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                if (!MessageDigest.isEqual(actualPin, expectedPin)) {
                    throw CertificateException("Bridge server certificate pin mismatch.")
                }
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
    }

    private fun java.io.InputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("Bridge response exceeds the size limit.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        private const val DEFAULT_READ_TIMEOUT_MS = 15_000
        private const val MAX_REQUEST_BYTES = 256 * 1024
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val HTTP_ACCEPTED = 202
        private const val SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1"
    }
}

internal class BridgeHttpsEndpoint private constructor(private val origin: URI) {
    fun resolveRequestUrl(): URI = origin.resolve("/bridge/v1/requests")

    companion object {
        fun parse(value: String): BridgeHttpsEndpoint {
            val uri = runCatching { URI(value) }
                .getOrElse { throw IllegalArgumentException("Invalid Bridge HTTPS origin.") }
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "Bridge transport requires HTTPS; plaintext HTTP is forbidden."
            }
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null
            ) { "Bridge origin must contain only an HTTPS host and optional port." }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "Bridge origin must not contain a path."
            }
            require(uri.port == -1 || uri.port in 1..65_535) { "Bridge HTTPS port is invalid." }
            return BridgeHttpsEndpoint(uri)
        }

        fun parseFingerprint(value: String): ByteArray {
            require(value.length == 64 && value.all { it in "0123456789abcdefABCDEF" }) {
                "Bridge certificate fingerprint must be a 32-byte SHA-256 hex value."
            }
            return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }
}
