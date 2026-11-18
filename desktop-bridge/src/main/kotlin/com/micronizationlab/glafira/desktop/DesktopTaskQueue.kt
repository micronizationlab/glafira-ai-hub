package com.micronizationlab.glafira.desktop

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal enum class DesktopTaskState {
    QUEUED, DISPATCHED, ACCEPTED, RUNNING, SUCCEEDED, FAILED, CANCELLED, EXPIRED
}

internal enum class DesktopTaskCapability {
    LOCAL_MODEL, GPU_INFERENCE, DOCUMENT_ANALYSIS, RAG_INDEX, CODE_BUILD, WEB_RESEARCH, DESKTOP_AUTOMATION
}

/**
 * Versioned task contract. Inputs are instructions and opaque context references only:
 * credentials, API keys and raw vault contents have no fields in this transport type.
 */
internal data class DesktopTaskEnvelope(
    val schemaVersion: Int = 1,
    val taskId: String,
    val projectId: String,
    val taskType: String,
    val instruction: String,
    val contextRefs: List<String>,
    val requiredCapabilities: Set<DesktopTaskCapability>,
    val requiredScopes: Set<String>,
    val priority: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val idempotencyKey: String
)

internal data class DesktopTaskRecord(
    val envelope: DesktopTaskEnvelope,
    val state: DesktopTaskState = DesktopTaskState.QUEUED,
    val sequence: Long = 0,
    val progressPercent: Int = 0,
    val progressMessage: String? = null,
    val result: String? = null,
    val errorCode: String? = null
)

