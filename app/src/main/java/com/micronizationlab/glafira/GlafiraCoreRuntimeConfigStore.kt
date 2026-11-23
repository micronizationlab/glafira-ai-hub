package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Versioned, transactional source of truth for Core runtime enablement and execution limits.
 * A missing/unsupported/corrupt row is never interpreted as permission to run.
 */
internal class GlafiraCoreRuntimeConfigStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    data class Snapshot(
        val enabled: Boolean,
        val budget: GlafiraRuntimeBudget,
        val configVersion: Int,
        val updatedAtMillis: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE runtime_config (
                singleton_id INTEGER PRIMARY KEY NOT NULL CHECK(singleton_id = 1),
                config_version INTEGER NOT NULL,
                enabled INTEGER NOT NULL CHECK(enabled IN (0, 1)),
                max_wall_clock_ms INTEGER NOT NULL,
                max_steps INTEGER NOT NULL,
                max_retries INTEGER NOT NULL,
                max_concurrent_tasks INTEGER NOT NULL,
                max_cost_micros INTEGER,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE runtime_config_audit (
                event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                config_version INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                enabled INTEGER NOT NULL CHECK(enabled IN (0, 1)),
                budget_json TEXT NOT NULL,
                occurred_at INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No destructive migration. Future versions must add an explicit, tested migration.
        throw IllegalStateException("Unsupported GLAFIRA runtime config migration: $oldVersion->$newVersion")
    }

    /** One-time migration from legacy preferences. No legacy enabled flag means PAUSED. */
    @Synchronized
    fun loadOrInitialize(
        legacyEnabled: Boolean?,
        legacyBudget: GlafiraRuntimeBudget?
    ): Snapshot? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = readSnapshot(db)
            if (existing != null) {
                db.setTransactionSuccessful()
                return existing
            }
            // A row with an unknown version is not overwritten or silently downgraded.
            val hasRow = db.rawQuery("SELECT 1 FROM runtime_config WHERE singleton_id = 1", null)
                .use { it.moveToFirst() }
            if (hasRow) return null

            val budget = legacyBudget ?: GlafiraRuntimeBudget()
            val enabled = legacyEnabled == true
            val now = System.currentTimeMillis()
            db.insertOrThrow("runtime_config", null, values(enabled, budget, now))
            appendAudit(db, "INITIALIZED_OR_MIGRATED", enabled, budget, now)
            db.setTransactionSuccessful()
            return Snapshot(enabled, budget, CURRENT_CONFIG_VERSION, now)
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun read(): Snapshot? = readSnapshot(readableDatabase)

    @Synchronized
    fun setEnabled(enabled: Boolean, eventType: String = "OWNER_ENABLEMENT_CHANGED") {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = readSnapshot(db) ?: error("RUNTIME_CONFIG_UNAVAILABLE")
            val now = System.currentTimeMillis()
            val changed = db.update(
                "runtime_config",
                ContentValues().apply {
                    put("enabled", if (enabled) 1 else 0)
                    put("updated_at", now)
                },
                "singleton_id = 1 AND config_version = ?",
                arrayOf(CURRENT_CONFIG_VERSION.toString())
            )
            check(changed == 1) { "RUNTIME_CONFIG_VERSION_CONFLICT" }
            appendAudit(db, eventType, enabled, current.budget, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun setBudget(budget: GlafiraRuntimeBudget) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = readSnapshot(db) ?: error("RUNTIME_CONFIG_UNAVAILABLE")
            val now = System.currentTimeMillis()
            val changed = db.update(
                "runtime_config",
                values(current.enabled, budget, now),
                "singleton_id = 1 AND config_version = ?",
                arrayOf(CURRENT_CONFIG_VERSION.toString())
            )
            check(changed == 1) { "RUNTIME_CONFIG_VERSION_CONFLICT" }
            appendAudit(db, "OWNER_BUDGET_CHANGED", current.enabled, budget, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readSnapshot(db: SQLiteDatabase): Snapshot? =
        db.query(
            "runtime_config",
            arrayOf("config_version", "enabled", "max_wall_clock_ms", "max_steps",
                "max_retries", "max_concurrent_tasks", "max_cost_micros", "updated_at"),
            "singleton_id = 1", null, null, null, null, "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val version = cursor.getInt(0)
            if (version != CURRENT_CONFIG_VERSION) return@use null
            val cost = if (cursor.isNull(6)) null else cursor.getLong(6)
            val budget = runCatching {
                GlafiraRuntimeBudget(
                    maxWallClockMillis = cursor.getLong(2),
                    maxSteps = cursor.getInt(3),
                    maxRetries = cursor.getInt(4),
                    maxConcurrentTasks = cursor.getInt(5),
                    maxCostMicros = cost
                )
            }.getOrNull() ?: return@use null
            Snapshot(cursor.getInt(1) == 1, budget, version, cursor.getLong(7))
        }

    private fun values(enabled: Boolean, budget: GlafiraRuntimeBudget, now: Long) =
        ContentValues().apply {
            put("singleton_id", 1)
            put("config_version", CURRENT_CONFIG_VERSION)
            put("enabled", if (enabled) 1 else 0)
            put("max_wall_clock_ms", budget.maxWallClockMillis)
            put("max_steps", budget.maxSteps)
            put("max_retries", budget.maxRetries)
            put("max_concurrent_tasks", budget.maxConcurrentTasks)
            if (budget.maxCostMicros == null) putNull("max_cost_micros")
            else put("max_cost_micros", budget.maxCostMicros)
            put("updated_at", now)
        }

    private fun appendAudit(
        db: SQLiteDatabase,
        eventType: String,
        enabled: Boolean,
        budget: GlafiraRuntimeBudget,
        now: Long
    ) {
        val budgetJson = org.json.JSONObject().apply {
            put("maxWallClockMillis", budget.maxWallClockMillis)
            put("maxSteps", budget.maxSteps)
            put("maxRetries", budget.maxRetries)
            put("maxConcurrentTasks", budget.maxConcurrentTasks)
            if (budget.maxCostMicros == null) put("maxCostMicros", org.json.JSONObject.NULL)
            else put("maxCostMicros", budget.maxCostMicros)
        }.toString()
        db.insertOrThrow("runtime_config_audit", null, ContentValues().apply {
            put("config_version", CURRENT_CONFIG_VERSION)
            put("event_type", eventType)
            put("enabled", if (enabled) 1 else 0)
            put("budget_json", budgetJson)
            put("occurred_at", now)
        })
    }

    companion object {
        private const val DATABASE_NAME = "glafira_core_runtime_config.db"
        private const val DATABASE_VERSION = 1
        private const val CURRENT_CONFIG_VERSION = 1
    }
}
