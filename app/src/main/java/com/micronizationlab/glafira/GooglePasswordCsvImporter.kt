package com.micronizationlab.glafira

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Imports Google Password Manager CSV entirely in memory.
 * Raw CSV bytes are never written to app storage or logs.
 */
object GooglePasswordCsvImporter {
    private const val MAX_BYTES = 10 * 1024 * 1024
    private const val MAX_ROWS = 10_000

    data class Result(
        val entries: List<VaultEntry>,
        val skippedRows: Int
    )

    fun parse(input: InputStream, now: Long = System.currentTimeMillis()): Result {
        val bytes = readBounded(input)
        try {
            val text = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
            val rows = parseCsv(text)
            require(rows.isNotEmpty()) { "CSV-файл пуст." }

            val headers = rows.first().mapIndexed { index, name ->
                name.trim().removePrefix("\uFEFF").lowercase() to index
            }.toMap()
            val nameIndex = headers["name"] ?: error("В CSV не найден столбец name.")
            val urlIndex = headers["url"] ?: error("В CSV не найден столбец url.")
            val usernameIndex = headers["username"] ?: error("В CSV не найден столбец username.")
            val passwordIndex = headers["password"] ?: error("В CSV не найден столбец password.")
            val noteIndex = headers["note"]

            require(rows.size - 1 <= MAX_ROWS) { "В CSV больше $MAX_ROWS записей. Разделите файл на части." }

            var skipped = 0
            val entries = rows.drop(1).mapNotNull { row ->
                val title = row.getOrNull(nameIndex).orEmpty().trim()
                val website = row.getOrNull(urlIndex).orEmpty().trim()
                val username = row.getOrNull(usernameIndex).orEmpty()
                val password = row.getOrNull(passwordIndex).orEmpty()
                val notes = noteIndex?.let { row.getOrNull(it).orEmpty() }.orEmpty()

                if (title.isBlank() && website.isBlank() && username.isBlank() && password.isBlank()) {
                    skipped++
                    null
                } else {
                    require(title.isNotBlank() || website.isNotBlank()) {
                        "Одна из записей не содержит ни названия, ни сайта."
                    }
                    VaultEntry(
                        id = UUID.randomUUID().toString(),
                        title = title.ifBlank { website },
                        username = username,
                        password = password,
                        website = website,
                        notes = notes,
                        updatedAt = now
                    )
                }
            }
            return Result(entries, skipped)
        } finally {
            bytes.fill(0)
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_BYTES) { "CSV-файл больше 10 МБ. Разделите его на части." }
            output.write(buffer, 0, count)
        }
        buffer.fill(0)
        return output.toByteArray()
    }

    private fun parseCsv(source: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        while (index < source.length) {
            val char = source[index]
            when {
                quoted && char == '"' && index + 1 < source.length && source[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' && !quoted && field.isEmpty() -> quoted = true
                char == '"' && quoted -> quoted = false
                char == ',' && !quoted -> {
                    row.add(field.toString())
                    field.setLength(0)
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    row.add(field.toString())
                    field.setLength(0)
                    if (row.isNotEmpty()) rows.add(row)
                    row = mutableListOf()
                    if (char == '\r' && index + 1 < source.length && source[index + 1] == '\n') index++
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "CSV содержит незакрытую кавычку." }
        row.add(field.toString())
        if (row.any { it.isNotEmpty() }) rows.add(row)
        return rows
    }
}
