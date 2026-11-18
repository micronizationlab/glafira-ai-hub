package com.micronizationlab.glafira

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Bounded TLS socket acceptor for an explicitly selected private LAN address.
 *
 * This class never starts itself, discovers an address, binds wildcard interfaces, or opens
 * a public relay. The owner-controlled foreground service must own its lifecycle and supply
 * a bounded HTTP handler. Pairing and paired-request routes remain separate at that layer.
 */
class GlafiraBridgeTlsSocketListener(
    private val identity: GlafiraBridgeServerIdentity,
    private val bindAddress: InetAddress,
    private val port: Int,
    private val maxConcurrentConnections: Int = 8,
    private val handshakeTimeoutMs: Int = 5_000,
    private val socketReadTimeoutMs: Int = 15_000,
    private val onTlsConnection: (SSLSocket) -> Unit
) : Closeable {
    private val lock = Any()
    @Volatile private var running = false
    private var serverSocket: SSLServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = ThreadPoolExecutor(
        maxConcurrentConnections,
        maxConcurrentConnections,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(maxConcurrentConnections),
        ThreadFactory { task -> Thread(task, "glafira-bridge-tls-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    init {
        require(port in 0..65_535)
        require(maxConcurrentConnections in 1..32)
        require(handshakeTimeoutMs in 1..30_000)
        require(socketReadTimeoutMs in 1..120_000)
        require(!bindAddress.isAnyLocalAddress && !bindAddress.isLoopbackAddress && !bindAddress.isMulticastAddress)
        require(bindAddress.isSiteLocalAddress || bindAddress.isLinkLocalAddress) {
            "Bridge listener must bind to a private LAN address."
        }
    }

    val isRunning: Boolean get() = running
    val boundPort: Int get() = synchronized(lock) { serverSocket?.localPort ?: -1 }

    /** Explicit start only. Call from an owner-approved foreground service. */
    fun start() {
        synchronized(lock) {
            check(!running) { "Bridge TLS listener is already running." }
            check(!workers.isShutdown) { "Bridge TLS listener cannot be restarted after stop." }
            val socket = identity.serverSslContext().serverSocketFactory.createServerSocket() as SSLServerSocket
            try {
                val supported = socket.supportedProtocols.toSet()
                val protocols = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }
                check(protocols.isNotEmpty()) { "No supported secure TLS protocol is available." }
                socket.enabledProtocols = protocols.toTypedArray()
                socket.needClientAuth = false
                socket.useClientMode = false
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(bindAddress, port), maxConcurrentConnections * 2)
                serverSocket = socket
                running = true
                acceptThread = Thread({ acceptLoop(socket) }, "glafira-bridge-tls-accept").apply {
                    isDaemon = true
                    start()
                }
            } catch (error: Exception) {
                runCatching { socket.close() }
                serverSocket = null
                running = false
                throw error
            }
        }
    }

    private fun acceptLoop(listener: SSLServerSocket) {
        while (running && !listener.isClosed) {
            val client = try {
                listener.accept() as? SSLSocket ?: continue
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                if (!running) break
                continue
            }
            try {
                workers.execute {
                    client.use { socket ->
                        try {
                            socket.soTimeout = handshakeTimeoutMs
                            socket.tcpNoDelay = true
                            socket.startHandshake()
                            socket.soTimeout = socketReadTimeoutMs
                            onTlsConnection(socket)
                        } catch (_: Exception) {
                            // Do not log peer data, certificate material, or request payloads here.
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!running && serverSocket == null) return
            running = false
            runCatching { serverSocket?.close() }
            serverSocket = null
            acceptThread?.interrupt()
            acceptThread = null
            workers.shutdownNow()
        }
    }
}
