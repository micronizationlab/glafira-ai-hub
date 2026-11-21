package com.micronizationlab.glafira

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.spec.SecretKeySpec

class AndroidStorageRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun directory() = temporary.newFolder()

    @Test fun queuedOperationRetainsItsOriginalPromptAfterRestart() {
        val root = directory()
        val message = PendingMessage("project", "agent", "question", "12:00", systemPrompt = "original project context")
        PendingMessageStore(root).add(message)
        assertEquals("original project context", PendingMessageStore(root).all().single().systemPrompt)
    }

    @Test fun missingProjectsAlwaysProvideUsableDefault() {
        assertEquals(defaultProjects(), projectStore(directory()).read(::defaultProjects))
    }

    @Test fun emptyAndMalformedProjectsRecoverPreviousValidGeneration() {
        listOf("[]", "[{broken").forEach { damaged ->
            val root = directory()
            val store = projectStore(root)
            val previous = listOf(Project("one", "One"))
            store.write(previous)
            store.write(listOf(Project("two", "Two")))
            File(root, "projects.json").writeText(damaged)
            assertEquals(previous, store.read(::defaultProjects))
            assertTrue(root.listFiles()!!.any { it.name.startsWith("projects.json.corrupt-") })
            assertTrue(File(root, "storage_diagnostics.log").readText().contains("recovered_previous_generation"))
        }
    }

    @Test fun bothCorruptGenerationsArePreservedAndDefaultDoesNotCrash() {
        val root = directory()
        File(root, "projects.json").writeText("bad")
        File(root, "projects.json.bak").writeText("bad-backup")
        assertEquals(defaultProjects(), projectStore(root).read(::defaultProjects))
        assertEquals(2, root.listFiles()!!.count { it.name.contains(".corrupt-") })
    }

    @Test fun interruptedTemporaryWriteDoesNotReplaceCommittedData() {
        val root = directory()
        val store = projectStore(root)
        val projects = listOf(Project("one", "One"))
        store.write(projects)
        File(root, "projects.json.tmp").writeText("[")
        assertEquals(projects, store.read(::defaultProjects))
    }

    @Test fun invalidReplacementDoesNotDamageValidGenerations() {
        val root = directory()
        val store = projectStore(root)
        store.write(defaultProjects())
        runCatching { store.write(emptyList()) }.onSuccess { fail("Empty replacement must be rejected") }
        assertEquals(defaultProjects(), store.read(::defaultProjects))
    }

    @Test fun concurrentProjectUpdatesDoNotLoseProjects() {
        val root = directory()
        val pool = Executors.newFixedThreadPool(4)
        try {
            pool.invokeAll((0 until 20).map { index -> Callable {
                projectStore(root).update(::defaultProjects) { it + Project("id-$index", "Project $index") }
            } }).forEach { it.get() }
            assertEquals(defaultProjects().size + 20, projectStore(root).read(::defaultProjects).size)
        } finally { pool.shutdownNow() }
    }

    @Test fun partialProviderResultsSurviveUncertainOutcomeAndRestart() {
        val root = directory()
        val store = PendingMessageStore(root, "session")
        store.add(PendingMessage("p", "a", "text", "", id = "one"))
        store.claim("one")
        store.failed("one", "synthesis failed", false, true, partialResult = "Actual peer response")
        val restored = PendingMessageStore(root, "new-session").all().single()
        assertEquals(PendingDeliveryState.UNCERTAIN, restored.state)
        assertEquals("Actual peer response", restored.result)
        assertNull(PendingMessageStore(root, "new-session").claim("one"))
    }

    @Test fun maintenanceExcludesAConcurrentClaimUntilRecoveryFinishes() {
        val root = directory()
        val store = PendingMessageStore(root, "session")
        store.add(PendingMessage("p", "a", "text", "", id = "one"))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val claim = GlafiraFileMaintenance.withExclusive {
                val waiting = pool.submit(Callable { store.claim("one") })
                store.recoverAfterRestore()
                waiting
            }
            assertNull(claim.get())
            assertEquals(PendingDeliveryState.UNCERTAIN, store.all().single().state)
        } finally { pool.shutdownNow() }
    }

    @Test fun restoredQueuedRecordAlsoRequiresReview() {
        val store = PendingMessageStore(directory(), "session")
        store.add(PendingMessage("p", "a", "text", "", id = "one"))
        store.recoverAfterRestore()
        assertEquals(PendingDeliveryState.UNCERTAIN, store.all().single().state)
        assertNull(store.claim("one"))
    }

    @Test fun concurrentHistoryAppendsDoNotOverwriteEachOther() {
        val root = directory()
        val workers = Executors.newFixedThreadPool(6)
        try {
            val jobs = (0 until 60).map { i -> Callable {
                appendHistory(root, "project_agent", listOf(Msg("message-$i", true, "12:00", operationId = "op-$i")))
            } }
            workers.invokeAll(jobs).forEach { it.get() }
            val result = historyStore(root, "project_agent").read { emptyList() }
            assertEquals(60, result.size)
            assertEquals(60, result.map { it.text }.distinct().size)
        } finally { workers.shutdownNow() }
    }

    @Test fun resultReplayIsIdempotentButDistinctIdenticalMessagesRemain() {
        val root = directory()
        val first = Msg("same text", false, "12:00", operationId = "op-1")
        appendHistory(root, "p_a", listOf(first, first.copy(operationId = "op-2")))
        appendHistory(root, "p_a", listOf(first))
        assertEquals(2, historyStore(root, "p_a").read { emptyList() }.size)
    }

    @Test fun clearRemovesBackupSoHistoryCannotResurrect() {
        val root = directory()
        val store = historyStore(root, "p_a")
        store.write(listOf(Msg("old", true, "")))
        store.write(listOf(Msg("new", true, "")))
        store.delete()
        assertEquals(emptyList<Msg>(), store.read { emptyList() })
        assertFalse(File(root, "chat_p_a.json.bak").exists())
    }

    @Test fun plaintextHistoryMigrationPreservesMessagesAndRemovesCommittedPlaintext() {
        val root = directory()
        val key = "project_agent"
        val secret = "legacy-sensitive-message"
        File(root, "chat_$key.json").writeText(encodeHistoryJson(listOf(Msg(secret, true, "12:00"))))

        val restored = encryptedHistoryStore(root, key, historyProtector(1)).read { emptyList() }

        assertEquals(listOf(secret), restored.map { it.text })
        listOf(File(root, "chat_$key.json"), File(root, "chat_$key.json.bak")).filter(File::exists).forEach {
            assertFalse(it.readText().contains(secret))
            assertTrue(it.readText().contains("GLAFIRA_ENCRYPTED_HISTORY"))
        }
    }

    @Test fun encryptedHistoryRestartRetainsDataWithoutPlaintextGenerations() {
        val root = directory()
        val protector = historyProtector(2)
        val store = encryptedHistoryStore(root, "p_a", protector)
        store.write(listOf(Msg("first-secret", true, "")))
        store.write(listOf(Msg("second-secret", false, "")))

        assertEquals("second-secret", encryptedHistoryStore(root, "p_a", protector).read { emptyList() }.single().text)
        listOf(File(root, "chat_p_a.json"), File(root, "chat_p_a.json.bak")).forEach {
            assertFalse(it.readText().contains("first-secret"))
            assertFalse(it.readText().contains("second-secret"))
        }
    }

    @Test fun replacementDeviceKeyFailsClosedAndPreservesCiphertextEvidence() {
        val root = directory()
        encryptedHistoryStore(root, "p_a", historyProtector(3)).write(listOf(Msg("device-bound", true, "")))

        val failure = runCatching {
            encryptedHistoryStore(root, "p_a", historyProtector(4)).read { emptyList() }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(root.listFiles().orEmpty().any { it.name.startsWith("chat_p_a.json.corrupt-") })
        assertTrue(root.listFiles().orEmpty().filter { it.name.startsWith("chat_p_a.json.corrupt-") }
            .all { !it.readText().contains("device-bound") })
    }

    @Test fun historyCiphertextCannotBeMovedToAnotherConversation() {
        val root = directory()
        val protector = historyProtector(5)
        encryptedHistoryStore(root, "project_a", protector).write(listOf(Msg("scoped", true, "")))
        File(root, "chat_project_a.json").copyTo(File(root, "chat_project_b.json"))

        assertNotNull(runCatching {
            encryptedHistoryStore(root, "project_b", protector).read { emptyList() }
        }.exceptionOrNull())
    }

    @Test fun failedHistoryMigrationLeavesOriginalPlaintextForRetry() {
        val root = directory()
        val file = File(root, "chat_p_a.json")
        file.writeText(encodeHistoryJson(listOf(Msg("must-survive", true, ""))))
        val failing = object : MemoryPayloadProtector {
            override fun protect(memoryId: MemoryIdentifier, plaintext: ByteArray): String = error("injected")
            override fun unprotect(memoryId: MemoryIdentifier, encoded: String): ByteArray = error("unused")
        }

        assertNotNull(runCatching { encryptedHistoryStore(root, "p_a", failing).read { emptyList() } }.exceptionOrNull())
        assertTrue(file.readText().contains("must-survive"))
        assertFalse(File(root, "chat_p_a.json.bak").exists())
    }

    @Test fun plaintextOutboxMigrationEncryptsPromptResultErrorAndSystemContext() {
        val root = directory()
        val file = File(root, "pending_queue.json")
        val message = PendingMessage(
            "project", "agent", "sensitive prompt", "12:00", id = "legacy-op",
            state = PendingDeliveryState.RESULT_READY, result = "sensitive result",
            error = "sensitive error", systemPrompt = "sensitive system context"
        )
        file.writeText(PendingMessageStore.encodePortable(listOf(message)))

        val restored = PendingMessageStore(root, "session", historyProtector(31)).all().single()

        assertEquals(message, restored)
        listOf(file, File(root, "pending_queue.json.bak")).filter(File::exists).forEach { generation ->
            val raw = generation.readText()
            assertTrue(raw.contains("GLAFIRA_ENCRYPTED_OUTBOX"))
            listOf(message.text, message.result, message.error, message.systemPrompt).forEach {
                assertFalse(raw.contains(requireNotNull(it)))
            }
        }
    }

    @Test fun encryptedOutboxSurvivesRestartAndStateTransitionsWithoutPlaintext() {
        val root = directory()
        val protector = historyProtector(32)
        val store = PendingMessageStore(root, "session", protector)
        store.add(PendingMessage("p", "a", "private request", "", id = "encrypted-op", systemPrompt = "private system"))
        store.claim("encrypted-op")
        store.result("encrypted-op", "private result")

        val restored = PendingMessageStore(root, "session", protector).all().single()
        assertEquals(PendingDeliveryState.RESULT_READY, restored.state)
        assertEquals("private request", restored.text)
        assertEquals("private result", restored.result)
        listOf(File(root, "pending_queue.json"), File(root, "pending_queue.json.bak")).forEach {
            assertFalse(it.readText().contains("private"))
        }
    }

    @Test fun replacementOutboxKeyFailsClosedWithoutOverwritingCiphertext() {
        val root = directory()
        val file = File(root, "pending_queue.json")
        PendingMessageStore(root, "session", historyProtector(33))
            .add(PendingMessage("p", "a", "keep ciphertext", "", id = "key-op"))
        val before = file.readBytes()

        assertNotNull(runCatching { PendingMessageStore(root, "session", historyProtector(34)).all() }.exceptionOrNull())
        assertArrayEquals(before, file.readBytes())
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("pending_queue.json.corrupt-") })
    }

    @Test fun outboxPayloadCannotBeReboundToAnotherOperationId() {
        val root = directory()
        val file = File(root, "pending_queue.json")
        val protector = historyProtector(35)
        PendingMessageStore(root, "session", protector)
            .add(PendingMessage("p", "a", "bound payload", "", id = "original-op"))
        val envelope = org.json.JSONObject(file.readText())
        envelope.getJSONArray("records").getJSONObject(0).put("id", "forged-op")
        file.writeText(envelope.toString())

        assertNotNull(runCatching { PendingMessageStore(root, "session", protector).all() }.exceptionOrNull())
    }

    @Test fun failedOutboxMigrationLeavesPlaintextGenerationForRetry() {
        val root = directory()
        val file = File(root, "pending_queue.json")
        file.writeText(PendingMessageStore.encodePortable(listOf(PendingMessage("p", "a", "must survive", "", id = "op"))))
        val failing = object : MemoryPayloadProtector {
            override fun protect(memoryId: MemoryIdentifier, plaintext: ByteArray): String = error("injected")
            override fun unprotect(memoryId: MemoryIdentifier, encoded: String): ByteArray = error("unused")
        }

        assertNotNull(runCatching { PendingMessageStore(root, "session", failing).all() }.exceptionOrNull())
        assertTrue(file.readText().contains("must survive"))
        assertFalse(File(root, "pending_queue.json.bak").exists())
    }

    @Test fun concurrentEnqueueAndClaimPreserveNewOperations() {
        val root = directory()
        val store = PendingMessageStore(root, "session")
        val first = PendingMessage("p", "a", "first", "", id = "first")
        store.add(first)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val jobs = (0 until 30).map { i -> Callable {
                PendingMessageStore(root, "session").add(first.copy(id = "other-$i", text = "other"))
            } } + (0 until 6).map { Callable { store.claim("first") } }
            pool.invokeAll(jobs).forEach { it.get() }
            assertEquals(31, store.all().size)
            assertEquals(1, store.all().first { it.id == "first" }.attempts)
        } finally { pool.shutdownNow() }
    }

    @Test fun processRestartDoesNotAutomaticallyRepeatUncertainCall() {
        val root = directory()
        val store = PendingMessageStore(root, "old-process")
        store.add(PendingMessage("p", "a", "text", "", id = "stable"))
        assertNotNull(store.claim("stable"))
        val restarted = PendingMessageStore(root, "new-process")
        assertEquals(PendingDeliveryState.UNCERTAIN, restarted.all().single().state)
        assertNull(restarted.claim("stable"))
        restarted.retry("stable")
        assertNotNull(restarted.claim("stable"))
    }

    @Test fun cancellationAfterClaimQuarantinesRequestAsUnknownAndBlocksReplay() {
        val store = PendingMessageStore(directory(), "session")
        store.add(PendingMessage("p", "a", "text", "", id = "cancelled-operation"))
        assertNotNull(store.claim("cancelled-operation"))

        markPendingCancellationUncertain(store, "cancelled-operation")

        assertEquals(PendingDeliveryState.UNCERTAIN, store.all().single().state)
        assertNull(store.claim("cancelled-operation", Long.MAX_VALUE))
        assertTrue(store.all().single().error.orEmpty().contains("неизвестен"))
    }

    @Test fun completedRequestAndSavedResultAreNeverClaimedAgain() {
        val store = PendingMessageStore(directory(), "session")
        store.add(PendingMessage("p", "a", "text", "", id = "stable"))
        store.claim("stable")
        store.result("stable", "actual result")
        assertNull(store.claim("stable"))
        assertEquals("actual result", store.all().single().result)
        store.delivered("stable")
        assertNull(store.claim("stable"))
        assertEquals(PendingDeliveryState.COMPLETED, store.all().single().state)
    }

    @Test fun legacyQueueRetainsStableIdentityAcrossReadsAndMigration() {
        val root = directory()
        File(root, "pending_queue.json").writeText("""[{"projectId":"p","agentName":"a","text":"legacy","time":"12:00"}]""")
        val store = PendingMessageStore(root, "session")
        val id = store.all().single().id
        assertEquals(id, store.all().single().id)
        assertEquals(PendingDeliveryState.UNCERTAIN, store.all().single().state)
        assertNull(store.claim(id))
        store.retry(id)
        assertNotNull(store.claim(id))
        assertEquals(id, PendingMessageStore(root, "next-session").all().single().id)
    }

    @Test fun uncertainFailureBlocksRetryButRejectedRequestUsesBoundedBackoff() {
        val store = PendingMessageStore(directory(), "session")
        store.add(PendingMessage("p", "a", "text", "", id = "one"))
        store.claim("one")
        store.failed("one", "connection lost", true, true)
        assertEquals(PendingDeliveryState.UNCERTAIN, store.all().single().state)
        assertNull(store.claim("one", Long.MAX_VALUE))
        store.retry("one")
        repeat(PendingMessageStore.MAX_ATTEMPTS) {
            assertNotNull(store.claim("one", Long.MAX_VALUE))
            store.failed("one", "quota", true, false, 1)
        }
        assertEquals(PendingDeliveryState.FAILED, store.all().single().state)
        assertNull(store.claim("one", Long.MAX_VALUE))
    }

    @Test fun recoveredPreviousQueueGenerationCannotRepeatCompletedRemoteCall() {
        val root = directory()
        val store = PendingMessageStore(root, "session")
        store.add(PendingMessage("p", "a", "text", "", id = "one"))
        store.claim("one") // backup still contains QUEUED before the request was sent.
        File(root, "pending_queue.json").writeText("broken")
        assertEquals(PendingDeliveryState.UNCERTAIN, store.all().single().state)
        assertNull(store.claim("one", Long.MAX_VALUE))
    }

    @Test fun historyKeysCannotEscapeStorageDirectory() {
        runCatching { historyStore(directory(), "../../outside") }.onSuccess { fail("Unsafe filename accepted") }
    }

    private fun historyProtector(seed: Int): MemoryPayloadProtector = AesGcmMemoryPayloadProtector(
        SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")
    )

    @Test fun restoredInFlightRequiresReviewEvenWithinSameProcess() {
        val store = PendingMessageStore(directory(), "session")
        store.add(PendingMessage("p", "a", "text", "", id = "one"))
        store.claim("one")
        store.recoverAfterRestore()
        assertEquals(PendingDeliveryState.UNCERTAIN, store.all().single().state)
    }
}
