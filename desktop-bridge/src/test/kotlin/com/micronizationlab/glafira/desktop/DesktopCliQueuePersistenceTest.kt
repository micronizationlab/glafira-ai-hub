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
    private fun credentials(deviceId: String = "device-0001", marker: Byte = 1) =
        DesktopBridgeCredentials(
            deviceId, ByteArray(32) { marker }, setOf("project.read"),
            if (marker == 1.toByte()) "a".repeat(64) else "b".repeat(64)
        )
    private fun session(
        path: java.nio.file.Path = vaultPath(),
        credential: DesktopBridgeCredentials = credentials(),
        origin: String = "https://192.168.1.10:9443"
    ): DesktopCliTaskSession {
        val binding = desktopCliPairingBinding(origin, credential)
        return desktopCliTaskSession(path, binding, credential.hmacSecret)
    }

    @Test fun differentPairingCannotReadOrFlushPreviousPairingQueue() {
        val path = vaultPath()
        val first = credentials("device-0001", 1)
        val second = credentials("device-0002", 2)
        val bindingA = desktopCliPairingBinding("https://192.168.1.10:9443", first)
        val bindingB = desktopCliPairingBinding("https://192.168.1.11:9443", second)
        assertNotEquals(bindingA, bindingB)
        val now = System.currentTimeMillis()
        desktopCliTaskSession(path, bindingA, first.hmacSecret).use { session ->
            session.queue.enqueue(DesktopTaskEnvelope(taskId = "isolated-task", projectId = "project-001",
                taskType = "analysis.document", instruction = "private context", contextRefs = emptyList(),
                requiredCapabilities = emptySet(), requiredScopes = emptySet(), priority = 1,
                createdAt = now, expiresAt = now + 60_000, idempotencyKey = "isolated-operation"))
            session.queue.claimNext()
            session.queue.transition("isolated-task", DesktopTaskState.ACCEPTED)
            session.queue.transition("isolated-task", DesktopTaskState.RUNNING)
            session.queue.complete("isolated-task", "private result")
        }
        desktopCliTaskSession(path, bindingB, second.hmacSecret).use { session ->
            assertTrue(session.queue.list().isEmpty())
            assertTrue(session.queue.pendingResults("project-001").isEmpty())
        }
        desktopCliTaskSession(path, bindingA, first.hmacSecret).use { session ->
            assertEquals("private result", session.queue.pendingResults("project-001").single().result)
        }
    }

    @Test
    fun productionCliFactoryRecoversUnacknowledgedResultAfterSessionRestart() {
        val path = vaultPath()
        val credential = credentials()
        val binding = desktopCliPairingBinding("https://192.168.1.10:9443", credential)
        assertEquals(path.parent.resolve("tasks-$binding.json"), desktopCliTaskQueuePath(path, binding))
        val terminal = session(path, credential).use { session ->
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
        val queuePath = path.parent.resolve("tasks-$binding.json")
        assertTrue(Files.isRegularFile(queuePath))
        assertFalse(String(Files.readAllBytes(queuePath), Charsets.ISO_8859_1)
            .contains("actual persisted CLI output"))
        session(path, credential).use { session ->
            val reopenedByCli = session.queue
            assertEquals("actual persisted CLI output", reopenedByCli.pendingResults("project-001").single().result)
            assertNull(reopenedByCli.claimNext())
            assertTrue(reopenedByCli.acknowledgeResult("cli-task-001", terminal.sequence))
        }
        session(path, credential).use { session ->
            assertTrue(session.queue.pendingResults("project-001").isEmpty())
            assertEquals("actual persisted CLI output", session.queue.get("cli-task-001")?.result)
        }
        if (Files.getFileStore(path.parent).supportsFileAttributeView("posix")) {
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(queuePath))
        }
    }

    @Test
    fun productionSessionEncryptsUnboundLegacyQueueWithoutAdmittingItToActivePairing() {
        val path = vaultPath()
        Files.createDirectories(path.parent)
        val now = System.currentTimeMillis()
        val legacyRecord = DesktopTaskRecord(DesktopTaskEnvelope(
            taskId = "legacy-task", projectId = "legacy-project", taskType = "analysis.document",
            instruction = "legacy private instruction", contextRefs = emptyList(),
            requiredCapabilities = emptySet(), requiredScopes = emptySet(), priority = 1,
            createdAt = now, expiresAt = now + 60_000, idempotencyKey = "legacy-operation-001"
        ))
        val legacyPath = desktopCliTaskQueuePath(path)
        Files.writeString(legacyPath, com.google.gson.Gson().toJson(listOf(legacyRecord)))

        session(path).use { active -> assertTrue(active.queue.list().isEmpty()) }

        val encrypted = Files.readAllBytes(legacyPath)
        assertEquals("GLFQ", String(encrypted.copyOfRange(0, 4), Charsets.US_ASCII))
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("legacy private instruction"))
    }

    @Test
    fun anotherCliSessionIsRejectedUntilOriginalClosesAndQueueRenameCannotReleaseLock() {
        val path = vaultPath()
        val original = session(path)
        original.use { first ->
            val rejected = assertThrows(IllegalStateException::class.java) { session(path) }
            assertTrue(rejected.message!!.contains("already using this task queue"))
            // Every queue write can replace tasks.json; the lock must remain on tasks.lock.
            assertNull(first.queue.claimNext())
            assertThrows(IllegalStateException::class.java) { session(path) }
        }
        session(path).use {
            original.close() // An already-closed session cannot release a newer reservation.
            assertThrows(IllegalStateException::class.java) { session(path) }
            assertTrue(it.queue.list().isEmpty())
        }
        assertTrue(Files.isRegularFile(path.parent.resolve("tasks.lock")))
    }

    @Test
    fun childJvmCannotTakeLockAfterRejectedLocalSessionButCanAfterClose() {
        val path = vaultPath()
        val lockPath = path.parent.resolve("tasks.lock")
        session(path).use {
            assertEquals(23, childLockProbe(lockPath))
            assertThrows(IllegalStateException::class.java) { session(path) }
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
        val credential = credentials()
        val binding = desktopCliPairingBinding("https://192.168.1.10:9443", credential)
        val queuePath = desktopCliTaskQueuePath(path, binding)
        for (persisted in listOf("[this is damaged queue data", "null")) {
            Files.writeString(queuePath, persisted)
            assertThrows(Exception::class.java) { session(path, credential) }
            assertEquals(persisted, Files.readString(queuePath))
            Files.writeString(queuePath, "[]")
            session(path, credential).use { assertTrue(it.queue.list().isEmpty()) }
        }
    }

    @Test
    fun queueDirectoryInPlaceOfFileStopsStartupWithoutInMemoryFallback() {
        val path = vaultPath()
        val credential = credentials()
        val binding = desktopCliPairingBinding("https://192.168.1.10:9443", credential)
        val queuePath = desktopCliTaskQueuePath(path, binding)
        Files.createDirectories(queuePath)
        assertThrows(Exception::class.java) { session(path, credential) }
        assertTrue(Files.isDirectory(queuePath))
        Files.delete(queuePath)
        session(path, credential).use { assertTrue(it.queue.list().isEmpty()) }
    }
    @Test
    fun failedWorkerCloseRetainsProcessLockUntilExplicitSafeRelease() {
        val path = vaultPath()
        val session = session(path)
        val stuckWorker = AutoCloseable { throw IllegalStateException("worker still active") }

        assertThrows(IllegalStateException::class.java) {
            closeDesktopWorkerAndSession(stuckWorker, session)
        }
        assertThrows(IllegalStateException::class.java) { session(path) }
        val lockPath = path.parent.resolve("tasks.lock")
        assertEquals("A separate JVM must not acquire the OS lock after worker close fails.", 23, childLockProbe(lockPath))

        // The caller may release only after it has independently confirmed worker termination.
        session.close()
        assertEquals("The OS lock becomes available only after safe session release.", 0, childLockProbe(lockPath))
        session(path).use { assertTrue(it.queue.list().isEmpty()) }
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
