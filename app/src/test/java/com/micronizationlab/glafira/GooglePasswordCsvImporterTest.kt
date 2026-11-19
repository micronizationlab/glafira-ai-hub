package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class GooglePasswordCsvImporterTest {
    private fun parse(csv: String) =
        GooglePasswordCsvImporter.parse(ByteArrayInputStream(csv.toByteArray(Charsets.UTF_8)))

    private fun assertRejected(csv: String, messagePart: String? = null) {
        val error = runCatching { parse(csv) }.exceptionOrNull()
        assertTrue("Expected CSV input to be rejected", error != null)
        if (messagePart != null) assertTrue(error?.message.orEmpty().contains(messagePart))
    }

    @Test
    fun importsGoogleCsvWithQuotedCommasAndMultilineNotes() {
        val csv = "\uFEFFname,url,username,password,note\r\n" +
            "\"Shop, main\",https://shop.example,user1,secret1,\"line one\nline two\"\r\n" +
            "Mail,https://mail.example,user2,secret2,personal\r\n"
        val result = parse(csv)
        assertEquals(2, result.entries.size)
        assertEquals("Shop, main", result.entries[0].title)
        assertEquals("line one\nline two", result.entries[0].notes)
        assertEquals("secret2", result.entries[1].password)
    }

    @Test
    fun skipsBlankRowsAndRejectsMissingPasswordColumn() {
        val csv = "name,url,username,password,note\nSite,https://site.example,user,pw,\n,,,,\n"
        val result = parse(csv)
        assertEquals(1, result.entries.size)
        assertEquals(1, result.skippedRows)
        assertRejected("name,url,username\nSite,https://site.example,user\n", "password")
    }

    @Test
    fun rejectsDuplicateHeaders() {
        assertRejected("name,url,username,password,URL\nSite,https://site.example,u,pw,x\n", "повторяющиеся")
    }

    @Test
    fun rejectsBlankHeaderNames() {
        assertRejected("name,url,,password\nSite,https://site.example,u,pw\n", "пустое имя")
    }

    @Test
    fun acceptsCaseInsensitiveHeadersAndTrimsHeaderWhitespace() {
        val result = parse(" NAME , URL , Username , Password \nSite,https://site.example,user,pw\n")
        assertEquals("Site", result.entries.single().title)
    }

    @Test
    fun rejectsQuoteInsideUnquotedField() {
        assertRejected("name,url,username,password\nSi\"te,https://site.example,u,pw\n")
    }

    @Test
    fun rejectsCharactersAfterClosingQuote() {
        assertRejected("name,url,username,password\n\"Site\"junk,https://site.example,u,pw\n", "лишние символы")
    }

    @Test
    fun rejectsUnclosedQuotedField() {
        assertRejected("name,url,username,password\n\"Site,https://site.example,u,pw\n", "незакрытую")
    }

    @Test
    fun rejectsRowsWithTooManyColumns() {
        assertRejected("name,url,username,password\nSite,https://site.example,u,pw,unexpected\n", "больше столбцов")
    }

    @Test
    fun rejectsMoreThan64Columns() {
        val header = (1..65).joinToString(",") { "h$it" }
        assertRejected(header + "\n", "слишком много столбцов")
    }

    @Test
    fun rejectsInvalidUtf8() {
        val bytes = byteArrayOf(0xC3.toByte(), 0x28)
        val error = runCatching {
            GooglePasswordCsvImporter.parse(ByteArrayInputStream(bytes))
        }.exceptionOrNull()
        assertTrue(error != null)
    }

    @Test
    fun rejectsNullCharacters() {
        assertRejected("name,url,username,password\nSite,https://site.example,u,pw\u0000\n", "нулевой")
    }

    @Test
    fun preservesWhitespaceInsideCredentials() {
        val result = parse("name,url,username,password\nSite,https://site.example, user , pass \n")
        assertEquals(" user ", result.entries.single().username)
        assertEquals(" pass ", result.entries.single().password)
    }

    @Test
    fun usesWebsiteWhenTitleIsBlank() {
        val result = parse("name,url,username,password\n,https://site.example,u,pw\n")
        assertEquals("https://site.example", result.entries.single().title)
    }

    @Test
    fun handlesCrOnlyLineEndings() {
        val result = parse("name,url,username,password\rSite,https://site.example,u,pw\r")
        assertEquals(1, result.entries.size)
    }

    @Test
    fun rejectsEmptyInput() {
        assertRejected("")
    }

    @Test
    fun rejectsOversizedField() {
        val huge = "x".repeat(1_048_577)
        assertRejected("name,url,username,password\n$huge,https://site.example,u,pw\n", "слишком длинное")
    }

    @Test
    fun rejectsMoreThanTenThousandRows() {
        val csv = buildString {
            append("name,url,username,password\n")
            repeat(10_001) { append("Site,https://site.example,u,pw\n") }
        }
        assertRejected(csv, "10 000")
    }

    @Test
    fun rejectsInputLargerThanTenMegabytes() {
        val bytes = ByteArray(10 * 1024 * 1024 + 1)
        val error = runCatching {
            GooglePasswordCsvImporter.parse(ByteArrayInputStream(bytes))
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("10 МБ"))
    }

    @Test
    fun handlesStreamsThatTemporarilyReturnZeroBytes() {
        val source = "name,url,username,password\nSite,https://site.example,u,pw\n".toByteArray()
        val stream = object : InputStream() {
            private val delegate = ByteArrayInputStream(source)
            private var first = true
            override fun read(): Int = delegate.read()
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (first) {
                    first = false
                    return 0
                }
                return delegate.read(target, offset, length)
            }
        }
        val result = GooglePasswordCsvImporter.parse(stream)
        assertEquals(1, result.entries.size)
    }
}
