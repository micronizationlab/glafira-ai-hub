package com.micronizationlab.glafira

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HrContactStoreInstrumentedTest {
    private lateinit var context: android.content.Context
    private lateinit var projectId: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        projectId = "hr-test-${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        File(context.noBackupFilesDir, "hr_contacts").listFiles()?.forEach { it.delete() }
    }

    private fun sampleContact(name: String = "Алексей Проверочный"): HrContactCard {
        val now = Instant.parse("2026-11-20T00:00:00Z")
        val source = HrSourceReference(
            sourceType = "owner_input",
            sourceId = projectId,
            recordId = UUID.randomUUID().toString(),
            observedAt = now,
            eventAt = now
        )
        return HrContactCard(
            contactId = UUID.randomUUID().toString(),
            displayName = name,
            organization = "Тестовая организация",
            role = "Партнёр",
            relationship = HrRelationshipType.PARTNER,
            identityStatus = HrIdentityStatus.VERIFIED,
            facts = listOf(
                HrContactFact(
                    category = HrFactCategory.NAME,
                    value = name,
                    confidence = HrFactConfidence.CONFIRMED,
                    sensitivity = HrFactSensitivity.PERSONAL,
                    sources = listOf(source),
                    isDirectlyStated = true
                )
            ),
            interactions = listOf(
                HrInteraction(
                    interactionId = UUID.randomUUID().toString(),
                    occurredAt = now,
                    channel = "owner_input",
                    summary = "Обсудили условия сотрудничества",
                    sources = listOf(source)
                )
            ),
            commitments = listOf(
                HrCommitment(
                    commitmentId = UUID.randomUUID().toString(),
                    description = "Отправить коммерческое предложение",
                    dueAt = now,
                    sources = listOf(source)
                )
            ),
            lastVerifiedAt = now
        )
    }

    @Test
    fun roundTripIsEncryptedAndProjectScoped() {
        val contact = sampleContact()
        HrContactStore.upsert(context, projectId, contact)

        assertEquals(listOf(contact), HrContactStore.list(context, projectId))
        assertTrue(HrContactStore.list(context, "another-project-${UUID.randomUUID()}").isEmpty())

        val directory = File(context.noBackupFilesDir, "hr_contacts")
        val storedFiles = directory.listFiles().orEmpty()
        assertEquals(1, storedFiles.size)
        val ciphertext = storedFiles.single().readText()
        assertFalse(File(context.filesDir, "hr_contacts").exists())
        assertFalse(ciphertext.contains(contact.displayName))
        assertFalse(ciphertext.contains(contact.organization.orEmpty()))
    }

    @Test
    fun deleteRemovesOnlyTheSelectedContact() {
        val first = sampleContact("Первый Контакт")
        val second = sampleContact("Второй Контакт")
        HrContactStore.upsert(context, projectId, first)
        HrContactStore.upsert(context, projectId, second)

        assertTrue(HrContactStore.delete(context, projectId, first.contactId))
        assertEquals(listOf(second), HrContactStore.list(context, projectId))
        assertFalse(HrContactStore.delete(context, projectId, first.contactId))
    }

    @Test
    fun tamperedEnvelopeFailsClosedWithoutReplacingTheFile() {
        HrContactStore.upsert(context, projectId, sampleContact())
        val file = File(context.noBackupFilesDir, "hr_contacts").listFiles().orEmpty().single()
        file.writeText("{tampered")

        val error = runCatching { HrContactStore.list(context, projectId) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("{tampered", file.readText())
    }

    @Test
    fun upsertReplacesExistingContactByStableId() {
        val contact = sampleContact()
        val updated = contact.copy(
            displayName = "Обновлённое имя",
            facts = contact.facts.map { fact ->
                if (fact.category == HrFactCategory.NAME) fact.copy(value = "Обновлённое имя") else fact
            }
        )
        HrContactStore.upsert(context, projectId, contact)
        HrContactStore.upsert(context, projectId, updated)

        assertEquals(listOf(updated), HrContactStore.list(context, projectId))
    }
}
