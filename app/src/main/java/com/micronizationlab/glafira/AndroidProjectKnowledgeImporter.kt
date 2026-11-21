package com.micronizationlab.glafira

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant

data class KnowledgeImportReceipt(
    val displayName: String,
    val sourceUri: String,
    val result: KnowledgeImportResult
)

/** SAF adapter: explicit owner selection, persistable read permission and bounded reads only. */
class AndroidProjectKnowledgeImporter(
    context: Context,
    private val repository: ProjectKnowledgeRepository,
    private val now: () -> Instant = Instant::now
) {
    private val appContext = context.applicationContext

    fun importDocument(
        uri: Uri,
        projectId: ProjectIdentifier,
        classification: CoreDataClassification,
        actorId: String = OWNER_ACTOR_ID
    ): KnowledgeImportReceipt {
        val resolver = appContext.contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val displayName = queryDisplayName(uri)
        val mediaType = (resolver.getType(uri) ?: mediaTypeFromName(displayName)).lowercase()
        if (mediaType !in SUPPORTED_MEDIA_TYPES) {
            return KnowledgeImportReceipt(
                displayName,
                uri.toString(),
                KnowledgeImportResult.Rejected("KNOWLEDGE_MEDIA_TYPE_NOT_SUPPORTED")
            )
        }
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use(::readBounded)
        }.getOrNull()
        if (bytes == null) {
            return KnowledgeImportReceipt(
                displayName,
                uri.toString(),
                KnowledgeImportResult.Rejected("KNOWLEDGE_SOURCE_UNAVAILABLE")
            )
        }
        if (bytes.size > MAX_IMPORT_BYTES) {
            bytes.fill(0)
            return KnowledgeImportReceipt(
                displayName,
                uri.toString(),
                KnowledgeImportResult.Rejected("KNOWLEDGE_IMPORT_TOO_LARGE")
            )
        }
        if (bytes.isEmpty()) {
            return KnowledgeImportReceipt(
                displayName,
                uri.toString(),
                KnowledgeImportResult.Rejected("KNOWLEDGE_CONTENT_EMPTY")
            )
        }
        val instant = now()
        val source = KnowledgeSourceRef(
            sourceId = KnowledgeSourceIdentifier("doc-" + sha256(uri.toString()).take(24)),
            version = instant.toEpochMilli().coerceAtLeast(1L)
        )
        val result = try {
            repository.importDocument(
                KnowledgeDocumentInput(
                    projectId = projectId,
                    source = source,
                    title = displayName,
                    sourceUri = uri.toString(),
                    mediaType = mediaType,
                    content = bytes,
                    classification = classification,
                    importedAt = instant
                ),
                KnowledgeAccessContext(
                    actorId = actorId,
                    projectId = projectId,
                    maximumClassification = CoreDataClassification.RESTRICTED
                )
            )
        } finally {
            bytes.fill(0)
        }
        return KnowledgeImportReceipt(displayName, uri.toString(), result)
    }

    private fun queryDisplayName(uri: Uri): String {
        val fromProvider = runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() } ?: "Документ проекта"
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (output.size() <= MAX_IMPORT_BYTES) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                val single = input.read()
                if (single < 0) break
                output.write(single)
                continue
            }
            output.write(buffer, 0, read)
        }
        buffer.fill(0)
        return output.toByteArray()
    }

    private fun mediaTypeFromName(name: String): String =
        if (name.endsWith(".md", ignoreCase = true) || name.endsWith(".markdown", ignoreCase = true)) {
            "text/markdown"
        } else {
            "text/plain"
        }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_IMPORT_BYTES = 512 * 1024
        const val OWNER_ACTOR_ID = "local-owner"
        val SUPPORTED_MEDIA_TYPES = setOf("text/plain", "text/markdown")
    }
}

object GlafiraProjectKnowledgeRuntime {
    @Volatile private var repository: ProjectKnowledgeRepository? = null

    fun repository(context: Context): ProjectKnowledgeRepository = repository ?: synchronized(this) {
        repository ?: GlafiraProjectKnowledgeStore(
            directory = File(context.applicationContext.noBackupFilesDir, "project-knowledge"),
            payloadProtector = AndroidKeystoreMemoryPayloadProtector(
                alias = "glafira.core.project-knowledge.v1"
            )
        ).also { repository = it }
    }
}
