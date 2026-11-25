package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlafiraCoreOwnershipTransferStoreInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var localCoreId: String
    private lateinit var taskStore: GlafiraTaskStore
    private lateinit var peerStore: GlafiraCoreTrustedPeerStore
    private lateinit var traceStore: GlafiraCoreTraceStore
    private lateinit var transferStore: GlafiraCoreOwnershipTransferStore
    private lateinit var peerKeyPair: KeyPair
    private lateinit var peerFingerprint: String
    private lateinit var peerDatabaseFile: File
    private val peerCoreId = "peer-core-0001"
    private val projectId = "ownership-transfer-project"
    private var nowMillis: Long = 0L

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        nowMillis = System.currentTimeMillis()
        localCoreId = AndroidCoreInstallationIdentityStore(context).loadOrCreate().coreId
        taskStore = GlafiraTaskStore(context, localCoreId)
        peerDatabaseFile = File(
            context.noBackupFilesDir,
            "ownership-transfer-peers-" + UUID.randomUUID() + ".db"
        )
        peerStore = GlafiraCoreTrustedPeerStore(context, peerDatabaseFile)
        peerKeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        peerFingerprint = GlafiraCoreOwnershipTransferCryptography.fingerprint(peerKeyPair.public)
        val candidate = GlafiraPeerEnrollmentCandidate(
            coreId = peerCoreId,
            keyEpoch = PEER_KEY_EPOCH,
            publicKeySpkiBase64 = Base64.getEncoder().encodeToString(peerKeyPair.public.encoded),
            claimedFingerprintSha256 = peerFingerprint
        )
        assertEquals(
            GlafiraPeerTrustMutation.ENROLLED,
            peerStore.enroll(candidate, peerFingerprint, ownerSession(), nowMillis)
        )
        traceStore = GlafiraCoreTraceStore(taskStore)
        transferStore = GlafiraCoreOwnershipTransferStore(taskStore, peerStore, localCoreId, traceStore)
    }

    @After
    fun tearDown() {
        runCatching { peerStore.close() }
        runCatching { taskStore.close() }
        peerDatabaseFile.delete()
        File(peerDatabaseFile.absolutePath + "-wal").delete()
        File(peerDatabaseFile.absolutePath + "-shm").delete()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun ownershipTransferReceiptRemainsLocalAcrossPortableBackupRestore() =
        kotlinx.coroutines.runBlocking {
            val task = enqueueRemoteOwnedTask("transfer-task-backup-compatibility")
            val passphrase = "GLAFIRA-transfer-backup-passphrase".toCharArray()
            val coordinator = GlafiraCoreBackupCoordinator(context, taskStore)
            val archive = coordinator.create(passphrase)
            try {
                // The transfer-receipt table already exists before export and must be explicitly
                // classified as device-local rather than rejected as an unknown Core table.
                val transfer = envelope(
                    task, "transfer-backup-0001", "nonce-backup-0001", nowMillis
                )
                assertEquals(
                    GlafiraOwnershipTransferApplyResult.APPLIED,
                    transferStore.applyIncoming(transfer, ownerSession(), nowMillis)
                )
                val localReceipt = requireNotNull(transferStore.receipt(transfer.payload.transferId))

                // Restore an older portable snapshot. Anti-replay receipts are not portable and
                // must remain in the destination database rather than being copied from archive.
                coordinator.restore(archive, passphrase)

                assertEquals(localReceipt, transferStore.receipt(transfer.payload.transferId))
            } finally {
                archive.delete()
                passphrase.fill('\u0000')
            }
        }

    @Test
    fun appliesTransferAtomicallyAndDuplicateSurvivesTaskStoreRestart() {
        val task = enqueueRemoteOwnedTask("transfer-task-success")
        val transfer = envelope(task, "transfer-00000001", "nonce-00000001", nowMillis)

        assertEquals(
            GlafiraOwnershipTransferApplyResult.APPLIED,
            transferStore.applyIncoming(transfer, ownerSession(), nowMillis)
        )
        val ownership = requireNotNull(taskStore.taskOwnership(task.taskId, projectId))
        assertEquals(localCoreId, ownership.ownerCoreId)
        assertEquals(2L, ownership.ownerGeneration)
        assertEquals(0L, ownership.stateVersion)
        assertNull(ownership.approvalRef)

        val receipt = requireNotNull(transferStore.receipt(transfer.payload.transferId))
        assertEquals(transfer.payload.nonce, receipt.nonce)
        assertEquals(peerCoreId, receipt.oldOwnerCoreId)
        assertEquals(localCoreId, receipt.newOwnerCoreId)
        assertEquals(1L, receipt.oldOwnerGeneration)
        assertEquals(2L, receipt.newOwnerGeneration)
        assertEquals(envelopeDigest(transfer), receipt.envelopeSha256)
        assertEquals(PEER_KEY_EPOCH, receipt.signingKeyEpoch)
        assertEquals(peerFingerprint, receipt.signingPublicKeySha256)
        assertEquals(nowMillis, receipt.issuedAtMillis)
        assertEquals(nowMillis + 60_000L, receipt.expiresAtMillis)
        assertEquals(1, traceCount(task.taskId, "TASK_OWNERSHIP_TRANSFERRED"))

        taskStore.close()
        taskStore = GlafiraTaskStore(context, localCoreId)
        traceStore = GlafiraCoreTraceStore(taskStore)
        transferStore = GlafiraCoreOwnershipTransferStore(taskStore, peerStore, localCoreId, traceStore)
        assertEquals(
            GlafiraOwnershipTransferApplyResult.ALREADY_APPLIED,
            transferStore.applyIncoming(transfer, ownerSession(), nowMillis + 120_000L)
        )
        assertEquals(1, traceCount(task.taskId, "TASK_OWNERSHIP_TRANSFERRED"))
    }

    @Test
    fun rejectsInvalidSignatureWrongRecipientExpiredAndRevokedPeer() {
        val task = enqueueRemoteOwnedTask("transfer-task-invalid")
        val valid = envelope(task, "transfer-00000002", "nonce-00000002", nowMillis)

        val signatureBytes = Base64.getDecoder().decode(valid.signatureBase64)
        signatureBytes[0] = (signatureBytes[0].toInt() xor 1).toByte()
        val invalidSignature = valid.copy(signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes))
        assertEquals(
            GlafiraOwnershipTransferApplyResult.INVALID_SIGNATURE,
            transferStore.applyIncoming(invalidSignature, ownerSession(), nowMillis)
        )

        val wrongRecipient = envelope(
            task, "transfer-00000003", "nonce-00000003", nowMillis,
            newOwnerCoreId = "other-core-0001"
        )
        assertEquals(
            GlafiraOwnershipTransferApplyResult.WRONG_RECIPIENT,
            transferStore.applyIncoming(wrongRecipient, ownerSession(), nowMillis)
        )

        val expired = envelope(
            task, "transfer-00000004", "nonce-00000004",
            issuedAtMillis = nowMillis - 120_000L,
            expiresAtMillis = nowMillis - 60_000L
        )
        assertEquals(
            GlafiraOwnershipTransferApplyResult.EXPIRED,
            transferStore.applyIncoming(expired, ownerSession(), nowMillis)
        )

        assertEquals(
            GlafiraPeerTrustMutation.REVOKED,
            peerStore.revoke(peerCoreId, PEER_KEY_EPOCH, ownerSession(), nowMillis + 1L)
        )
        assertEquals(
            GlafiraOwnershipTransferApplyResult.UNTRUSTED_PEER,
            transferStore.applyIncoming(valid, ownerSession(), nowMillis)
        )
        assertEquals(peerCoreId, taskStore.taskOwnership(task.taskId, projectId)?.ownerCoreId)
        assertNull(transferStore.receipt(valid.payload.transferId))
    }

    @Test
    fun rejectsNonceReplayAndStaleOwnershipWithoutChangingOwner() {
        val task = enqueueRemoteOwnedTask("transfer-task-replay")
        val first = envelope(task, "transfer-00000005", "nonce-00000005", nowMillis)
        assertEquals(
            GlafiraOwnershipTransferApplyResult.APPLIED,
            transferStore.applyIncoming(first, ownerSession(), nowMillis)
        )

        val replayPayload = first.payload.copy(transferId = "transfer-00000006")
        val replaySignature = GlafiraCoreOwnershipTransferCryptography.sign(
            replayPayload, peerKeyPair.private, peerKeyPair.public
        )
        val replay = GlafiraSignedOwnershipTransferEnvelope(
            replayPayload, Base64.getEncoder().encodeToString(replaySignature)
        )
        assertEquals(
            GlafiraOwnershipTransferApplyResult.NONCE_REPLAY,
            transferStore.applyIncoming(replay, ownerSession(), nowMillis)
        )

        val staleTask = enqueueRemoteOwnedTask("transfer-task-stale")
        val stale = envelope(staleTask, "transfer-00000007", "nonce-00000007", nowMillis)
        taskStore.writableDatabase.update(
            "core_task_ownership",
            ContentValues().apply { put("owner_generation", 3L) },
            "task_id = ?",
            arrayOf(staleTask.taskId)
        )
        assertEquals(
            GlafiraOwnershipTransferApplyResult.STALE_OWNERSHIP,
            transferStore.applyIncoming(stale, ownerSession(), nowMillis)
        )
        assertEquals(peerCoreId, taskStore.taskOwnership(staleTask.taskId, projectId)?.ownerCoreId)
        assertNull(transferStore.receipt(stale.payload.transferId))
    }

    @Test
    fun requiresQueuedUnleasedTaskAndDeletesExpiredLeaseAndOldLiveGrant() {
        val activeTask = enqueueRemoteOwnedTask("transfer-task-active-lease")
        taskStore.writableDatabase.insertOrThrow("core_desktop_leases", null, ContentValues().apply {
            put("task_id", activeTask.taskId)
            put("device_id", "desktop-node-0001")
            put("lease_id", "desktop-lease-0001")
            put("fencing_token", 1L)
            put("lease_expires_at", nowMillis + 60_000L)
            put("attempt_count", 1)
            put("progress_percent", 0)
            put("updated_at", nowMillis)
        })
        val activeEnvelope = envelope(activeTask, "transfer-00000008", "nonce-00000008", nowMillis)
        assertEquals(
            GlafiraOwnershipTransferApplyResult.TASK_NOT_QUIESCENT,
            transferStore.applyIncoming(activeEnvelope, ownerSession(), nowMillis)
        )

        val expiredLeaseTask = enqueueRemoteOwnedTask("transfer-task-expired-lease")
        taskStore.writableDatabase.insertOrThrow("core_desktop_leases", null, ContentValues().apply {
            put("task_id", expiredLeaseTask.taskId)
            put("device_id", "desktop-node-0001")
            put("lease_id", "desktop-lease-0002")
            put("fencing_token", 1L)
            put("lease_expires_at", nowMillis - 1L)
            put("attempt_count", 1)
            put("progress_percent", 0)
            put("updated_at", nowMillis - 10L)
        })
        taskStore.writableDatabase.insertOrThrow(GlafiraCoreLiveRunStore.TABLE, null, ContentValues().apply {
            put("task_id", expiredLeaseTask.taskId)
            put("project_id", projectId)
            put("tool_id", "project.read")
            put("plan_hash", "a".repeat(64))
            put("action_hash", "b".repeat(64))
            put("approved_at", nowMillis - 10L)
            put("expires_at", nowMillis + 60_000L)
            put("approved_session_id", "owner-session-0001")
        })
        val expiredEnvelope = envelope(expiredLeaseTask, "transfer-00000009", "nonce-00000009", nowMillis)
        assertEquals(
            GlafiraOwnershipTransferApplyResult.APPLIED,
            transferStore.applyIncoming(expiredEnvelope, ownerSession(), nowMillis)
        )
        assertEquals(0, rowCount("core_desktop_leases", expiredLeaseTask.taskId))
        assertEquals(0, rowCount(GlafiraCoreLiveRunStore.TABLE, expiredLeaseTask.taskId))
    }

    @Test
    fun expiresOldGenerationApprovalAndClearsApprovalReference() {
        val task = enqueueRemoteOwnedTask(
            "transfer-task-approval",
            taskType = "message.send",
            payload = """{"actionSummary":"Send a test message","recipient":"example@example.com","message":"hello"}"""
        )
        val approvalRef = "approval-00000001"
        taskStore.writableDatabase.update(
            "core_task_ownership",
            ContentValues().apply { put("approval_ref", approvalRef) },
            "task_id = ?",
            arrayOf(task.taskId)
        )
        taskStore.writableDatabase.insertOrThrow("core_action_approvals", null, ContentValues().apply {
            put("task_id", task.taskId)
            put("project_id", projectId)
            put("tool_id", "message.send")
            put("action_hash", "c".repeat(64))
            put("requested_scopes", """["message.send"]""")
            put("requested_at", nowMillis - 1_000L)
            put("expires_at", nowMillis + 60_000L)
            put("status", "APPROVED")
            put("approved_at", nowMillis - 500L)
            put("grant_expires_at", nowMillis + 60_000L)
            put("decided_by", "owner")
            put("owner_core_id", peerCoreId)
            put("owner_generation", 1L)
        })
        val transfer = envelope(task, "transfer-00000010", "nonce-00000010", nowMillis)

        assertEquals(
            GlafiraOwnershipTransferApplyResult.APPLIED,
            transferStore.applyIncoming(transfer, ownerSession(), nowMillis)
        )
        assertNull(taskStore.taskOwnership(task.taskId, projectId)?.approvalRef)
        taskStore.readableDatabase.query(
            "core_action_approvals",
            arrayOf("status", "approved_at", "grant_expires_at", "decided_by"),
            "task_id = ?",
            arrayOf(task.taskId),
            null, null, null, "1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("EXPIRED", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertEquals("ownership-transfer", cursor.getString(3))
        }
    }

    @Test
    fun rollsBackOwnerReceiptAndTraceWhenReceiptInsertFails() {
        val task = enqueueRemoteOwnedTask("transfer-task-rollback")
        val transfer = envelope(task, "transfer-00000011", "nonce-00000011", nowMillis)
        taskStore.writableDatabase.execSQL(
            """CREATE TRIGGER fail_core_ownership_receipt_insert
                BEFORE INSERT ON core_ownership_transfer_receipts
                BEGIN SELECT RAISE(ABORT, 'INJECTED_RECEIPT_FAILURE'); END"""
        )

        assertThrows(SQLiteException::class.java) {
            transferStore.applyIncoming(transfer, ownerSession(), nowMillis)
        }
        assertEquals(peerCoreId, taskStore.taskOwnership(task.taskId, projectId)?.ownerCoreId)
        assertEquals(1L, taskStore.taskOwnership(task.taskId, projectId)?.ownerGeneration)
        assertNull(transferStore.receipt(transfer.payload.transferId))
        assertEquals(0, traceCount(task.taskId, "TASK_OWNERSHIP_TRANSFERRED"))
    }

    @Test
    fun concurrentTransfersHaveExactlyOneWinner() {
        val task = enqueueRemoteOwnedTask("transfer-task-concurrent")
        val first = envelope(task, "transfer-00000012", "nonce-00000012", nowMillis)
        val second = envelope(task, "transfer-00000013", "nonce-00000013", nowMillis)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val one = executor.submit<GlafiraOwnershipTransferApplyResult> {
                start.await()
                transferStore.applyIncoming(first, ownerSession(), nowMillis)
            }
            val two = executor.submit<GlafiraOwnershipTransferApplyResult> {
                start.await()
                transferStore.applyIncoming(second, ownerSession(), nowMillis)
            }
            start.countDown()
            val results = listOf(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))
            assertEquals(1, results.count { it == GlafiraOwnershipTransferApplyResult.APPLIED })
            assertEquals(1, results.count { it == GlafiraOwnershipTransferApplyResult.STALE_OWNERSHIP })
            assertEquals(localCoreId, taskStore.taskOwnership(task.taskId, projectId)?.ownerCoreId)
            assertEquals(2L, taskStore.taskOwnership(task.taskId, projectId)?.ownerGeneration)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun enqueueRemoteOwnedTask(
        taskId: String,
        taskType: String = "project.read",
        payload: String = """{"record":"$taskId"}"""
    ): GlafiraTask {
        val task = GlafiraTask(
            taskId = taskId,
            projectId = projectId,
            taskType = taskType,
            payload = payload,
            origin = "ownership-transfer-instrumentation",
            createdAtMillis = nowMillis - 1_000L,
            updatedAtMillis = nowMillis - 1_000L,
            idempotencyKey = "idem-$taskId"
        )
        assertTrue(taskStore.enqueue(task).inserted)
        val changed = taskStore.writableDatabase.update(
            "core_task_ownership",
            ContentValues().apply {
                put("owner_core_id", peerCoreId)
                put("owner_generation", 1L)
            },
            "task_id = ?",
            arrayOf(taskId)
        )
        assertEquals(1, changed)
        return task
    }

    private fun envelope(
        task: GlafiraTask,
        transferId: String,
        nonce: String,
        issuedAtMillis: Long,
        expiresAtMillis: Long = issuedAtMillis + 60_000L,
        newOwnerCoreId: String = localCoreId
    ): GlafiraSignedOwnershipTransferEnvelope {
        val ownership = requireNotNull(taskStore.taskOwnership(task.taskId, projectId))
        val payload = GlafiraOwnershipTransferSigningPayload(
            schemaVersion = 1,
            transferId = transferId,
            nonce = nonce,
            taskId = task.taskId,
            projectId = projectId,
            oldOwnerCoreId = peerCoreId,
            newOwnerCoreId = newOwnerCoreId,
            oldOwnerGeneration = ownership.ownerGeneration,
            newOwnerGeneration = ownership.ownerGeneration + 1L,
            stateVersion = ownership.stateVersion,
            payloadSha256 = ownership.payloadSha256,
            idempotencyKey = ownership.idempotencyKey,
            effectRisk = ownership.effectRisk,
            approvalRef = ownership.approvalRef,
            issuedAt = Instant.ofEpochMilli(issuedAtMillis).toString(),
            expiresAt = Instant.ofEpochMilli(expiresAtMillis).toString(),
            signingKeyEpoch = PEER_KEY_EPOCH,
            signingPublicKeySha256 = GlafiraCoreOwnershipTransferCryptography.fingerprint(peerKeyPair.public)
        )
        val signature = GlafiraCoreOwnershipTransferCryptography.sign(
            payload, peerKeyPair.private, peerKeyPair.public
        )
        return GlafiraSignedOwnershipTransferEnvelope(payload, Base64.getEncoder().encodeToString(signature))
    }

    private fun ownerSession(): GlafiraCoreOwnerSession {
        val constructor = GlafiraCoreOwnerSession::class.java.getDeclaredConstructor(
            String::class.java, Long::class.javaPrimitiveType!!
        )
        constructor.isAccessible = true
        return constructor.newInstance(UUID.randomUUID().toString(), SystemClock.elapsedRealtime() + 60_000L)
    }

    private fun traceCount(taskId: String, eventType: String): Int =
        taskStore.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM " + GlafiraCoreTraceStore.TABLE + " WHERE task_id = ? AND event_type = ?",
            arrayOf(taskId, eventType)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun rowCount(table: String, taskId: String): Int =
        taskStore.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $table WHERE task_id = ?",
            arrayOf(taskId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun envelopeDigest(envelope: GlafiraSignedOwnershipTransferEnvelope): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(GlafiraCoreOwnershipTransferCryptography.canonicalBytes(envelope.payload))
        digest.update(Base64.getDecoder().decode(envelope.signatureBase64))
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val DATABASE_NAME = "glafira_core_tasks.db"
        private const val PEER_KEY_EPOCH = 1L
    }
}
