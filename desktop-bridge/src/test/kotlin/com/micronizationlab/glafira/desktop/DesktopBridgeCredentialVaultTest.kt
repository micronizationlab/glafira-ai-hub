package com.micronizationlab.glafira.desktop

import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
            loaded.hmacSecret.fill(0)
            assertFalseContains(Files.readAllBytes(path), secret)
        } finally {
            password.fill(0.toChar())
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun importsAndroidOwnerCredentialBundle() {
        val directory = Files.createTempDirectory("glafira-owner-bundle-test")
        val path = directory.resolve("device.vault")
        val password = "another-long-owner-passphrase".toCharArray()
        val secret = ByteArray(32) { (it + 9).toByte() }
        val bundle = org.json.JSONObject().apply {
            put("format", "GLAFIRA_BRIDGE_CREDENTIAL_V1")
            put("origin", "https://192.168.1.20:47321")
            put("deviceId", "device-identifier-02")
            put("hmacSecretBase64Url", Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
            put("grantedScopes", org.json.JSONArray(listOf("project.read", "conversation.read")))
            put("serverCertificateSha256", "c".repeat(64))
        }.toString()
        try {
            DesktopBridgeCredentialVault.importOwnerBundle(path, bundle, password)
            val loaded = DesktopBridgeCredentialVault.load(path, password)
            assertEquals("device-identifier-02", loaded.deviceId)
            assertEquals(setOf("project.read", "conversation.read"), loaded.grantedScopes)
            assertArrayEquals(secret, loaded.hmacSecret)
            loaded.hmacSecret.fill(0)
        } finally {
            password.fill(0.toChar())
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
            assertThrows(IllegalArgumentException::class.java) {
                DesktopBridgeCredentialVault.import(
                    path, "https://192.168.1.20:47321", "device-identifier-01",
                    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)),
                    setOf("unknown.scope"), "b".repeat(64), password
                )
            }
        } finally {
            password.fill(0.toChar())
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun loadAndUseVaultClearsCredentialAndConsumesPassphraseAfterSuccess() {
        val directory = Files.createTempDirectory("glafira-vault-scoped-success")
        val path = directory.resolve("device.vault")
        val importPassphrase = "vault-import-passphrase-01".toCharArray()
        val usePassphrase = "vault-use-passphrase-0001".toCharArray()
        val secret = ByteArray(32) { (it + 17).toByte() }
        try {
            DesktopBridgeCredentialVault.import(
                path, "https://192.168.1.20:47321", "device-identifier-03",
                Base64.getUrlEncoder().withoutPadding().encodeToString(secret),
                setOf("project.read"), "d".repeat(64), importPassphrase
            )
            var capturedSecret: ByteArray? = null
            val result = DesktopBridgeCredentialVault.loadAndUseVault(path, usePassphrase) { origin, credentials ->
                assertEquals("https://192.168.1.20:47321", origin)
                assertEquals("device-identifier-03", credentials.deviceId)
                assertArrayEquals(secret, credentials.hmacSecret)
                capturedSecret = credentials.hmacSecret
                "completed"
            }
            assertEquals("completed", result)
            assertArrayEquals(ByteArray(32), requireNotNull(capturedSecret))
            assertTrue(usePassphrase.all { it == '\u0000' })
        } finally {
            importPassphrase.fill(0.toChar())
            usePassphrase.fill(0.toChar())
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun loadAndUseVaultClearsCredentialAndPassphraseWhenActionThrows() {
        val directory = Files.createTempDirectory("glafira-vault-scoped-failure")
        val path = directory.resolve("device.vault")
        val importPassphrase = "vault-import-passphrase-02".toCharArray()
        val usePassphrase = "vault-use-passphrase-0002".toCharArray()
        val secret = ByteArray(32) { (it + 29).toByte() }
        try {
            DesktopBridgeCredentialVault.import(
                path, "https://192.168.1.20:47321", "device-identifier-04",
                Base64.getUrlEncoder().withoutPadding().encodeToString(secret),
                setOf("project.read"), "e".repeat(64), importPassphrase
            )
            var capturedSecret: ByteArray? = null
            assertThrows(IllegalStateException::class.java) {
                DesktopBridgeCredentialVault.loadAndUseVault(path, usePassphrase) { _, credentials ->
                    capturedSecret = credentials.hmacSecret
                    throw IllegalStateException("simulated operation failure")
                }
            }
            assertArrayEquals(ByteArray(32), capturedSecret)
            assertTrue(usePassphrase.all { it == '\u0000' })
        } finally {
            importPassphrase.fill(0.toChar())
            usePassphrase.fill(0.toChar())
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun loadAndUseVaultConsumesPassphraseWhenVaultLoadFails() {
        val directory = Files.createTempDirectory("glafira-vault-scoped-load-failure")
        val path = directory.resolve("missing.vault")
        val passphrase = "vault-use-passphrase-0003".toCharArray()
        try {
            assertThrows(java.nio.file.NoSuchFileException::class.java) {
                DesktopBridgeCredentialVault.loadAndUseVault(path, passphrase) { _, _ -> "unused" }
            }
            assertTrue(passphrase.all { it == '\u0000' })
        } finally {
            passphrase.fill(0.toChar())
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
