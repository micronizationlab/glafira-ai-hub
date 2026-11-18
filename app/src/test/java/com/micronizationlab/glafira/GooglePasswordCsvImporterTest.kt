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
    fun parsesReorderedHeadersAndPreservesTimestamp() {
        val csv = "\uFEFFurl,password,note,username,name\nhttps://example.com,secret,private,user,Example"
        val entry = GooglePasswordCsvImporter.parse(csv.byteInputStream(), now = 123L).entries.single()

        assertEquals("Example", entry.title)
        assertEquals("https://example.com", entry.website)
        assertEquals("user", entry.username)
        assertEquals("secret", entry.password)
        assertEquals("private", entry.notes)
        assertEquals(123L, entry.updatedAt)
    }

    @Test
    fun preservesEscapedQuotesInsideQuotedFields() {
        val csv = "name,url,username,password,note\nExample,https://example.com,user,secret,\"text with \"\"quotes\"\"\""
        val entry = GooglePasswordCsvImporter.parse(csv.byteInputStream()).entries.single()
        assertEquals("text with \"quotes\"", entry.notes)
    }

    @Test
    fun rejectsUnclosedQuotedFields() {
        val csv = "name,url,username,password\n\"unfinished,https://example.com,user,secret"
        val error = runCatching { GooglePasswordCsvImporter.parse(csv.byteInputStream()) }.exceptionOrNull()
        assertTrue(error?.message?.contains("незакрытую кавычку") == true)
    }

    @Test
    fun enforcesInputByteLimitBeforeParsing() {
        val oversized = ByteArray(10 * 1024 * 1024 + 1) { 'a'.code.toByte() }
        val error = runCatching {
            GooglePasswordCsvImporter.parse(ByteArrayInputStream(oversized))
        }.exceptionOrNull()
        assertTrue(error?.message?.contains("10 МБ") == true)
    }

    @Test
    fun rejectsMoreThanTenThousandDataRows() {
        val csv = buildString {
            append("name,url,username,password,note\n")
            repeat(10_001) { append("Example,https://example.com,user,secret,\n") }
        }
        val error = runCatching { GooglePasswordCsvImporter.parse(csv.byteInputStream()) }.exceptionOrNull()
        assertTrue(error?.message?.contains("10000") == true)
    }

    @Test
    fun rejectsQuoteInsideUnquotedFieldAndTrailingDataAfterQuotedField() {
        val embeddedQuote = "name,url,username,password\nExa\"mple,https://example.com,user,secret"
        val embeddedError = runCatching {
            GooglePasswordCsvImporter.parse(ByteArrayInputStream(embeddedQuote.toByteArray()))
        }.exceptionOrNull()
        assertTrue(embeddedError?.message?.contains("кавычку внутри") == true)

        val trailingData = "name,url,username,password\n\"Example\"oops,https://example.com,user,secret"
        val trailingError = runCatching {
            GooglePasswordCsvImporter.parse(ByteArrayInputStream(trailingData.toByteArray()))
        }.exceptionOrNull()
        assertTrue(trailingError?.message?.contains("после закрывающей кавычки") == true)
    }

    @Test
    fun rejectsDuplicateColumnNames() {
        val csv = "name,url,username,password,name\nExample,https://example.com,user,secret,Other"
        val error = runCatching {
            GooglePasswordCsvImporter.parse(ByteArrayInputStream(csv.toByteArray()))
        }.exceptionOrNull()
        assertTrue(error?.message?.contains("повторяющиеся") == true)
    }

}
