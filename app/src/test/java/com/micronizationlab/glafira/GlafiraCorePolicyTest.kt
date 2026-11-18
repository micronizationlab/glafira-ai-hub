package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun autonomyPolicyPreservesRoleAndAddsCapabilityHonestyRules() {
        val base = "Ты — Глафира, главный координатор."

        val result = GlafiraAutonomyPolicy.apply(base)

        assertTrue(result.startsWith(base))
        assertTrue(result.contains("не «просто помощник»"))
        assertTrue(result.contains("сайт"))
        assertTrue(result.contains("Не утверждай, что открыла или проверила ресурс"))
        assertTrue(result.contains("подтверждения владельца"))
    }

    @Test
    fun autonomyPolicyCanBeUsedWithoutAnAgentSpecificBasePrompt() {
        val result = GlafiraAutonomyPolicy.apply("")

        assertTrue(result.isNotBlank())
        assertTrue(result.contains("самостоятельный интеллектуальный координатор"))
    }
}
