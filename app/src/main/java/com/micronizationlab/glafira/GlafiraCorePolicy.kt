package com.micronizationlab.glafira

internal object GlafiraCorePolicy {
    fun selectAllowedPeers(
        primaryProvider: ProviderId,
        configuredProviders: List<ProviderId>,
        approvedPeerIds: Set<ProviderId>
    ): List<ProviderId> = configuredProviders.filter { provider ->
        provider != primaryProvider &&
            provider in approvedPeerIds &&
            provider != ProviderId.YANDEX
    }
}
