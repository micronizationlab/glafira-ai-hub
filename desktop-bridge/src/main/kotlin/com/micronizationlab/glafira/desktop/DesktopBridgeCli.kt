package com.micronizationlab.glafira.desktop

import java.nio.file.Path

/**
 * Terminal test client for owner-approved Android LAN pairing.
 *
 * Run from a real terminal so invitation, credential bundle and passphrase entry can be hidden.
 * No credentials or raw audio are logged. This is a validation utility, not the final Desktop UI.
 */
fun main() {
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
    println("Vault: $vaultPath")
    println("1 — Pair this Desktop with Android")
    println("2 — Use an already imported device credential")
    println("3 — Run the Desktop task worker (requires local Ollama)")
    val selection = console.readLine("Select: ").trim()
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
            var worker: DesktopBridgeTaskWorker? = null
            var shutdownConfirmed = false
            try {
                val projectId = console.readLine("Project ID: ").trim()
                require(projectId.isNotBlank())
                val model = console.readLine("Installed Ollama model name: ").trim()
                require(model.isNotBlank())
                println("Desktop task state and unacknowledged results persist beside the credential vault.")
                println("Android must be paired with scopes desktop.task.claim and desktop.task.result.")
                println("Ollama must be running locally at 127.0.0.1:11434.")
                val activeSession = desktopCliTaskSession(vaultPath)
                try {
                    val createdWorker = DesktopBridgeTaskWorker(
                        origin = entry.origin,
                        credentials = credentials,
                        projectId = projectId,
                        queue = activeSession.queue,
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
                    )
                    worker = createdWorker
                    try {
                        createdWorker.runPollingLoop(shouldContinue = { true })
                    } finally {
                        // If a handler ignores interruption, worker.close() throws. In that case
                        // deliberately retain the OS queue lock and HMAC key until the handler stops.
                        closeDesktopWorkerAndSession(createdWorker, activeSession)
                        shutdownConfirmed = true
                    }
                } finally {
                    if (worker == null) activeSession.close()
                }
            } finally {
                if (worker == null || shutdownConfirmed) credentials.hmacSecret.fill(0)
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
                val conversationId = console.readLine("Conversation ID (projectId_agentName): ").trim()
                val message = console.readLine("Message to append: ")
                DesktopBridgeOperation.CONVERSATION_APPEND to mapOf(
                    "conversationId" to conversationId,
                    "messageText" to message
                )
            }
            else -> error("Unknown operation.")
        }

        val response = flow.postFromVault(
            vaultPath = vaultPath,
            vaultPassphrase = passphrase,
            projectId = projectId,
            operation = operation,
            payload = payload
        )
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
