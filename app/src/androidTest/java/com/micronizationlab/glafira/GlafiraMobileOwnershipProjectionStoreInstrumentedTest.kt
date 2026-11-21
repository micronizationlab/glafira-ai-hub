package com.micronizationlab.glafira

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraMobileOwnershipProjectionStoreInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val projectionFile: File
        get() = File(context.noBackupFilesDir, "mobile-ownership-projection-v1.json")

    @Before
    fun setUp() {
        projectionFile.delete()
        File(projectionFile.path + ".bak").delete()
        File(projectionFile.path + ".new").delete()
    }

    @After
    fun tearDown() = setUp()

    @Test
    fun projectionSurvivesStoreRecreation() {
        val projection = projection()
        AndroidGlafiraMobileOwnershipProjectionStore(context).write(projection)

        assertEquals(
            projection,
            AndroidGlafiraMobileOwnershipProjectionStore(context)
                .read(projection.taskId, projection.projectId)
        )
    }

    @Test
    fun unknownPersistedFieldsFailClosed() {
        projectionFile.writeText(
            """[{"schemaVersion":1,"unexpected":true}]""",
            Charsets.UTF_8
        )

        assertThrows(IllegalArgumentException::class.java) {
            AndroidGlafiraMobileOwnershipProjectionStore(context)
                .read("task-000001", "project-0001")
        }
    }

    private fun projection() = GlafiraMobileOwnershipProjection(
        taskId = "task-000001",
        projectId = "project-0001",
        ownerCoreId = "desktop-core-01",
        ownerGeneration = 8,
        stateVersion = 42,
        payloadSha256 = "a".repeat(64),
        idempotencyKey = "idem-000001",
        effectRisk = "READ_ONLY",
        approvalRef = null,
        lastTransferId = "transfer-0001",
        lastNonce = "nonce-000001",
        updatedAtEpochMs = 1_800_000_000_000
    )
}
