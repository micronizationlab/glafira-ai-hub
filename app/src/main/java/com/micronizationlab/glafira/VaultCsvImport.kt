package com.micronizationlab.glafira

import android.content.Context
import android.net.Uri

internal data class VaultCsvImportOutcome(
    val entries: List<VaultEntry>,
    val imported: Int,
    val skippedRows: Int,
    val duplicates: Int
)

/** Authenticate the session before reading a potentially sensitive selected document. */
internal fun importVaultCsv(context: Context, session: VaultSession, uri: Uri): VaultCsvImportOutcome {
    val existing = session.loadEntries()
    val parsed = context.contentResolver.openInputStream(uri)?.use { GooglePasswordCsvImporter.parse(it) }
        ?: error("Не удалось прочитать выбранный CSV-файл.")
    val known = existing.map { it.website.trim().lowercase() + "|" + it.username.trim().lowercase() }.toMutableSet()
    val added = parsed.entries.filter { known.add(it.website.trim().lowercase() + "|" + it.username.trim().lowercase()) }
    val updated = existing + added
    session.saveEntries(updated)
    return VaultCsvImportOutcome(updated, added.size, parsed.skippedRows, parsed.entries.size - added.size)
}
