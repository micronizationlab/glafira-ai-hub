package com.micronizationlab.glafira.desktop

import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopBridgeCredentialVaultTest {
    @Test fun importsAndReloadsEncryptedDeviceCredential() {
        val directory = Files.createTempDirectory("glafira-vault-test")
        val path = directory.resolve("device.vault")
        val secret = ByteArray(32) { (it + 1).toByte() }
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        val password = "a-long-owner-passphrase".toCharArray()
        try {
            DesktopBridgeCredentialVault.import(
                path = path,
                origin = "https://192.168.1.20:47321",
                deviceId = "device-identifier-01",
                hmacSecretBase64Url = encoded,
                grantedScopes = setOf("project.read", "conversation.read"),
                serverCertificateSha256 = "a".repeat(64),
                passphrase = password
            )
            val loaded = DesktopBridgeCredentialVault.load(path, password)
            assertEquals("device-identifier-01", loaded.deviceId)
            assertEquals(setOf("project.read", "conversation.read"), loaded.grantedScopes)
            assertEquals("a".repeat(64), loaded.serverCertificateSha256)
            assertArrayEquals(secret, loaded.hmacSecret)
            assertFalseContains(Files.readAllBytes(path), secret)
        } finally {
            password.fill('\u0000')
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun rejectsWrongPassphraseAndInvalidSecret() {
        val directory = Files.createTempDirectory("glafira-vault-test")
        val path = directory.resolve("device.vault")
        val password = "a-long-owner-passphrase".toCharArray()
        try {
            DesktopBridgeCredentialVault.import(
                path, "https://192.168.1.20:47321", "device-identifier-01",
                Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)),
                setOf("project.read"), "b".repeat(64), password
            )
            assertThrows(SecurityException::class.java) {
                DesktopBridgeCredentialVault.load(path, "wrong-owner-passphrase".toCharArray())
            }
            assertThrows(IllegalArgumentException::class.java) {
                DesktopBridgeCredentialVault.import(
                    path, "https://192.168.1.20:47321", "device-identifier-01",
                    "bad", setOf("project.read"), "b".repeat(64), password
                )
            }
        } finally {
            password.fill('\u0000')
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    private fun assertFalseContains(container: ByteArray, secret: ByteArray) {
        val found = container.indices.any { start ->
            start + secret.size <= container.size &&
                secret.indices.all { container[start + it] == secret[it] }
        }
        org.junit.Assert.assertFalse(found)
    }
}
