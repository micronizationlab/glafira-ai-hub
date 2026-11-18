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
}
