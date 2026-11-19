package com.micronizationlab.glafira.desktop

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

enum class DesktopBridgeOperation {
    PROJECT_READ,
    CONVERSATION_READ,
    CONVERSATION_APPEND,
    APPROVAL_REQUEST,
    DESKTOP_TASK_CLAIM,
    DESKTOP_TASK_RESULT,
    TASK_STATUS,
    TASK_RESULT_ACK
}

enum class DesktopBridgeStatus {
    ACCEPTED,
    PENDING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

/**
 * The caller must obtain these credentials from an OS-protected credential vault.
 * This transport deliberately does not persist the HMAC secret itself.
 */
data class DesktopBridgeCredentials(
    val deviceId: String,
    val hmacSecret: ByteArray,
    val grantedScopes: Set<String>,
    val serverCertificateSha256: String
) {
    init {
        require(isIdentifier(deviceId))
        require(hmacSecret.size == 32)
        require(grantedScopes.isNotEmpty())
        require(serverCertificateSha256.matches(Regex("[0-9a-fA-F]{64}")))
    }

    companion object {
        private fun isIdentifier(value: String): Boolean =
            value.length in 8..128 && value.all { it.isLetterOrDigit() || it in "-_." }
    }
}

data class DesktopBridgeResponse(
    val requestId: String,
    val status: DesktopBridgeStatus,
    val result: Map<String, String>,
    val errorCode: String?
)

/**
 * Standalone JVM Desktop transport for the Android LAN Bridge.
 *
 * TLS is HTTPS-only, hostname verification remains enabled, and the leaf SHA-256 pin
 * must be delivered through the owner-authenticated pairing channel. This class does
 * not implement credential storage, UI, or arbitrary command execution.
 */
class DesktopBridgeHttpsClient(
    private val connectTimeout: Duration = Duration.ofSeconds(10),
    private val requestTimeout: Duration = Duration.ofSeconds(15)
) {
    @JvmOverloads
    fun post(
        origin: String,
        credentials: DesktopBridgeCredentials,
        projectId: String,
        operation: DesktopBridgeOperation,
        payload: Map<String, String>,
        logicalOperationId: String? = null
    ): DesktopBridgeResponse {
        val request = prepareRequest(credentials, projectId, operation, payload, logicalOperationId)
        val requestId = request.requestId

        val endpoint = parseOrigin(origin)
        val pin = parsePin(credentials.serverCertificateSha256)
        val sslContext = pinnedSslContext(pin)
        val sslParameters = SSLParameters().apply {
            endpointIdentificationAlgorithm = "HTTPS"
            protocols = arrayOf("TLSv1.3", "TLSv1.2")
        }
        val client = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .sslContext(sslContext)
            .sslParameters(sslParameters)
            .build()

        val body = encode(request).toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_REQUEST_BYTES) { "Bridge request exceeds the size limit." }

        val response = try {
            val httpRequest = HttpRequest.newBuilder(endpoint.resolve(REQUEST_PATH))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
            try {
                client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Bridge request was interrupted.", error)
            }
        } finally {
            body.fill(0)
        }
        if (response.statusCode() !in setOf(200, 202)) {
            response.body().close()
            throw IOException("Bridge HTTPS request failed with status ${response.statusCode()}.")
        }
        val responseBytes = response.body().use {
            DesktopBridgeHttpBody.readBounded(it, MAX_RESPONSE_BYTES)
        }
        return try {
            decodeResponse(responseBytes, requestId)
        } finally {
            responseBytes.fill(0)
        }
    }

