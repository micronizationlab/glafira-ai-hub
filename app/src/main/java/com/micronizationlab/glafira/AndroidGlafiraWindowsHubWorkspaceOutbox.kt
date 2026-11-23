package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class AndroidWorkspaceOutboxState {
    PENDING,
    REQUIRES_OWNER_RESOLUTION
}

internal data class AndroidWorkspaceOutboxEntry(
    val operationId: String,
    val projectId: String,
    val conversationId: String,
    val messageText: String,
    val baseRemoteVersion: String?,
    val createdAtEpochMs: Long,
    val state: AndroidWorkspaceOutboxState,
    val currentRemoteVersion: String? = null
)

internal data class AndroidWorkspaceOutboxSnapshot(
    val pendingCount: Int,
    val conflictCount: Int,
    val oldest: AndroidWorkspaceOutboxEntry?
)

internal sealed interface AndroidWorkspaceOutboxOutcome {
    data object NoPendingWork : AndroidWorkspaceOutboxOutcome
    data class Synchronized(
        val entry: AndroidWorkspaceOutboxEntry,
        val newRemoteVersion: String,
        val duplicate: Boolean
    ) : AndroidWorkspaceOutboxOutcome
    data class RequiresOwnerResolution(
        val entry: AndroidWorkspaceOutboxEntry,
        val currentRemoteVersion: String?
    ) : AndroidWorkspaceOutboxOutcome
    data class Unavailable(
        val entry: AndroidWorkspaceOutboxEntry,
        val errorCode: String?
    ) : AndroidWorkspaceOutboxOutcome
}

/**
 * Device-encrypted, durable Android outbox for owner-authored Windows Hub writes.
 * It is intentionally separate from PendingMessageStore, which owns AI-provider work.
 */
