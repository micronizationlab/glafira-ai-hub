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
                BackupArchive.create(applicationContext, recoveryPassphrase).also { created ->
                    created.copyTo(archive, overwrite = true)
                    created.delete()
                }
                BackupArchive.verify(archive, recoveryPassphrase)

                val uploadName = "GLAFIRA-upload-${UUID.randomUUID()}.glb"
                temporaryRemote = folder.createFile("application/octet-stream", uploadName)
                    ?: error("Облачный сервис не разрешил создать временный файл.")
                applicationContext.contentResolver.openOutputStream(temporaryRemote.uri, "wt")?.use { output ->
                    archive.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Не удалось загрузить архив в облако.")

                copyRemoteToLocal(applicationContext, temporaryRemote, verifyCopy)
                val verified = BackupArchive.verify(verifyCopy, recoveryPassphrase)

                val latest = folder.findFile(LATEST_BACKUP)
                val weekKey = SimpleDateFormat("YYYY-'W'ww", Locale.US).format(Date())
                val weeklyName = "GLAFIRA-weekly-$weekKey.glb"
                if (latest != null && folder.findFile(weeklyName) == null) {
                    val weekly = folder.createFile("application/octet-stream", weeklyName)
                        ?: error("Не удалось создать контрольную недельную копию.")
                    try {
                        copyRemoteToRemote(applicationContext, latest, weekly)
                        copyRemoteToLocal(applicationContext, weekly, verifyCopy)
                        BackupArchive.verify(verifyCopy, recoveryPassphrase)
                    } catch (error: Exception) {
                        weekly.delete()
                        throw error
                    }
                }

                val previous = latest?.let {
                    val previousName = "GLAFIRA-previous-${UUID.randomUUID()}.glb"
                    if (!it.renameTo(previousName)) {
                        error("Облачное хранилище не поддерживает безопасную замену файла. Старая копия сохранена.")
                    }
                    folder.findFile(previousName)
                        ?: error("Не удалось найти предыдущую копию после переименования.")
                }

                if (!temporaryRemote.renameTo(LATEST_BACKUP)) {
                    previous?.renameTo(LATEST_BACKUP)
                    error("Не удалось опубликовать новую проверенную копию. Предыдущая копия сохранена.")
                }
                temporaryRemote = null
                previous?.delete()

                folder.listFiles()
                    .filter { it.name?.startsWith("GLAFIRA-weekly-") == true && it.name != weeklyName }
                    .forEach { it.delete() }
                folder.listFiles()
                    .filter { it.name?.startsWith("GLAFIRA-upload-") == true }
                    .forEach { it.delete() }

                prefs.edit()
                    .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                    .putLong(KEY_LAST_SIZE, archive.length())
                    .putString(KEY_LAST_STATUS, "Резервная копия загружена и проверена")
                    .remove(KEY_LAST_ERROR)
                    .apply()
                Result.success()
            } finally {
                recoveryPassphrase.fill('\u0000')
            }
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
        return Result.retry()
    }

    private fun copyRemoteToLocal(context: Context, source: DocumentFile, target: File) {
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Не удалось прочитать файл из облака для проверки.")
    }

    private fun copyRemoteToRemote(context: Context, source: DocumentFile, target: DocumentFile) {
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                input.copyTo(output)
            } ?: error("Не удалось записать контрольную копию.")
        } ?: error("Не удалось прочитать предыдущую копию.")
    }

    companion object {
        const val BACKUP_PREFS = "glafira_backup_prefs"
        const val KEY_TREE_URI = "backup_tree_uri"
        const val KEY_LAST_SUCCESS = "backup_last_success"
        const val KEY_LAST_SIZE = "backup_last_size"
        const val KEY_LAST_STATUS = "backup_last_status"
        const val KEY_LAST_ERROR = "backup_last_error"
        const val BACKUP_PASSPHRASE_KEY = "backup_recovery_passphrase"
        const val LATEST_BACKUP = "GLAFIRA-latest.glb"
    }
}
