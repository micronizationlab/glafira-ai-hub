package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraWebAccessPolicyTest {
    @Test
    fun extractsTitleAndReadableTextWithoutScriptContent() {
        val html = """
            <html><head><title>Example &amp; Test</title></head>
            <body><h1>Hello</h1><script>secret()</script><p>Public content&nbsp;here.</p></body></html>
        """.trimIndent()

        assertEquals("Example & Test", GlafiraWebAccessPolicy.extractTitle(html))
        val text = GlafiraWebAccessPolicy.extractReadableText(html)
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("Public content here."))
        assertTrue(!text.contains("secret"))
    }

    @Test
    fun rejectsLoopbackAndLocalHosts() {
        listOf("http://127.0.0.1/", "http://localhost/", "http://[::1]/").forEach { url ->
            val result = runCatching { GlafiraWebAccessPolicy.validatePublicHttpUrl(url) }
            assertTrue("Expected URL to be rejected: $url", result.isFailure)
        }
    }

    @Test
    fun rejectsNonHttpSchemesAndCredentialUrls() {
        listOf("file:///etc/passwd", "ftp://example.com/", "https://user:pass@example.com/").forEach { url ->
            val result = runCatching { GlafiraWebAccessPolicy.validatePublicHttpUrl(url) }
            assertTrue("Expected URL to be rejected: $url", result.isFailure)
        }
    }
}
