package com.micronizationlab.glafira

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

internal fun interface GlafiraWindowsHubBridgeExchange {
    fun send(
        credential: GlafiraWindowsHubClientCredential,
        request: GlafiraBridgeRequest
    ): GlafiraBridgeResponse
}

class GlafiraWindowsHubCredentialUnavailableException : IllegalStateException(
    "WINDOWS_HUB_CREDENTIAL_MISSING_OR_EXPIRED"
)

/**
 * Read-only Android client for authoritative ownership state held by Windows Hub.
 * It cannot sign a transfer, issue a lease, or enqueue work.
 */
internal class AndroidGlafiraWindowsHubOwnershipClient(
    private val credentials: GlafiraWindowsHubClientCredentialProvider,
    private val exchange: GlafiraWindowsHubBridgeExchange,
    private val consumer: GlafiraOwnershipBridgeResponseConsumer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val identifier: () -> String = { UUID.randomUUID().toString() }
) {
    fun read(projectId: String, transferId: String): GlafiraOwnershipSyncResult? {
        val now = clock()
        val credential = credentials.load(now)
            ?: throw GlafiraWindowsHubCredentialUnavailableException()
        try {
            require("ownership.read" in credential.scopes) {
                "WINDOWS_HUB_OWNERSHIP_SCOPE_MISSING"
            }
            val request = GlafiraBridgeRequest(
                protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
                requestId = identifier(),
                idempotencyKey = identifier(),
                deviceId = credential.deviceId,
                projectId = projectId,
                operation = GlafiraBridgeOperation.OWNERSHIP_TRANSFER_READ,
                issuedAtEpochMs = now,
                expiresAtEpochMs = now + REQUEST_TTL_MS,
                payload = mapOf("transferId" to transferId)
            )
            require(GlafiraBridgeProtocol.validate(request, now) == null) {
                "WINDOWS_HUB_OWNERSHIP_REQUEST_INVALID"
            }
            val secret = credential.secretCopy()
            val signed = try {
                GlafiraBridgeProtocol.sign(request, secret)
            } finally {
                secret.fill(0)
            }
            val response = exchange.send(credential, signed)
            return consumer.applyBridgeResponse(signed, response, Instant.ofEpochMilli(now))
        } finally {
            credential.clearSecret()
        }
    }

    companion object {
        private const val REQUEST_TTL_MS = 60_000L
    }
}

