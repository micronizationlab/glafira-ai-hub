package com.micronizationlab.glafira

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlafiraCoreRuntimeEnablementControllerTest {
    private class Harness {
        var restoreInterrupted = false
        var persisted = false
        var scheduleCount = 0
        var cancelCount = 0
        var failPersistence = false
        var failSchedule = false

        val controller = GlafiraCoreRuntimeEnablementController(
            restoreInterrupted = { restoreInterrupted },
            persistEnabled = {
                if (failPersistence) error("injected persistence failure")
                persisted = it
            },
            scheduleImmediate = {
                scheduleCount++
                if (failSchedule) error("injected scheduler failure")
            },
            cancelScheduledWork = { cancelCount++ }
        )
    }

    @Test
    fun enablingRequiresFreshOwnerProofWithoutMutatingRuntime() {
        val harness = Harness()

        assertEquals(
            GlafiraCoreRuntimeEnablementMutation.OWNER_AUTHENTICATION_REQUIRED,
            harness.controller.apply(enabled = true)
        )
        assertFalse(harness.persisted)
        assertEquals(0, harness.scheduleCount)
    }

    @Test
    fun rejectedOwnerProofCannotEnableRuntime() {
        val harness = Harness()

        assertEquals(
            GlafiraCoreRuntimeEnablementMutation.OWNER_AUTHENTICATION_REQUIRED,
            harness.controller.apply(enabled = true, consumeOwnerProof = { false })
        )
        assertFalse(harness.persisted)
        assertEquals(0, harness.scheduleCount)
    }

    @Test
    fun freshOwnerProofEnablesAndSchedulesRuntime() {
        val harness = Harness()
        var proofConsumed = 0

        assertEquals(
            GlafiraCoreRuntimeEnablementMutation.ENABLED,
            harness.controller.apply(enabled = true, consumeOwnerProof = {
                proofConsumed++
                true
            })
        )
        assertEquals(1, proofConsumed)
        assertTrue(harness.persisted)
        assertEquals(1, harness.scheduleCount)
    }

    @Test
    fun interruptedRestoreBlocksEnablementWithoutConsumingProof() {
        val harness = Harness().apply { restoreInterrupted = true }
        var proofConsumed = false

        assertEquals(
            GlafiraCoreRuntimeEnablementMutation.RESTORE_RECOVERY_REQUIRED,
            harness.controller.apply(enabled = true, consumeOwnerProof = {
                proofConsumed = true
                true
            })
        )
        assertFalse(proofConsumed)
        assertFalse(harness.persisted)
        assertEquals(0, harness.scheduleCount)
    }

    @Test
    fun pausingNeverRequiresOwnerProof() {
        val harness = Harness().apply { persisted = true }

        assertEquals(
            GlafiraCoreRuntimeEnablementMutation.PAUSED,
            harness.controller.apply(enabled = false)
        )
        assertFalse(harness.persisted)
        assertEquals(1, harness.cancelCount)
    }

    @Test
    fun schedulerFailureRollsBackDurableEnablement() {
        val harness = Harness().apply { failSchedule = true }

        assertEquals(
            GlafiraCoreRuntimeEnablementMutation.STORAGE_ERROR,
            harness.controller.apply(enabled = true, consumeOwnerProof = { true })
        )
        assertFalse(harness.persisted)
        assertEquals(1, harness.scheduleCount)
        assertEquals(1, harness.cancelCount)
    }

    @Test
    fun persistenceFailureDoesNotScheduleRuntime() {
        val harness = Harness().apply { failPersistence = true }

        assertEquals(
            GlafiraCoreRuntimeEnablementMutation.STORAGE_ERROR,
            harness.controller.apply(enabled = true, consumeOwnerProof = { true })
        )
        assertFalse(harness.persisted)
        assertEquals(0, harness.scheduleCount)
        assertEquals(1, harness.cancelCount)
    }
}
