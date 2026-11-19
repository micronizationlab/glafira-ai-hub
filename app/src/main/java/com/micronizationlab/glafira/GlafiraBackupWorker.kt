package com.micronizationlab.glafira

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GlafiraBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
        val folderValue = prefs.getString(KEY_TREE_URI, null)
            ?: return@withContext fail(prefs, "Не выбрана папка облачной резервной копии.")
        val passphrase = EncryptedApiKeyStore.load(applicationContext, BACKUP_PASSPHRASE_KEY)
        if (passphrase.length < 12) return@withContext fail(prefs, "Не задан пароль резервной копии.")

        val folderUri = Uri.parse(folderValue)
        val folder = DocumentFile.fromTreeUri(applicationContext, folderUri)
            ?: return@withContext fail(prefs, "Облачная папка недоступна. Переподключите хранилище.")

        val archive = File(applicationContext.cacheDir, "glafira-cloud-${UUID.randomUUID()}.glb")
        val verifyCopy = File(applicationContext.cacheDir, "glafira-cloud-verify-${UUID.randomUUID()}.glb")
        var temporaryRemote: DocumentFile? = null
        try {
            val recoveryPassphrase = passphrase.toCharArray()
            try {
                val coordinator = GlafiraCoreBackupCoordinator(
                    applicationContext, GlafiraCoreRuntimeOwner.get(applicationContext).taskStore
                )
                coordinator.create(recoveryPassphrase).also { created ->
                    created.copyTo(archive, overwrite = true)
                    created.delete()
                }
                coordinator.verify(archive, recoveryPassphrase)

                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val uploadName = "GLAFIRA-current-$stamp-${UUID.randomUUID()}.glb"
                val uploadedRemote = folder.createFile("application/octet-stream", uploadName)
                    ?: error("Облачный сервис не разрешил создать временный файл.")
                temporaryRemote = uploadedRemote
                applicationContext.contentResolver.openOutputStream(uploadedRemote.uri, "wt")?.use { output ->
                    archive.inputStream().use { input -> BackupArchive.copyBounded(input, output) }
                } ?: error("Не удалось загрузить архив в облако.")

                copyRemoteToLocal(applicationContext, uploadedRemote, verifyCopy)
                coordinator.verify(verifyCopy, recoveryPassphrase)

                val staleCopies = folder.listFiles()
                    .filter { it.name?.startsWith("GLAFIRA-current-") == true && it.uri != uploadedRemote.uri }
                val undeletedCopies = staleCopies.filterNot { it.delete() }
                temporaryRemote = null
                
                prefs.edit()
                    .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                    .putLong(KEY_LAST_SIZE, archive.length())
                    .putString(
                        KEY_LAST_STATUS,
                        if (undeletedCopies.isEmpty()) "Резервная копия загружена и проверена"
                        else "Новая копия проверена, но старые файлы не удалось удалить"
                    )
                    .also { editor ->
                        if (undeletedCopies.isEmpty()) editor.remove(KEY_LAST_ERROR)
                        else editor.putString(KEY_LAST_ERROR, "Облачный провайдер не удалил ${undeletedCopies.size} старых копий. Проверь папку вручную.")
                    }
                    .apply()
                Result.success()
            } finally {
                recoveryPassphrase.fill('\u0000')
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            fail(prefs, error.message ?: "Неизвестная ошибка резервного копирования.")
        } finally {
            archive.delete()
            verifyCopy.delete()
            temporaryRemote?.delete()
        }
    }

    private fun fail(
        prefs: android.content.SharedPreferences,
        message: String
    ): Result {
        prefs.edit()
            .putString(KEY_LAST_STATUS, "Ошибка резервного копирования")
            .putString(KEY_LAST_ERROR, message)
            .apply()
        return if (runAttemptCount < 4) Result.retry() else Result.failure()
    }

    private fun copyRemoteToLocal(context: Context, source: DocumentFile, target: File) {
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            target.outputStream().use { output -> BackupArchive.copyBounded(input, output) }
        } ?: error("Не удалось прочитать файл из облака для проверки.")
    }


    companion object {
        const val BACKUP_PREFS = "glafira_backup_prefs"
        const val KEY_TREE_URI = "backup_tree_uri"
        const val KEY_LAST_SUCCESS = "backup_last_success"
        const val KEY_LAST_SIZE = "backup_last_size"
        const val KEY_LAST_STATUS = "backup_last_status"
        const val KEY_LAST_ERROR = "backup_last_error"
        const val BACKUP_PASSPHRASE_KEY = "backup_recovery_passphrase"
    }
}
