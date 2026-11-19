package com.micronizationlab.glafira

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GlafiraDurableBridgeIngressInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val runtime = GlafiraCoreRuntimeOwner.get(context)
    private val projectId = "ingress-project-${UUID.randomUUID()}"
    private val operationKey = "ingress-operation-${UUID.randomUUID()}"
    private val device = "ingress-device-001"
    private val secret = ByteArray(32) { 7 }
    private val scopes = setOf("project.read", "conversation.read", "conversation.append")

    @Before fun pauseAutonomousWork() {
        // These fault tests exercise admission/storage only, never a LIVE handler.
        runtime.setEnabled(false)
    }

    @After fun cleanUp() {
        runtime.taskStore.writableDatabase.delete("core_tasks", "project_id = ?", arrayOf(projectId))
    }

    private fun request(deviceId: String = device, message: String = "append once") =
        GlafiraBridgeProtocol.sign(GlafiraBridgeRequest(1, "request-${UUID.randomUUID()}",
            operationKey, deviceId, projectId, GlafiraBridgeOperation.CONVERSATION_APPEND,
            System.currentTimeMillis(), System.currentTimeMillis() + 60_000,
            mapOf("conversationId" to "${projectId}_ГЛАФИРА", "messageText" to message)), secret)

    private fun admission(replay: GlafiraBridgeReplayStore) = GlafiraBridgeRequestAdmission(
        object : GlafiraBridgeCredentialProvider {
            override fun load(deviceId: String) = GlafiraBridgeDeviceCredential(deviceId, secret.copyOf(), scopes)
        }, replay)

    private fun noReplay() = object : GlafiraBridgeReplayStore {
        override fun claim(authenticatedDeviceId: String, idempotencyKey: String,
            expiresAtEpochMs: Long, nowEpochMs: Long): BridgeReplayClaim =
            error("Durable submissions must not consume a separate replay claim")
    }

    private fun submit(request: GlafiraBridgeRequest): GlafiraCoreBridgeIngress.Submission =
        GlafiraCoreBridgeIngress(runtime).submit(GlafiraCoreBridgeIngress.VerifiedRequest(
            request.requestId, request.idempotencyKey, request.deviceId, request.projectId,
            GlafiraCoreBridgeIngress.Operation.CONVERSATION_APPEND, request.payload, request.expiresAtEpochMs))

    private fun countTasks(): Int = runtime.taskStore.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM core_tasks WHERE project_id = ? AND idempotency_key = ?",
        arrayOf(projectId, operationKey)).use { it.moveToFirst(); it.getInt(0) }

    @Test fun disconnectAfterAdmissionBeforeEnqueueCanRetryIncludingAnOldOrphanClaim() {
        val original = request()
        GlafiraBridgeReplayGuard(context).use { legacy ->
            assertEquals(BridgeReplayClaim.ACCEPTED, legacy.claim(device, operationKey,
                original.expiresAtEpochMs, System.currentTimeMillis()))
            assertEquals(GlafiraBridgeStatus.ACCEPTED, admission(legacy).admitDurableTask(original).status)
            // Simulate loss of the worker/process before submit: no Core acceptance row exists.
            assertEquals(0, countTasks())
        }
        runtime.taskStore.close()
        val retry = request()
        GlafiraBridgeReplayGuard(context).use { reopened ->
            assertEquals(GlafiraBridgeStatus.ACCEPTED, admission(reopened).admitDurableTask(retry).status)
            assertTrue(submit(retry).inserted)
        }
        assertEquals(1, countTasks())
    }

    @Test fun enqueueFailureDoesNotPoisonTheNextAuthenticatedSubmission() {
        val original = request()
        assertEquals(GlafiraBridgeStatus.ACCEPTED, admission(noReplay()).admitDurableTask(original).status)
        val db = runtime.taskStore.writableDatabase
        val trigger = "ingress_failure_" + UUID.randomUUID().toString().replace("-", "")
        db.execSQL("""CREATE TEMP TRIGGER $trigger BEFORE INSERT ON core_tasks
            WHEN NEW.project_id = '$projectId' BEGIN SELECT RAISE(ABORT, 'injected enqueue failure'); END""".trimIndent())
        try {
            assertThrows(Exception::class.java) { submit(original) }
            assertEquals(0, countTasks())
        } finally { db.execSQL("DROP TRIGGER $trigger") }
        val retry = request()
        assertEquals(GlafiraBridgeStatus.ACCEPTED, admission(noReplay()).admitDurableTask(retry).status)
        assertTrue(submit(retry).inserted)
        assertEquals(1, countTasks())
    }

    @Test fun lostAcceptanceReplyReturnsTheSameReceiptAfterTheDatabaseReopens() {
        val original = request()
        assertEquals(GlafiraBridgeStatus.ACCEPTED, admission(noReplay()).admitDurableTask(original).status)
        val lostReply = submit(original)
        assertTrue(lostReply.inserted)
        runtime.taskStore.close()
        val retry = request()
        assertEquals(GlafiraBridgeStatus.ACCEPTED, admission(noReplay()).admitDurableTask(retry).status)
        val receipt = submit(retry)
        assertFalse(receipt.inserted)
        assertEquals(lostReply.taskId, receipt.taskId)
        assertEquals(1, countTasks())
    }

    @Test fun terminalReceiptReturnsItsOriginalTaskAndResultWithoutRequeueingExecution() {
        val original = request()
        val first = submit(original)
        val store = runtime.taskStore
        assertTrue(store.transition(first.taskId, setOf(GlafiraTaskState.QUEUED), GlafiraTaskState.RUNNING))
        val task = store.getTask(first.taskId)!!
        val values = mapOf("messageId" to "already-completed-message")
        assertTrue(runtime.resultStore.complete(task, values, System.currentTimeMillis()))
        val completed = store.getTask(first.taskId)!!
        store.close()
        val retry = request()
        assertEquals(GlafiraBridgeStatus.ACCEPTED, admission(noReplay()).admitDurableTask(retry).status)
        val receipt = submit(retry)
        assertEquals(first.taskId, receipt.taskId)
        assertFalse(receipt.inserted)
        assertEquals(GlafiraTaskState.SUCCEEDED, receipt.state)
        assertEquals(completed, store.getTask(first.taskId))
        assertEquals(values, runtime.resultStore.get(first.taskId, projectId)?.values)
        assertEquals(1, countTasks())
    }

    @Test fun sameKeyWithAnotherPayloadOrDeviceIsAConflictAndDoesNotExposeTheReceipt() {
        val original = request()
        val first = submit(original)
        val saved = runtime.taskStore.getTask(first.taskId)
        for (conflict in listOf(request(message = "different message"), request(deviceId = "other-device-001"))) {
            assertEquals(GlafiraBridgeStatus.ACCEPTED, admission(noReplay()).admitDurableTask(conflict).status)
            val error = assertThrows(IllegalArgumentException::class.java) { submit(conflict) }
            assertEquals("BRIDGE_IDEMPOTENCY_CONFLICT", error.message)
            assertEquals(saved, runtime.taskStore.getTask(first.taskId))
            assertEquals(1, countTasks())
        }
    }
}
