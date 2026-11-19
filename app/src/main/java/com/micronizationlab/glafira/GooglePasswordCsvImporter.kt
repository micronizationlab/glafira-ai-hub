package com.micronizationlab.glafira

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

/**
 * Imports Google Password Manager CSV entirely in memory.
 * Raw CSV bytes are never written to app storage or logs.
 */
object GooglePasswordCsvImporter {
    private const val MAX_BYTES = 10 * 1024 * 1024
    private const val MAX_ROWS = 10_000
    private const val MAX_COLUMNS = 64
    private const val MAX_FIELD_CHARS = 1_048_576

    data class Result(
        val entries: List<VaultEntry>,
        val skippedRows: Int
    )

    fun parse(input: InputStream, now: Long = System.currentTimeMillis()): Result {
        val bytes = readBounded(input)
        try {
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
            require('\u0000' !in text) { "CSV содержит недопустимый нулевой символ." }

            val rows = parseCsv(text)
            require(rows.isNotEmpty()) { "CSV-файл пуст." }

            val normalizedHeaders = rows.first().map { it.trim().removePrefix("\uFEFF").lowercase(Locale.ROOT) }
            require(normalizedHeaders.none { it.isBlank() }) { "CSV содержит пустое имя столбца." }
            require(normalizedHeaders.distinct().size == normalizedHeaders.size) {
                "CSV содержит повторяющиеся имена столбцов."
            }
            val headers = normalizedHeaders.withIndex().associate { it.value to it.index }
            val nameIndex = headers["name"] ?: error("В CSV не найден столбец name.")
            val urlIndex = headers["url"] ?: error("В CSV не найден столбец url.")
            val usernameIndex = headers["username"] ?: error("В CSV не найден столбец username.")
            val passwordIndex = headers["password"] ?: error("В CSV не найден столбец password.")
            val noteIndex = headers["note"]

            require(rows.size - 1 <= MAX_ROWS) { "В CSV больше 10 000 записей. Разделите файл на части." }

            var skipped = 0
            val entries = rows.drop(1).mapNotNull { row ->
                require(row.size <= normalizedHeaders.size) {
                    "В одной из записей больше столбцов, чем в заголовке."
                }
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

    private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = WipingByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val single = input.read()
                    if (single < 0) break
                    total++
                    require(total <= MAX_BYTES) { "CSV-файл больше 10 МБ. Разделите его на части." }
                    output.write(single)
                } else {
                    total += count
                    require(total <= MAX_BYTES) { "CSV-файл больше 10 МБ. Разделите его на части." }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
            output.wipe()
        }
    }

    private fun parseCsv(source: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var afterQuote = false
        var index = 0

        fun append(char: Char) {
            require(field.length < MAX_FIELD_CHARS) { "В CSV найдено слишком длинное поле." }
            field.append(char)
        }

        fun finishField() {
            row.add(field.toString())
            field.setLength(0)
            require(row.size <= MAX_COLUMNS) { "В CSV слишком много столбцов." }
        }

        fun finishRow() {
            finishField()
            require(rows.size < MAX_ROWS + 1) {
                "В CSV больше 10 000 записей. Разделите файл на части."
            }
            // Preserve empty CSV records so the importer can report them as skipped.
            rows.add(row)
            row = mutableListOf()
        }

        while (index < source.length) {
            val char = source[index]
            when {
                quoted && char == '"' && index + 1 < source.length && source[index + 1] == '"' -> {
                    append('"')
                    index++
                }
                quoted && char == '"' -> {
                    quoted = false
                    afterQuote = true
                }
                afterQuote && char == ',' -> {
                    finishField()
                    afterQuote = false
                }
                afterQuote && (char == '\n' || char == '\r') -> {
                    finishRow()
                    afterQuote = false
                    if (char == '\r' && index + 1 < source.length && source[index + 1] == '\n') index++
                }
                afterQuote -> error("После закрывающей кавычки обнаружены лишние символы.")
                char == '"' && !quoted && field.isEmpty() -> quoted = true
                char == '"' && !quoted -> error("Кавычка встретилась внутри незаключённого в кавычки поля.")
                char == ',' && !quoted -> finishField()
                (char == '\n' || char == '\r') && !quoted -> {
                    finishRow()
                    if (char == '\r' && index + 1 < source.length && source[index + 1] == '\n') index++
                }
                else -> append(char)
            }
            index++
        }
        require(!quoted) { "CSV содержит незакрытую кавычку." }
        if (afterQuote || field.isNotEmpty() || row.isNotEmpty()) finishRow()
        return rows
    }
}
