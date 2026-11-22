package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubRefreshTokenPolicyTest {
    @Test
    fun omittedRotationKeepsExistingRefreshToken() {
        assertEquals(
            "existing-refresh-token",
            resolveGitHubRefreshToken(null, "existing-refresh-token")
        )
    }

    @Test
    fun blankRotationKeepsExistingRefreshToken() {
        assertEquals(
            "existing-refresh-token",
            resolveGitHubRefreshToken("  ", "existing-refresh-token")
        )
    }

    @Test
    fun suppliedRotationReplacesExistingRefreshToken() {
        assertEquals(
            "rotated-refresh-token",
            resolveGitHubRefreshToken("rotated-refresh-token", "existing-refresh-token")
        )
    }
}
