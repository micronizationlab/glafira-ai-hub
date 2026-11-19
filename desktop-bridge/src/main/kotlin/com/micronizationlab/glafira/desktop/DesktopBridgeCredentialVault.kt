package com.micronizationlab.glafira.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class DesktopBridgeVaultEntry(
    val origin: String,
    val deviceId: String,
    val hmacSecret: ByteArray,
    val grantedScopes: Set<String>,
    val serverCertificateSha256: String
) {
    fun toCredentials() = DesktopBridgeCredentials(
        deviceId, hmacSecret.copyOf(), grantedScopes.toSet(), serverCertificateSha256
    )
}

/**
 * Passphrase-protected local Desktop credential vault.
 *
 * Secrets are encrypted with AES-256-GCM and a PBKDF2-HMAC-SHA256 derived key.
 * The file is written atomically with owner-only POSIX permissions where supported.
 * This is an encrypted application vault, not a claim of native Windows Credential
 * Manager/macOS Keychain integration; native OS keyring adapters remain a follow-up.
 */
object DesktopBridgeCredentialVault {
    private const val MAGIC = 0x474C4642 // GLFB
    private const val VERSION = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 310_000
    private const val MAX_FILE_BYTES = 16 * 1024

    fun import(
        path: Path,
        origin: String,
        deviceId: String,
        hmacSecretBase64Url: String,
        grantedScopes: Set<String>,
        serverCertificateSha256: String,
        passphrase: CharArray
    ) {
        require(passphrase.size >= 12) { "Vault passphrase must contain at least 12 characters." }
        val secret = Base64.getUrlDecoder().decode(hmacSecretBase64Url)
        try {
                require(secret.size == 32) { "Device HMAC secret must be exactly 256 bits." }
            val entry = DesktopBridgeVaultEntry(
                origin = origin,
                deviceId = deviceId,
                hmacSecret = secret,
                grantedScopes = grantedScopes.toSet(),
                serverCertificateSha256 = serverCertificateSha256.lowercase()
            )
            validate(entry)
            val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
            val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
            val plaintext = serialize(entry)
            val key = deriveKey(passphrase, salt)
            val ciphertext = try {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                    updateAAD(aad())
                    doFinal(plaintext)
                }
            } finally {
                plaintext.fill(0)
                key.encoded?.fill(0)
            }
            val output = ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { data ->
                    data.writeInt(MAGIC)
                    data.writeInt(VERSION)
                    data.writeInt(PBKDF2_ITERATIONS)
                    data.writeInt(salt.size)
                    data.write(salt)
                    data.writeInt(iv.size)
                    data.write(iv)
                    data.writeInt(ciphertext.size)
                    data.write(ciphertext)
                }
                buffer.toByteArray()
            }
            require(output.size <= MAX_FILE_BYTES)
            path.toAbsolutePath().parent?.let(Files::createDirectories)
            val temp = Files.createTempFile(path.toAbsolutePath().parent, ".glafira-vault-", ".tmp")
            try {
                setOwnerOnly(temp)
                Files.write(temp, output)
                try {
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
                }
                setOwnerOnly(path)
            } finally {
                Files.deleteIfExists(temp)
                output.fill(0)
                ciphertext.fill(0)
                salt.fill(0)
                iv.fill(0)
            }
        } finally {
            // Cover validation, serialization and filesystem failures as well as encryption.
            secret.fill(0)
        }
    }

    /** Imports the one-time owner-delivered JSON bundle copied from the Android approval screen. */
    fun importOwnerBundle(path: Path, bundleJson: String, passphrase: CharArray) {
        val json = DesktopBridgeJson.parseExactObject(
            bundleJson,
            maxBytes = 8 * 1024,
            expectedKeys = setOf(
                "format", "origin", "deviceId", "hmacSecretBase64Url",
                "grantedScopes", "serverCertificateSha256"
            )
        )
        require(DesktopBridgeJson.stringField(json, "format") == "GLAFIRA_BRIDGE_CREDENTIAL_V1")
        val scopesValue = json.get("grantedScopes")
        require(scopesValue is org.json.JSONArray)
        val scopesJson = scopesValue
        require(scopesJson.length() in 1..32)
        val scopes = buildSet {
            for (index in 0 until scopesJson.length()) {
                val scope = scopesJson.get(index)
                require(scope is String)
                require(add(scope))
            }
        }
        import(
            path = path,
            origin = DesktopBridgeJson.stringField(json, "origin"),
            deviceId = DesktopBridgeJson.stringField(json, "deviceId"),
            hmacSecretBase64Url = DesktopBridgeJson.stringField(json, "hmacSecretBase64Url"),
            grantedScopes = scopes,
            serverCertificateSha256 = DesktopBridgeJson.stringField(json, "serverCertificateSha256"),
            passphrase = passphrase
        )
    }

    fun load(path: Path, passphrase: CharArray): DesktopBridgeVaultEntry {
        require(passphrase.size >= 12)
        val fileSize = Files.size(path)
        require(fileSize in 1..MAX_FILE_BYTES) { "Credential vault file size is invalid." }
        val bytes = Files.readAllBytes(path)
        require(bytes.size.toLong() == fileSize && bytes.size in 1..MAX_FILE_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC && input.readInt() == VERSION) { "Unsupported credential vault." }
        val iterations = input.readInt()
        require(iterations == PBKDF2_ITERATIONS) { "Vault KDF parameters are unsupported." }
        val saltSize = input.readInt()
        require(saltSize == SALT_BYTES)
        val salt = ByteArray(saltSize).also(input::readFully)
        val ivSize = input.readInt()
        require(ivSize == IV_BYTES)
        val iv = ByteArray(ivSize).also(input::readFully)
        val cipherSize = input.readInt()
        require(cipherSize in 16..MAX_FILE_BYTES && cipherSize == input.available())
        val ciphertext = ByteArray(cipherSize).also(input::readFully)
        val key = deriveKey(passphrase, salt)
        val plaintext = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                updateAAD(aad())
                doFinal(ciphertext)
            }
        } catch (_: AEADBadTagException) {
            throw SecurityException("Vault passphrase is incorrect or the file was modified.")
        } finally {
            key.encoded?.fill(0)
            salt.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
            bytes.fill(0)
        }
        return try {
            deserialize(plaintext).also(::validate)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun validate(entry: DesktopBridgeVaultEntry) {
        require(entry.origin.length <= 512)
        val uri = java.net.URI(entry.origin)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        require(uri.port == -1 || uri.port in 1..65_535)
        require(entry.deviceId.length in 8..128 && entry.deviceId.all { it.isLetterOrDigit() || it in "-_." })
        require(entry.hmacSecret.size == 32)
        require(entry.grantedScopes.isNotEmpty() && entry.grantedScopes.size <= 32)
        require(entry.grantedScopes.all(DesktopBridgeScopePolicy::isValidScope))
        require(entry.serverCertificateSha256.matches(Regex("[0-9a-f]{64}")))
    }

    private fun serialize(entry: DesktopBridgeVaultEntry): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { data ->
                data.writeUTF(entry.origin)
                data.writeUTF(entry.deviceId)
                data.writeInt(entry.hmacSecret.size)
                data.write(entry.hmacSecret)
                data.writeInt(entry.grantedScopes.size)
                entry.grantedScopes.sorted().forEach(data::writeUTF)
                data.writeUTF(entry.serverCertificateSha256)
            }
            buffer.toByteArray()
        }

    private fun deserialize(bytes: ByteArray): DesktopBridgeVaultEntry {
        val data = DataInputStream(ByteArrayInputStream(bytes))
        val origin = data.readUTF()
        val deviceId = data.readUTF()
        val secretSize = data.readInt()
        require(secretSize == 32)
        val secret = ByteArray(secretSize).also(data::readFully)
        val count = data.readInt()
        require(count in 1..32)
        val scopes = buildSet { repeat(count) { require(add(data.readUTF())) } }
        val pin = data.readUTF()
        require(data.available() == 0)
        return DesktopBridgeVaultEntry(origin, deviceId, secret, scopes, pin)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return try {
                SecretKeySpec(encoded, "AES")
            } finally {
                encoded.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    private fun aad() = "GLAFIRA-DESKTOP-CREDENTIAL-VAULT-V1".toByteArray(Charsets.UTF_8)

    private fun setOwnerOnly(path: Path) {
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX fallback (for example Windows). Fail closed if ACL-style
            // owner-only permissions cannot be applied.
            val file = path.toFile()
            check(file.setReadable(false, false) && file.setWritable(false, false) &&
                file.setReadable(true, true) && file.setWritable(true, true)) {
                "Unable to restrict Desktop credential vault permissions."
            }
        }
    }
}