/** Small durable queue; execution workers and Bridge transport are deliberately separate. */
internal class DesktopTaskQueue(
    private val file: Path,
    private val maxQueued: Int = DEFAULT_MAX_QUEUED,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val gson = Gson()
    private val lock = Any()

    init {
        require(maxQueued in 1..MAX_QUEUE_LIMIT)
        file.parent?.let { Files.createDirectories(it) }
    }

    fun enqueue(envelope: DesktopTaskEnvelope): DesktopTaskRecord = synchronized(lock) {
        validate(envelope)
        val records = readAll().toMutableList()
        expireQueued(records, clock())
        require(records.none { it.envelope.taskId == envelope.taskId }) { "Duplicate taskId." }
        require(records.none { it.envelope.idempotencyKey == envelope.idempotencyKey }) {
            "Duplicate idempotency key."
        }
        require(records.count { it.state == DesktopTaskState.QUEUED } < maxQueued) {
            "Desktop task queue is full."
        }
        val record = DesktopTaskRecord(envelope = envelope)
        records += record
        writeAll(records)
        record
    }

    /** Claims the highest-priority non-expired task, FIFO for equal priorities. */
    fun claimNext(): DesktopTaskRecord? = synchronized(lock) {
        val records = readAll().toMutableList()
        expireQueued(records, clock())
        val index = records.indices
            .filter { records[it].state == DesktopTaskState.QUEUED }
            .maxWithOrNull(
                compareBy<Int> { records[it].envelope.priority }
                    .thenByDescending { -records[it].envelope.createdAt }
            )
        if (index == null) {
            writeAll(records)
            return@synchronized null
        }
        val claimed = records[index].copy(
            state = DesktopTaskState.DISPATCHED,
            sequence = records[index].sequence + 1
        )
        records[index] = claimed
        writeAll(records)
        claimed
    }

    fun transition(taskId: String, next: DesktopTaskState): DesktopTaskRecord = synchronized(lock) {
        val records = readAll().toMutableList()
        val index = records.indexOfFirst { it.envelope.taskId == taskId }
        require(index >= 0) { "Unknown task." }
        val current = records[index]
        require(isAllowed(current.state, next)) { "Invalid task state transition." }
        val updated = current.copy(state = next, sequence = current.sequence + 1)
        records[index] = updated
        writeAll(records)
        updated
    }

    fun reportProgress(taskId: String, percent: Int, message: String): DesktopTaskRecord =
        synchronized(lock) {
            require(percent in 0..99 && message.length <= MAX_PROGRESS_CHARS)
            val records = readAll().toMutableList()
            val index = records.indexOfFirst { it.envelope.taskId == taskId }
            require(index >= 0) { "Unknown task." }
            val current = records[index]
            require(current.state == DesktopTaskState.RUNNING) { "Task is not running." }
            val updated = current.copy(
                sequence = current.sequence + 1,
                progressPercent = percent,
                progressMessage = message
            )
            records[index] = updated
            writeAll(records)
            updated
        }

    fun complete(taskId: String, result: String): DesktopTaskRecord = synchronized(lock) {
        require(result.isNotBlank() && result.length <= MAX_RESULT_CHARS)
        val records = readAll().toMutableList()
        val index = records.indexOfFirst { it.envelope.taskId == taskId }
        require(index >= 0) { "Unknown task." }
        val current = records[index]
        require(current.state == DesktopTaskState.RUNNING) { "Task is not running." }
        val updated = current.copy(
            state = DesktopTaskState.SUCCEEDED,
            sequence = current.sequence + 1,
            progressPercent = 100,
            result = result
        )
        records[index] = updated
        writeAll(records)
        updated
    }

    fun fail(taskId: String, errorCode: String): DesktopTaskRecord = synchronized(lock) {
        require(errorCode.matches(Regex("[A-Z0-9_]{1,64}")))
        updateTerminal(taskId, DesktopTaskState.FAILED, errorCode)
    }

    fun cancel(taskId: String): DesktopTaskRecord = synchronized(lock) {
        updateTerminal(taskId, DesktopTaskState.CANCELLED, null)
    }

    fun get(taskId: String): DesktopTaskRecord? = synchronized(lock) {
        readAll().firstOrNull { it.envelope.taskId == taskId }
    }

    fun list(): List<DesktopTaskRecord> = synchronized(lock) { readAll().toList() }

    private fun updateTerminal(
        taskId: String,
        state: DesktopTaskState,
        errorCode: String?
    ): DesktopTaskRecord {
        val records = readAll().toMutableList()
        val index = records.indexOfFirst { it.envelope.taskId == taskId }
        require(index >= 0) { "Unknown task." }
        val current = records[index]
        require(current.state in setOf(
            DesktopTaskState.QUEUED, DesktopTaskState.DISPATCHED,
            DesktopTaskState.ACCEPTED, DesktopTaskState.RUNNING
        )) { "Task is already terminal." }
        val updated = current.copy(
            state = state,
            sequence = current.sequence + 1,
            errorCode = errorCode
        )
        records[index] = updated
        writeAll(records)
        return updated
    }

    private fun validate(task: DesktopTaskEnvelope) {
        require(task.schemaVersion == 1)
        require(task.taskId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(task.projectId.isNotBlank() && task.projectId.length <= 128)
        require(task.taskType.matches(Regex("[a-z][a-z0-9_.-]{1,63}")))
        require(task.instruction.isNotBlank() && task.instruction.length <= MAX_INSTRUCTION_CHARS)
        require(task.contextRefs.size <= MAX_CONTEXT_REFS &&
            task.contextRefs.all { it.isNotBlank() && it.length <= 256 })
        require(task.requiredScopes.size <= MAX_SCOPES &&
            task.requiredScopes.all { it.matches(Regex("[a-z][a-z0-9_.-]{1,63}")) })
        require(task.priority in 0..9)
        require(task.createdAt <= clock() + MAX_CLOCK_SKEW_MS)
        require(task.expiresAt > task.createdAt && task.expiresAt > clock())
        require(task.idempotencyKey.matches(Regex("[A-Za-z0-9_-]{16,128}")))
    }

    private fun expireQueued(records: MutableList<DesktopTaskRecord>, now: Long) {
        for (index in records.indices) {
            val current = records[index]
            if (current.state == DesktopTaskState.QUEUED && current.envelope.expiresAt <= now) {
                records[index] = current.copy(
                    state = DesktopTaskState.EXPIRED,
                    sequence = current.sequence + 1
                )
            }
        }
    }

    private fun readAll(): List<DesktopTaskRecord> {
        if (!Files.exists(file)) return emptyList()
        val type = object : TypeToken<List<DesktopTaskRecord>>() {}.type
        return gson.fromJson<List<DesktopTaskRecord>>(Files.readString(file), type) ?: emptyList()
    }

    private fun writeAll(records: List<DesktopTaskRecord>) {
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(temp, gson.toJson(records))
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isAllowed(from: DesktopTaskState, to: DesktopTaskState): Boolean = when (from) {
        DesktopTaskState.DISPATCHED -> to == DesktopTaskState.ACCEPTED
        DesktopTaskState.ACCEPTED -> to == DesktopTaskState.RUNNING
        else -> false
    }

    companion object {
        const val DEFAULT_MAX_QUEUED = 100
        const val MAX_QUEUE_LIMIT = 1_000
        private const val MAX_INSTRUCTION_CHARS = 8_000
        private const val MAX_CONTEXT_REFS = 50
        private const val MAX_SCOPES = 32
        private const val MAX_PROGRESS_CHARS = 500
        private const val MAX_RESULT_CHARS = 24_000
        private const val MAX_CLOCK_SKEW_MS = 60_000L
    }
}
