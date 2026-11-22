package com.micronizationlab.glafira

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AnswerCitationUiSmokeTest {
    @Test fun durableResponseShowsOwnerVisibleExactSourcesWithoutProviderCall() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
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

            forceStopTargetApp(instrumentation)
            scenario = ActivityScenario.launch(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            )
            device.waitForIdle()
            assertNotNull(
                "GLAFIRA home screen did not open",
                device.wait(Until.findObject(By.text("СЕТЬ АГЕНТОВ · 2003")), 30_000)
            )
            assertNotNull(
                "Agent list did not open",
                device.wait(Until.findObject(By.text("ГЛАФИРА")), 20_000)
            )
            device.findObject(By.text("ГЛАФИРА")).click()
            assertNotNull(
                "Seeded durable response was not rendered",
                device.wait(Until.findObject(By.text("Ответ с проверяемыми источниками")), 10_000)
            )
            val citationAction = device.wait(
                Until.findObject(By.desc("Показать источники ответа")),
                5_000
            )
            assertNotNull(
                "Citation action is absent from the response",
                citationAction
            )
            val citationButton = generateSequence(citationAction) { it.parent }
                .firstOrNull { it.isClickable }
            assertNotNull(
                "Citation action is not contained in an accessible clickable control",
                citationButton
            )
            citationButton!!.click()
            device.waitForIdle()

            assertNotNull(
                "Citation dialog did not open",
                device.wait(Until.findObject(By.text("Источники ответа")), 5_000)
            )
            assertNotNull(
                "Exact document citation is not visible",
                device.wait(Until.findObject(By.textContains("UI citation document")), 3_000)
            )
            assertNotNull(
                "Exact chat citation is not visible",
                device.wait(Until.findObject(By.textContains("chat:default:Юрист")), 3_000)
            )
            assertNotNull(
                "Operation identity is not visible",
                device.wait(Until.findObject(By.textContains(operationId)), 3_000)
            )
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
