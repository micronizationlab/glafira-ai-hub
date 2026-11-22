package com.micronizationlab.glafira

import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale

enum class SentenceAttributionStatus { PENDING, VALIDATED, ABSENT, REJECTED }

data class SentenceCitationAttribution(
    val sentenceOrdinal: Int,
    val statementDigest: String,
    val sourceAliases: List<String>
) {
    init {
        require(sentenceOrdinal > 0)
        require(statementDigest.matches(Regex("[0-9a-f]{64}")))
        require(sourceAliases.isNotEmpty())
        require(sourceAliases == sourceAliases.distinct().sortedWith(SOURCE_ALIAS_COMPARATOR))
        require(sourceAliases.all { it.matches(SOURCE_ALIAS_PATTERN) })
    }
}

data class SentenceAttributionParseResult(
    val status: SentenceAttributionStatus,
    val attributions: List<SentenceCitationAttribution>
) {
    init {
        require(status != SentenceAttributionStatus.PENDING)
        require((status == SentenceAttributionStatus.VALIDATED) == attributions.isNotEmpty())
    }
}

/**
 * Parses only explicit provider markers. It never infers a source from semantic similarity.
 * A source-shaped marker outside the owner-approved alias set rejects all sentence attribution.
 */
object GlafiraSentenceAttribution {
    fun instruction(documentCount: Int, chatCount: Int): String {
        require(documentCount >= 0 && chatCount >= 0 && documentCount + chatCount > 0)
        val aliases = buildList {
            repeat(documentCount) { add("D${it + 1}") }
            repeat(chatCount) { add("C${it + 1}") }
        }
        return buildString {
            append("Для каждого фактического предложения, которое опирается на контекст проекта, ")
            append("добавь перед конечным знаком препинания точные маркеры источников, например [D1] или [C1][D2]. ")
            append("Разрешены только маркеры: ")
            append(aliases.joinToString(", "))
            append(". Не добавляй маркер к собственному выводу или предположению: такое предложение будет считаться выводом модели.")
        }
    }

    fun parse(answer: String, documentCount: Int, chatCount: Int): SentenceAttributionParseResult {
        require(documentCount >= 0 && chatCount >= 0)
        val allowed = buildSet {
            repeat(documentCount) { add("D${it + 1}") }
            repeat(chatCount) { add("C${it + 1}") }
        }
        val answerAliases = SOURCE_MARKER.findAll(answer).map { it.groupValues[1] }.toList()
        if (answerAliases.any { it !in allowed }) {
            return SentenceAttributionParseResult(SentenceAttributionStatus.REJECTED, emptyList())
        }

        var ordinal = 0
        val attributions = mutableListOf<SentenceCitationAttribution>()
        answer.lineSequence().forEach { line ->
            val iterator = BreakIterator.getSentenceInstance(Locale("ru"))
            iterator.setText(line)
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                val sentence = line.substring(start, end).trim()
                if (sentence.isNotBlank()) {
                    ordinal += 1
                    val aliases = SOURCE_MARKER.findAll(sentence).map { it.groupValues[1] }
                        .distinct().sortedWith(SOURCE_ALIAS_COMPARATOR).toList()
                    if (aliases.isNotEmpty()) {
                        val normalized = SOURCE_MARKER.replace(sentence, "")
                            .replace(Regex("\\s+"), " ").trim()
                        if (normalized.isNotBlank()) {
                            attributions += SentenceCitationAttribution(
                                sentenceOrdinal = ordinal,
                                statementDigest = sha256(normalized),
                                sourceAliases = aliases
                            )
                        }
                    }
                }
                start = end
                end = iterator.next()
            }
        }
        return if (attributions.isEmpty()) {
            SentenceAttributionParseResult(SentenceAttributionStatus.ABSENT, emptyList())
        } else {
            SentenceAttributionParseResult(SentenceAttributionStatus.VALIDATED, attributions)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal val SOURCE_ALIAS_PATTERN = Regex("[DC][1-9][0-9]*")
internal val SOURCE_MARKER = Regex("\\[([DC][1-9][0-9]*)]")
internal val SOURCE_ALIAS_COMPARATOR = compareBy<String>({ it.first() }, { it.drop(1).toInt() })
