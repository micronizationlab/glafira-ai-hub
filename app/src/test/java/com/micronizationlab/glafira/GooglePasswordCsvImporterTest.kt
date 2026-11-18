package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class GooglePasswordCsvImporterTest {
    @Test
    fun importsGoogleCsvWithQuotedCommasAndMultilineNotes() {
        val csv = "\uFEFFname,url,username,password,note\r\n" +
            "\"Shop, main\",https://shop.example,user1,secret1,\"line one\nline two\"\r\n" +
            "Mail,https://mail.example,user2,secret2,personal\r\n"
        val result = GooglePasswordCsvImporter.parse(ByteArrayInputStream(csv.toByteArray()))

        assertEquals(2, result.entries.size)
        assertEquals("Shop, main", result.entries[0].title)
        assertEquals("line one\nline two", result.entries[0].notes)
        assertEquals("secret2", result.entries[1].password)
    }

    @Test
    fun skipsBlankRowsAndRejectsMissingPasswordColumn() {
        val csv = "name,url,username,password,note\nSite,https://site.example,user,pw,\n,,,,\n"
        val result = GooglePasswordCsvImporter.parse(ByteArrayInputStream(csv.toByteArray()))
        assertEquals(1, result.entries.size)
        assertEquals(1, result.skippedRows)

        val bad = "name,url,username\nSite,https://site.example,user\n"
        val error = runCatching {
            GooglePasswordCsvImporter.parse(ByteArrayInputStream(bad.toByteArray()))
        }.exceptionOrNull()
        assertTrue(error?.message?.contains("password") == true)
    }
    @Test
    fun rejectsDuplicateHeadersInsteadOfSilentlyChoosingOne() {
        val csv = "name,url,username,password,password\nSite,https://site.example,user,pw,pw2\n"
        val error = runCatching {
            GooglePasswordCsvImporter.parse(ByteArrayInputStream(csv.toByteArray()))
        }.exceptionOrNull()
        assertTrue(error?.message?.contains("повторяются") == true)
    }

    @Test
    fun credentialIdentityUsesTitleWhenWebsiteIsMissing() {
        val first = VaultEntry("1", "Personal mail", "user@example.com", "one", "", "", 1)
        val same = VaultEntry("2", "Personal mail", "USER@example.com", "two", "", "", 2)
        val different = VaultEntry("3", "Work mail", "user@example.com", "three", "", "", 3)
        assertEquals(VaultEntryIdentity.key(first), VaultEntryIdentity.key(same))
        assertTrue(VaultEntryIdentity.key(first) != VaultEntryIdentity.key(different))
    }

    @Test
    fun credentialIdentityNormalizesUrlAndUsername() {
        val first = VaultEntry("1", "Shop", "Alice", "one", "https://shop.example/", "", 1)
        val second = VaultEntry("2", "Shop", "alice", "two", "shop.example", "", 2)
        assertEquals(VaultEntryIdentity.key(first), VaultEntryIdentity.key(second))
    }

}
