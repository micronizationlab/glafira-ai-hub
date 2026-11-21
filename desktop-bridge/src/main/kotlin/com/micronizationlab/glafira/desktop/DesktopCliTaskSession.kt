package com.micronizationlab.glafira.desktop

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The process lock is on a separate file because queue persistence replaces tasks.json. */
internal class DesktopCliTaskSession(
    val queue: DesktopTaskQueue,
    private val channel: FileChannel,
    private val lock: FileLock,
    private val releaseReservation: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { queue.close() }
        finally {
            try { if (lock.isValid) lock.release() }
            finally { try { channel.close() } finally { releaseReservation() } }
        }
    }
}

// Closing a second descriptor for a locked inode can release process-wide POSIX locks.
// Reject another local session before it opens that file, including canonical path aliases.
private val desktopCliSessionPaths = ConcurrentHashMap.newKeySet<Path>()

/** Startup failures release the lock and propagate; no empty in-memory queue is substituted. */
internal fun desktopCliTaskSession(
    vaultPath: Path,
    binding: String,
    hmacSecret: ByteArray
): DesktopCliTaskSession {
    val queuePath = desktopCliTaskQueuePath(vaultPath, binding)
    Files.createDirectories(queuePath.parent)
    val lockPath = queuePath.resolveSibling("tasks.lock")
    val reservation = lockPath.toFile().canonicalFile.toPath()
    check(desktopCliSessionPaths.add(reservation)) {
        "A Desktop worker is already using this task queue. Close it before starting another."
    }
    var channel: FileChannel? = null
    var lock: FileLock? = null
    var queue: DesktopTaskQueue? = null
    try {
        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            Files.setPosixFilePermissions(lockPath, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX platforms inherit the ACL of the user's private vault directory.
        }
        lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
        check(lock != null) { "A Desktop worker is already using this task queue. Close it before starting another." }
        val unboundLegacyPath = desktopCliTaskQueuePath(vaultPath)
        if (unboundLegacyPath != queuePath && Files.isRegularFile(unboundLegacyPath) &&
            isPlaintextDesktopQueue(unboundLegacyPath)
        ) {
            DesktopTaskQueue(
                unboundLegacyPath,
                protector = PairingDesktopTaskQueueProtector.fromPairing(binding, hmacSecret)
            ).use { it.list() }
        }
        queue = DesktopTaskQueue(
            queuePath,
            protector = PairingDesktopTaskQueueProtector.fromPairing(binding, hmacSecret)
        )
        queue.list() // Validate/migrate storage before any remote task can be claimed.
        return DesktopCliTaskSession(queue, channel, lock) { desktopCliSessionPaths.remove(reservation) }
    } catch (error: Throwable) {
        try { queue?.close() }
        finally {
            try { if (lock?.isValid == true) lock.release() }
            finally { try { channel?.close() } finally { desktopCliSessionPaths.remove(reservation) } }
        }
        throw error
    }
}

private fun isPlaintextDesktopQueue(path: Path): Boolean {
    val prefix = ByteArray(4)
    val count = Files.newInputStream(path).use { it.read(prefix) }
    return count < 4 || String(prefix, Charsets.US_ASCII) != "GLFQ"
}

internal fun desktopCliTaskQueuePath(vaultPath: Path, binding: String? = null): Path {
    require(binding == null || binding.matches(Regex("[0-9a-f]{64}")))
    return requireNotNull(vaultPath.toAbsolutePath().normalize().parent) { "Credential vault needs a parent directory." }
        .resolve(if (binding == null) "tasks.json" else "tasks-$binding.json")
}

/** Public pairing identity only; HMAC secret bytes must never become filesystem identifiers. */
internal fun desktopCliPairingBinding(origin: String, credentials: DesktopBridgeCredentials): String {
    val identity = listOf("glafira-desktop-outbox-v1", origin, credentials.deviceId,
        credentials.serverCertificateSha256.lowercase()).joinToString("\n")
    return java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
/**
 * Release the durable queue lock only after the worker confirms that every handler has stopped.
 * If close fails, the session deliberately remains open and retains its OS lock.
 */
internal fun closeDesktopWorkerAndSession(
    worker: AutoCloseable,
    session: DesktopCliTaskSession
) {
    worker.close()
    session.close()
}
