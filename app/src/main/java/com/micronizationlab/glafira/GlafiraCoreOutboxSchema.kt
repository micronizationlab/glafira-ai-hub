package com.micronizationlab.glafira

import android.database.sqlite.SQLiteDatabase

/** Schema for the owning Core's durable, signed, at-least-once event outbox. */
internal object GlafiraCoreOutboxSchema {
    const val TABLE = "core_outbox"

    fun create(db: SQLiteDatabase) {
        // Sequence state survives project deletion so a source Core never reuses an event number.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS core_outbox_sequences (
                source_core_id TEXT PRIMARY KEY NOT NULL CHECK(length(source_core_id) BETWEEN 8 AND 128),
                last_sequence INTEGER NOT NULL CHECK(last_sequence >= 0),
                updated_at INTEGER NOT NULL CHECK(updated_at > 0)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE (
                event_id TEXT PRIMARY KEY NOT NULL,
                source_core_id TEXT NOT NULL,
                recipient_core_id TEXT NOT NULL,
                project_id TEXT NOT NULL,
                source_sequence INTEGER NOT NULL CHECK(source_sequence > 0),
                correlation_id TEXT,
                causation_id TEXT,
                source_key_epoch INTEGER NOT NULL CHECK(source_key_epoch > 0),
                protocol_version INTEGER NOT NULL CHECK(protocol_version > 0),
                event_type TEXT NOT NULL CHECK(event_type IN (
                    'TASK_ASSIGNMENT', 'TASK_RESULT', 'TASK_CANCELLED',
                    'OWNERSHIP_TRANSFER', 'TASK_RECONCILIATION'
                )),
                task_id TEXT NOT NULL,
                owner_generation INTEGER NOT NULL CHECK(owner_generation > 0),
                state_version INTEGER NOT NULL CHECK(state_version >= 0),
                idempotency_key TEXT NOT NULL,
                payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256) = 64),
                payload_ciphertext TEXT NOT NULL CHECK(length(payload_ciphertext) > 0),
                signature_base64 TEXT NOT NULL CHECK(length(signature_base64) > 0),
                created_at INTEGER NOT NULL CHECK(created_at > 0),
                delivery_state TEXT NOT NULL DEFAULT 'PENDING' CHECK(delivery_state IN (
                    'PENDING', 'ACKNOWLEDGED', 'QUARANTINED'
                )),
                attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
                next_attempt_at INTEGER NOT NULL CHECK(next_attempt_at > 0),
                last_error TEXT,
                acknowledged_at INTEGER,
                ack_sender_core_id TEXT,
                ack_recipient_core_id TEXT,
                ack_owner_generation INTEGER,
                ack_state_version INTEGER,
                ack_payload_sha256 TEXT CHECK(ack_payload_sha256 IS NULL OR ack_payload_sha256 NOT GLOB '*[^0-9a-f]*'),
                ack_signature_base64 TEXT,
                UNIQUE(source_core_id, source_sequence),
                UNIQUE(source_core_id, idempotency_key, event_type, owner_generation, state_version),
                CHECK(length(source_core_id) BETWEEN 8 AND 128),
                CHECK(length(recipient_core_id) BETWEEN 8 AND 128),
                CHECK(source_core_id != recipient_core_id),
                CHECK(length(idempotency_key) BETWEEN 1 AND 256),
                CHECK(payload_sha256 NOT GLOB '*[^0-9a-f]*'),
                CHECK(
                    (delivery_state = 'ACKNOWLEDGED' AND acknowledged_at IS NOT NULL
                        AND ack_sender_core_id IS NOT NULL AND ack_sender_core_id = recipient_core_id
                        AND ack_recipient_core_id IS NOT NULL AND ack_recipient_core_id = source_core_id
                        AND ack_owner_generation IS NOT NULL AND ack_owner_generation = owner_generation
                        AND ack_state_version IS NOT NULL AND ack_state_version = state_version
                        AND ack_payload_sha256 IS NOT NULL AND ack_payload_sha256 = payload_sha256
                        AND ack_signature_base64 IS NOT NULL AND length(ack_signature_base64) > 0)
                    OR
                    (delivery_state != 'ACKNOWLEDGED' AND acknowledged_at IS NULL
                        AND ack_sender_core_id IS NULL AND ack_recipient_core_id IS NULL
                        AND ack_owner_generation IS NULL AND ack_state_version IS NULL
                        AND ack_payload_sha256 IS NULL AND ack_signature_base64 IS NULL)
                )
            )
        """.trimIndent())
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_core_outbox_pending_delivery " +
                "ON $TABLE(recipient_core_id, next_attempt_at, created_at) " +
                "WHERE delivery_state = 'PENDING'"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_core_outbox_task_generation " +
                "ON $TABLE(task_id, owner_generation, state_version)"
        )
    }
}
