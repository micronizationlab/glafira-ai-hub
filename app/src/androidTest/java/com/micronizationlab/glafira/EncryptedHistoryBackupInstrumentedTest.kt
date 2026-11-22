package com.micronizationlab.glafira

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedHistoryBackupInstrumentedTest {
    private val baseContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun portableBackupReencryptsHistoryWithReplacementDeviceKeyWithoutPlaintextResidue() {
        val (context, testRoot) = isolatedContext("history")
        val historyKey = "portable-history-test_${System.nanoTime()}"
        val secret = "portable-secret-${System.nanoTime()}"
        val passphrase = "GLAFIRA-portable-history-test".toCharArray()
        val sourceProtector = protector(11)
        val replacementProtector = protector(29)
        var archive: File? = null
        try {
            PendingMessageStore(context.filesDir).deleteAll()
            HistoryCryptoRuntime.setProtectorForTesting(sourceProtector)
            saveHistory(context, historyKey, listOf(Msg(secret, true, "12:00", operationId = "portable-op")))
            archive = BackupArchive.create(context, passphrase)
            deleteHistory(context, historyKey)

            HistoryCryptoRuntime.setProtectorForTesting(replacementProtector)
            BackupArchive.restore(context, archive, passphrase)

            assertEquals(secret, loadHistory(context, historyKey).single().text)
            val raw = File(context.filesDir, "chat_$historyKey.json").readText()
            assertTrue(raw.contains("GLAFIRA_ENCRYPTED_HISTORY"))
            assertFalse(raw.contains(secret))
            assertFalse(context.cacheDir.walkTopDown().filter { it.isFile && it != archive }
                .any { runCatching { it.readText().contains(secret) }.getOrDefault(false) })
        } finally {
            runCatching { deleteHistory(context, historyKey) }
            archive?.delete()
            passphrase.fill('\u0000')
            HistoryCryptoRuntime.setProtectorForTesting(null)
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun portableBackupReencryptsOutboxWithReplacementDeviceKeyWithoutPlaintextResidue() {
        val (context, testRoot) = isolatedContext("outbox")
        val operationId = "portable-outbox-${System.nanoTime()}"
        val prompt = "portable-outbox-prompt-${System.nanoTime()}"
        val result = "portable-outbox-result-${System.nanoTime()}"
        val systemPrompt = "portable-outbox-system-${System.nanoTime()}"
        val passphrase = "GLAFIRA-portable-outbox-test".toCharArray()
        val sourceProtector = protector(41)
        val replacementProtector = protector(57)
        var archive: File? = null
        try {
            PendingMessageStore(context.filesDir).deleteAll()
            PendingCryptoRuntime.setProtectorForTesting(sourceProtector)
            pendingMessageStore(context).add(PendingMessage(
                projectId = "portable-project", agentName = "portable-agent", text = prompt,
                time = "12:00", id = operationId, state = PendingDeliveryState.RESULT_READY,
                result = result, systemPrompt = systemPrompt
            ))
            archive = BackupArchive.create(context, passphrase)
            PendingMessageStore(context.filesDir).deleteAll()

            PendingCryptoRuntime.setProtectorForTesting(replacementProtector)
            BackupArchive.restore(context, archive, passphrase)

            val restored = PendingMessageStore(context.filesDir, "test-session", replacementProtector)
                .portableSnapshot().single { it.id == operationId }
            assertEquals(prompt, restored.text)
            assertEquals(result, restored.result)
            assertEquals(systemPrompt, restored.systemPrompt)
            val raw = File(context.filesDir, "pending_queue.json").readText()
            assertTrue(raw.contains("GLAFIRA_ENCRYPTED_OUTBOX"))
            listOf(prompt, result, systemPrompt).forEach { assertFalse(raw.contains(it)) }
            assertFalse(context.cacheDir.walkTopDown().filter { it.isFile && it != archive }
                .any { file -> listOf(prompt, result, systemPrompt).any { secret ->
                    runCatching { file.readText().contains(secret) }.getOrDefault(false)
                } })
        } finally {
            PendingMessageStore(context.filesDir).deleteAll()
            archive?.delete()
            passphrase.fill('\u0000')
            PendingCryptoRuntime.setProtectorForTesting(null)
            testRoot.deleteRecursively()
        }
    }

    private fun isolatedContext(name: String): Pair<Context, File> {
        val root = File(baseContext.cacheDir, "encrypted-backup-$name-${UUID.randomUUID()}")
        check(root.mkdirs())
        val files = File(root, "files").apply { check(mkdirs()) }
        val cache = File(root, "cache").apply { check(mkdirs()) }
        return object : ContextWrapper(baseContext) {
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        } to root
    }

    private fun protector(seed: Int): MemoryPayloadProtector = AesGcmMemoryPayloadProtector(
        SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")
    )
}
