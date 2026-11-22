package com.micronizationlab.glafira

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AnswerCitationUiSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test fun durableResponseShowsOwnerVisibleExactSourcesWithoutProviderCall() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val operationId = "ui-citation-${UUID.randomUUID()}"
        val historyKey = "default_ГЛАФИРА"
        val historyFile = File(context.filesDir, "chat_$historyKey.json")
        val historyExisted = historyFile.exists() || File(historyFile.path + ".bak").exists()
        val previousHistory = loadHistory(context, historyKey)
        val previousBiometric = isBiometricEnabled(context)
        val previousProviderKey = loadProviderKey(context, ProviderId.GIGACHAT)
        val manifestStore = GlafiraContextCitationManifestRuntime.store(context)
        val manifestAccess = ContextCitationAccess(ProjectIdentifier("default"), "local-owner")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            assertTrue(
                context.getSharedPreferences("glafira_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("biometric_enabled", false).commit()
            )
            assertTrue(saveProviderKey(context, ProviderId.GIGACHAT, "instrumentation-ui-placeholder"))
            saveHistory(context, historyKey, listOf(
                Msg("Покажи источники", true, "08:00", "2026-11-21", operationId),
                Msg("Ответ с проверяемыми источниками", false, "08:01", "2026-11-21", operationId)
            ))
            assertTrue(
                manifestStore.put(
                    ContextCitationManifest(
                        operationId = operationId,
                        projectId = ProjectIdentifier("default"),
                        recipient = "GigaChat",
                        createdAt = Instant.parse("2026-11-21T05:00:00Z"),
                        documents = listOf(DocumentCitationReference(
                            sourceId = "ui-document",
                            version = 7,
                            title = "UI citation document",
                            sourceUri = "content://test/ui-document",
                            classification = CoreDataClassification.CONFIDENTIAL
                        )),
                        chats = listOf(ChatCitationReference(
                            sourceId = "chat:default:Юрист",
                            contentVersion = "b".repeat(64),
                            agentName = "Юрист",
                            classification = CoreDataClassification.RESTRICTED
                        ))
                    ),
                    manifestAccess
                ) == CitationManifestWriteResult.STORED
            )

            scenario = ActivityScenario.launch(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            )
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText("СЕТЬ АГЕНТОВ · 2003").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("ГЛАФИРА").assertExists("Agent list did not open").performClick()
            compose.onNodeWithText("Ответ с проверяемыми источниками")
                .assertExists("Seeded durable response was not rendered")
            compose.onNodeWithContentDescription("Показать источники ответа")
                .assertExists("Citation action is absent from the response")
                .performClick()
            compose.onNodeWithText("Источники ответа").assertExists("Citation dialog did not open")
            compose.onNodeWithText("UI citation document", substring = true)
                .assertExists("Exact document citation is not visible")
            compose.onNodeWithText("chat:default:Юрист", substring = true)
                .assertExists("Exact chat citation is not visible")
            compose.onNodeWithText(operationId, substring = true)
                .assertExists("Operation identity is not visible")
        } finally {
            scenario?.close()
            manifestStore.delete(operationId, manifestAccess)
            if (historyExisted) saveHistory(context, historyKey, previousHistory)
            else deleteHistory(context, historyKey)
            saveProviderKey(context, ProviderId.GIGACHAT, previousProviderKey)
            context.getSharedPreferences("glafira_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("biometric_enabled", previousBiometric).commit()
        }
    }
}
