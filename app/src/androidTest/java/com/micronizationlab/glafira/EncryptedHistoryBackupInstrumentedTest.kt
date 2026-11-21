package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedHistoryBackupInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun portableBackupReencryptsHistoryWithReplacementDeviceKeyWithoutPlaintextResidue() {
        val historyKey = "portable-history-test_${System.nanoTime()}"
        val secret = "portable-secret-${System.nanoTime()}"
        val passphrase = "GLAFIRA-portable-history-test".toCharArray()
        val sourceProtector = protector(11)
        val replacementProtector = protector(29)
        var archive: File? = null
        try {
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
        }
    }

    private fun protector(seed: Int): MemoryPayloadProtector = AesGcmMemoryPayloadProtector(
        SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")
    )
}
