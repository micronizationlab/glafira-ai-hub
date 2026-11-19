package com.micronizationlab.glafira

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * The single durable Core database boundary. Memory and Trace share this file so
 * callers can commit domain changes and their audit event in one transaction.
 * Future Core queue tables must be added here rather than creating another DB.
 */
class GlafiraCoreDatabase(context: Context) :
    SQLiteOpenHelper(
        context.applicationContext,
        File(context.applicationContext.filesDir, DATABASE_NAME).absolutePath,
        null,
        DATABASE_VERSION
    ) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA secure_delete = ON")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE memory_records (
                id TEXT PRIMARY KEY NOT NULL,
                schema_version INTEGER NOT NULL,
                scope_type TEXT NOT NULL,
                scope_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                classification TEXT NOT NULL,
                status TEXT NOT NULL,
                confidence REAL NOT NULL,
                confidence_basis TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                valid_from TEXT,
                expires_at TEXT,
                payload BLOB
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_memory_scope_status ON memory_records(scope_type, scope_id, status)")
        db.execSQL("CREATE INDEX idx_memory_project_updated ON memory_records(scope_id, updated_at)")
        db.execSQL(
            """
            CREATE TABLE trace_events (
                event_id TEXT PRIMARY KEY NOT NULL,
                schema_version INTEGER NOT NULL,
                occurred_at TEXT NOT NULL,
                recorded_at TEXT NOT NULL,
                event_type TEXT NOT NULL,
                severity TEXT NOT NULL,
                actor_id TEXT NOT NULL,
                actor_type TEXT NOT NULL,
                project_id TEXT,
                task_id TEXT,
                parent_task_id TEXT,
                correlation_id TEXT NOT NULL,
                causation_id TEXT,
                component TEXT NOT NULL,
                action TEXT NOT NULL,
                target_type TEXT,
                target_id TEXT,
                result_code TEXT,
                approval_ref TEXT,
                evidence_refs TEXT NOT NULL,
                data_classification TEXT NOT NULL,
                redaction_applied INTEGER NOT NULL,
                duration_ms INTEGER,
                safe_attributes TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_trace_project_time ON trace_events(project_id, occurred_at, event_id)")
        db.execSQL("CREATE INDEX idx_trace_task_time ON trace_events(task_id, occurred_at)")
        db.execSQL(
            """
            CREATE TRIGGER trace_events_no_update
            BEFORE UPDATE ON trace_events
            BEGIN
                SELECT RAISE(ABORT, 'Trace is append-only');
            END
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER trace_events_no_delete
            BEFORE DELETE ON trace_events
            BEGIN
                SELECT RAISE(ABORT, 'Trace is append-only');
            END
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations are additive and explicit. Never drop Core data on upgrade.
        if (oldVersion < 1) onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Core database downgrade is not supported")
    }

    companion object {
        const val DATABASE_NAME = "glafira_core.db"
        private const val DATABASE_VERSION = 1
    }
}
