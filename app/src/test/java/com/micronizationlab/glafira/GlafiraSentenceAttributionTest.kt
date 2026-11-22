package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraSentenceAttributionTest {
    @Test fun exactDocumentAndChatAliasesBindOnlyMarkedSentences() {
        val result = GlafiraSentenceAttribution.parse(
            "Срок поставки — 18 декабря [D1]. Это может снизить риск. Юрист подтвердил ограничение [C1][D2].",
            documentCount = 2,
            chatCount = 1
        )

        assertEquals(SentenceAttributionStatus.VALIDATED, result.status)
        assertEquals(listOf(1, 3), result.attributions.map { it.sentenceOrdinal })
        assertEquals(listOf("D1"), result.attributions[0].sourceAliases)
        assertEquals(listOf("C1", "D2"), result.attributions[1].sourceAliases)
        assertTrue(result.attributions.all { it.statementDigest.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test fun unknownAliasRejectsAllAttributionInsteadOfPartiallyTrustingAnswer() {
        val result = GlafiraSentenceAttribution.parse(
            "Подтверждённый факт [D1]. Выдуманная ссылка [D9].",
            documentCount = 1,
            chatCount = 0
        )

        assertEquals(SentenceAttributionStatus.REJECTED, result.status)
        assertTrue(result.attributions.isEmpty())
    }

    @Test fun answerWithoutMarkersIsExplicitlyAbsent() {
        val result = GlafiraSentenceAttribution.parse(
            "Это самостоятельный вывод модели.", documentCount = 1, chatCount = 1
        )

        assertEquals(SentenceAttributionStatus.ABSENT, result.status)
        assertTrue(result.attributions.isEmpty())
    }
}
