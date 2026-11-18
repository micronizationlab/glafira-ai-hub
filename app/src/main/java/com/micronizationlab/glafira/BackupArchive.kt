package com.micronizationlab.glafira

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BackupVerification(
    val createdAt: String,
    val fileCount: Int,
    val totalBytes: Long
)

object BackupArchive {
    private val MAGIC = byteArrayOf(71, 76, 65, 70, 73, 82, 65, 66) // GLAFIRAB
    private const val FORMAT_VERSION = 1
    private const val KDF_ITERATIONS = 310_000
    private const val MAX_KDF_ITERATIONS = 1_000_000
    private const val MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L
    private const val MAX_FILES = 20_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun create(context: Context, passphrase: CharArray): File {
        require(passphrase.size >= 12) { "Пароль резервной копии должен содержать не менее 12 символов." }
        val sourceFiles = collectFiles(context.filesDir)
        val totalBytes = sourceFiles.sumOf { it.length() }
        require(totalBytes <= MAX_ARCHIVE_BYTES) { "Размер данных превышает допустимый размер резервной копии." }

        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val key = deriveKey(passphrase, salt, KDF_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)))
        }
        val iv = cipher.iv
        val output = File(context.cacheDir, "glafira-backup-${System.currentTimeMillis()}.glb")

        val manifest = JSONObject().apply {
            put("format", "GLAFIRA_BACKUP")
            put("version", FORMAT_VERSION)
            put("createdAt", java.time.Instant.now().toString())
            put("appId", context.packageName)
            put("fileCount", sourceFiles.size)
            put("totalBytes", totalBytes)
            put("excluded", JSONArray().put("API keys").put("OAuth tokens").put("password vault secrets").put("Android Keystore keys"))
            put("files", JSONArray().apply {
                sourceFiles.forEach { file ->
                    put(JSONObject().apply {
                        put("path", file.relativeTo(context.filesDir).invariantSeparatorsPath)
                        put("size", file.length())
                        put("sha256", sha256(file))
                    })
                }
            })
        }

        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(output))).use { header ->
                header.write(MAGIC)
                header.writeInt(FORMAT_VERSION)
                header.writeInt(KDF_ITERATIONS)
                header.writeInt(salt.size)
                header.write(salt)
                header.writeInt(iv.size)
                header.write(iv)
                val encrypted = CipherOutputStream(header, cipher)
                ZipOutputStream(BufferedOutputStream(encrypted)).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    sourceFiles.forEach { file ->
                        val relative = file.relativeTo(context.filesDir).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry("data/$relative"))
                        FileInputStream(file).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            verify(output, passphrase)
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    fun verify(file: File, passphrase: CharArray): BackupVerification {
        val extracted = decryptToStaging(file, passphrase)
        try {
            return extracted.verification
        } finally {
            extracted.directory.deleteRecursively()
        }
    }

    fun restore(context: Context, file: File, passphrase: CharArray): BackupVerification {
        val extracted = decryptToStaging(file, passphrase)
        try {
            val targetRoot = context.filesDir.canonicalFile
            extracted.files.forEach { staged ->
                val target = File(targetRoot, staged.relativePath).canonicalFile
                require(target.path.startsWith(targetRoot.path + File.separator)) { "Недопустимый путь в архиве." }
                target.parentFile?.mkdirs()
                val replacement = File(target.parentFile, target.name + ".restore-tmp")
                staged.file.copyTo(replacement, overwrite = true)
                if (target.exists() && !target.delete()) {
                    replacement.delete()
                    throw IllegalStateException("Не удалось заменить файл ${staged.relativePath}")
                }
                if (!replacement.renameTo(target)) {
                    replacement.delete()
                    throw IllegalStateException("Не удалось восстановить файл ${staged.relativePath}")
                }
            }
            return extracted.verification
        } finally {
            extracted.directory.deleteRecursively()
        }
    }

    private data class StagedFile(val relativePath: String, val file: File)
    private data class StagedArchive(
        val directory: File,
        val files: List<StagedFile>,
        val verification: BackupVerification
    )

    private fun decryptToStaging(file: File, passphrase: CharArray): StagedArchive {
        require(file.exists() && file.length() > 0L) { "Файл резервной копии пуст или отсутствует." }
        require(file.length() <= MAX_ARCHIVE_BYTES) { "Файл резервной копии слишком большой." }
        val staging = File(file.parentFile, "glafira-restore-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "Это не архив GLAFIRA." }
                val version = input.readInt()
                require(version == FORMAT_VERSION) { "Версия архива не поддерживается: $version" }
                val iterations = input.readInt()
                require(iterations in 100_000..MAX_KDF_ITERATIONS) { "Некорректные параметры шифрования." }
                val saltLength = input.readInt()
                require(saltLength == SALT_BYTES) { "Некорректная соль архива." }
                val salt = ByteArray(saltLength)
                input.readFully(salt)
                val ivLength = input.readInt()
                require(ivLength == IV_BYTES) { "Некорректный IV архива." }
                val iv = ByteArray(ivLength)
                input.readFully(iv)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
                }
                val stagedFiles = mutableListOf<StagedFile>()
                var manifest: JSONObject? = null
                var totalBytes = 0L
                var entries = 0
                ZipInputStream(BufferedInputStream(CipherInputStream(input, cipher))).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        require(!entry.isDirectory) { "Каталоги как отдельные записи не поддерживаются." }
                        entries++
                        require(entries <= MAX_FILES + 1) { "В архиве слишком много файлов." }
                        val name = entry.name
                        require(!name.startsWith("/") && !name.contains("\\") &&
                            name.split("/").none { it == ".." || it.isBlank() }) { "Обнаружен небезопасный путь в архиве." }
                        if (name == "manifest.json") {
                            require(manifest == null) { "Повторный manifest в архиве." }
                            val bytes = zip.readBytesLimited(2L * 1024L * 1024L)
                            manifest = JSONObject(String(bytes, Charsets.UTF_8))
                        } else {
                            require(name.startsWith("data/")) { "Неизвестная запись в архиве." }
                            val relative = name.removePrefix("data/")
                            val destination = File(staging, relative).canonicalFile
                            require(destination.path.startsWith(staging.canonicalPath + File.separator)) { "Недопустимый путь в архиве." }
                            destination.parentFile?.mkdirs()
                            var copied = 0L
                            FileOutputStream(destination).use { out ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    copied += count
                                    totalBytes += count
                                    require(totalBytes <= MAX_ARCHIVE_BYTES) { "Распакованный архив превышает допустимый размер." }
                                    out.write(buffer, 0, count)
                                }
                            }
                            stagedFiles.add(StagedFile(relative, destination))
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }

                val parsed = requireNotNull(manifest) { "В архиве отсутствует manifest." }
                require(parsed.optString("format") == "GLAFIRA_BACKUP") { "Неверный формат manifest." }
                require(parsed.optInt("version") == FORMAT_VERSION) { "Версия manifest не поддерживается." }
                val expected = parsed.getJSONArray("files")
                require(expected.length() == stagedFiles.size) { "Количество файлов не совпадает с manifest." }
                val stagedByPath = stagedFiles.associateBy { it.relativePath }
                for (index in 0 until expected.length()) {
                    val item = expected.getJSONObject(index)
                    val relative = item.getString("path")
                    val staged = stagedByPath[relative] ?: error("В архиве отсутствует файл $relative")
                    require(staged.file.length() == item.getLong("size")) { "Размер файла не совпадает: $relative" }
                    require(sha256(staged.file) == item.getString("sha256")) { "Контрольная сумма не совпадает: $relative" }
                }
                return StagedArchive(
                    staging,
                    stagedFiles,
                    BackupVerification(
                        parsed.optString("createdAt"),
                        stagedFiles.size,
                        totalBytes
                    )
                )
            }
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun collectFiles(root: File): List<File> {
        val result = mutableListOf<File>()
        root.walkTopDown().forEach { file ->
            if (file.isFile && !file.name.startsWith("glafira-backup-") &&
                !file.name.endsWith(".restore-tmp") && !file.name.endsWith(".tmp")) {
                result.add(file)
            }
        }
        require(result.size <= MAX_FILES) { "Слишком много файлов для резервной копии." }
        return result.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun InputStream.readBytesLimited(limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Manifest слишком большой." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
