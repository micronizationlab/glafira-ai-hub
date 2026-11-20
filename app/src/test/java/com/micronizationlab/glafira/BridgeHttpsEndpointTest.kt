package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeHttpsEndpointTest {
    @Test
    fun acceptsHttpsOriginAndUsesFixedBridgeRequestPath() {
        val endpoint = BridgeHttpsEndpoint.parse("https://glafira.local:9443/")
        assertEquals(
            "https://glafira.local:9443/bridge/v1/requests",
            endpoint.resolveRequestUrl().toString()
        )
    }

    @Test
    fun rejectsPlainHttpAndOriginsWithPathOrCredentials() {
        assertTrue(runCatching { BridgeHttpsEndpoint.parse("http://glafira.local") }.isFailure)
        assertTrue(runCatching { BridgeHttpsEndpoint.parse("https://glafira.local/unsafe") }.isFailure)
        assertTrue(runCatching { BridgeHttpsEndpoint.parse("https://user:pass@glafira.local") }.isFailure)
        assertTrue(runCatching { BridgeHttpsEndpoint.parse("https://glafira.local?redirect=1") }.isFailure)
    }

    @Test
    fun requiresExactlyA256BitSha256Fingerprint() {
        assertEquals(32, BridgeHttpsEndpoint.parseFingerprint("ab".repeat(32)).size)
        assertTrue(runCatching { BridgeHttpsEndpoint.parseFingerprint("ab".repeat(31)) }.isFailure)
        assertTrue(runCatching { BridgeHttpsEndpoint.parseFingerprint("zz".repeat(32)) }.isFailure)
    }
}