    private fun pinnedSslContext(expectedPin: ByteArray): SSLContext {
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw java.security.cert.CertificateException("Client certificate is not expected.")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull()
                    ?: throw java.security.cert.CertificateException("Bridge server certificate is missing.")
                if (authType.isNullOrBlank()) {
                    throw java.security.cert.CertificateException("TLS authentication type is missing.")
                }
                leaf.checkValidity()
                if (leaf.basicConstraints != -1 || leaf.keyUsage?.getOrNull(0) != true) {
                    throw java.security.cert.CertificateException("Bridge certificate key usage is invalid.")
                }
                if (SERVER_AUTH_OID !in (leaf.extendedKeyUsage
                        ?: throw java.security.cert.CertificateException("TLS serverAuth EKU is required."))) {
                    throw java.security.cert.CertificateException("Certificate is not valid for TLS server authentication.")
                }
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                if (!MessageDigest.isEqual(actual, expectedPin)) {
                    throw java.security.cert.CertificateException("Bridge server certificate pin mismatch.")
                }
            }
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(manager), null)
        }
    }

    /** New transport envelope, same logical operation on an explicit retry. No retry is performed here. */
    internal fun prepareRequest(
        credentials: DesktopBridgeCredentials,
        projectId: String,
        operation: DesktopBridgeOperation,
        payload: Map<String, String>,
        logicalOperationId: String? = null
    ): BridgeRequest {
        require(isProjectIdentifier(projectId))
        require(payload.size <= 16)
        require(payload.entries.all { (key, value) ->
            key.matches(Regex("[A-Za-z][A-Za-z0-9]{0,63}")) && value.length <= 16_384
        })

        validateLogicalOperationId(logicalOperationId)
        if (logicalOperationId != null) require(operation in DURABLE_CORE_OPERATIONS) {
            "A logical operation ID is supported only for durable Core operations."
        }
        require(operation != DesktopBridgeOperation.CONVERSATION_APPEND || logicalOperationId != null) {
            "CONVERSATION_APPEND requires a stable logical operation ID (CLI: --operation-id message-2026-001). Reuse it with the same project and payload after an uncertain response."
        }
        val issuedAt = System.currentTimeMillis()
        val request = BridgeRequest(
            protocolVersion = PROTOCOL_VERSION,
            requestId = UUID.randomUUID().toString(),
            idempotencyKey = logicalOperationId ?: UUID.randomUUID().toString(),
            deviceId = credentials.deviceId,
            projectId = projectId,
            operation = operation,
            issuedAtEpochMs = issuedAt,
            expiresAtEpochMs = issuedAt + REQUEST_TTL_MS,
            payload = payload.toSortedMap()
        )
        return request.copy(signatureHex = sign(request, credentials.hmacSecret))
    }

    private fun decodeResponse(bytes: ByteArray, expectedRequestId: String): DesktopBridgeResponse {
        val json = JSONObject(Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString())
        val allowed = setOf("protocolVersion", "requestId", "status", "result", "errorCode")
        val actual = buildSet {
            val keys = json.keys()
            while (keys.hasNext()) add(keys.next())
        }
        if (actual.any { it !in allowed } ||
            !actual.containsAll(setOf("protocolVersion", "requestId", "status", "result"))) {
            throw IOException("Bridge response envelope is invalid.")
        }
        if (json.getInt("protocolVersion") != PROTOCOL_VERSION) {
            throw IOException("Bridge response protocol version is unsupported.")
        }
        val requestId = json.getString("requestId")
        if (requestId != expectedRequestId) throw IOException("Bridge response request ID mismatch.")
        val status = runCatching { DesktopBridgeStatus.valueOf(json.getString("status")) }
            .getOrElse { throw IOException("Bridge response status is invalid.") }
        val resultJson = json.getJSONObject("result")
        val result = buildMap {
            val keys = resultJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = resultJson.opt(key)
                if (value !is String) throw IOException("Bridge result contains a non-string value.")
                put(key, value)
            }
        }
        val errorCode = json.optString("errorCode").takeIf { it.isNotBlank() && it != "null" }
        return DesktopBridgeResponse(requestId, status, result, errorCode)
    }

    private fun encode(request: BridgeRequest): String =
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

    private fun sign(request: BridgeRequest, secret: ByteArray): String {
        val fields = buildList {
            add(request.protocolVersion.toString())
            add(request.requestId)
            add(request.idempotencyKey)
            add(request.deviceId)
            add(request.projectId)
            add(request.operation.name)
            add(request.issuedAtEpochMs.toString())
            add(request.expiresAtEpochMs.toString())
            request.payload.toSortedMap().forEach { (key, value) -> add(key); add(value) }
        }
        val canonical = fields.joinToString(separator = "") { "${it.length}:$it" }
            .toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val signature = try {
            mac.doFinal(canonical)
        } finally {
            canonical.fill(0)
        }
        return try {
            signature.joinToString("") { "%02x".format(it) }
        } finally {
            signature.fill(0)
        }
    }

    private fun parseOrigin(value: String): URI {
        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("Invalid Bridge HTTPS origin.", it) }
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        require(uri.port == -1 || uri.port in 1..65_535)
        return uri
    }

    private fun parsePin(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun isProjectIdentifier(value: String): Boolean =
        value == "default" || isIdentifier(value)

    private fun isIdentifier(value: String): Boolean =
        value.length in 8..128 && value.all { it.isLetterOrDigit() || it in "-_." }

    internal data class BridgeRequest(
        val protocolVersion: Int,
        val requestId: String,
        val idempotencyKey: String,
        val deviceId: String,
        val projectId: String,
        val operation: DesktopBridgeOperation,
        val issuedAtEpochMs: Long,
        val expiresAtEpochMs: Long,
        val payload: Map<String, String>,
        val signatureHex: String = ""
    )

    companion object {
        internal fun validateLogicalOperationId(value: String?) {
            require(value == null || value.matches(Regex("[A-Za-z0-9_-]{8,128}"))) {
                "Operation ID must contain 8–128 ASCII letters, digits, '_' or '-'."
            }
        }

        private val DURABLE_CORE_OPERATIONS = setOf(
            DesktopBridgeOperation.PROJECT_READ,
            DesktopBridgeOperation.CONVERSATION_READ,
            DesktopBridgeOperation.CONVERSATION_APPEND
        )
        private const val PROTOCOL_VERSION = 1
        private const val REQUEST_TTL_MS = 60_000L
        private const val REQUEST_PATH = "/bridge/v1/requests"
        private const val MAX_REQUEST_BYTES = 64 * 1024
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1"
    }
}
