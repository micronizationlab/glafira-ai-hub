package com.micronizationlab.glafira

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.text.DecimalFormat
import java.util.UUID

object IncomingFileHelper {
    fun importSharedFile(context: Context, intent: Intent): Msg? {
        if (intent.action != Intent.ACTION_SEND) return null
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.clipData?.getItemAt(0)?.uri ?: return null
        return try {
            val displayName = queryName(context, uri).ifBlank { "received_file" }
            val safeName = displayName.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9А-Яа-я._ -]"), "_").take(120).ifBlank { "received_file" }
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val directory = File(context.filesDir, "chat_attachments").apply { mkdirs() }
            val destination = File(directory, "${UUID.randomUUID()}_$safeName")
            val input = context.contentResolver.openInputStream(uri) ?: return null
            try {
                input.use { source ->
                    destination.outputStream().use { output -> source.copyTo(output) }
                }
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
            Msg(
                text = "Получен файл",
                isUser = true,
                time = "",
                attachmentPath = destination.absolutePath,
                attachmentName = displayName,
                attachmentMime = mime,
                attachmentSize = destination.length()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun queryName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        } catch (_: Exception) { "" }
    }

    fun openFile(context: Context, path: String, mime: String) {
        try {
            val file = File(path)
            if (!file.exists()) return
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(view, "Открыть файл"))
        } catch (_: Exception) {
            ToastHelper.short(context, "Не удалось открыть файл")
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${DecimalFormat("0.#").format(kb)} КБ"
        return "${DecimalFormat("0.#").format(kb / 1024)} МБ"
    }
}
