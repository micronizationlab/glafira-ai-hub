package com.micronizationlab.glafira

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.json.JSONObject

/**
 * Bounded HTTPS/1.1 host for the separate one-time pairing and paired-request routes.
 *
 * Pairing proofs are consumed by the enrollment coordinator and only create a pending
 * owner-approval request; they never grant scopes. Paired requests pass through credential,
 * signature, scope, rate-limit and replay admission. This host does not dispatch arbitrary
 * commands or treat admission as operation completion.
 */
class GlafiraBridgeTlsHttpServer(
    private val identity: BridgeServerIdentity,
    private val admission: GlafiraBridgeRequestAdmission,
    private val enrollmentCoordinator: GlafiraBridgeEnrollmentCoordinator,
    private val operationDispatcher: GlafiraBridgeOperationDispatcher,
    private val addressRateLimiter: GlafiraBridgeRateLimiter,
    private val deviceRateLimiter: GlafiraBridgeRateLimiter
) : AutoCloseable {
    @Volatile private var running = false
    @Volatile private var boundPort: Int = DEFAULT_PORT
    private var serverSocket: SSLServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = ThreadPoolExecutor(
        WORKER_COUNT,
        WORKER_COUNT,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(WORK_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "glafira-bridge-http").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    @Synchronized
    fun start(bindAddress: InetAddress, port: Int = DEFAULT_PORT) {
        check(!running) { "Bridge HTTPS listener is already running." }
        require(bindAddress is java.net.Inet4Address && bindAddress.isSiteLocalAddress) {
            "Bridge host must bind to an explicitly selected private IPv4 LAN address."
        }
        require(port in 1024..65_535) { "Bridge HTTPS port is invalid." }
        require(identity.host == bindAddress.hostAddress) {
            "TLS certificate SAN must exactly match the selected LAN IPv4 address."
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(identity.keyManagers, null, SecureRandom())
        }
        val socket = sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
        try {
            socket.reuseAddress = false
            val supported = socket.supportedProtocols.toSet()
            val enabled = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }
            require(enabled.isNotEmpty()) { "No supported secure TLS protocol is available." }
            socket.enabledProtocols = enabled.toTypedArray()
            socket.needClientAuth = false // device authentication is HMAC + owner-approved credential
            socket.soTimeout = ACCEPT_TIMEOUT_MS
            socket.bind(InetSocketAddress(bindAddress, port), WORK_QUEUE_CAPACITY)
            serverSocket = socket
            boundPort = port
            running = true
            acceptThread = Thread({ acceptLoop(socket) }, "glafira-bridge-accept").apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            runCatching { socket.close() }
            running = false
            serverSocket = null
            throw error
        }
    }

    @Synchronized
    override fun close() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        workers.shutdownNow()
    }

    private fun acceptLoop(listener: SSLServerSocket) {
        while (running) {
            val socket = try {
                listener.accept() as SSLSocket
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (running) running = false
                break
            }
            val remote = socket.inetAddress.hostAddress
            if (remote == null) {
                socket.close()
                continue
            }
            if (!addressRateLimiter.tryAcquire(remote)) {
                runCatching { writeResponse(socket, HTTP_TOO_MANY_REQUESTS, errorResponse("RATE_LIMITED")) }
                runCatching { socket.close() }
                continue
            }
            try {
                workers.execute { handle(socket) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: SSLSocket) {
        socket.use { client ->
            try {
                client.soTimeout = HANDSHAKE_TIMEOUT_MS
                client.startHandshake()
                client.soTimeout = HEADER_IDLE_TIMEOUT_MS
                val requestLine = readCrlfLine(client, MAX_REQUEST_LINE_BYTES)
                val requestParts = requestLine.split(' ')
                if (requestParts.size != 3 || requestParts[2] != "HTTP/1.1") {
                    writeResponse(client, HTTP_BAD_REQUEST, errorResponse("INVALID_REQUEST"))
                    return
                }
                val method = requestParts[0]
                val target = requestParts[1]
                val routeError = GlafiraBridgeIngressPolicy.validateRequestLine(method, target)
                if (routeError != null) {
                    val status = if (routeError == GlafiraBridgeIngressRejection.METHOD_NOT_ALLOWED) HTTP_METHOD_NOT_ALLOWED else HTTP_NOT_FOUND
                    writeResponse(client, status, errorResponse("ROUTE_REJECTED"))
                    return
                }

                val headers = readHeaders(client)
                if (headers == null) {
                    writeResponse(client, HTTP_BAD_REQUEST, errorResponse("INVALID_HEADERS"))
                    return
                }
                val expectedHost = if (boundPort == 443) identity.host else "${identity.host}:$boundPort"
                if (!GlafiraBridgeIngressPolicy.validateHostHeader(headers["host"], expectedHost)) {
                    writeResponse(client, HTTP_BAD_REQUEST, errorResponse("INVALID_HOST"))
                    return
                }
                if (headers.containsKey("transfer-encoding") ||
                    headers.containsKey("content-encoding") ||
                    headers.containsKey("expect")
                ) {
                    writeResponse(client, HTTP_BAD_REQUEST, errorResponse("UNSUPPORTED_FRAMING"))
                    return
                }
                val contentLength = headers["content-length"]?.toLongOrNull()
                if (contentLength == null || contentLength !in 1..GlafiraBridgeIngressPolicy.MAX_REQUEST_BODY_BYTES.toLong()) {
                    val status = if (contentLength != null && contentLength > GlafiraBridgeIngressPolicy.MAX_REQUEST_BODY_BYTES) HTTP_TOO_LARGE else HTTP_BAD_REQUEST
                    writeResponse(client, status, errorResponse("INVALID_BODY_LENGTH"))
                    return
                }
                val contentType = headers["content-type"]
                if (!GlafiraBridgeIngressPolicy.validateContentType(contentType)) {
                    writeResponse(client, HTTP_UNSUPPORTED_MEDIA, errorResponse("UNSUPPORTED_CONTENT_TYPE"))
                    return
                }

                client.soTimeout = BODY_IDLE_TIMEOUT_MS
                val body = readExactly(client, contentLength.toInt())
                val bounded = GlafiraBridgeIngressPolicy.readBoundedBody(
                    ByteArrayInputStream(body),
                    contentLength
                )
                val accepted = bounded as? GlafiraBridgeIngressBodyResult.Accepted
                if (accepted == null) {
                    writeResponse(client, HTTP_BAD_REQUEST, errorResponse("INVALID_BODY"))
                    return
                }

                if (target == GlafiraBridgeIngressPolicy.PAIRING_PATH) {
                    val pairing = try {
                        GlafiraBridgePairingJsonCodec.decode(accepted.bytes)
                    } catch (_: Exception) {
                        writeResponse(client, HTTP_BAD_REQUEST, errorResponse("INVALID_PAIRING_ENVELOPE"))
                        return
                    }
                    val result = enrollmentCoordinator.receiveVerifiedEnrollmentProof(
                        ticketId = pairing.ticketId,
                        clientNonce = pairing.clientNonce,
                        proofHex = pairing.proofHex,
                        deviceLabel = pairing.deviceLabel,
                        platform = pairing.platform,
                        requestedScopes = pairing.requestedScopes
                    )
                    when (result) {
                        is GlafiraBridgeEnrollmentSubmission.Pending -> {
                            val body = JSONObject().apply {
                                put("status", "PENDING_OWNER_APPROVAL")
                                put("approvalId", result.approval.approvalId)
                                put("receivedAtEpochMs", result.approval.receivedAtEpochMs)
                            }.toString().toByteArray(Charsets.UTF_8)
                            writeResponse(client, HTTP_ACCEPTED, body)
                        }
                        is GlafiraBridgeEnrollmentSubmission.Rejected ->
                            writeResponse(client, HTTP_FORBIDDEN, errorResponse("PAIRING_${result.error.name}"))
                        GlafiraBridgeEnrollmentSubmission.InvalidMetadata ->
                            writeResponse(client, HTTP_BAD_REQUEST, errorResponse("INVALID_PAIRING_METADATA"))
                        GlafiraBridgeEnrollmentSubmission.QueueStorageError ->
                            writeResponse(client, HTTP_SERVICE_UNAVAILABLE, errorResponse("PAIRING_QUEUE_UNAVAILABLE"))
                    }
                    return
                }

                val request = try {
                    GlafiraBridgeRequestJsonCodec.decode(accepted.bytes)
                } catch (_: Exception) {
                    writeResponse(client, HTTP_BAD_REQUEST, errorResponse("INVALID_ENVELOPE"))
                    return
                }
                val admitted = admission.admit(request) { authenticatedDeviceId ->
                    deviceRateLimiter.tryAcquire(authenticatedDeviceId)
                }
                if (admitted.errorCode == "DEVICE_RATE_LIMITED") {
                    writeResponse(client, HTTP_TOO_MANY_REQUESTS, responseJson(admitted))
                    return
                }
                // Only a fully authenticated, scoped, non-replayed ACCEPTED request reaches Core execution.
                // PENDING_APPROVAL remains a queue decision and is never executed here.
                val response = if (admitted.status == GlafiraBridgeStatus.ACCEPTED) {
                    operationDispatcher.dispatch(request)
                } else admitted
                val status = if (response.status == GlafiraBridgeStatus.ACCEPTED ||
                    response.status == GlafiraBridgeStatus.PENDING_APPROVAL
                ) HTTP_ACCEPTED else HTTP_OK
                writeResponse(client, status, responseJson(response))
            } catch (_: java.net.SocketTimeoutException) {
                runCatching { writeResponse(client, HTTP_REQUEST_TIMEOUT, errorResponse("REQUEST_TIMEOUT")) }
            } catch (_: Exception) {
                runCatching { writeResponse(client, HTTP_BAD_REQUEST, errorResponse("REQUEST_REJECTED")) }
            }
        }
    }

    private fun readHeaders(socket: Socket): Map<String, String>? {
        val headers = LinkedHashMap<String, String>()
        var totalBytes = 0
        repeat(MAX_HEADER_COUNT + 1) {
            val line = readCrlfLine(socket, MAX_HEADER_LINE_BYTES)
            totalBytes += line.toByteArray(Charsets.US_ASCII).size + 2
            if (totalBytes > MAX_HEADER_BYTES) return null
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val name = line.substring(0, colon)
            if (!name.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" }) return null
            val key = name.lowercase(Locale.ROOT)
            if (headers.containsKey(key)) return null
            val value = line.substring(colon + 1).trim()
            if (value.any { it.code < 0x20 && it != '\t' }) return null
            headers[key] = value
        }
        return null
    }

    private fun readCrlfLine(socket: Socket, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        while (output.size() <= maxBytes) {
            val next = socket.getInputStream().read()
            if (next < 0) throw IOException("Unexpected end of HTTP stream.")
            if (next == 13) {
                val lf = socket.getInputStream().read()
                if (lf != 10) throw IOException("Malformed HTTP line ending.")
                return output.toString(Charsets.US_ASCII.name())
            }
            if (next == 10 || next == 0) throw IOException("Malformed HTTP line.")
            output.write(next)
        }
        throw IOException("HTTP line exceeds the configured limit.")
    }

    private fun readExactly(socket: Socket, expectedBytes: Int): ByteArray {
        val result = ByteArray(expectedBytes)
        var offset = 0
        while (offset < expectedBytes) {
            val read = socket.getInputStream().read(result, offset, expectedBytes - offset)
            if (read < 0) throw IOException("Truncated HTTP request body.")
            if (read == 0) continue
            offset += read
        }
        return result
    }

    private fun responseJson(response: GlafiraBridgeResponse): ByteArray =
        JSONObject().apply {
            put("protocolVersion", response.protocolVersion)
            put("requestId", response.requestId)
            put("status", response.status.name)
            put("result", JSONObject(response.result))
            response.errorCode?.let { put("errorCode", it) }
        }.toString().toByteArray(Charsets.UTF_8)

    private fun errorResponse(code: String): ByteArray =
        JSONObject().put("errorCode", code).toString().toByteArray(Charsets.UTF_8)

    private fun writeResponse(socket: Socket, status: Int, body: ByteArray) {
        val reason = when (status) {
            HTTP_OK -> "OK"
            HTTP_ACCEPTED -> "Accepted"
            HTTP_BAD_REQUEST -> "Bad Request"
            HTTP_NOT_FOUND -> "Not Found"
            HTTP_METHOD_NOT_ALLOWED -> "Method Not Allowed"
            HTTP_TOO_LARGE -> "Payload Too Large"
            HTTP_UNSUPPORTED_MEDIA -> "Unsupported Media Type"
            HTTP_REQUEST_TIMEOUT -> "Request Timeout"
            HTTP_TOO_MANY_REQUESTS -> "Too Many Requests"
            HTTP_FORBIDDEN -> "Forbidden"
            HTTP_SERVICE_UNAVAILABLE -> "Service Unavailable"
            else -> "Error"
        }
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: " + body.size + "\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }
    companion object {
        const val DEFAULT_PORT = 47_321
        private const val WORKER_COUNT = 4
        private const val WORK_QUEUE_CAPACITY = 8
        private const val ACCEPT_TIMEOUT_MS = 1_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val HEADER_IDLE_TIMEOUT_MS = 5_000
        private const val BODY_IDLE_TIMEOUT_MS = 5_000
        private const val MAX_REQUEST_LINE_BYTES = 2_048
        private const val MAX_HEADER_LINE_BYTES = 2_048
        private const val MAX_HEADER_BYTES = 8_192
        private const val MAX_HEADER_COUNT = 32
        private const val HTTP_OK = 200
        private const val HTTP_ACCEPTED = 202
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_TOO_LARGE = 413
        private const val HTTP_UNSUPPORTED_MEDIA = 415
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_SERVICE_UNAVAILABLE = 503
    }
}