internal class AndroidWorkspaceOutboxStore(
    directory: File,
    private val protector: MemoryPayloadProtector,
    private val operationIdentifier: () -> String = {
        "android-workspace-" + UUID.randomUUID().toString().lowercase()
    }
) {
    private val payloadId = MemoryIdentifier("android-windows-hub-workspace-outbox:v1")
    private val store = DurableJsonFile(
        storageFile(directory, FILE_NAME),
        decode = ::decode,
        encode = ::encode
    )

    constructor(context: Context) : this(
        directory = context.noBackupFilesDir,
        protector = AndroidWorkspaceOutboxCryptoRuntime.protector()
    )

    fun enqueue(
        projectId: String,
        conversationId: String,
        messageText: String,
        baseRemoteVersion: String,
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): AndroidWorkspaceOutboxEntry {
        require(isProjectIdentifier(projectId)) { "WORKSPACE_PROJECT_ID_INVALID" }
        require(isIdentifier(conversationId)) { "WORKSPACE_CONVERSATION_ID_INVALID" }
        require(messageText.isNotBlank() && messageText.length <= MAX_MESSAGE_CHARS) {
            "WORKSPACE_MESSAGE_INVALID"
        }
        require(isCanonicalPositiveVersion(baseRemoteVersion)) {
            "WORKSPACE_BASE_VERSION_INVALID"
        }
        require(createdAtEpochMs > 0) { "WORKSPACE_CREATED_AT_INVALID" }
        val operationId = operationIdentifier()
        require(isIdentifier(operationId)) { "WORKSPACE_OPERATION_ID_INVALID" }
        val entry = AndroidWorkspaceOutboxEntry(
            operationId = operationId,
            projectId = projectId,
            conversationId = conversationId,
            messageText = messageText,
            baseRemoteVersion = baseRemoteVersion,
            createdAtEpochMs = createdAtEpochMs,
            state = AndroidWorkspaceOutboxState.PENDING
        )
        store.update({ emptyList() }) { entries ->
            require(entries.size < MAX_ENTRIES) { "WORKSPACE_OUTBOX_LIMIT_REACHED" }
            require(entries.none { it.operationId == operationId }) {
                "WORKSPACE_OPERATION_ID_DUPLICATE"
            }
            require(entries.none {
                it.projectId == projectId &&
                    it.conversationId == conversationId &&
                    it.state == AndroidWorkspaceOutboxState.REQUIRES_OWNER_RESOLUTION
            }) { "WORKSPACE_CONFLICT_REQUIRES_OWNER_RESOLUTION" }
            entries + entry
        }
        return entry
    }

    fun oldestPending(): AndroidWorkspaceOutboxEntry? = entries()
        .filter { it.state == AndroidWorkspaceOutboxState.PENDING }
        .minWithOrNull(compareBy(AndroidWorkspaceOutboxEntry::createdAtEpochMs, AndroidWorkspaceOutboxEntry::operationId))

    fun snapshot(): AndroidWorkspaceOutboxSnapshot {
        val values = entries().sortedWith(
            compareBy(AndroidWorkspaceOutboxEntry::createdAtEpochMs, AndroidWorkspaceOutboxEntry::operationId)
        )
        return AndroidWorkspaceOutboxSnapshot(
            pendingCount = values.count { it.state == AndroidWorkspaceOutboxState.PENDING },
            conflictCount = values.count { it.state == AndroidWorkspaceOutboxState.REQUIRES_OWNER_RESOLUTION },
            oldest = values.firstOrNull {
                it.state == AndroidWorkspaceOutboxState.REQUIRES_OWNER_RESOLUTION
            } ?: values.firstOrNull()
        )
    }

    fun acknowledge(operationId: String, newRemoteVersion: String) {
        require(isCanonicalPositiveVersion(newRemoteVersion)) { "WORKSPACE_REMOTE_VERSION_INVALID" }
        store.update({ emptyList() }) { entries ->
            val entry = entries.firstOrNull { it.operationId == operationId }
                ?: error("WORKSPACE_OPERATION_NOT_FOUND")
            check(entry.state == AndroidWorkspaceOutboxState.PENDING) {
                "WORKSPACE_OPERATION_REQUIRES_OWNER_RESOLUTION"
            }
            entries.filterNot { it.operationId == operationId }
        }
    }

    fun requireOwnerResolution(operationId: String, currentRemoteVersion: String?) {
        require(currentRemoteVersion == null || isCanonicalPositiveVersion(currentRemoteVersion)) {
            "WORKSPACE_REMOTE_VERSION_INVALID"
        }
        updateEntry(operationId) {
            it.copy(
                state = AndroidWorkspaceOutboxState.REQUIRES_OWNER_RESOLUTION,
                currentRemoteVersion = currentRemoteVersion
            )
        }
    }

    fun rebase(operationId: String, newBaseRemoteVersion: String) {
        require(isCanonicalPositiveVersion(newBaseRemoteVersion)) {
            "WORKSPACE_BASE_VERSION_INVALID"
        }
        updateEntry(operationId) {
            check(it.state == AndroidWorkspaceOutboxState.REQUIRES_OWNER_RESOLUTION) {
                "WORKSPACE_OPERATION_NOT_CONFLICTED"
            }
            it.copy(
                baseRemoteVersion = newBaseRemoteVersion,
                state = AndroidWorkspaceOutboxState.PENDING,
                currentRemoteVersion = null
            )
        }
    }

    fun discard(operationId: String) {
        store.update({ emptyList() }) { entries ->
            val entry = entries.firstOrNull { it.operationId == operationId }
                ?: error("WORKSPACE_OPERATION_NOT_FOUND")
            check(entry.state == AndroidWorkspaceOutboxState.REQUIRES_OWNER_RESOLUTION) {
                "WORKSPACE_OPERATION_NOT_CONFLICTED"
            }
            entries.filterNot { it.operationId == operationId }
        }
    }

    internal fun entries(): List<AndroidWorkspaceOutboxEntry> = store.read { emptyList() }

    private fun updateEntry(
        operationId: String,
        transform: (AndroidWorkspaceOutboxEntry) -> AndroidWorkspaceOutboxEntry
    ) {
        store.update({ emptyList() }) { entries ->
            val index = entries.indexOfFirst { it.operationId == operationId }
            check(index >= 0) { "WORKSPACE_OPERATION_NOT_FOUND" }
            entries.toMutableList().apply { this[index] = transform(this[index]) }
        }
    }

    private fun encode(entries: List<AndroidWorkspaceOutboxEntry>): String {
        validateEntries(entries)
        val plaintext = JSONObject().apply {
            put("schemaVersion", 1)
            put("entries", JSONArray().apply {
                entries.forEach { entry ->
                    put(JSONObject().apply {
                        put("operationId", entry.operationId)
                        put("projectId", entry.projectId)
                        put("conversationId", entry.conversationId)
                        put("messageText", entry.messageText)
                        put("baseRemoteVersion", entry.baseRemoteVersion ?: JSONObject.NULL)
                        put("createdAtEpochMs", entry.createdAtEpochMs)
                        put("state", entry.state.name)
                        put("currentRemoteVersion", entry.currentRemoteVersion ?: JSONObject.NULL)
                    })
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)
        return try {
            JSONObject().apply {
                put("format", FORMAT)
                put("version", 1)
                put("payload", protector.protect(payloadId, plaintext))
            }.toString()
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decode(raw: String): List<AndroidWorkspaceOutboxEntry> {
        val envelope = JSONObject(raw)
        require(envelope.keys().asSequence().toSet() == ENVELOPE_FIELDS) {
            "WORKSPACE_OUTBOX_ENVELOPE_INVALID"
        }
        require(envelope.getString("format") == FORMAT && envelope.getInt("version") == 1) {
            "WORKSPACE_OUTBOX_VERSION_INVALID"
        }
        val plaintext = protector.unprotect(payloadId, envelope.getString("payload"))
        return try {
            val value = JSONObject(String(plaintext, Charsets.UTF_8))
            require(value.keys().asSequence().toSet() == PAYLOAD_FIELDS &&
                value.getInt("schemaVersion") == 1) { "WORKSPACE_OUTBOX_PAYLOAD_INVALID" }
            val array = value.getJSONArray("entries")
            require(array.length() <= MAX_ENTRIES) { "WORKSPACE_OUTBOX_LIMIT_REACHED" }
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                require(item.keys().asSequence().toSet() == ENTRY_FIELDS) {
                    "WORKSPACE_OUTBOX_ENTRY_INVALID"
                }
                AndroidWorkspaceOutboxEntry(
                    operationId = item.getString("operationId"),
                    projectId = item.getString("projectId"),
                    conversationId = item.getString("conversationId"),
                    messageText = item.getString("messageText"),
                    baseRemoteVersion = item.nullableString("baseRemoteVersion"),
                    createdAtEpochMs = item.getLong("createdAtEpochMs"),
                    state = AndroidWorkspaceOutboxState.valueOf(item.getString("state")),
                    currentRemoteVersion = item.nullableString("currentRemoteVersion")
                )
            }.also(::validateEntries)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun validateEntries(entries: List<AndroidWorkspaceOutboxEntry>) {
        require(entries.size <= MAX_ENTRIES && entries.map { it.operationId }.distinct().size == entries.size) {
            "WORKSPACE_OUTBOX_INVALID"
        }
        entries.forEach { entry ->
            require(isIdentifier(entry.operationId) && isProjectIdentifier(entry.projectId) &&
                isIdentifier(entry.conversationId) && entry.messageText.isNotBlank() &&
                entry.messageText.length <= MAX_MESSAGE_CHARS && entry.createdAtEpochMs > 0 &&
                (entry.baseRemoteVersion == null || isCanonicalPositiveVersion(entry.baseRemoteVersion)) &&
                (entry.currentRemoteVersion == null || isCanonicalPositiveVersion(entry.currentRemoteVersion))
            ) { "WORKSPACE_OUTBOX_ENTRY_INVALID" }
            require(entry.state == AndroidWorkspaceOutboxState.REQUIRES_OWNER_RESOLUTION ||
                entry.currentRemoteVersion == null) { "WORKSPACE_OUTBOX_ENTRY_INVALID" }
        }
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else get(name).let {
            require(it is String) { "WORKSPACE_OUTBOX_ENTRY_INVALID" }
            it
        }

    companion object {
        private const val FILE_NAME = "windows_hub_workspace_outbox_v1.json"
        private const val FORMAT = "GLAFIRA_WINDOWS_HUB_WORKSPACE_OUTBOX"
        private const val MAX_ENTRIES = 2_000
        private const val MAX_MESSAGE_CHARS = 16_384
        private val ENVELOPE_FIELDS = setOf("format", "version", "payload")
        private val PAYLOAD_FIELDS = setOf("schemaVersion", "entries")
        private val ENTRY_FIELDS = setOf(
            "operationId", "projectId", "conversationId", "messageText",
            "baseRemoteVersion", "createdAtEpochMs", "state", "currentRemoteVersion"
        )

        internal fun isCanonicalPositiveVersion(value: String): Boolean =
            value.matches(Regex("[1-9][0-9]*")) && value.toLongOrNull()?.let { it > 0 } == true

        private fun isProjectIdentifier(value: String): Boolean = value == "default" || isIdentifier(value)
        private fun isIdentifier(value: String): Boolean = value.length in 8..128 && value.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-_."
        }
    }
}

internal object AndroidWorkspaceOutboxCryptoRuntime {
    @Volatile private var production: MemoryPayloadProtector? = null

    fun protector(): MemoryPayloadProtector = production ?: synchronized(this) {
        production ?: AndroidKeystoreMemoryPayloadProtector(
            "glafira.windows-hub.workspace-outbox.v1"
        ).also { production = it }
    }
}

/** Sends exactly one oldest durable write; no background or automatic replay occurs. */
internal class AndroidGlafiraWindowsHubWorkspaceOutboxConsumer(
    private val store: AndroidWorkspaceOutboxStore,
    private val credentials: GlafiraWindowsHubClientCredentialProvider,
    private val exchange: GlafiraWindowsHubBridgeExchange,
    private val clock: () -> Long = System::currentTimeMillis,
    private val requestIdentifier: () -> String = {
        "android-workspace-request-" + UUID.randomUUID().toString().lowercase()
    }
) {
    fun consumeNext(): AndroidWorkspaceOutboxOutcome {
        val entry = store.oldestPending() ?: return AndroidWorkspaceOutboxOutcome.NoPendingWork
        val baseVersion = entry.baseRemoteVersion
        if (baseVersion == null || !AndroidWorkspaceOutboxStore.isCanonicalPositiveVersion(baseVersion)) {
            store.requireOwnerResolution(entry.operationId, null)
            return AndroidWorkspaceOutboxOutcome.RequiresOwnerResolution(entry, null)
        }
        val now = clock()
        require(now > 0) { "WORKSPACE_CLOCK_INVALID" }
        val credential = credentials.load(now)
            ?: throw GlafiraWindowsHubCredentialUnavailableException()
        try {
            require("conversation.append" in credential.scopes) {
                "WINDOWS_HUB_CONVERSATION_APPEND_SCOPE_MISSING"
            }
            require(credential.enrolledAtEpochMs <= now && credential.expiresAtEpochMs > now) {
                "WINDOWS_HUB_CREDENTIAL_BINDING_INVALID"
            }
            val request = GlafiraBridgeRequest(
                protocolVersion = GlafiraBridgeProtocol.CURRENT_VERSION,
                requestId = requestIdentifier(),
                idempotencyKey = entry.operationId,
                deviceId = credential.deviceId,
                projectId = entry.projectId,
                operation = GlafiraBridgeOperation.CONVERSATION_APPEND,
                issuedAtEpochMs = now,
                expiresAtEpochMs = Math.addExact(now, REQUEST_TTL_MS),
                payload = mapOf(
                    "conversationId" to entry.conversationId,
                    "messageText" to entry.messageText,
                    "baseRemoteVersion" to baseVersion
                )
            )
            require(GlafiraBridgeProtocol.validate(request, now) == null) {
                "WINDOWS_HUB_WORKSPACE_REQUEST_INVALID"
            }
            val secret = credential.secretCopy()
            val signed = try {
                GlafiraBridgeProtocol.sign(request, secret)
            } finally {
                secret.fill(0)
            }
            val response = exchange.send(credential, signed)
            require(response.protocolVersion == GlafiraBridgeProtocol.CURRENT_VERSION &&
                response.requestId == signed.requestId) { "WINDOWS_HUB_WORKSPACE_RESPONSE_INVALID" }
            return when {
                response.status in setOf(GlafiraBridgeStatus.ACCEPTED, GlafiraBridgeStatus.SUCCEEDED) -> {
                    require(response.errorCode == null && response.result.keys == ACK_FIELDS &&
                        response.result["operationId"] == entry.operationId &&
                        response.result["conversationId"] == entry.conversationId) {
                        "WINDOWS_HUB_WORKSPACE_ACK_INVALID"
                    }
                    val version = response.result.getValue("version")
                    require(AndroidWorkspaceOutboxStore.isCanonicalPositiveVersion(version)) {
                        "WINDOWS_HUB_WORKSPACE_ACK_INVALID"
                    }
                    val duplicate = when (response.result.getValue("duplicate")) {
                        "true" -> true
                        "false" -> false
                        else -> error("WINDOWS_HUB_WORKSPACE_ACK_INVALID")
                    }
                    store.acknowledge(entry.operationId, version)
                    AndroidWorkspaceOutboxOutcome.Synchronized(entry, version, duplicate)
                }
                response.status == GlafiraBridgeStatus.FAILED &&
                    response.errorCode == "WORKSPACE_VERSION_CONFLICT" -> {
                    require(response.result.keys == setOf("currentVersion")) {
                        "WINDOWS_HUB_WORKSPACE_CONFLICT_INVALID"
                    }
                    val currentVersion = response.result.getValue("currentVersion")
                    require(AndroidWorkspaceOutboxStore.isCanonicalPositiveVersion(currentVersion)) {
                        "WINDOWS_HUB_WORKSPACE_CONFLICT_INVALID"
                    }
                    store.requireOwnerResolution(entry.operationId, currentVersion)
                    AndroidWorkspaceOutboxOutcome.RequiresOwnerResolution(entry, currentVersion)
                }
                response.status == GlafiraBridgeStatus.FAILED &&
                    response.errorCode == "WORKSPACE_IDEMPOTENCY_CONFLICT" -> {
                    require(response.result.isEmpty()) { "WINDOWS_HUB_WORKSPACE_CONFLICT_INVALID" }
                    store.requireOwnerResolution(entry.operationId, null)
                    AndroidWorkspaceOutboxOutcome.RequiresOwnerResolution(entry, null)
                }
                else -> AndroidWorkspaceOutboxOutcome.Unavailable(entry, response.errorCode)
            }
        } finally {
            credential.clearSecret()
        }
    }

    companion object {
        private const val REQUEST_TTL_MS = 60_000L
        private val ACK_FIELDS = setOf("conversationId", "version", "operationId", "duplicate")
    }
}
