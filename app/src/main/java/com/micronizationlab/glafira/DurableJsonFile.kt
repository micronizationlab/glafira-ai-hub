package com.micronizationlab.glafira

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/** Serializes read/modify/write in this process and retains the previous valid generation. */
internal class DurableJsonFile<T>(
    private val file: File,
    private val decode: (String) -> T,
    private val encode: (T) -> String,
    private val recover: (T) -> T = { it }
) {
    private val lock = locks.getOrPut(file.canonicalPath) { Any() }
    private val backup get() = File(file.path + ".bak")

    fun read(default: () -> T): T = GlafiraFileMaintenance.withExclusive { synchronized(lock) { readLocked(default) } }
    fun write(value: T) = GlafiraFileMaintenance.withExclusive { synchronized(lock) { writeLocked(value) } }
    fun update(default: () -> T, transform: (T) -> T): T = GlafiraFileMaintenance.withExclusive { synchronized(lock) {
        transform(readLocked(default)).also { writeLocked(it) }
    } }
    fun delete() = GlafiraFileMaintenance.withExclusive { synchronized(lock) {
        // Both generations must go: otherwise a deliberate clear resurrects old messages.
        val generations = listOf(file, backup, File(file.path + ".tmp"), File(file.path + ".bak.tmp")) + file.parentFile!!.listFiles().orEmpty().filter {
            it.name.startsWith(file.name + ".corrupt-") || it.name.startsWith(file.name + ".bak.corrupt-")
        }
        generations.forEach {
            check(!it.exists() || it.delete()) { "Cannot delete ${it.name}" }
        }
    } }

    private fun readLocked(default: () -> T): T {
        readValid(file)?.let { return it }
        readValid(backup)?.let { previous ->
            val recovered = recover(previous)
            recordStorageDiagnostic(file.parentFile!!, file.name, "recovered_previous_generation")
            // Keep the valid backup while replacing the damaged primary.
            if (!file.exists()) atomicWrite(file, encode(recovered))
            return recovered
        }
        return default()
    }

    private fun readValid(source: File): T? {
        if (!source.exists()) return null
        return try { decode(source.readText()) } catch (e: Exception) {
            recordStorageDiagnostic(file.parentFile!!, source.name, "invalid_${e.javaClass.simpleName}")
            // Preserve evidence/data rather than silently replacing an unreadable user's file.
            val quarantine = File(source.path + ".corrupt-" + System.currentTimeMillis())
            if (!source.renameTo(quarantine)) {
                recordStorageDiagnostic(file.parentFile!!, source.name, "quarantine_failed")
            }
            null
        }
    }

    private fun writeLocked(value: T) {
        val encoded = encode(value)
        decode(encoded) // Reject invalid replacement before touching either generation.
        if (file.exists()) {
            val previous = readValid(file)
            if (previous != null) atomicWrite(backup, encode(previous))
            else if (file.exists()) throw java.io.IOException("Cannot safely replace damaged ${file.name}")
        }
        atomicWrite(file, encoded)
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private fun atomicWrite(destination: File, text: String) {
            destination.parentFile!!.mkdirs()
            val temporary = File(destination.path + ".tmp")
            FileOutputStream(temporary).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            // Persist the rename when the filesystem supports syncing directory metadata.
            runCatching { FileChannel.open(destination.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) } }
        }
    }
}

/** Records only filenames and error categories, never conversation contents or secrets. */
internal fun recordStorageDiagnostic(directory: File, name: String, category: String) {
    synchronized(StorageDiagnosticsLock) {
        runCatching {
            val file = File(directory, "storage_diagnostics.log")
            val previous = if (file.exists()) file.readLines().takeLast(99) else emptyList()
            file.writeText((previous + "${System.currentTimeMillis()} $name $category").joinToString("\n", postfix = "\n"))
        }
    }
}
private object StorageDiagnosticsLock

internal fun storageFile(directory: File, name: String): File {
    require(name.isNotBlank() && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Invalid storage filename" }
    return File(directory, name)
}

/** Maintenance acquires this before any per-file lock; never acquire a Core store lock inside it. */
internal object GlafiraFileMaintenance {
    private val monitor = Any()
    fun <T> withExclusive(block: () -> T): T = synchronized(monitor) { block() }
}
