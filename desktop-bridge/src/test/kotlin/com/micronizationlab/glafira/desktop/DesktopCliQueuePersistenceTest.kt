package com.micronizationlab.glafira.desktop

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class DesktopCliQueuePersistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun vaultPath() = temp.root.toPath().resolve("bridge/device.vault")

    @Test
    fun productionCliFactoryRecoversUnacknowledgedResultAfterSessionRestart() {
        val path = vaultPath()
        assertEquals(path.parent.resolve("tasks.json"), desktopCliTaskQueuePath(path))
        val terminal = desktopCliTaskSession(path).use { session ->
            val first = session.queue
            val now = System.currentTimeMillis()
            first.enqueue(DesktopTaskEnvelope(taskId = "cli-task-001", projectId = "project-001",
                taskType = "analysis.document", instruction = "Analyze", contextRefs = emptyList(),
                requiredCapabilities = emptySet(), requiredScopes = emptySet(), priority = 1,
                createdAt = now, expiresAt = now + 60_000, idempotencyKey = "cli-operation-001"))
            first.claimNext()
            first.transition("cli-task-001", DesktopTaskState.ACCEPTED)
            first.transition("cli-task-001", DesktopTaskState.RUNNING)
            first.complete("cli-task-001", "actual persisted CLI output")
        }
        assertTrue(Files.isRegularFile(path.parent.resolve("tasks.json")))
        desktopCliTaskSession(path).use { session ->
            val reopenedByCli = session.queue
            assertEquals("actual persisted CLI output", reopenedByCli.pendingResults("project-001").single().result)
            assertNull(reopenedByCli.claimNext())
            assertTrue(reopenedByCli.acknowledgeResult("cli-task-001", terminal.sequence))
        }
        desktopCliTaskSession(path).use { session ->
            assertTrue(session.queue.pendingResults("project-001").isEmpty())
            assertEquals("actual persisted CLI output", session.queue.get("cli-task-001")?.result)
        }
        if (Files.getFileStore(path.parent).supportsFileAttributeView("posix")) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(path.parent.resolve("tasks.json")))
        }
    }

    @Test
    fun anotherCliSessionIsRejectedUntilOriginalClosesAndQueueRenameCannotReleaseLock() {
        val path = vaultPath()
        val original = desktopCliTaskSession(path)
        original.use { first ->
            val rejected = assertThrows(IllegalStateException::class.java) { desktopCliTaskSession(path) }
            assertTrue(rejected.message!!.contains("already using this task queue"))
            // Every queue write can replace tasks.json; the lock must remain on tasks.lock.
            assertNull(first.queue.claimNext())
            assertThrows(IllegalStateException::class.java) { desktopCliTaskSession(path) }
        }
        desktopCliTaskSession(path).use {
            original.close() // An already-closed session cannot release a newer reservation.
            assertThrows(IllegalStateException::class.java) { desktopCliTaskSession(path) }
            assertTrue(it.queue.list().isEmpty())
        }
        assertTrue(Files.isRegularFile(path.parent.resolve("tasks.lock")))
    }

    @Test
    fun childJvmCannotTakeLockAfterRejectedLocalSessionButCanAfterClose() {
        val path = vaultPath()
        val lockPath = path.parent.resolve("tasks.lock")
        desktopCliTaskSession(path).use {
            assertEquals(23, childLockProbe(lockPath))
            assertThrows(IllegalStateException::class.java) { desktopCliTaskSession(path) }
            assertEquals(23, childLockProbe(lockPath))
        }
        assertEquals(0, childLockProbe(lockPath))
    }

    private fun childLockProbe(path: java.nio.file.Path): Int {
        val javaName = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
        val javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", javaName)
        val classpath = listOf(DesktopCliLockProbe::class.java, kotlin.Unit::class.java)
            .map { java.nio.file.Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(java.io.File.pathSeparator)
        val process = ProcessBuilder(javaExecutable.toString(), "-cp", classpath,
            DesktopCliLockProbe::class.java.name, path.toString()).redirectErrorStream(true).start()
        try {
            assertTrue("Child JVM lock probe timed out", process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertTrue("Unexpected child lock probe exit: $output", process.exitValue() in setOf(0, 23))
            return process.exitValue()
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    @Test
    fun corruptedOrNullQueueStopsStartupPreservesBytesAndReleasesLock() {
        val path = vaultPath()
        Files.createDirectories(path.parent)
        for (persisted in listOf("[this is damaged queue data", "null")) {
            Files.writeString(path.parent.resolve("tasks.json"), persisted)
            assertThrows(Exception::class.java) { desktopCliTaskSession(path) }
            assertEquals(persisted, Files.readString(path.parent.resolve("tasks.json")))
            Files.writeString(path.parent.resolve("tasks.json"), "[]")
            desktopCliTaskSession(path).use { assertTrue(it.queue.list().isEmpty()) }
        }
    }

    @Test
    fun queueDirectoryInPlaceOfFileStopsStartupWithoutInMemoryFallback() {
        val path = vaultPath()
        Files.createDirectories(path.parent.resolve("tasks.json"))
        assertThrows(Exception::class.java) { desktopCliTaskSession(path) }
        assertTrue(Files.isDirectory(path.parent.resolve("tasks.json")))
        Files.delete(path.parent.resolve("tasks.json"))
        desktopCliTaskSession(path).use { assertTrue(it.queue.list().isEmpty()) }
    }
}

/** A real second process avoids mistaking the JVM's own overlap registry for an OS lock. */
internal object DesktopCliLockProbe {
    @JvmStatic fun main(args: Array<String>) {
        val acquired = java.nio.channels.FileChannel.open(java.nio.file.Path.of(args.single()),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            channel.tryLock()?.use { true } ?: false
        }
        kotlin.system.exitProcess(if (acquired) 0 else 23)
    }
}
