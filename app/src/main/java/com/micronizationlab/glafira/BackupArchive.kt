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
            put("excluded", JSONArray().put("API keys").put("OAuth tokens").put("device-bound Android Keystore keys"))
            put("safePreferences", collectSafePreferences(context))
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
            verify(context, output, passphrase)
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    fun verify(context: Context, file: File, passphrase: CharArray): BackupVerification {
        val extracted = decryptToStaging(context, file, passphrase)
        try {
            return extracted.verification
        } finally {
            extracted.directory.deleteRecursively()
        }
    }

    /** Verifies the authenticated archive contains a required relative data path. */
    fun verifyContains(context: Context, file: File, passphrase: CharArray, relativePath: String): BackupVerification {
        require(relativePath.isNotBlank() && !relativePath.startsWith("/") &&
            relativePath.split("/").none { it == ".." || it.isBlank() }) {
            "Недопустимый путь обязательного файла архива."
        }
        val extracted = decryptToStaging(context, file, passphrase)
        try {
            require(extracted.appId == context.packageName) {
                "Резервная копия создана для другого приложения."
            }
            require(extracted.files.any { it.relativePath == relativePath }) {
                "Архив не содержит обязательную базу GLAFIRA Core."
            }
            return extracted.verification
        } finally {
            extracted.directory.deleteRecursively()
        }
    }

    fun restore(context: Context, file: File, passphrase: CharArray): BackupVerification {
        // Keep a verified snapshot of the current state so a partial restore can be rolled back.
        val safetySnapshot = create(context, passphrase)
        val extracted = try {
            decryptToStaging(file, passphrase)
        } catch (error: Exception) {
            safetySnapshot.delete()
            throw error
        }
        var safetySnapshotCanBeDeleted = true
        try {
            require(extracted.appId == context.packageName) {
                "Резервная копия создана для другого приложения."
            }
            applyStaged(context, extracted.files, extracted.safePreferences)
            return extracted.verification
        } catch (restoreError: Exception) {
            safetySnapshotCanBeDeleted = false
            try {
                val rollback = decryptToStaging(context, safetySnapshot, passphrase)
                try {
                    val safePaths = rollback.files.map { it.relativePath }.toSet()
                    extracted.files.map { it.relativePath }.filter { it !in safePaths }.forEach { relative ->
                        File(context.filesDir, relative).delete()
                    }
                    applyStaged(context, rollback.files, rollback.safePreferences)
                    safetySnapshotCanBeDeleted = true
                } finally {
                    rollback.directory.deleteRecursively()
                }
            } catch (rollbackError: Exception) {
                restoreError.addSuppressed(rollbackError)
                restoreError.addSuppressed(
                    IllegalStateException("Safety archive retained for manual recovery: ${safetySnapshot.absolutePath}")
                )
            }
            throw restoreError
        } finally {
            extracted.directory.deleteRecursively()
            if (safetySnapshotCanBeDeleted) safetySnapshot.delete()
        }
    }

    private fun applyStaged(context: Context, files: List<StagedFile>, safePreferences: JSONObject) {
        val targetRoot = context.filesDir.canonicalFile
        val restoredPaths = files.map { it.relativePath }.toSet()

        // Restore an exact snapshot: files created after the selected backup must not survive it.
        files.forEach { staged ->
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

        context.filesDir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".restore-tmp") && !it.name.endsWith(".tmp") }
            .forEach { existing ->
                val canonical = existing.canonicalFile
                require(canonical.path.startsWith(targetRoot.path + File.separator)) {
                    "Обнаружен файл вне каталога данных приложения."
                }
                val relative = canonical.relativeTo(targetRoot).invariantSeparatorsPath
                if (relative !in restoredPaths && !existing.delete()) {
                    throw IllegalStateException("Не удалось удалить файл, отсутствующий в выбранной резервной копии: $relative")
                }
            }

        restoreSafePreferences(context, safePreferences)
    }

    private data class StagedFile(val relativePath: String, val file: File)
    private data class StagedArchive(
        val directory: File,
        val files: List<StagedFile>,
        val verification: BackupVerification,
        val safePreferences: JSONObject,
        val appId: String
    )

    private fun decryptToStaging(context: Context, file: File, passphrase: CharArray): StagedArchive {
        require(file.exists() && file.length() > 0L) { "Файл резервной копии пуст или отсутствует." }
        require(file.length() <= MAX_ARCHIVE_BYTES) { "Файл резервной копии слишком большой." }
        // Decrypted staging data must stay in the app-private cache, never beside a
        // user-selected archive which may live in Downloads or shared storage.
        val staging = File(context.cacheDir, "glafira-restore-${java.util.UUID.randomUUID()}")
        check(staging.mkdir()) { "Не удалось создать защищённый каталог восстановления." }
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
                val archiveAppId = parsed.optString("appId")
                require(archiveAppId.isNotBlank()) { "В manifest отсутствует идентификатор приложения." }
                require(parsed.optInt("fileCount", -1) == stagedFiles.size) {
                    "Количество файлов не совпадает с заголовком manifest."
                }
                require(parsed.optLong("totalBytes", -1L) == totalBytes) {
                    "Общий размер файлов не совпадает с manifest."
                }
                val expected = parsed.getJSONArray("files")
                require(expected.length() == stagedFiles.size) { "Количество файлов не совпадает с manifest." }
                val stagedByPath = stagedFiles.associateBy { it.relativePath }
                require(stagedByPath.size == stagedFiles.size) {
                    "В архиве обнаружены повторяющиеся пути файлов."
                }
                val manifestPaths = mutableSetOf<String>()
                for (index in 0 until expected.length()) {
                    val item = expected.getJSONObject(index)
                    val relative = item.getString("path")
                    require(manifestPaths.add(relative)) { "В manifest обнаружен повторяющийся путь." }
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
                    ),
                    parsed.optJSONObject("safePreferences") ?: JSONObject(),
                    archiveAppId
                )
            }
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun collectSafePreferences(context: Context): JSONObject {
        val allowed = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE).all
        return JSONObject().apply {
            allowed.forEach { (key, value) ->
                val safeKey = key == "glafira_primary_provider" ||
                    key == "peer_consultation_enabled" ||
                    key.startsWith("peer_consultation_enabled_") ||
                    key == "peer_consultation_providers" ||
                    key.startsWith("peer_consultation_providers_")
                if (safeKey && (value is String || value is Boolean || value is Int || value is Long || value is Float)) {
                    put(key, value)
                }
            }
        }
    }

    private fun restoreSafePreferences(context: Context, values: JSONObject) {
        val preferences = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
        val editor = preferences.edit()
        preferences.all.keys.filter {
            it == "glafira_primary_provider" || it == "peer_consultation_enabled" ||
                it.startsWith("peer_consultation_enabled_") ||
                it == "peer_consultation_providers" || it.startsWith("peer_consultation_providers_")
        }.forEach { editor.remove(it) }
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val safeKey = key == "glafira_primary_provider" ||
                key == "peer_consultation_enabled" ||
                key.startsWith("peer_consultation_enabled_")
            if (!safeKey) continue
            val value = values.opt(key)
            when {
                key == "glafira_primary_provider" && value is String -> editor.putString(key, value)
                value is Boolean -> editor.putBoolean(key, value)
            }
        }
        check(editor.commit()) { "Не удалось восстановить настройки приложения." }
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
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return try {
                SecretKeySpec(bytes, "AES")
            } finally {
                bytes.fill(0)
            }
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
