package com.micronizationlab.glafira

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

data class GlafiraAppUpdate(
    val versionCode: Long,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String
)

object GlafiraUpdater {
    private const val RELEASES_API =
        "https://api.github.com/repos/micronizationlab/glafira-ai-hub/releases/latest"
    private const val APK_PREFIX = "glafira-"
    private const val APK_SUFFIX = ".apk"

    suspend fun checkForUpdate(context: Context): GlafiraAppUpdate? =
        withContext(Dispatchers.IO) {
            val connection = (URL(RELEASES_API).openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "GLAFIRA-AI-HUB-Android")
            }
            try {
                if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
                    throw IllegalStateException("Сервер обновлений ответил: HTTP ${connection.responseCode}")
                }
                val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
                    return@withContext null
                }
                val assets = release.optJSONArray("assets")
                    ?: throw IllegalStateException("В опубликованном релизе нет APK")
                var candidate: GlafiraAppUpdate? = null
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    val name = asset.optString("name")
                    if (!name.startsWith(APK_PREFIX) || !name.endsWith(APK_SUFFIX)) continue
                    val code = name.removePrefix(APK_PREFIX).removeSuffix(APK_SUFFIX).toLongOrNull()
                        ?: continue
                    val digest = asset.optString("digest")
                    val sha256 = digest.removePrefix("sha256:").lowercase(Locale.ROOT)
                    val url = asset.optString("browser_download_url")
                    if (!sha256.matches(Regex("[a-f0-9]{64}")) ||
                        !url.startsWith("https://github.com/")) {
                        throw IllegalStateException("Релиз не содержит корректную SHA-256 контрольную сумму или HTTPS-ссылку")
                    }
                    candidate = GlafiraAppUpdate(
                        versionCode = code,
                        versionName = release.optString("tag_name", "версия $code"),
                        downloadUrl = url,
                        sha256 = sha256
                    )
                    break
                }
                val currentCode = currentVersionCode(context)
                candidate?.takeIf { it.versionCode > currentCode }
            } finally {
                connection.disconnect()
            }
        }

    suspend fun downloadAndPromptInstall(context: Context, update: GlafiraAppUpdate) =
        withContext(Dispatchers.IO) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                withContext(Dispatchers.Main) {
                    val settings = Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(settings)
                }
                throw IllegalStateException("Разреши установку обновлений для GLAFIRA и повтори действие")
            }

            val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
                .setTitle("Обновление GLAFIRA AI HUB")
                .setDescription("Загрузка версии ${update.versionName}")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(
                    context, Environment.DIRECTORY_DOWNLOADS, "glafira-${update.versionCode}.apk"
                )
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = manager.enqueue(request)
            var completedUri: Uri? = null
            repeat(600) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                manager.query(query).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                completedUri = manager.getUriForDownloadedFile(downloadId)
                                return@repeat
                            }
                            DownloadManager.STATUS_FAILED -> {
                                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                manager.remove(downloadId)
                                throw IllegalStateException("Не удалось загрузить обновление (код $reason)")
                            }
                        }
                    }
                }
                if (completedUri == null) delay(1_000)
            }
            val uri = completedUri ?: run {
                manager.remove(downloadId)
                throw IllegalStateException("Истекло время ожидания загрузки обновления")
            }

            val apkFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "glafira-${update.versionCode}.apk"
            )
            val actualHash = withContext(Dispatchers.IO) {
                MessageDigest.getInstance("SHA-256").digest(apkFile.readBytes())
                    .joinToString("") { "%02x".format(it) }
            }
            if (!actualHash.equals(update.sha256, ignoreCase = true)) {
                manager.remove(downloadId)
                apkFile.delete()
                throw SecurityException("Контрольная сумма APK не совпала. Установка отменена")
            }

            verifyPackageAndSigningCertificate(context, apkFile, update.versionCode)
            withContext(Dispatchers.Main) {
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            }
        }

    private fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    private fun verifyPackageAndSigningCertificate(context: Context, apk: File, expectedVersion: Long) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val candidate = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw SecurityException("Файл обновления не является корректным APK")
        if (candidate.packageName != context.packageName) {
            throw SecurityException("APK предназначен для другого приложения")
        }
        val candidateCode = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        if (candidateCode != expectedVersion || candidateCode <= currentVersionCode(context)) {
            throw SecurityException("Версия APK не соответствует опубликованному обновлению")
        }
        val installedSigners = if (Build.VERSION.SDK_INT >= 28) {
            installed.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet()
        } else installed.signatures?.map { it.toByteArray().toList() }?.toSet()
        val candidateSigners = if (Build.VERSION.SDK_INT >= 28) {
            candidate.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet()
        } else candidate.signatures?.map { it.toByteArray().toList() }?.toSet()
        if (installedSigners.isNullOrEmpty() || installedSigners != candidateSigners) {
            throw SecurityException("Подпись обновления не совпадает с установленной версией. Установка заблокирована")
        }
    }
}
