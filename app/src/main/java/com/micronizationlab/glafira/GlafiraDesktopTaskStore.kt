package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Durable Android-side inbox/outbox for authenticated Desktop Bridge task exchange. */
internal class GlafiraDesktopTaskStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "desktop_task_queue.json")
    private val lock = Any()
    private val nodeState = context.applicationContext.getSharedPreferences("glafira_desktop_node", Context.MODE_PRIVATE)

    fun enqueue(task: GlafiraDistributedTask): String = synchronized(lock) {
        validateTask(task)
        val records = read().toMutableList()
        require(records.none { it.optString("taskId") == task.taskId }) { "Duplicate task ID." }
        require(records.count { it.optString("state") == "QUEUED" } < MAX_QUEUED) { "Desktop task queue is full." }
        records += JSONObject().apply {
            put("taskId", task.taskId)
            put("projectId", task.projectId)
            put("parentTaskId", task.parentTaskId)
            put("subtaskId", task.subtaskId)
            put("title", task.title)
            put("instruction", task.instruction)
            put("executor", task.executor.name)
            put("requiredCapabilities", JSONArray(task.requiredCapabilities.toList()))
            put("createdAtEpochMs", task.createdAtEpochMs)
            put("expiresAtEpochMs", task.expiresAtEpochMs)
            put("priority", task.priority)
            put("state", "QUEUED")
            put("sequence", 0)
            put("progressPercent", 0)
        }
        write(records)
        task.taskId
    }

    fun claimNext(supportedCapabilities: Set<String>, maxParallelTasks: Int): JSONObject? = synchronized(lock) {
        require(supportedCapabilities.size <= 16 && supportedCapabilities.all { it.matches(Regex("[a-z][a-z0-9_.-]{1,63}")) })
        require(maxParallelTasks in 1..8)
        nodeState.edit().putLong("last_seen_epoch_ms", System.currentTimeMillis())
            .putStringSet("capabilities", supportedCapabilities.toSet())
            .putInt("max_parallel_tasks", maxParallelTasks).apply()
        val records = read().toMutableList()
        val now = System.currentTimeMillis()
        records.forEach { item ->
            val state = item.optString("state")
            if (state == "QUEUED" && item.optLong("expiresAtEpochMs") <= now) {
                item.put("state", "EXPIRED")
                item.put("sequence", item.optLong("sequence") + 1)
            } else if (state in setOf("DISPATCHED", "ACCEPTED", "RUNNING") && item.optLong("leaseExpiresAtEpochMs") in 1..now) {
                if (item.optInt("attemptCount") >= MAX_DELIVERY_ATTEMPTS) {
                    item.put("state", "FAILED")
                    item.put("errorCode", "DESKTOP_DELIVERY_RETRIES_EXCEEDED")
                } else {
                    item.put("state", "QUEUED")
                }
                item.put("sequence", item.optLong("sequence") + 1)
            }
        }
        val next = records.filter { item ->
                item.optString("state") == "QUEUED" &&
                    runCatching {
                        val required = item.getJSONArray("requiredCapabilities")
                        (0 until required.length()).all { index -> supportedCapabilities.contains(required.getString(index)) }
                    }.getOrDefault(false)
            }
            .sortedWith(compareByDescending<JSONObject> { it.optInt("priority", 5) }
                .thenBy { it.optLong("createdAtEpochMs") })
            .firstOrNull()
        if (next == null) {
            write(records)
            return@synchronized null
        }
        next.put("state", "DISPATCHED")
        next.put("attemptCount", next.optInt("attemptCount") + 1)
        next.put("leaseExpiresAtEpochMs", now + CLAIM_LEASE_MS)
        next.put("sequence", next.optLong("sequence") + 1)
        write(records)
        JSONObject(next.toString())
    }

    fun update(
        taskId: String,
        state: String,
        progressPercent: Int,
        progressMessage: String?,
        result: String?,
        errorCode: String?
    ): Boolean = synchronized(lock) {
        require(taskId.matches(Regex("[A-Za-z0-9_-]{8,64}")))
        require(progressPercent in 0..100)
        require(progressMessage == null || progressMessage.length <= 500)
        require(result == null || result.length <= 24_000)
        require(errorCode == null || errorCode.matches(Regex("[A-Z0-9_]{1,64}")))
        val records = read().toMutableList()
        val item = records.firstOrNull { it.optString("taskId") == taskId } ?: return@synchronized false
        val current = item.optString("state")
        require(current in setOf("QUEUED", "DISPATCHED", "ACCEPTED", "RUNNING")) {
            "Task is already terminal."
        }
        val allowedNext = when (current) {
            "DISPATCHED" -> setOf("ACCEPTED", "FAILED")
            "ACCEPTED" -> setOf("RUNNING", "FAILED", "CANCELLED")
            "RUNNING" -> setOf("RUNNING", "SUCCEEDED", "FAILED", "CANCELLED")
            else -> emptySet()
        }
        require(state in allowedNext) { "Invalid task state transition." }
        item.put("state", state)
        if (state in setOf("ACCEPTED", "RUNNING")) item.put("leaseExpiresAtEpochMs", System.currentTimeMillis() + CLAIM_LEASE_MS)
        if (state in setOf("SUCCEEDED", "FAILED", "CANCELLED")) item.remove("leaseExpiresAtEpochMs")
        item.put("sequence", item.optLong("sequence") + 1)
        item.put("progressPercent", progressPercent)
        if (progressMessage != null) item.put("progressMessage", progressMessage)
        if (result != null) item.put("result", result)
        if (errorCode != null) item.put("errorCode", errorCode)
        write(records)
        true
    }

    fun nodeCapabilities(maxAgeMs: Long = 45_000L): GlafiraNodeCapabilities? {
        val now = System.currentTimeMillis()
        val lastSeen = nodeState.getLong("last_seen_epoch_ms", 0L)
        if (lastSeen <= 0L || now - lastSeen !in 0..maxAgeMs) return null
        return GlafiraNodeCapabilities(
            nodeId = "paired-desktop",
            available = true,
            maxParallelTasks = nodeState.getInt("max_parallel_tasks", 1).coerceIn(1, 8),
            capabilities = nodeState.getStringSet("capabilities", emptySet()).orEmpty().toSet()
        )
    }

    fun get(taskId: String): JSONObject? = synchronized(lock) {
        read().firstOrNull { it.optString("taskId") == taskId }?.let { JSONObject(it.toString()) }
    }

    private fun validateTask(task: GlafiraDistributedTask) {
        require(task.taskId.matches(Regex("[A-Za-z0-9_-]{8,64}")))
        require(task.projectId.isNotBlank() && task.projectId.length <= 128)
        require(task.instruction.isNotBlank() && task.instruction.length <= 8_000)
        require(task.title.isNotBlank() && task.title.length <= 160)
        require(task.priority in 0..9)
        require(task.requiredCapabilities.size <= 16 &&
            task.requiredCapabilities.all { it.matches(Regex("[a-z][a-z0-9_.-]{1,63}")) })
        require(task.expiresAtEpochMs > System.currentTimeMillis())
    }

    private fun read(): List<JSONObject> {
        if (!file.exists()) return emptyList()
        require(file.length() in 1..MAX_FILE_BYTES) { "Desktop task queue file size is invalid." }
        val array = JSONArray(file.readText(Charsets.UTF_8))
        require(array.length() <= MAX_RECORDS)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun write(records: List<JSONObject>) {
        val bytes = JSONArray(records).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES)
        val temp = Files.createTempFile(file.parentFile.toPath(), ".glafira-task-", ".tmp")
        try {
            Files.write(temp, bytes)
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
            bytes.fill(0)
        }
    }

    companion object {
        private const val MAX_QUEUED = 100
        private const val MAX_RECORDS = 500
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024L
        private const val CLAIM_LEASE_MS = 10 * 60 * 1000L
        private const val MAX_DELIVERY_ATTEMPTS = 3
    }
}