/** Strict HTTPS exchange bound to the endpoint, pin and device in the composite credential. */
internal class AndroidGlafiraPinnedHttpsBridgeExchange(
    private val connectionFactory: (URL) -> HttpsURLConnection = { url ->
        url.openConnection() as HttpsURLConnection
    }
) : GlafiraWindowsHubBridgeExchange {
    override fun send(
        credential: GlafiraWindowsHubClientCredential,
        request: GlafiraBridgeRequest
    ): GlafiraBridgeResponse {
        require(request.deviceId == credential.deviceId && request.signatureHex.length == 64) {
            "BRIDGE_REQUEST_CREDENTIAL_BINDING_INVALID"
        }
        val endpoint = validateEndpoint(credential.endpoint)
        require(credential.tlsCertificateSha256.matches(LOWER_SHA256)) {
            "BRIDGE_TLS_PIN_INVALID"
        }
        val requestBytes = encode(request)
        require(requestBytes.size in 1..MAX_REQUEST_BYTES) { "BRIDGE_REQUEST_SIZE_INVALID" }
        val connection = connectionFactory(endpoint.toURL())
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.sslSocketFactory = AndroidGlafiraPinnedHttpsSupport.socketFactory(
                credential.tlsCertificateSha256
            )
            connection.setFixedLengthStreamingMode(requestBytes.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")

            connection.connect()
            AndroidGlafiraPinnedHttpsSupport.verifyConnectedLeaf(
                connection,
                credential.tlsCertificateSha256
            )
            connection.outputStream.use { it.write(requestBytes) }
            require(connection.responseCode in setOf(
                HttpURLConnection.HTTP_OK,
                HttpURLConnection.HTTP_ACCEPTED
            )) { "BRIDGE_HTTP_STATUS_INVALID" }
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            require(contentType == "application/json") { "BRIDGE_CONTENT_TYPE_INVALID" }
            val declaredLength = connection.contentLengthLong
            require(declaredLength == -1L || declaredLength in 1..MAX_RESPONSE_BYTES.toLong()) {
                "BRIDGE_RESPONSE_SIZE_INVALID"
            }
            val responseBytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4_096)
                try {
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_RESPONSE_BYTES) {
                            "BRIDGE_RESPONSE_SIZE_INVALID"
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } finally {
                    buffer.fill(0)
                }
            }
            return try {
                decode(responseBytes, request.requestId)
            } finally {
                responseBytes.fill(0)
            }
        } finally {
            requestBytes.fill(0)
            connection.disconnect()
        }
    }

    private fun encode(request: GlafiraBridgeRequest): ByteArray = JSONObject().apply {
        put("protocolVersion", request.protocolVersion)
        put("requestId", request.requestId)
        put("idempotencyKey", request.idempotencyKey)
        put("deviceId", request.deviceId)
        put("projectId", request.projectId)
        put("operation", request.operation.name)
        put("issuedAtEpochMs", request.issuedAtEpochMs)
        put("expiresAtEpochMs", request.expiresAtEpochMs)
        put("payload", JSONObject(request.payload.toSortedMap()))
        put("signatureHex", request.signatureHex)
    }.toString().toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray, expectedRequestId: String): GlafiraBridgeResponse {
        require(bytes.isNotEmpty()) { "BRIDGE_RESPONSE_EMPTY" }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        val value = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("BRIDGE_RESPONSE_JSON_INVALID", it) }
        require(value.keys().asSequence().toSet() == RESPONSE_FIELDS) {
            "BRIDGE_RESPONSE_FIELDS_INVALID"
        }
        val protocolVersion = exactLong(value, "protocolVersion").toInt()
        val requestId = exactString(value, "requestId")
        require(protocolVersion == GlafiraBridgeProtocol.CURRENT_VERSION &&
            requestId == expectedRequestId) { "BRIDGE_RESPONSE_CORRELATION_INVALID" }
        val status = runCatching { GlafiraBridgeStatus.valueOf(exactString(value, "status")) }
            .getOrElse { throw IllegalArgumentException("BRIDGE_RESPONSE_STATUS_INVALID", it) }
        val resultValue = value.get("result")
        require(resultValue is JSONObject) { "BRIDGE_RESPONSE_RESULT_INVALID" }
        val result = buildMap {
            resultValue.keys().asSequence().forEach { key ->
                val field = resultValue.get(key)
                require(field is String) { "BRIDGE_RESPONSE_RESULT_INVALID" }
                put(key, field)
            }
        }
        val errorCode = if (value.isNull("errorCode")) null else exactString(value, "errorCode")
        return GlafiraBridgeResponse(protocolVersion, requestId, status, result, errorCode)
    }

    private fun validateEndpoint(value: String): URI {
        val endpoint = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("BRIDGE_ENDPOINT_INVALID", it) }
        require(endpoint.scheme == "https" && !endpoint.host.isNullOrBlank() &&
            endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null &&
            endpoint.path == "/bridge/v1" &&
            (endpoint.port == -1 || endpoint.port in 1..65_535)) {
            "BRIDGE_ENDPOINT_INVALID"
        }
        return endpoint
    }

    private fun exactString(value: JSONObject, name: String): String = value.get(name).let {
        require(it is String) { "BRIDGE_RESPONSE_TYPE_INVALID:$name" }
        it
    }

    private fun exactLong(value: JSONObject, name: String): Long = value.get(name).let {
        require(it is Int || it is Long) { "BRIDGE_RESPONSE_TYPE_INVALID:$name" }
        (it as Number).toLong()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_REQUEST_BYTES = 64 * 1_024
        private const val MAX_RESPONSE_BYTES = 1_024 * 1_024
        private val LOWER_SHA256 = Regex("^[a-f0-9]{64}$")
        private val RESPONSE_FIELDS = setOf(
            "protocolVersion", "requestId", "status", "result", "errorCode"
        )
    }
}
