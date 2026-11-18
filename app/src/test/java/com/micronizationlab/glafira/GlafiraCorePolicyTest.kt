package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Test

class GlafiraCorePolicyTest {
    @Test
    fun onlyReturnsConfiguredAndExplicitlyApprovedPeers() {
        val configured = listOf(ProviderId.GIGACHAT, ProviderId.OPENAI, ProviderId.DEEPSEEK, ProviderId.GROK)
        val allowed = setOf(ProviderId.OPENAI, ProviderId.GROK)

        val result = GlafiraCorePolicy.selectAllowedPeers(ProviderId.GIGACHAT, configured, allowed)

        assertEquals(listOf(ProviderId.OPENAI, ProviderId.GROK), result)
    }

    @Test
    fun neverReturnsPrimaryOrUnapprovedProvider() {
        val configured = listOf(ProviderId.GIGACHAT, ProviderId.OPENAI, ProviderId.DEEPSEEK)
        val allowed = setOf(ProviderId.GIGACHAT, ProviderId.OPENAI)

        val result = GlafiraCorePolicy.selectAllowedPeers(ProviderId.GIGACHAT, configured, allowed)

        assertEquals(listOf(ProviderId.OPENAI), result)
    }

    @Test
    fun excludesUnimplementedYandexPeerAndEmptyAllowlist() {
        val configured = listOf(ProviderId.OPENAI, ProviderId.YANDEX)

        assertEquals(
            emptyList<ProviderId>(),
            GlafiraCorePolicy.selectAllowedPeers(ProviderId.DEEPSEEK, configured, emptySet())
        )
        assertEquals(
            emptyList<ProviderId>(),
            GlafiraCorePolicy.selectAllowedPeers(ProviderId.DEEPSEEK, configured, setOf(ProviderId.YANDEX))
        )
    }
}
