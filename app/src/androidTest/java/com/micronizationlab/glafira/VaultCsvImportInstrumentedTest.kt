package com.micronizationlab.glafira

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultCsvImportInstrumentedTest {
    @Test fun selectedCsvIsImportedOnlyAfterVaultIsUnlockedAgain() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "vault-import-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(target) { override fun getFilesDir(): File = root }
        val csv = File(root, "selected.csv").apply {
            writeText("name,url,username,password,note\nExample,https://example.test,user,private-test-password,note\n")
        }
        val passphrase = "vault-import-passphrase".toCharArray()
        var active: VaultSession? = null
        try {
            val original = VaultSession.create(context, passphrase.copyOf())
            original.lock() // Lifecycle ON_STOP while the system picker is open.
            val selected = Uri.fromFile(csv)
            val encryptedBefore = root.listFiles()!!.filter { it != csv }.associate { it.name to it.readBytes().toList() }
            assertThrows(IllegalStateException::class.java) { importVaultCsv(context, original, selected) }
            assertEquals(encryptedBefore, root.listFiles()!!.filter { it != csv }.associate { it.name to it.readBytes().toList() })
            val unlocked = VaultSession.unlock(context, passphrase.copyOf())
            active = unlocked
            val imported = importVaultCsv(context, unlocked, selected)
            assertEquals(1, imported.imported)
            assertEquals("private-test-password", unlocked.loadEntries().single().password)
            assertEquals(0, importVaultCsv(context, unlocked, selected).imported)
            assertTrue(root.listFiles()!!.filter { it != csv }.none { it.readText().contains("private-test-password") })
        } finally {
            active?.lock()
            passphrase.fill('\u0000')
            root.deleteRecursively()
        }
    }
}
