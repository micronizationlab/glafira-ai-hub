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

/**
 * Terminal test client for owner-approved Android LAN pairing.
 *
 * Run from a real terminal so invitation, credential bundle and passphrase entry can be hidden.
 * Usage: glafira-desktop [--operation-id message-2026-001]
 * Repeating an uncertain append requires the same ID, project, operation and exact payload.
 * No credentials or raw audio are logged. This is a validation utility, not the final Desktop UI.
 */
fun main(args: Array<String>) {
    val logicalOperationId = parseDesktopOperationId(args)
    val console = System.console()
        ?: error("Run GLAFIRA Desktop Bridge from a real terminal; hidden secret input is required.")
    val flow = DesktopBridgeEnrollmentFlow()
    val vaultPath = Path.of(
        System.getProperty("user.home"),
        ".glafira-ai-hub",
        "bridge",
        "device.vault"
    )

    println("GLAFIRA AI HUB — Desktop Bridge test client")
    println("Usage: glafira-desktop [--operation-id message-2026-001]")
    println("Vault: $vaultPath")
    println("1 — Pair this Desktop with Android")
    println("2 — Use an already imported device credential")
    println("3 — Run the Desktop task worker (requires local Ollama)")
    val selection = console.readLine("Select: ").trim()
    require(selection != "3" || logicalOperationId == null) {
        "--operation-id applies to an individual Core operation, not the task worker."
    }
    val passphrase = when (selection) {
        "1" -> pairAndImport(console, flow, vaultPath)
        "2", "3" -> readSecret(console, "Vault passphrase: ")
        else -> error("Unknown menu choice.")
    }
    try {
        if (selection == "3") {
            val entry = DesktopBridgeCredentialVault.load(vaultPath, passphrase)
            val credentials = entry.toCredentials()
            entry.hmacSecret.fill(0)
            try {
                val projectId = console.readLine("Project ID: ").trim()
                require(projectId.isNotBlank())
                val model = console.readLine("Installed Ollama model name: ").trim()
                require(model.isNotBlank())
                println("Desktop task state and unacknowledged results persist beside the credential vault.")
                println("Android must be paired with scopes desktop.task.claim and desktop.task.result.")
                println("Ollama must be running locally at 127.0.0.1:11434.")
                desktopCliTaskSession(vaultPath).use { session ->
                    DesktopBridgeTaskWorker(
                        origin = entry.origin,
                        credentials = credentials,
                        projectId = projectId,
                        queue = session.queue,
                        handlers = DesktopLocalAgentRegistry.ollama(model),
                        onDryRunPreview = { preview ->
                            println()
                            println("=== GLAFIRA DRY RUN — PREVIEW ONLY; NOT EXECUTED ===")
                            println("Task: ${preview.taskId}")
                            println("Project: ${preview.projectId}")
                            println("Tool: ${preview.toolId}")
                            println("Risk: ${preview.risk}")
                            println("Simulation: ${preview.simulationStatus}")
                            println("Plan SHA-256: ${preview.planHash}")
                            println("Redacted plan: ${preview.safeJson}")
                            println("No Desktop agent was started for this preview.")
                        }
                    ).use { worker ->
                        worker.runPollingLoop(shouldContinue = { true })
                    }
                }
            } finally {
                credentials.hmacSecret.fill(0)
            }
            return
        }
        val projectId = console.readLine("Project ID (default is allowed): ").trim()
        require(projectId.isNotBlank())
        println("1 — Read project")
        println("2 — Read conversation")
        println("3 — Append user message to conversation")
        val (operation, payload) = when (console.readLine("Operation: ").trim()) {
            "1" -> DesktopBridgeOperation.PROJECT_READ to emptyMap()
            "2" -> {
                val conversationId = console.readLine("Conversation ID (projectId_agentName): ").trim()
                DesktopBridgeOperation.CONVERSATION_READ to mapOf("conversationId" to conversationId)
            }
            "3" -> {
                require(logicalOperationId != null) {
                    "Appending a message requires --operation-id <stable-id>. Choose a unique ID and reuse it with the same project and message after an uncertain response."
                }
                val conversationId = console.readLine("Conversation ID (projectId_agentName): ").trim()
                val message = console.readLine("Message to append: ")
                DesktopBridgeOperation.CONVERSATION_APPEND to mapOf(
                    "conversationId" to conversationId,
                    "messageText" to message
                )
            }
            else -> error("Unknown operation.")
        }

        logicalOperationId?.let {
            println("Logical operation ID: $it. Reuse it only for this same project, operation and payload.")
        }
        val response = try {
            flow.postFromVault(vaultPath, passphrase, projectId, operation, payload, logicalOperationId)
        } catch (error: java.io.IOException) {
            if (logicalOperationId != null) {
                System.err.println("Response not confirmed. No automatic retry was made. Keep operation ID $logicalOperationId and the exact same project and payload for reconciliation or explicit retry.")
            }
            throw error
        }
        println("Bridge status: ${response.status}")
        response.errorCode?.let { println("Error code: $it") }
        response.result.forEach { (key, value) ->
            println("$key: $value")
        }
    } finally {
        passphrase.fill(0.toChar())
    }
}

