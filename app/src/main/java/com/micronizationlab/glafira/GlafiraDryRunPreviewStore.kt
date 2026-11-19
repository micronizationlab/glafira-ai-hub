package com.micronizationlab.glafira

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

enum class GlafiraDryRunReviewStatus { PENDING, ACKNOWLEDGED, REJECTED }

data class GlafiraDryRunPreviewRecord(
    val taskId: String,
    val projectId: String,
    val toolId: String,
    val risk: String,
    val simulationStatus: String,
    val planHash: String,
    val createdAtMillis: Long,
    val reviewStatus: GlafiraDryRunReviewStatus,
    val reviewedAtMillis: Long?,
    val planJson: String
)

/** Implemented by Core's single durable store; previews never create a second task queue. */
interface GlafiraDryRunPreviewPersistence {
    fun persistDryRunPreview(
        taskId: String,
        expectedStates: Set<GlafiraTaskState>,
        plan: GlafiraDryRunPlan,
        nowMillis: Long
    ): Boolean
}

internal object GlafiraDryRunPreviewSchema {
    const val TABLE = "core_dry_run_previews"

    fun create(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE (
                task_id TEXT PRIMARY KEY NOT NULL REFERENCES core_tasks(task_id) ON DELETE CASCADE,
                project_id TEXT NOT NULL,
                tool_id TEXT NOT NULL,
                risk TEXT NOT NULL CHECK(risk IN ('READ_ONLY','DRAFT','EXTERNAL_WRITE','FINANCIAL','DESTRUCTIVE')),
                simulation_status TEXT NOT NULL CHECK(simulation_status IN ('PREVIEW_ONLY','NOT_SIMULATABLE')),
                plan_hash TEXT NOT NULL CHECK(length(plan_hash) = 64),
                plan_ciphertext TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                review_status TEXT NOT NULL CHECK(review_status IN ('PENDING','ACKNOWLEDGED','REJECTED')),
                reviewed_at INTEGER
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS core_dry_run_review_idx ON $TABLE(review_status, created_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS core_dry_run_project_idx ON $TABLE(project_id, created_at DESC)")
    }
}