private fun pairAndImport(
    console: java.io.Console,
    flow: DesktopBridgeEnrollmentFlow,
    vaultPath: Path
): CharArray {
    val invitation = readHiddenText(console, "Paste Android one-time invitation JSON (input hidden): ")
    val scopes = console.readLine(
        "Requested scopes, comma-separated (e.g. project.read,conversation.read): "
    ).split(',').map { it.trim() }.filter(String::isNotEmpty).toSet()
    require(scopes.isNotEmpty() && scopes.all(DesktopBridgeScopePolicy::isValidScope))

    val result = flow.submitInvitation(
        invitationBundle = invitation,
        deviceLabel = "GLAFIRA Desktop",
        requestedScopes = scopes
    )
    println("Pairing status: ${result.status}")
    println("Now approve this Desktop on Android and select the actual granted scopes.")
    val credentialBundle = readHiddenText(
        console,
        "After owner approval, paste the one-time credential bundle (input hidden): "
    )
    val passphrase = readSecret(console, "Create Desktop vault passphrase (12+ characters): ")
    return try {
        flow.importOwnerApprovedCredential(vaultPath, credentialBundle, passphrase)
        println("Credential bundle imported into the encrypted Desktop vault.")
        println("Return to Android and confirm transfer to erase its temporary credential copy.")
        console.readLine("Press Enter after confirming on Android: ")
        passphrase
    } catch (error: Exception) {
        passphrase.fill(0.toChar())
        throw error
    }
}

private fun readHiddenText(console: java.io.Console, prompt: String): String {
    val value = readSecret(console, prompt)
    return try {
        String(value)
    } finally {
        value.fill(0.toChar())
    }
}

private fun readSecret(console: java.io.Console, prompt: String): CharArray =
    console.readPassword("%s", prompt) ?: error("Secret input was cancelled.")

/** Parse before opening the vault or asking for secrets; flags are never inferred from previous runs. */
internal fun parseDesktopOperationId(args: Array<String>): String? {
    if (args.isEmpty()) return null
    val value = when {
        args.size == 2 && args[0] == "--operation-id" -> args[1]
        args.size == 1 && args[0].startsWith("--operation-id=") -> args[0].substringAfter('=')
        else -> throw IllegalArgumentException("Usage: glafira-desktop [--operation-id <stable-id>]")
    }
    DesktopBridgeHttpsClient.validateLogicalOperationId(value)
    return value
}

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
        try { if (lock.isValid) lock.release() }
        finally { try { channel.close() } finally { releaseReservation() } }
    }
}

// Closing a second descriptor for a locked inode can release process-wide POSIX locks.
// Reject another local session before it opens that file, including canonical path aliases.
private val desktopCliSessionPaths = ConcurrentHashMap.newKeySet<Path>()

/** Startup failures release the lock and propagate; no empty in-memory queue is substituted. */
internal fun desktopCliTaskSession(vaultPath: Path): DesktopCliTaskSession {
    val queuePath = desktopCliTaskQueuePath(vaultPath)
    Files.createDirectories(queuePath.parent)
    val lockPath = queuePath.resolveSibling("tasks.lock")
    val reservation = lockPath.toFile().canonicalFile.toPath()
    check(desktopCliSessionPaths.add(reservation)) {
        "A Desktop worker is already using this task queue. Close it before starting another."
    }
    var channel: FileChannel? = null
    var lock: FileLock? = null
    try {
        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            Files.setPosixFilePermissions(lockPath, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX platforms inherit the ACL of the user's private vault directory.
        }
        lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
        check(lock != null) { "A Desktop worker is already using this task queue. Close it before starting another." }
        val queue = DesktopTaskQueue(queuePath)
        queue.list() // Validate existing storage before any remote task can be claimed.
        return DesktopCliTaskSession(queue, channel, lock) { desktopCliSessionPaths.remove(reservation) }
    } catch (error: Throwable) {
        try { if (lock?.isValid == true) lock.release() }
        finally { try { channel?.close() } finally { desktopCliSessionPaths.remove(reservation) } }
        throw error
    }
}

internal fun desktopCliTaskQueuePath(vaultPath: Path): Path =
    requireNotNull(vaultPath.toAbsolutePath().normalize().parent) { "Credential vault needs a parent directory." }
        .resolve("tasks.json")
