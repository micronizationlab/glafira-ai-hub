package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class GlafiraDesktopTaskLease(
    val task: GlafiraTask,
    val leaseId: String,
    val fencingToken: Long
)

private data class StoredDesktopLease(
    val deviceId: String,
    val leaseId: String,
    val fencingToken: Long,
    val expiresAtMillis: Long
)

/**
 * Transactional, process-death-safe queue storage for GLAFIRA Core.
 * Payloads are encrypted before they enter SQLite; task metadata remains queryable.
 */
class GlafiraTaskStore(context: Context, private val ownerCoreId: String? = null) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION),
    GlafiraTaskQueue, GlafiraOwnedDryRunPreviewPersistence, GlafiraInterruptedTaskRecovery, GlafiraOwnedCoreTaskResultPersistence, GlafiraTaskOwnershipExecutionFence {
    private val appContext = context.applicationContext
    override val ownershipFencingEnabled: Boolean get() = ownerCoreId != null

    init {
        require(ownerCoreId == null || ownerCoreId.matches(Regex("^[A-Za-z0-9._-]{8,128}$"))) {
            "CORE_OWNERSHIP_ID_INVALID"
        }
    }

    private val coreTraceStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { GlafiraCoreTraceStore(this) }

    /** The caller holds maintenance and pauses pending delivery before deleting project files. */
    @Synchronized
    internal fun deleteFinishedProject(projectId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT 1 FROM core_tasks WHERE project_id = ? AND state NOT IN ('succeeded','failed','cancelled') LIMIT 1",
                arrayOf(projectId)).use {
                check(!it.moveToFirst()) { "Сначала завершите или отмените задачи проекта в очереди Core." }
            }
            val traceExists = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(GlafiraCoreTraceStore.TABLE)
            ).use { it.moveToFirst() }
            if (traceExists) GlafiraCoreTraceStore.purgeProjectForOwnerDeletion(db, projectId)
            db.delete(GlafiraCoreOutboxSchema.TABLE, "project_id = ?", arrayOf(projectId))
            db.delete(TABLE_TASKS, "project_id = ?", arrayOf(projectId))
            db.delete("core_task_plans", "project_id = ?", arrayOf(projectId))
            val approvalsExist = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='core_action_approvals'", null)
                .use { it.moveToFirst() }
            if (approvalsExist) db.delete("core_action_approvals", "project_id = ?", arrayOf(projectId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE_TASKS (
                task_id TEXT PRIMARY KEY NOT NULL,
                project_id TEXT NOT NULL,
                task_type TEXT NOT NULL,
                payload_ciphertext TEXT NOT NULL,
                origin TEXT NOT NULL,
                priority INTEGER NOT NULL,
                state TEXT NOT NULL CHECK (state IN (
                    'queued', 'running', 'waiting_for_network', 'waiting_for_owner',
                    'retry_scheduled', 'succeeded', 'failed', 'cancelled'
                )),
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                attempt_count INTEGER NOT NULL,
                deadline_at INTEGER,
                idempotency_key TEXT NOT NULL,
                required_scopes TEXT NOT NULL,
                retry_at INTEGER,
                last_error TEXT
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX index_glafira_tasks_project_idempotency " +
                "ON $TABLE_TASKS(project_id, idempotency_key)"
        )
        db.execSQL(
            "CREATE INDEX index_glafira_tasks_state_priority " +
                "ON $TABLE_TASKS(state, priority DESC, created_at ASC)"
        )
        createDesktopTables(db)
        GlafiraDryRunPreviewSchema.create(db)
        createHistorySchema(db)
        createCoreTaskResultsSchema(db)
        createTaskDependenciesSchema(db)
        createTaskPlansSchema(db)
        createCoreTaskOwnershipSchema(db)
        GlafiraCoreLiveRunStore.createSchema(db)
        GlafiraCoreTraceStore.createSchema(db)
        GlafiraCoreOutboxSchema.create(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDesktopTables(db)
        if (oldVersion < 3) GlafiraDryRunPreviewSchema.create(db)
        if (oldVersion < 4) createHistorySchema(db)
        if (oldVersion < 5) createCoreTaskResultsSchema(db)
        if (oldVersion < 6) createTaskDependenciesSchema(db)
        if (oldVersion < 7) createTaskPlansSchema(db)
        if (oldVersion < 8) migrateDesktopLeaseFencing(db)
        if (oldVersion < 9) GlafiraCoreLiveRunStore.createSchema(db)
        if (oldVersion < 10) GlafiraCoreTraceStore.createSchema(db)
        if (oldVersion < 11) migrateCoreTaskOwnership(db)
        if (oldVersion < 12) GlafiraCoreOutboxSchema.create(db)
        if (newVersion > 12) throw IllegalStateException("A reviewed GLAFIRA task-queue migration is required.")
    }

    private fun createTaskDependenciesSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_DEPENDENCIES (
            task_id TEXT NOT NULL REFERENCES $TABLE_TASKS(task_id) ON DELETE CASCADE,
            depends_on_task_id TEXT NOT NULL REFERENCES $TABLE_TASKS(task_id) ON DELETE CASCADE,
            created_at INTEGER NOT NULL,
            PRIMARY KEY(task_id, depends_on_task_id),
            CHECK(task_id != depends_on_task_id)
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_core_task_dependencies_predecessor ON $TABLE_DEPENDENCIES(depends_on_task_id, task_id)")
    }

    /** Triggers also cover state writes by same-database approval/result adapters. */
    private fun createHistorySchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS core_task_history (
            event_id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id TEXT NOT NULL REFERENCES core_tasks(task_id) ON DELETE CASCADE,
            project_id TEXT NOT NULL, from_state TEXT, to_state TEXT NOT NULL,
            changed_at INTEGER NOT NULL, attempt_count INTEGER NOT NULL, reason TEXT
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_core_task_history_task ON core_task_history(task_id,event_id)")
        // A migration can preserve only the current state, never invent earlier events.
        db.execSQL("""INSERT INTO core_task_history(task_id,project_id,from_state,to_state,changed_at,attempt_count,reason)
            SELECT task_id,project_id,NULL,state,updated_at,attempt_count,'HISTORY_BASELINE'
            FROM core_tasks WHERE NOT EXISTS
            (SELECT 1 FROM core_task_history h WHERE h.task_id = core_tasks.task_id)""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS core_task_history_insert AFTER INSERT ON core_tasks BEGIN
            INSERT INTO core_task_history(task_id,project_id,from_state,to_state,changed_at,attempt_count,reason)
            VALUES(NEW.task_id,NEW.project_id,NULL,NEW.state,NEW.updated_at,NEW.attempt_count,NEW.last_error);
        END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS core_task_history_transition AFTER UPDATE OF state ON core_tasks
            WHEN OLD.state != NEW.state BEGIN
            INSERT INTO core_task_history(task_id,project_id,from_state,to_state,changed_at,attempt_count,reason)
            VALUES(NEW.task_id,NEW.project_id,OLD.state,NEW.state,NEW.updated_at,NEW.attempt_count,NEW.last_error);
        END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS core_task_terminal_guard BEFORE UPDATE OF state ON core_tasks
            WHEN OLD.state IN ('succeeded','failed','cancelled') AND OLD.state != NEW.state BEGIN
            SELECT RAISE(ABORT,'TERMINAL_TASK_IMMUTABLE');
        END""")
    }

    private fun createCoreTaskOwnershipSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE_OWNERSHIP (
            task_id TEXT PRIMARY KEY NOT NULL REFERENCES $TABLE_TASKS(task_id) ON DELETE CASCADE,
            project_id TEXT NOT NULL,
            owner_core_id TEXT NOT NULL,
            owner_generation INTEGER NOT NULL CHECK(owner_generation > 0),
            state_version INTEGER NOT NULL DEFAULT 0 CHECK(state_version >= 0),
            payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256) = 64),
            idempotency_key TEXT NOT NULL,
            effect_risk TEXT NOT NULL CHECK(effect_risk IN (
                'READ_ONLY', 'DRAFT', 'EXTERNAL_SIDE_EFFECT', 'FINANCIAL', 'DESTRUCTIVE'
            )),
            approval_ref TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_core_task_ownership_project_owner ON $TABLE_OWNERSHIP(project_id, owner_core_id)")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS core_task_ownership_state_version
            AFTER UPDATE OF state ON $TABLE_TASKS
            WHEN OLD.state != NEW.state
            BEGIN
                UPDATE $TABLE_OWNERSHIP
                SET state_version = state_version + 1, updated_at = NEW.updated_at
                WHERE task_id = NEW.task_id;
            END""")
    }

    private fun migrateCoreTaskOwnership(db: SQLiteDatabase) {
        createCoreTaskOwnershipSchema(db)

        // v10 did not bind the task database to the noBackup installation identity.
        // After reinstall/restore, the current Core ID may be new; never infer that it
        // owns legacy rows. Quarantine interrupted work for explicit owner review.
        db.execSQL(
            "UPDATE $TABLE_TASKS SET state = ?, updated_at = ?, last_error = COALESCE(last_error, ?) " +
                "WHERE state NOT IN ('succeeded', 'failed', 'cancelled', ?)",
            arrayOf(
                GlafiraTaskState.WAITING_FOR_OWNER.databaseValue,
                System.currentTimeMillis(),
                "LEGACY_TASK_OWNER_REVIEW_REQUIRED",
                GlafiraTaskState.WAITING_FOR_OWNER.databaseValue
            )
        )
        // A legacy executor lease, paired-node record or approval grant is authority too.
        // None can be proven to belong to the current noBackup installation identity.
        db.delete("core_desktop_leases", null, null)
        db.delete("core_desktop_nodes", null, null)
        if (tableExists(db, "core_action_approvals")) db.delete("core_action_approvals", null, null)
        if (tableExists(db, GlafiraCoreLiveRunStore.TABLE)) {
            db.delete(GlafiraCoreLiveRunStore.TABLE, null, null)
        }
    }

    private fun tableExists(db: SQLiteDatabase, tableName: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName)
        ).use { it.moveToFirst() }

    private fun insertTaskOwnershipRecord(
        db: SQLiteDatabase,
        task: GlafiraTask,
        coreId: String
    ) {
        db.insertOrThrow(TABLE_OWNERSHIP, null, ContentValues().apply {
            put("task_id", task.taskId)
            put("project_id", task.projectId)
            put("owner_core_id", coreId)
            put("owner_generation", 1)
            put("state_version", 0)
            put("payload_sha256", sha256(task.payload))
            put("idempotency_key", task.idempotencyKey)
            put("effect_risk", effectRiskFor(task.taskType))
            putNull("approval_ref")
            put("created_at", task.createdAtMillis)
            put("updated_at", task.updatedAtMillis)
        })
    }

    private fun effectRiskFor(taskType: String): String =
        when (GlafiraToolRegistry.find(taskType)?.risk) {
            ToolRiskLevel.READ_ONLY -> "READ_ONLY"
            ToolRiskLevel.DRAFT -> "DRAFT"
            ToolRiskLevel.EXTERNAL_WRITE -> "EXTERNAL_SIDE_EFFECT"
            ToolRiskLevel.FINANCIAL -> "FINANCIAL"
            ToolRiskLevel.DESTRUCTIVE -> "DESTRUCTIVE"
            // Unknown tools are deliberately treated as destructive for ownership transfer.
            null -> "DESTRUCTIVE"
        }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun createTaskPlansSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS core_task_plans (
            plan_id TEXT PRIMARY KEY NOT NULL,
            project_id TEXT NOT NULL,
            idempotency_key TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            UNIQUE(project_id, idempotency_key)
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS core_task_plan_nodes (
            plan_id TEXT NOT NULL REFERENCES core_task_plans(plan_id) ON DELETE CASCADE,
            task_id TEXT NOT NULL UNIQUE REFERENCES $TABLE_TASKS(task_id) ON DELETE CASCADE,
            ordinal INTEGER NOT NULL,
            PRIMARY KEY(plan_id, task_id)
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_core_task_plans_project_updated ON core_task_plans(project_id, updated_at DESC)")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS core_task_plan_updated AFTER UPDATE OF state ON $TABLE_TASKS
            WHEN OLD.state != NEW.state BEGIN
            UPDATE core_task_plans SET updated_at = NEW.updated_at
            WHERE plan_id IN (SELECT plan_id FROM core_task_plan_nodes WHERE task_id = NEW.task_id);
        END""")
    }

    private fun createCoreTaskResultsSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE_CORE_RESULTS (
                task_id TEXT PRIMARY KEY NOT NULL REFERENCES $TABLE_TASKS(task_id) ON DELETE CASCADE,
                result_ciphertext TEXT NOT NULL,
                completed_at INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    private fun createDesktopTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS core_desktop_leases (task_id TEXT PRIMARY KEY NOT NULL REFERENCES $TABLE_TASKS(task_id) ON DELETE CASCADE, device_id TEXT NOT NULL, lease_id TEXT NOT NULL, fencing_token INTEGER NOT NULL CHECK(fencing_token > 0), lease_expires_at INTEGER NOT NULL, attempt_count INTEGER NOT NULL, progress_percent INTEGER NOT NULL DEFAULT 0, progress_message TEXT, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS core_desktop_results (task_id TEXT PRIMARY KEY NOT NULL REFERENCES $TABLE_TASKS(task_id) ON DELETE CASCADE, result_ciphertext TEXT, error_code TEXT, completed_at INTEGER NOT NULL, device_id TEXT, lease_id TEXT, fencing_token INTEGER, terminal_state TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_core_desktop_lease_device ON core_desktop_leases(device_id, lease_expires_at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS core_desktop_nodes (device_id TEXT PRIMARY KEY NOT NULL, last_seen_at INTEGER NOT NULL, capabilities_json TEXT NOT NULL, max_parallel_tasks INTEGER NOT NULL)")
    }

    private fun migrateDesktopLeaseFencing(db: SQLiteDatabase) {
        fun addColumn(table: String, column: String, declaration: String) {
            val present = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == column) found = true
                }
                found
            }
            if (!present) db.execSQL("ALTER TABLE $table ADD COLUMN $column $declaration")
        }
        addColumn("core_desktop_leases", "lease_id", "TEXT")
        addColumn("core_desktop_leases", "fencing_token", "INTEGER")
        db.execSQL("UPDATE core_desktop_leases SET lease_id = 'legacy-' || lower(hex(randomblob(16))) WHERE lease_id IS NULL")
        db.execSQL("UPDATE core_desktop_leases SET fencing_token = CASE WHEN attempt_count > 0 THEN attempt_count ELSE 1 END WHERE fencing_token IS NULL")
        addColumn("core_desktop_results", "device_id", "TEXT")
        addColumn("core_desktop_results", "lease_id", "TEXT")
        addColumn("core_desktop_results", "fencing_token", "INTEGER")
        addColumn("core_desktop_results", "terminal_state", "TEXT")
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Downgrading the GLAFIRA task database is not supported.")
    }

    @Synchronized
    override fun persistDryRunPreview(
        taskId: String,
        expectedStates: Set<GlafiraTaskState>,
        plan: GlafiraDryRunPlan,
        nowMillis: Long
    ): Boolean = persistDryRunPreviewInternal(null, taskId, expectedStates, plan, nowMillis)

    @Synchronized
    override fun persistDryRunPreviewOwned(
        token: GlafiraTaskOwnershipExecutionToken,
        expectedStates: Set<GlafiraTaskState>,
        plan: GlafiraDryRunPlan,
        nowMillis: Long
    ): Boolean = persistDryRunPreviewInternal(token, token.taskId, expectedStates, plan, nowMillis)

    private fun persistDryRunPreviewInternal(
        token: GlafiraTaskOwnershipExecutionToken?,
        taskId: String,
        expectedStates: Set<GlafiraTaskState>,
        plan: GlafiraDryRunPlan,
        nowMillis: Long
    ): Boolean {
        require(taskId == plan.taskId && taskId.isNotBlank())
        require(plan.planHash.matches(Regex("[0-9a-f]{64}")))
        require(expectedStates.isNotEmpty())
        require(expectedStates.all { it in setOf(GlafiraTaskState.QUEUED, GlafiraTaskState.RUNNING) })
        val json = plan.toSafeJson()
        require(json.toByteArray(Charsets.UTF_8).size <= 16_384)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (token != null && (token.taskId != taskId || token.ownerCoreId != ownerCoreId)) return false
            if (ownerCoreId != null) {
                val currentOwner = db.query(TABLE_OWNERSHIP, arrayOf("owner_core_id", "owner_generation", "state_version"),
                    "task_id = ?", arrayOf(taskId), null, null, null, "1").use { cursor ->
                    if (!cursor.moveToFirst()) null else Triple(cursor.getString(0), cursor.getLong(1), cursor.getLong(2))
                } ?: return false
                if (currentOwner.first != ownerCoreId ||
                    (token != null && (currentOwner.second != token.ownerGeneration ||
                        currentOwner.third != token.stateVersion))
                ) return false
            }
            val existingHash = db.query(
                GlafiraDryRunPreviewSchema.TABLE, arrayOf("plan_hash"),
                "task_id = ?", arrayOf(taskId), null, null, null, "1"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (existingHash != null) {
                check(existingHash == plan.planHash) { "DRY_RUN_PLAN_HASH_CONFLICT" }
                val task = queryTask(db, taskId) ?: return false
                if (task.projectId != plan.projectId || task.taskType != plan.toolId ||
                    task.state !in expectedStates
                ) return false
                val placeholders = expectedStates.joinToString(",") { "?" }
                val changed = db.update(
                    TABLE_TASKS,
                    ContentValues().apply {
                        put("state", GlafiraTaskState.WAITING_FOR_OWNER.databaseValue)
                        put("updated_at", nowMillis)
                        put("last_error", "DRY_RUN_PREVIEW_STORED")
                    },
                    "task_id = ? AND state IN ($placeholders)" +
                        if (token != null) " AND EXISTS (SELECT 1 FROM $TABLE_OWNERSHIP own WHERE own.task_id = $TABLE_TASKS.task_id AND own.owner_core_id = ? AND own.owner_generation = ? AND own.state_version = ?)"
                        else if (ownerCoreId != null) " AND EXISTS (SELECT 1 FROM $TABLE_OWNERSHIP own WHERE own.task_id = $TABLE_TASKS.task_id AND own.owner_core_id = ?)"
                        else "",
                    arrayOf(taskId) + expectedStates.map { it.databaseValue }.toTypedArray() +
                        if (token != null) arrayOf(token.ownerCoreId, token.ownerGeneration.toString(), token.stateVersion.toString())
                        else if (ownerCoreId != null) arrayOf(ownerCoreId) else emptyArray()
                )
                if (changed != 1) return false
                db.update(
                    GlafiraDryRunPreviewSchema.TABLE,
                    ContentValues().apply {
                        put("review_status", GlafiraDryRunReviewStatus.PENDING.name)
                        putNull("reviewed_at")
                        put("created_at", nowMillis)
                    },
                    "task_id = ? AND plan_hash = ?",
                    arrayOf(taskId, plan.planHash)
                )
                coreTraceStore.appendInTransaction(
                    db,
                    GlafiraCoreTraceDraft(
                        projectId = task.projectId,
                        taskId = task.taskId,
                        correlationId = task.taskId,
                        occurredAtMillis = nowMillis,
                        actorType = GlafiraCoreTraceActorType.CORE,
                        actorId = "core",
                        component = "CoreDryRunPlanner",
                        eventType = GlafiraCoreTraceEventType.POLICY_CHECKED,
                        actionCode = "DRY_RUN_PREVIEW_STORED",
                        resultStatus = plan.simulationStatus.name
                    )
                )
                db.setTransactionSuccessful()
                return true
            }
            val task = queryTask(db, taskId) ?: return false
            if (task.projectId != plan.projectId || task.taskType != plan.toolId ||
                task.state !in expectedStates
            ) return false
            val placeholders = expectedStates.joinToString(",") { "?" }
            val changed = db.update(
                TABLE_TASKS,
                ContentValues().apply {
                    put("state", GlafiraTaskState.WAITING_FOR_OWNER.databaseValue)
                    put("updated_at", nowMillis)
                    put("last_error", "DRY_RUN_PREVIEW_STORED")
                },
                "task_id = ? AND state IN ($placeholders)" +
                        if (token != null) " AND EXISTS (SELECT 1 FROM $TABLE_OWNERSHIP own WHERE own.task_id = $TABLE_TASKS.task_id AND own.owner_core_id = ? AND own.owner_generation = ?)"
                        else if (ownerCoreId != null) " AND EXISTS (SELECT 1 FROM $TABLE_OWNERSHIP own WHERE own.task_id = $TABLE_TASKS.task_id AND own.owner_core_id = ?)"
                        else "",
                    arrayOf(taskId) + expectedStates.map { it.databaseValue }.toTypedArray() +
                        if (token != null) arrayOf(token.ownerCoreId, token.ownerGeneration.toString())
                        else if (ownerCoreId != null) arrayOf(ownerCoreId) else emptyArray()
            )
            if (changed != 1) return false
            val inserted = db.insertOrThrow(
                GlafiraDryRunPreviewSchema.TABLE,
                null,
                ContentValues().apply {
                    put("task_id", taskId)
                    put("project_id", plan.projectId)
                    put("tool_id", plan.toolId)
                    put("risk", plan.risk.name)
                    put("simulation_status", plan.simulationStatus.name)
                    put("plan_hash", plan.planHash)
                    put("plan_ciphertext", GlafiraTaskPayloadCrypto.encrypt(
                        json, taskId + "|dry-run-preview|" + plan.projectId
                    ))
                    put("created_at", nowMillis)
                    put("review_status", GlafiraDryRunReviewStatus.PENDING.name)
                    putNull("reviewed_at")
                }
            )
            check(inserted != -1L)
            coreTraceStore.appendInTransaction(
                db,
                GlafiraCoreTraceDraft(
                    projectId = task.projectId,
                    taskId = task.taskId,
                    correlationId = task.taskId,
                    occurredAtMillis = nowMillis,
                    actorType = GlafiraCoreTraceActorType.CORE,
                    actorId = "core",
                    component = "CoreDryRunPlanner",
                    eventType = GlafiraCoreTraceEventType.POLICY_CHECKED,
                    actionCode = "DRY_RUN_PREVIEW_STORED",
                    resultStatus = plan.simulationStatus.name
                )
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun dryRunPreview(taskId: String): GlafiraDryRunPreviewRecord? =
        readableDatabase.query(
            GlafiraDryRunPreviewSchema.TABLE, null, "task_id = ?",
            arrayOf(taskId), null, null, null, "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toDryRunPreviewRecord() else null }

    @Synchronized
    fun dryRunPreviews(
        limit: Int = 100,
        includeReviewed: Boolean = true
    ): List<GlafiraDryRunPreviewRecord> {
        require(limit in 1..500)
        // Failed/cancelled tasks are no longer actionable review items. Filter them in
        // SQL before LIMIT so stale previews cannot crowd out valid pending approvals.
        val selection = if (includeReviewed) null else
            "review_status = ? AND EXISTS (SELECT 1 FROM $TABLE_TASKS task " +
                "WHERE task.task_id = ${GlafiraDryRunPreviewSchema.TABLE}.task_id " +
                "AND task.project_id = ${GlafiraDryRunPreviewSchema.TABLE}.project_id " +
                "AND task.state NOT IN (?, ?))"
        val args = if (includeReviewed) null else arrayOf(
            GlafiraDryRunReviewStatus.PENDING.name,
            GlafiraTaskState.FAILED.databaseValue,
            GlafiraTaskState.CANCELLED.databaseValue
        )
        return readableDatabase.query(
            GlafiraDryRunPreviewSchema.TABLE, null, selection, args, null, null,
            "created_at DESC", limit.toString()
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toDryRunPreviewRecord()) }
        }
    }

    @Synchronized
    fun reviewDryRunPreview(
        taskId: String,
        expectedPlanHash: String,
        decision: GlafiraDryRunReviewStatus,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        require(decision != GlafiraDryRunReviewStatus.PENDING)
        require(expectedPlanHash.matches(Regex("[0-9a-f]{64}")))
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previewProjectId = db.query(
                GlafiraDryRunPreviewSchema.TABLE,
                arrayOf("project_id"),
                "task_id = ? AND plan_hash = ? AND review_status = ?",
                arrayOf(taskId, expectedPlanHash, GlafiraDryRunReviewStatus.PENDING.name),
                null, null, null, "1"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: return false
            val task = queryTask(db, taskId, previewProjectId) ?: return false
            if (task.state == GlafiraTaskState.CANCELLED || task.state == GlafiraTaskState.FAILED) return false
            val changed = db.update(
                GlafiraDryRunPreviewSchema.TABLE,
                ContentValues().apply {
                    put("review_status", decision.name)
                    put("reviewed_at", nowMillis)
                },
                "task_id = ? AND plan_hash = ? AND review_status = ?",
                arrayOf(taskId, expectedPlanHash, GlafiraDryRunReviewStatus.PENDING.name)
            )
            if (changed != 1) return false
            coreTraceStore.appendInTransaction(
                db,
                GlafiraCoreTraceDraft(
                    projectId = task.projectId,
                    taskId = task.taskId,
                    correlationId = task.taskId,
                    occurredAtMillis = nowMillis,
                    actorType = GlafiraCoreTraceActorType.OWNER,
                    actorId = "owner",
                    component = "CoreDryRunReview",
                    eventType = if (decision == GlafiraDryRunReviewStatus.ACKNOWLEDGED) {
                        GlafiraCoreTraceEventType.REVIEW_ACCEPTED
                    } else {
                        GlafiraCoreTraceEventType.REVIEW_REJECTED
                    },
                    actionCode = if (decision == GlafiraDryRunReviewStatus.ACKNOWLEDGED) {
                        "DRY_RUN_REVIEW_ACCEPTED"
                    } else {
                        "DRY_RUN_REVIEW_REJECTED"
                    },
                    targetType = "DRY_RUN_PREVIEW",
                    targetId = task.taskId,
                    resultStatus = decision.name
                )
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.toDryRunPreviewRecord(): GlafiraDryRunPreviewRecord {
        val taskId = getString(getColumnIndexOrThrow("task_id"))
        val projectId = getString(getColumnIndexOrThrow("project_id"))
        val reviewedIndex = getColumnIndexOrThrow("reviewed_at")
        return GlafiraDryRunPreviewRecord(
            taskId = taskId,
            projectId = projectId,
            toolId = getString(getColumnIndexOrThrow("tool_id")),
            risk = getString(getColumnIndexOrThrow("risk")),
            simulationStatus = getString(getColumnIndexOrThrow("simulation_status")),
            planHash = getString(getColumnIndexOrThrow("plan_hash")),
            createdAtMillis = getLong(getColumnIndexOrThrow("created_at")),
            reviewStatus = GlafiraDryRunReviewStatus.valueOf(
                getString(getColumnIndexOrThrow("review_status"))
            ),
            reviewedAtMillis = if (isNull(reviewedIndex)) null else getLong(reviewedIndex),
            planJson = GlafiraTaskPayloadCrypto.decrypt(
                getString(getColumnIndexOrThrow("plan_ciphertext")),
                taskId + "|dry-run-preview|" + projectId
            )
        )
    }

    @Synchronized
    override fun enqueue(task: GlafiraTask): GlafiraTaskEnqueueResult {
        GlafiraTaskPolicy.validate(task)
        val db = writableDatabase
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            val insertedId = db.insertWithOnConflict(
                TABLE_TASKS,
                null,
                task.toContentValues(),
                SQLiteDatabase.CONFLICT_IGNORE
            )
            if (insertedId != -1L) {
                ownerCoreId?.let { insertTaskOwnershipRecord(db, task, it) }
                if (ownsTransaction) db.setTransactionSuccessful()
                return GlafiraTaskEnqueueResult(task, true)
            }

            val existing = db.query(
                TABLE_TASKS,
                null,
                "project_id = ? AND idempotency_key = ?",
                arrayOf(task.projectId, task.idempotencyKey),
                null, null, null, "1"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toTask() else null }
                ?: throw IllegalStateException("Task ID already exists with a different idempotency key.")
            if (ownsTransaction) db.setTransactionSuccessful()
            return GlafiraTaskEnqueueResult(existing, false)
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    /** Adds predecessor edges to an existing queued task atomically. */
    @Synchronized
    fun addTaskDependencies(
        taskId: String,
        dependencyTaskIds: Set<String>,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        require(taskId.isNotBlank() && nowMillis > 0L)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val task = queryTask(db, taskId) ?: return false
            if (task.state != GlafiraTaskState.QUEUED) return false
            attachTaskDependencies(db, task, dependencyTaskIds, nowMillis)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** Inserts a task and its predecessor graph in one transaction, preventing early claims. */
    @Synchronized
    fun enqueueWithDependencies(
        task: GlafiraTask,
        dependencyTaskIds: Set<String>,
        nowMillis: Long = System.currentTimeMillis()
    ): GlafiraTaskEnqueueResult {
        GlafiraTaskPolicy.validate(task)
        require(nowMillis > 0L)
        require(dependencyTaskIds.size <= GlafiraTaskDependencyPolicy.MAX_DEPENDENCIES_PER_TASK)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = enqueue(task)
            if (result.inserted) {
                val insertedTask = queryTask(db, task.taskId)
                    ?: throw IllegalStateException("Inserted Core task could not be read back.")
                attachTaskDependencies(db, insertedTask, dependencyTaskIds, nowMillis)
            } else {
                val existing = db.query(
                    TABLE_DEPENDENCIES, arrayOf("depends_on_task_id"),
                    "task_id = ?", arrayOf(result.task.taskId), null, null, null
                ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                require(existing == dependencyTaskIds) { "IDEMPOTENCY_DEPENDENCY_CONFLICT" }
            }
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun attachTaskDependencies(
        db: SQLiteDatabase,
        task: GlafiraTask,
        dependencyTaskIds: Set<String>,
        nowMillis: Long
    ) {
        require(dependencyTaskIds.size <= GlafiraTaskDependencyPolicy.MAX_DEPENDENCIES_PER_TASK)
        val existing = db.query(
            TABLE_DEPENDENCIES, arrayOf("depends_on_task_id"),
            "task_id = ?", arrayOf(task.taskId), null, null, null
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val merged = existing + dependencyTaskIds
        val graph = mutableMapOf<String, MutableSet<String>>()
        db.query(TABLE_DEPENDENCIES, arrayOf("task_id", "depends_on_task_id"),
            null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                graph.getOrPut(cursor.getString(0)) { mutableSetOf() }.add(cursor.getString(1))
            }
        }
        GlafiraTaskDependencyPolicy.validate(task.taskId, merged, graph)
        for (dependencyId in dependencyTaskIds) {
            val predecessor = queryTask(db, dependencyId)
                ?: throw IllegalArgumentException("A task dependency must reference an existing task.")
            require(predecessor.projectId == task.projectId) {
                "Task dependencies cannot cross project boundaries."
            }
            if (dependencyId !in existing) {
                db.insertOrThrow(TABLE_DEPENDENCIES, null, ContentValues().apply {
                    put("task_id", task.taskId)
                    put("depends_on_task_id", dependencyId)
                    put("created_at", nowMillis)
                })
            }
        }
    }

    @Synchronized
    fun enqueuePlan(plan: GlafiraCoreTaskPlan): GlafiraCoreTaskPlanEnqueueResult {
        GlafiraCoreTaskPlanPolicy.validate(plan)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existingPlan = db.query(
                "core_task_plans", null, "project_id = ? AND idempotency_key = ?",
                arrayOf(plan.projectId, plan.idempotencyKey), null, null, null, "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(cursor.getColumnIndexOrThrow("plan_id"))
            }
            if (existingPlan != null) {
                require(existingPlan == plan.planId) { "TASK_PLAN_IDEMPOTENCY_CONFLICT" }
                val existingRecord = readTaskPlan(db, plan.planId, plan.projectId)
                    ?: throw IllegalStateException("Persisted task plan could not be read.")
                require(existingRecord.createdAtMillis == plan.createdAtMillis) {
                    "TASK_PLAN_CONTENT_CONFLICT"
                }
                val existingNodes = existingRecord.nodes.associateBy { it.taskId }
                val expectedIds = plan.nodes.map { it.task.taskId }
                require(existingRecord.nodes.map { it.taskId } == expectedIds) {
                    "TASK_PLAN_CONTENT_CONFLICT"
                }
                plan.nodes.forEach { node ->
                    val existingTask = existingNodes[node.task.taskId]
                        ?: throw IllegalStateException("TASK_PLAN_NODE_MISSING")
                    require(
                        existingTask.projectId == node.task.projectId &&
                        existingTask.taskType == node.task.taskType &&
                        existingTask.payload == node.task.payload &&
                        existingTask.origin == node.task.origin &&
                        existingTask.priority == node.task.priority &&
                        existingTask.createdAtMillis == node.task.createdAtMillis &&
                        existingTask.deadlineAtMillis == node.task.deadlineAtMillis &&
                        existingTask.idempotencyKey == node.task.idempotencyKey &&
                        existingTask.requiredScopes == node.task.requiredScopes
                    ) { "TASK_PLAN_CONTENT_CONFLICT" }
                    val actual = db.query(TABLE_DEPENDENCIES, arrayOf("depends_on_task_id"),
                        "task_id = ?", arrayOf(node.task.taskId), null, null, null
                    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                    require(actual == node.dependsOnTaskIds) { "TASK_PLAN_DEPENDENCY_CONFLICT" }
                }
                db.setTransactionSuccessful()
                return GlafiraCoreTaskPlanEnqueueResult(existingRecord, false)
            }

            db.insertOrThrow("core_task_plans", null, ContentValues().apply {
                put("plan_id", plan.planId)
                put("project_id", plan.projectId)
                put("idempotency_key", plan.idempotencyKey)
                put("created_at", plan.createdAtMillis)
                put("updated_at", plan.createdAtMillis)
            })

            // Insert every node before attaching edges, allowing forward references in a DAG.
            plan.nodes.forEachIndexed { index, node ->
                val result = enqueue(node.task)
                require(result.inserted) { "TASK_PLAN_NODE_ALREADY_EXISTS:" + node.task.taskId }
                db.insertOrThrow("core_task_plan_nodes", null, ContentValues().apply {
                    put("plan_id", plan.planId)
                    put("task_id", node.task.taskId)
                    put("ordinal", index)
                })
            }
            plan.nodes.forEach { node ->
                val persisted = queryTask(db, node.task.taskId)
                    ?: throw IllegalStateException("Task plan node disappeared during insertion.")
                attachTaskDependencies(db, persisted, node.dependsOnTaskIds, plan.createdAtMillis)
            }
            db.setTransactionSuccessful()
            val record = readTaskPlan(db, plan.planId, plan.projectId)
                ?: throw IllegalStateException("Committed task plan could not be read.")
            return GlafiraCoreTaskPlanEnqueueResult(record, true)
        } finally {
            db.endTransaction()
        }
    }

    /** Recent orchestration journal for one project, newest plans first. */
    @Synchronized
    fun taskPlans(projectId: String, limit: Int = 100): List<GlafiraCoreTaskPlanRecord> {
        require(projectId.isNotBlank() && limit in 1..500)
        return readableDatabase.query(
            "core_task_plans", arrayOf("plan_id"), "project_id = ?",
            arrayOf(projectId), null, null, "updated_at DESC, created_at DESC", limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    readTaskPlan(readableDatabase, cursor.getString(0), projectId)?.let(::add)
                }
            }
        }
    }

    /** Combined plan/node state history and decrypted result journal, project scoped. */
    @Synchronized
    fun taskPlanJournal(planId: String, projectId: String): GlafiraCoreTaskPlanJournal? {
        val plan = taskPlan(planId, projectId) ?: return null
        val nodes = plan.nodes.map { task ->
            val coreResult = coreTaskResult(task.taskId, projectId)?.result
            val desktopResult = if (coreResult == null) desktopTaskResult(task.taskId) else null
            GlafiraCoreTaskPlanNodeJournal(
                task = task,
                history = taskHistory(task.taskId, projectId, 500),
                result = coreResult ?: desktopResult
            )
        }
        return GlafiraCoreTaskPlanJournal(plan, nodes)
    }

    /** Returns the durable plan graph and current task states, scoped to its project. */
    @Synchronized
    fun taskPlan(planId: String, projectId: String): GlafiraCoreTaskPlanRecord? {
        require(planId.isNotBlank() && projectId.isNotBlank())
        return readTaskPlan(readableDatabase, planId, projectId)
    }

    private fun readTaskPlan(
        db: SQLiteDatabase,
        planId: String,
        projectId: String
    ): GlafiraCoreTaskPlanRecord? {
        val header = db.query("core_task_plans", null,
            "plan_id = ? AND project_id = ?", arrayOf(planId, projectId),
            null, null, null, "1").use { cursor ->
            if (!cursor.moveToFirst()) null else Triple(
                cursor.getString(cursor.getColumnIndexOrThrow("idempotency_key")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
            )
        } ?: return null
        val tasks = db.rawQuery(
            "SELECT t.* FROM $TABLE_TASKS t JOIN core_task_plan_nodes n ON n.task_id = t.task_id " +
                "WHERE n.plan_id = ? ORDER BY n.ordinal ASC",
            arrayOf(planId)
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTask()) } }
        val dependencies = db.rawQuery(
            "SELECT d.task_id, d.depends_on_task_id FROM $TABLE_DEPENDENCIES d " +
                "JOIN core_task_plan_nodes n ON n.task_id = d.task_id WHERE n.plan_id = ?",
            arrayOf(planId)
        ).use { cursor ->
            buildMap<String, MutableSet<String>> {
                while (cursor.moveToNext()) {
                    getOrPut(cursor.getString(0)) { mutableSetOf() }.add(cursor.getString(1))
                }
            }.mapValues { it.value.toSet() }
        }
        return GlafiraCoreTaskPlanRecord(
            planId, projectId, header.first, header.second, header.third, tasks, dependencies
        )
    }

    /** Returns direct predecessors for diagnostics and dependency-aware scheduling. */
    @Synchronized
    fun taskDependencies(taskId: String): Set<String> = readableDatabase.query(
        TABLE_DEPENDENCIES, arrayOf("depends_on_task_id"),
        "task_id = ?", arrayOf(taskId), null, null, null
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    @Synchronized
    override fun getTask(taskId: String): GlafiraTask? =
        readableDatabase.query(
            TABLE_TASKS,
            null,
            "task_id = ?",
            arrayOf(taskId),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toTask() else null }

    @Synchronized
    fun listTasks(
        states: Set<GlafiraTaskState> = emptySet(),
        limit: Int = 100,
        projectId: String? = null
    ): List<GlafiraTask> {
        require(limit in 1..500) { "Task list limit must be between 1 and 500." }
        require(projectId == null || projectId.isNotBlank())
        val predicates = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        if (states.isNotEmpty()) {
            predicates += "state IN (" + states.joinToString(",") { "?" } + ")"
            arguments += states.map { it.databaseValue }
        }
        if (projectId != null) {
            predicates += "project_id = ?"
            arguments += projectId
        }
        val selection = predicates.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val args = arguments.takeIf { it.isNotEmpty() }?.toTypedArray()
        return readableDatabase.query(
            TABLE_TASKS,
            null,
            selection,
            args,
            null,
            null,
            "priority DESC, created_at ASC",
            limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toTask())
            }
        }
    }

    /** Scoped status lookup for callers with a project authorization boundary. */
    @Synchronized
    fun getTask(taskId: String, projectId: String): GlafiraTask? {
        require(projectId.isNotBlank())
        return readableDatabase.query(TABLE_TASKS, null, "task_id = ? AND project_id = ?",
            arrayOf(taskId, projectId), null, null, null, "1"
        ).use { if (it.moveToFirst()) it.toTask() else null }
    }

    /** Project-scoped canonical owner record; deliberately separate from Desktop/Agent leases. */
    @Synchronized
    fun taskOwnership(taskId: String, projectId: String): GlafiraTaskOwnershipRecord? {
        require(taskId.isNotBlank() && projectId.isNotBlank())
        return readableDatabase.query(
            TABLE_OWNERSHIP,
            null,
            "task_id = ? AND project_id = ?",
            arrayOf(taskId, projectId),
            null, null, null, "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else GlafiraTaskOwnershipRecord(
                taskId = taskId,
                projectId = projectId,
                ownerCoreId = cursor.getString(cursor.getColumnIndexOrThrow("owner_core_id")),
                ownerGeneration = cursor.getLong(cursor.getColumnIndexOrThrow("owner_generation")),
                stateVersion = cursor.getLong(cursor.getColumnIndexOrThrow("state_version")),
                payloadSha256 = cursor.getString(cursor.getColumnIndexOrThrow("payload_sha256")),
                idempotencyKey = cursor.getString(cursor.getColumnIndexOrThrow("idempotency_key")),
                effectRisk = cursor.getString(cursor.getColumnIndexOrThrow("effect_risk")),
                approvalRef = cursor.getColumnIndexOrThrow("approval_ref").let {
                    if (cursor.isNull(it)) null else cursor.getString(it)
                },
                createdAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                updatedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
            )
        }
    }

    @Synchronized
    fun taskHistory(taskId: String, projectId: String, limit: Int = 100): List<GlafiraTaskStateEvent> {
        require(projectId.isNotBlank() && limit in 1..500)
        return readableDatabase.query("core_task_history", null, "task_id = ? AND project_id = ?",
            arrayOf(taskId, projectId), null, null, "event_id DESC", limit.toString()
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(GlafiraTaskStateEvent(
                eventId = cursor.getLong(cursor.getColumnIndexOrThrow("event_id")),
                taskId = taskId, projectId = projectId,
                fromState = cursor.getColumnIndexOrThrow("from_state").let {
                    if (cursor.isNull(it)) null else GlafiraTaskState.fromDatabaseValue(cursor.getString(it))
                },
                toState = GlafiraTaskState.fromDatabaseValue(cursor.getString(cursor.getColumnIndexOrThrow("to_state"))),
                changedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("changed_at")),
                attemptCount = cursor.getInt(cursor.getColumnIndexOrThrow("attempt_count")),
                reason = cursor.getColumnIndexOrThrow("reason").let { if (cursor.isNull(it)) null else cursor.getString(it) }
            ))
        } }
    }

    /** Called only from WorkManager's connected-network wakeup, never from claimNext. */
    @Synchronized
    fun resumeWaitingForNetwork(nowMillis: Long = System.currentTimeMillis()): Int {
        val ownerClause = if (ownerCoreId == null) "" else
            " AND task_id IN (SELECT task_id FROM $TABLE_OWNERSHIP WHERE owner_core_id = ? AND owner_generation > 0 AND state_version >= 0)"
        val args = arrayOf(GlafiraTaskState.WAITING_FOR_NETWORK.databaseValue, DESKTOP_TASK_TYPE) +
            if (ownerCoreId == null) emptyArray() else arrayOf(ownerCoreId)
        return writableDatabase.update(TABLE_TASKS, ContentValues().apply {
            put("state", GlafiraTaskState.QUEUED.databaseValue)
            put("updated_at", nowMillis)
            putNull("retry_at")
            put("last_error", "NETWORK_AVAILABLE")
        }, "state = ? AND task_type != ?$ownerClause", args)
    }

    @Synchronized
    fun hasWaitingForNetworkTasks(): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM core_tasks WHERE state = ? AND task_type != ? LIMIT 1",
        arrayOf(GlafiraTaskState.WAITING_FOR_NETWORK.databaseValue, DESKTOP_TASK_TYPE)
    ).use { it.moveToFirst() }

    @Synchronized
    fun nextRetryAtMillis(): Long? = readableDatabase.rawQuery(
        "SELECT MIN(retry_at) FROM core_tasks WHERE state = ? AND task_type != ?",
        arrayOf(GlafiraTaskState.RETRY_SCHEDULED.databaseValue, DESKTOP_TASK_TYPE)
    ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    private fun expirePendingTasks(db: SQLiteDatabase, nowMillis: Long) {
        val ownerClause = if (ownerCoreId == null) "" else
            " AND task_id IN (SELECT task_id FROM $TABLE_OWNERSHIP WHERE owner_core_id = ? " +
            "AND owner_generation > 0 AND state_version >= 0)"
        val args = arrayOf(nowMillis.toString()) +
            if (ownerCoreId == null) emptyArray() else arrayOf(ownerCoreId)
        db.update(TABLE_TASKS, ContentValues().apply {
            put("state", GlafiraTaskState.FAILED.databaseValue)
            put("updated_at", nowMillis)
            putNull("retry_at")
            put("last_error", "TASK_DEADLINE_EXPIRED")
        }, "state IN ('queued','waiting_for_network','waiting_for_owner','retry_scheduled') AND deadline_at <= ? " +
            "AND NOT (COALESCE(last_error,'') IN ('CORE_EXECUTION_OUTCOME_UNKNOWN','DESKTOP_EXECUTION_OUTCOME_UNKNOWN','EXECUTION_OUTCOME_UNCERTAIN') " +
            "OR (COALESCE(last_error,'') GLOB 'CORE_RESULT_PERSIST_FAILED:*' AND EXISTS (" +
            "SELECT 1 FROM $TABLE_OWNERSHIP own_risk WHERE own_risk.task_id = $TABLE_TASKS.task_id " +
            "AND own_risk.effect_risk IN ('EXTERNAL_SIDE_EFFECT','FINANCIAL','DESTRUCTIVE'))))$ownerClause",
            args)
    }

    /**
     * Atomically claims one eligible task and increments its attempt count.
     * A worker crash after this point is recovered by a later Core recovery pass.
     */
    @Synchronized
    override fun claimNext(nowMillis: Long): GlafiraTask? =
        claimNextInternal(nowMillis, requireOwnershipToken = false)?.task

    @Synchronized
    override fun claimNextOwned(nowMillis: Long): GlafiraOwnedTaskClaim? {
        if (ownerCoreId == null) return null
        return claimNextInternal(nowMillis, requireOwnershipToken = true)
    }

    /** Claim and capture owner generation in the same SQLite transaction. */
    private fun claimNextInternal(
        nowMillis: Long,
        requireOwnershipToken: Boolean
    ): GlafiraOwnedTaskClaim? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            expirePendingTasks(db, nowMillis)
            failTasksWithFailedDependencies(db, nowMillis)
            val taskId = db.rawQuery(
                "SELECT task_id FROM $TABLE_TASKS t " +
                    "WHERE state IN (?, ?) AND task_type != ? " +
                    "AND (retry_at IS NULL OR retry_at <= ?) " +
                    "AND (deadline_at IS NULL OR deadline_at > ?) " +
                    "AND (? = '' OR EXISTS (SELECT 1 FROM $TABLE_OWNERSHIP own " +
                    "WHERE own.task_id = t.task_id AND own.owner_core_id = ?)) " +
                    "AND NOT (COALESCE(t.last_error,'') IN ('CORE_EXECUTION_OUTCOME_UNKNOWN','DESKTOP_EXECUTION_OUTCOME_UNKNOWN','EXECUTION_OUTCOME_UNCERTAIN') " +
                    "OR (COALESCE(t.last_error,'') GLOB 'CORE_RESULT_PERSIST_FAILED:*' AND EXISTS (" +
                    "SELECT 1 FROM $TABLE_OWNERSHIP own_risk WHERE own_risk.task_id = t.task_id " +
                    "AND own_risk.effect_risk IN ('EXTERNAL_SIDE_EFFECT','FINANCIAL','DESTRUCTIVE')))) " +
                    "AND NOT EXISTS (SELECT 1 FROM $TABLE_DEPENDENCIES d " +
                    "JOIN $TABLE_TASKS p ON p.task_id = d.depends_on_task_id " +
                    "WHERE d.task_id = t.task_id AND p.state != ?) " +
                    "ORDER BY " +
                    "(SELECT COUNT(*) FROM $TABLE_TASKS active " +
                    "WHERE active.project_id = t.project_id AND active.state = ?) ASC, " +
                    "COALESCE((SELECT MAX(h.changed_at) FROM core_task_history h " +
                    "WHERE h.project_id = t.project_id AND h.to_state = ?), 0) ASC, " +
                    "priority DESC, created_at ASC LIMIT 1",
                arrayOf(
                    GlafiraTaskState.QUEUED.databaseValue,
                    GlafiraTaskState.RETRY_SCHEDULED.databaseValue,
                    DESKTOP_TASK_TYPE,
                    nowMillis.toString(),
                    nowMillis.toString(),
                    ownerCoreId.orEmpty(),
                    ownerCoreId.orEmpty(),
                    GlafiraTaskState.SUCCEEDED.databaseValue,
                    GlafiraTaskState.RUNNING.databaseValue,
                    GlafiraTaskState.RUNNING.databaseValue
                )
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

            if (taskId == null) {
                db.setTransactionSuccessful()
                return null
            }

            val candidate = queryTask(db, taskId)
                ?: throw IllegalStateException("Claim candidate disappeared inside a transaction.")
            val changed = db.update(
                TABLE_TASKS,
                ContentValues().apply {
                    put("state", GlafiraTaskState.RUNNING.databaseValue)
                    put("updated_at", nowMillis)
                    put("attempt_count", candidate.attemptCount + 1)
                    putNull("retry_at")
                },
                "task_id = ? AND state = ?",
                arrayOf(taskId, candidate.state.databaseValue)
            )
            check(changed == 1) { "Task claim lost its transactional state check." }

            val owner = db.query(
                TABLE_OWNERSHIP, arrayOf("owner_core_id", "owner_generation", "state_version"),
                "task_id = ?", arrayOf(taskId), null, null, null, "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else Triple(cursor.getString(0), cursor.getLong(1), cursor.getLong(2))
            }
            if (requireOwnershipToken && (owner == null || owner.first != ownerCoreId ||
                owner.second <= 0L || owner.third <= 0L)) {
                return null
            }
            db.setTransactionSuccessful()
            val task = queryTask(db, taskId)!!
            val token = GlafiraTaskOwnershipExecutionToken(
                taskId = taskId,
                ownerCoreId = owner?.first ?: ownerCoreId ?: "legacy-core-queue",
                ownerGeneration = owner?.second ?: 1L,
                stateVersion = owner?.third ?: 1L
            )
            return GlafiraOwnedTaskClaim(task, token)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Requeues Core RUNNING tasks left behind by process death. The caller must hold the
     * application-wide Core maintenance gate, so no live in-process handler is running.
     * Desktop tasks are excluded because their independent leases have their own recovery.
     */
    @Synchronized
    override fun recoverInterruptedCoreTasks(
        nowMillis: Long,
        maxRetries: Int
    ): Int {
        require(nowMillis > 0L)
        require(maxRetries in 0..20)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ownerClause = if (ownerCoreId == null) "" else
                " AND task_id IN (SELECT task_id FROM $TABLE_OWNERSHIP WHERE owner_core_id = ?)"
            val selectionArgs = arrayOf(GlafiraTaskState.RUNNING.databaseValue, DESKTOP_TASK_TYPE) +
                if (ownerCoreId == null) emptyArray() else arrayOf(ownerCoreId)
            val interrupted = db.query(TABLE_TASKS, null, "state = ? AND task_type != ?$ownerClause",
                selectionArgs, null, null, null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTask()) } }
            for (task in interrupted) {
                val decision = GlafiraTaskRecoveryPolicy.interrupted(task, maxRetries)
                val ownership = taskOwnership(task.taskId, task.projectId)
                if (ownerCoreId != null && ownership != null) {
                    transitionOwned(
                        GlafiraTaskOwnershipExecutionToken(task.taskId, ownership.ownerCoreId, ownership.ownerGeneration, ownership.stateVersion),
                        setOf(GlafiraTaskState.RUNNING), decision.state, nowMillis,
                        retryAtMillis = if (decision.state == GlafiraTaskState.RETRY_SCHEDULED) nowMillis else null,
                        lastError = decision.reason
                    )
                } else if (ownerCoreId == null) {
                    transition(task.taskId, setOf(GlafiraTaskState.RUNNING), decision.state, nowMillis,
                        retryAtMillis = if (decision.state == GlafiraTaskState.RETRY_SCHEDULED) nowMillis else null,
                        lastError = decision.reason)
                }
            }
            db.setTransactionSuccessful()
            return interrupted.size
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Cancels only work that has not been claimed by a Core executor.
     * The state change, history trigger and dependency propagation share one SQLite transaction.
     */
    internal fun cancelUnclaimedTask(
        taskId: String,
        projectId: String,
        expectedOwnerCoreId: String,
        expectedOwnerGeneration: Long,
        expectedStateVersion: Long,
        nowMillis: Long
    ): Boolean {
        require(taskId.isNotBlank() && projectId.isNotBlank() && expectedOwnerCoreId.isNotBlank())
        require(expectedOwnerGeneration > 0L && expectedStateVersion >= 0L && nowMillis > 0L)
        // Legacy/unbound stores are not cancellation authorities. A project match alone
        // must never let a peer Core mutate another Core's canonical task.
        val actingCoreId = ownerCoreId ?: return false
        if (actingCoreId != expectedOwnerCoreId) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ownership = db.query(
                TABLE_OWNERSHIP, arrayOf("owner_core_id", "owner_generation", "state_version"),
                "task_id = ? AND project_id = ?", arrayOf(taskId, projectId), null, null, null, "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else Triple(
                    cursor.getString(0), cursor.getLong(1), cursor.getLong(2)
                )
            } ?: return false
            if (ownership.first != expectedOwnerCoreId || ownership.second != expectedOwnerGeneration ||
                ownership.third != expectedStateVersion
            ) return false
            val task = queryTask(db, taskId, projectId) ?: return false
            if (!GlafiraTaskPolicy.ownerCanCancel(task)) return false
            val changed = transitionInternal(
                token = null,
                taskId = taskId,
                expectedStates = GlafiraTaskPolicy.ownerCancellableStates,
                nextState = GlafiraTaskState.CANCELLED,
                nowMillis = nowMillis,
                retryAtMillis = null,
                lastError = "OWNER_CANCELLED"
            )
            if (!changed) return false
            invalidateTaskArtifacts(
                db, task, nowMillis, "OWNER_CANCELLED", GlafiraCoreTraceActorType.OWNER
            )
            // The state trigger records the durable Core state transition. Add a separate,
            // explicit OWNER action so audit consumers do not attribute the user's decision
            // to Core merely because Core executed the transaction.
            coreTraceStore.appendInTransaction(db, GlafiraCoreTraceDraft(
                projectId = task.projectId,
                taskId = task.taskId,
                correlationId = task.taskId,
                occurredAtMillis = nowMillis,
                actorType = GlafiraCoreTraceActorType.OWNER,
                actorId = "owner",
                component = "CoreTaskStore",
                eventType = GlafiraCoreTraceEventType.TASK_CANCELLED,
                actionCode = "OWNER_CANCELLED_UNCLAIMED_TASK",
                targetType = "TASK",
                targetId = task.taskId,
                resultStatus = "cancelled"
            ))
            failDescendantsWithFailedDependency(db, taskId, projectId, actingCoreId, nowMillis)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Compare-and-set transition. Callers must supply the states they observed;
     * illegal state transitions are rejected before SQLite is modified.
     */
    @Synchronized
    override fun transition(
        taskId: String,
        expectedStates: Set<GlafiraTaskState>,
        nextState: GlafiraTaskState,
        nowMillis: Long,
        retryAtMillis: Long?,
        lastError: String?
    ): Boolean = transitionInternal(null, taskId, expectedStates, nextState, nowMillis, retryAtMillis, lastError)

    @Synchronized
    override fun transitionOwned(
        token: GlafiraTaskOwnershipExecutionToken,
        expectedStates: Set<GlafiraTaskState>,
        nextState: GlafiraTaskState,
        nowMillis: Long,
        retryAtMillis: Long?,
        lastError: String?
    ): Boolean = transitionInternal(token, token.taskId, expectedStates, nextState, nowMillis, retryAtMillis, lastError)

    private fun transitionInternal(
        token: GlafiraTaskOwnershipExecutionToken?,
        taskId: String,
        expectedStates: Set<GlafiraTaskState>,
        nextState: GlafiraTaskState,
        nowMillis: Long,
        retryAtMillis: Long?,
        lastError: String?
    ): Boolean {
        require(nowMillis > 0L && (retryAtMillis == null || retryAtMillis >= nowMillis))
        require(expectedStates.isNotEmpty()) { "At least one expected task state is required." }
        require(expectedStates.all { GlafiraTaskPolicy.canTransition(it, nextState) }) {
            "The requested GLAFIRA task state transition is not allowed."
        }
        require((nextState == GlafiraTaskState.RETRY_SCHEDULED) == (retryAtMillis != null)) {
            "Only retry_scheduled tasks may have a retry timestamp."
        }
        if (token != null && (token.taskId != taskId || token.ownerCoreId != ownerCoreId)) return false

        val values = ContentValues().apply {
            put("state", nextState.databaseValue)
            put("updated_at", nowMillis)
            if (retryAtMillis == null) putNull("retry_at") else put("retry_at", retryAtMillis)
            if (lastError.isNullOrBlank()) putNull("last_error") else put("last_error", lastError.take(2000))
        }
        val placeholders = expectedStates.joinToString(",") { "?" }
        val ownerGuard = when {
            token != null -> " AND EXISTS (SELECT 1 FROM $TABLE_OWNERSHIP own WHERE own.task_id = $TABLE_TASKS.task_id AND own.owner_core_id = ? AND own.owner_generation = ? AND own.state_version = ?)"
            ownerCoreId != null -> " AND EXISTS (SELECT 1 FROM $TABLE_OWNERSHIP own WHERE own.task_id = $TABLE_TASKS.task_id AND own.owner_core_id = ?)"
            else -> ""
        }
        val args = arrayOf(taskId) + expectedStates.map { it.databaseValue }.toTypedArray() +
            when {
                token != null -> arrayOf(token.ownerCoreId, token.ownerGeneration.toString(), token.stateVersion.toString())
                ownerCoreId != null -> arrayOf(ownerCoreId)
                else -> emptyArray()
            }
        return writableDatabase.update(
            TABLE_TASKS, values, "task_id = ? AND state IN ($placeholders)$ownerGuard", args
        ) == 1
    }

    /**
     * Atomically stores an encrypted handler result and marks the Core task succeeded.
     * State history and the result row commit or roll back together.
     */
    @Synchronized
    override fun persistCoreTaskResult(
        taskId: String,
        expectedStates: Set<GlafiraTaskState>,
        result: String,
        nowMillis: Long
    ): Boolean = persistCoreTaskResultInternal(null, taskId, expectedStates, result, nowMillis)

    @Synchronized
    override fun persistCoreTaskResultOwned(
        token: GlafiraTaskOwnershipExecutionToken,
        expectedStates: Set<GlafiraTaskState>,
        result: String,
        nowMillis: Long
    ): Boolean = persistCoreTaskResultInternal(token, token.taskId, expectedStates, result, nowMillis)

    private fun persistCoreTaskResultInternal(
        token: GlafiraTaskOwnershipExecutionToken?,
        taskId: String,
        expectedStates: Set<GlafiraTaskState>,
        result: String,
        nowMillis: Long
    ): Boolean {
        require(taskId.isNotBlank() && nowMillis > 0L)
        require(expectedStates == setOf(GlafiraTaskState.RUNNING))
        require(result.length <= GlafiraCoreTaskResultPersistence.MAX_RESULT_CHARS) { "CORE_TASK_RESULT_TOO_LARGE" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val task = queryTask(db, taskId) ?: return false
            if (token != null && (token.taskId != taskId || token.ownerCoreId != ownerCoreId)) return false
            if (ownerCoreId != null) {
                val currentOwner = db.query(TABLE_OWNERSHIP, arrayOf("owner_core_id", "owner_generation", "state_version"),
                    "task_id = ?", arrayOf(taskId), null, null, null, "1").use { cursor ->
                    if (!cursor.moveToFirst()) null else Triple(cursor.getString(0), cursor.getLong(1), cursor.getLong(2))
                } ?: return false
                if (currentOwner.first != ownerCoreId ||
                    (token != null && (currentOwner.second != token.ownerGeneration || currentOwner.third != token.stateVersion))
                ) return false
            }
            val existingResult = db.query(
                TABLE_CORE_RESULTS, arrayOf("result_ciphertext"),
                "task_id = ?", arrayOf(taskId), null, null, null, "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else GlafiraTaskPayloadCrypto.decrypt(
                    cursor.getString(0), taskId + "|core-result|" + task.projectId
                )
            }
            if (existingResult != null) {
                check(existingResult == result) { "CORE_TASK_RESULT_CONFLICT" }
                if (task.state == GlafiraTaskState.SUCCEEDED) {
                    db.setTransactionSuccessful()
                    return true
                }
                throw IllegalStateException("CORE_TASK_RESULT_STATE_MISMATCH")
            }
            if (task.state !in expectedStates) return false

            val ciphertext = GlafiraTaskPayloadCrypto.encrypt(
                result, taskId + "|core-result|" + task.projectId
            )
            val changed = db.update(
                TABLE_TASKS,
                ContentValues().apply {
                    put("state", GlafiraTaskState.SUCCEEDED.databaseValue)
                    put("updated_at", nowMillis)
                    putNull("retry_at")
                    putNull("last_error")
                },
                "task_id = ? AND state = ?" + if (token != null) {
                    " AND EXISTS (SELECT 1 FROM $TABLE_OWNERSHIP own WHERE own.task_id = $TABLE_TASKS.task_id AND own.owner_core_id = ? AND own.owner_generation = ? AND own.state_version = ?)"
                } else if (ownerCoreId != null) {
                    " AND EXISTS (SELECT 1 FROM $TABLE_OWNERSHIP own WHERE own.task_id = $TABLE_TASKS.task_id AND own.owner_core_id = ?)"
                } else "",
                arrayOf(taskId, GlafiraTaskState.RUNNING.databaseValue) +
                    if (token != null) arrayOf(token.ownerCoreId, token.ownerGeneration.toString(), token.stateVersion.toString())
                    else if (ownerCoreId != null) arrayOf(ownerCoreId) else emptyArray()
            )
            if (changed != 1) return false
            db.insertOrThrow(
                TABLE_CORE_RESULTS,
                null,
                ContentValues().apply {
                    put("task_id", taskId)
                    put("result_ciphertext", ciphertext)
                    put("completed_at", nowMillis)
                }
            )
            coreTraceStore.appendInTransaction(
                db,
                GlafiraCoreTraceDraft(
                    projectId = task.projectId,
                    taskId = task.taskId,
                    correlationId = task.taskId,
                    occurredAtMillis = nowMillis,
                    actorType = GlafiraCoreTraceActorType.CORE,
                    actorId = "core",
                    component = "CoreTaskStore",
                    eventType = GlafiraCoreTraceEventType.TASK_SUCCEEDED,
                    actionCode = "TASK_RESULT_COMMITTED",
                    resultStatus = "succeeded"
                )
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** Returns a decrypted result only when the task belongs to the requested project. */
    @Synchronized
    fun coreTaskResult(taskId: String, projectId: String): GlafiraCoreTaskResultRecord? {
        require(taskId.isNotBlank() && projectId.isNotBlank())
        val task = getTask(taskId, projectId) ?: return null
        if (task.state != GlafiraTaskState.SUCCEEDED) return null
        return readableDatabase.query(
            TABLE_CORE_RESULTS, arrayOf("result_ciphertext", "completed_at"),
            "task_id = ?", arrayOf(taskId), null, null, null, "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            GlafiraCoreTaskResultRecord(
                taskId = taskId,
                projectId = projectId,
                result = GlafiraTaskPayloadCrypto.decrypt(
                    cursor.getString(0), taskId + "|core-result|" + projectId
                ),
                completedAtMillis = cursor.getLong(1)
            )
        }
    }

    @Synchronized
    fun recordDesktopNode(deviceId: String, capabilities: Set<String>, maxParallelTasks: Int,
                          nowMillis: Long = System.currentTimeMillis()) {
        require(deviceId.matches(Regex("[A-Za-z0-9_.-]{8,128}")))
        require(capabilities.size <= 16 && capabilities.all { it.matches(Regex("[a-z][a-z0-9_.-]{1,63}")) })
        require(maxParallelTasks in 1..8)
        writableDatabase.insertWithOnConflict("core_desktop_nodes", null, ContentValues().apply {
            put("device_id", deviceId); put("last_seen_at", nowMillis)
            put("capabilities_json", JSONArray(capabilities.sorted()).toString())
            put("max_parallel_tasks", maxParallelTasks)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun activeDesktopLeaseCounts(nowMillis: Long = System.currentTimeMillis()): Map<String, Int> {
        require(nowMillis > 0L)
        return readableDatabase.rawQuery(
            "SELECT device_id, COUNT(*) FROM core_desktop_leases WHERE lease_expires_at > ? GROUP BY device_id",
            arrayOf(nowMillis.toString())
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
            }
        }
    }

    /** All recently heartbeating Desktop nodes, ordered by freshness for dispatch planning. */
    @Synchronized
    fun desktopNodes(
        maxAgeMillis: Long = 45_000L,
        nowMillis: Long = System.currentTimeMillis()
    ): List<GlafiraNodeCapabilities> {
        require(maxAgeMillis in 1_000L..300_000L && nowMillis > 0L)
        val cutoff = nowMillis - maxAgeMillis
        return readableDatabase.query(
            "core_desktop_nodes", null, "last_seen_at >= ?",
            arrayOf(cutoff.toString()), null, null, "last_seen_at DESC", "32"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val json = JSONArray(cursor.getString(cursor.getColumnIndexOrThrow("capabilities_json")))
                    val capabilities = buildSet {
                        for (index in 0 until json.length()) add(json.getString(index))
                    }
                    add(GlafiraNodeCapabilities(
                        nodeId = cursor.getString(cursor.getColumnIndexOrThrow("device_id")),
                        available = true,
                        maxParallelTasks = cursor.getInt(cursor.getColumnIndexOrThrow("max_parallel_tasks")).coerceIn(1, 8),
                        capabilities = capabilities
                    ))
                }
            }
        }
    }

    @Synchronized
    fun desktopNodeCapabilities(maxAgeMillis: Long = 45_000L): GlafiraNodeCapabilities? {
        val now = System.currentTimeMillis()
        return readableDatabase.query("core_desktop_nodes", null, "last_seen_at >= ?",
            arrayOf((now - maxAgeMillis).toString()), null, null, "last_seen_at DESC", "1").use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val json = JSONArray(cursor.getString(cursor.getColumnIndexOrThrow("capabilities_json")))
            val capabilities = buildSet { for (i in 0 until json.length()) add(json.getString(i)) }
            GlafiraNodeCapabilities(
                nodeId = cursor.getString(cursor.getColumnIndexOrThrow("device_id")),
                available = true,
                maxParallelTasks = cursor.getInt(cursor.getColumnIndexOrThrow("max_parallel_tasks")).coerceIn(1, 8),
                capabilities = capabilities
            )
        }
    }

    private fun invalidateTaskArtifacts(
        db: SQLiteDatabase,
        task: GlafiraTask,
        nowMillis: Long,
        actionPrefix: String,
        actorType: GlafiraCoreTraceActorType
    ) {
        val actorId = if (actorType == GlafiraCoreTraceActorType.OWNER) "owner" else "core"
        if (tableExists(db, "core_action_approvals")) {
            val pending = db.update(
                "core_action_approvals",
                ContentValues().apply { put("status", "EXPIRED"); putNull("approved_at"); putNull("grant_expires_at") },
                "task_id = ? AND status = 'PENDING'", arrayOf(task.taskId)
            )
            if (pending > 0) coreTraceStore.appendInTransaction(db, GlafiraCoreTraceDraft(
                projectId = task.projectId, taskId = task.taskId, correlationId = task.taskId,
                occurredAtMillis = nowMillis, actorType = actorType, actorId = actorId,
                component = "CoreTaskStore", eventType = GlafiraCoreTraceEventType.APPROVAL_EXPIRED,
                actionCode = "${actionPrefix}_PENDING_APPROVAL_EXPIRED", targetType = "ACTION_APPROVAL",
                targetId = task.taskId, approvalRef = task.taskId, resultStatus = "expired"
            ))
            val approved = db.update(
                "core_action_approvals",
                ContentValues().apply { put("status", "EXPIRED"); putNull("grant_expires_at") },
                "task_id = ? AND status = 'APPROVED'", arrayOf(task.taskId)
            )
            if (approved > 0) coreTraceStore.appendInTransaction(db, GlafiraCoreTraceDraft(
                projectId = task.projectId, taskId = task.taskId, correlationId = task.taskId,
                occurredAtMillis = nowMillis, actorType = actorType, actorId = actorId,
                component = "CoreTaskStore", eventType = GlafiraCoreTraceEventType.APPROVAL_REVOKED,
                actionCode = "${actionPrefix}_APPROVED_GRANT_REVOKED", targetType = "ACTION_APPROVAL",
                targetId = task.taskId, approvalRef = task.taskId, resultStatus = "revoked"
            ))
        }
        if (tableExists(db, GlafiraDryRunPreviewSchema.TABLE)) {
            val preview = db.update(
                GlafiraDryRunPreviewSchema.TABLE,
                ContentValues().apply {
                    // The enum has no CLOSED state. Keep the preview non-actionable in
                    // storage, but audit task cancellation as lifecycle closure, never as an
                    // owner review rejection.
                    put("review_status", GlafiraDryRunReviewStatus.REJECTED.name)
                    put("reviewed_at", nowMillis)
                },
                "task_id = ? AND review_status = ?", arrayOf(task.taskId, GlafiraDryRunReviewStatus.PENDING.name)
            )
            if (preview > 0) coreTraceStore.appendInTransaction(db, GlafiraCoreTraceDraft(
                projectId = task.projectId, taskId = task.taskId, correlationId = task.taskId,
                occurredAtMillis = nowMillis, actorType = actorType, actorId = actorId,
                component = "CoreTaskStore", eventType = GlafiraCoreTraceEventType.POLICY_CHECKED,
                actionCode = "${actionPrefix}_DRY_RUN_PREVIEW_CLOSED", targetType = "DRY_RUN_PREVIEW",
                targetId = task.taskId, resultStatus = "closed"
            ))
        }
    }

    private fun readTaskOwnership(
        db: SQLiteDatabase,
        taskId: String,
        projectId: String
    ): GlafiraTaskOwnershipRecord? = db.query(
        TABLE_OWNERSHIP, null, "task_id = ? AND project_id = ?",
        arrayOf(taskId, projectId), null, null, null, "1"
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else GlafiraTaskOwnershipRecord(
            taskId = taskId,
            projectId = projectId,
            ownerCoreId = cursor.getString(cursor.getColumnIndexOrThrow("owner_core_id")),
            ownerGeneration = cursor.getLong(cursor.getColumnIndexOrThrow("owner_generation")),
            stateVersion = cursor.getLong(cursor.getColumnIndexOrThrow("state_version")),
            payloadSha256 = cursor.getString(cursor.getColumnIndexOrThrow("payload_sha256")),
            idempotencyKey = cursor.getString(cursor.getColumnIndexOrThrow("idempotency_key")),
            effectRisk = cursor.getString(cursor.getColumnIndexOrThrow("effect_risk")),
            approvalRef = cursor.getColumnIndexOrThrow("approval_ref").let {
                if (cursor.isNull(it)) null else cursor.getString(it)
            },
            createdAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
        )
    }

    private fun failDescendantsWithFailedDependency(
        db: SQLiteDatabase, rootTaskId: String, projectId: String, actingCoreId: String, nowMillis: Long
    ) {
        // Keep the bind count below SQLite's 999-parameter limit on older Android SQLite.
        // Other query parameters (project, states and owner) are in addition to this batch.
        val ownerId = ownerCoreId ?: return
        if (ownerId != actingCoreId) return
        val states = arrayOf(
            GlafiraTaskState.QUEUED.databaseValue, GlafiraTaskState.RETRY_SCHEDULED.databaseValue,
            GlafiraTaskState.WAITING_FOR_NETWORK.databaseValue, GlafiraTaskState.WAITING_FOR_OWNER.databaseValue
        )
        var frontier = listOf(rootTaskId)
        val values = ContentValues().apply {
            put("state", GlafiraTaskState.FAILED.databaseValue)
            put("updated_at", nowMillis)
            put("last_error", "DEPENDENCY_FAILED")
            putNull("retry_at")
        }
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<String>()
            frontier.chunked(500).forEach { batch ->
                val placeholders = batch.joinToString(",") { "?" }
                val selection = "project_id = ? AND state IN (?, ?, ?, ?) AND task_id IN (" +
                    "SELECT task_id FROM $TABLE_DEPENDENCIES WHERE depends_on_task_id IN ($placeholders))" +
                    " AND task_id IN (SELECT task_id FROM $TABLE_OWNERSHIP WHERE owner_core_id = ?)"
                val args = arrayOf(projectId) + states + batch.toTypedArray() + arrayOf(ownerId)
                val candidates = db.rawQuery(
                    "SELECT DISTINCT task_id FROM $TABLE_TASKS WHERE $selection", args
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                candidates.forEach { childId ->
                    val child = queryTask(db, childId, projectId) ?: return@forEach
                    if (GlafiraTaskPolicy.hasUnresolvedExecutionOutcome(child)) return@forEach
                    val ownership = readTaskOwnership(db, childId, projectId) ?: return@forEach
                    if (ownership.ownerCoreId != actingCoreId ||
                        ownership.ownerGeneration <= 0L || ownership.stateVersion < 0L
                    ) return@forEach
                    val updateSelection = "task_id = ? AND project_id = ? AND state IN (?, ?, ?, ?) " +
                        "AND EXISTS (SELECT 1 FROM $TABLE_OWNERSHIP own " +
                        "WHERE own.task_id = $TABLE_TASKS.task_id AND own.owner_core_id = ? " +
                        "AND own.owner_generation = ? AND own.state_version = ?)"
                    val updateArgs = arrayOf(childId, projectId) + states +
                        arrayOf(actingCoreId, ownership.ownerGeneration.toString(), ownership.stateVersion.toString())
                    if (db.update(TABLE_TASKS, values, updateSelection, updateArgs) == 1) {
                        invalidateTaskArtifacts(
                            db, child, nowMillis, "DEPENDENCY_FAILED", GlafiraCoreTraceActorType.CORE
                        )
                        next.add(childId)
                    }
                }
            }
            frontier = next
        }
    }

    /** Propagates failed predecessor states through the entire acyclic task graph. */
    private fun failTasksWithFailedDependencies(db: SQLiteDatabase, nowMillis: Long) {
        val values = ContentValues().apply {
            put("state", GlafiraTaskState.FAILED.databaseValue)
            put("updated_at", nowMillis)
            put("last_error", "DEPENDENCY_FAILED")
            putNull("retry_at")
        }
        val ownerClause = if (ownerCoreId == null) "" else
            " AND task_id IN (SELECT task_id FROM $TABLE_OWNERSHIP WHERE owner_core_id = ?)"
        val selection = "state IN (?, ?) AND task_id IN (" +
            "SELECT d.task_id FROM $TABLE_DEPENDENCIES d " +
            "JOIN $TABLE_TASKS p ON p.task_id = d.depends_on_task_id " +
            "WHERE p.state IN (?, ?))$ownerClause " +
            "AND NOT (COALESCE($TABLE_TASKS.last_error,'') IN (" +
            "'CORE_EXECUTION_OUTCOME_UNKNOWN','DESKTOP_EXECUTION_OUTCOME_UNKNOWN','EXECUTION_OUTCOME_UNCERTAIN') " +
            "OR (COALESCE($TABLE_TASKS.last_error,'') GLOB 'CORE_RESULT_PERSIST_FAILED:*' AND EXISTS (" +
            "SELECT 1 FROM $TABLE_OWNERSHIP own_risk WHERE own_risk.task_id = $TABLE_TASKS.task_id " +
            "AND own_risk.effect_risk IN ('EXTERNAL_SIDE_EFFECT','FINANCIAL','DESTRUCTIVE'))))"
        val args = arrayOf(
            GlafiraTaskState.QUEUED.databaseValue,
            GlafiraTaskState.RETRY_SCHEDULED.databaseValue,
            GlafiraTaskState.FAILED.databaseValue,
            GlafiraTaskState.CANCELLED.databaseValue
        ) + if (ownerCoreId == null) emptyArray() else arrayOf(ownerCoreId)
        while (db.update(TABLE_TASKS, values, selection, args) > 0) {
            // Iterate so failure propagates to grandchildren in the same transaction.
        }
    }

    private fun hasUnresolvedTaskDependencies(db: SQLiteDatabase, taskId: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM $TABLE_DEPENDENCIES d " +
                "JOIN $TABLE_TASKS p ON p.task_id = d.depends_on_task_id " +
                "WHERE d.task_id = ? AND p.state != ? LIMIT 1",
            arrayOf(taskId, GlafiraTaskState.SUCCEEDED.databaseValue)
        ).use { it.moveToFirst() }

    /** Expiration proves loss of contact, never that an external operation did not happen. */
    private fun quarantineExpiredDesktopLeases(db: SQLiteDatabase, nowMillis: Long) {
        db.execSQL(
            "UPDATE $TABLE_TASKS SET state = ?, updated_at = ?, last_error = ? " +
                "WHERE task_type = ? AND state = ? AND task_id IN " +
                "(SELECT task_id FROM core_desktop_leases WHERE lease_expires_at <= ?)",
            arrayOf(GlafiraTaskState.WAITING_FOR_OWNER.databaseValue, nowMillis,
                "DESKTOP_EXECUTION_OUTCOME_UNKNOWN", DESKTOP_TASK_TYPE,
                GlafiraTaskState.RUNNING.databaseValue, nowMillis)
        )
        // Preserve the original device binding so a delayed terminal result can reconcile it.
    }

    @Synchronized
    fun queuedDesktopTasks(limit: Int = 100, projectId: String? = null): List<GlafiraTask> {
        require(limit in 1..500)
        require(projectId == null || projectId.isNotBlank())
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            quarantineExpiredDesktopLeases(db, now)
            failTasksWithFailedDependencies(db, now)
            val selection = "task_type = ? AND state = ?" +
                " AND NOT EXISTS (SELECT 1 FROM $TABLE_DEPENDENCIES d " +
                "JOIN $TABLE_TASKS p ON p.task_id = d.depends_on_task_id " +
                "WHERE d.task_id = $TABLE_TASKS.task_id AND p.state != ?)" +
                if (projectId == null) "" else " AND project_id = ?"
            val args = arrayOf(DESKTOP_TASK_TYPE, GlafiraTaskState.QUEUED.databaseValue,
                GlafiraTaskState.SUCCEEDED.databaseValue) +
                if (projectId == null) emptyArray() else arrayOf(projectId)
            val tasks = db.query(TABLE_TASKS, null, selection, args,
                null, null, "priority DESC, created_at ASC", limit.toString()).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.toTask()) }
            }
            db.setTransactionSuccessful()
            return tasks
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun claimDesktopTask(taskId: String, deviceId: String, supportedCapabilities: Set<String>,
                         maxParallelTasks: Int, nowMillis: Long = System.currentTimeMillis()): GlafiraDesktopTaskLease? {
        require(deviceId.matches(Regex("[A-Za-z0-9_.-]{8,128}")))
        require(supportedCapabilities.size <= 16 && supportedCapabilities.all { it.matches(Regex("[a-z][a-z0-9_.-]{1,63}")) })
        require(maxParallelTasks in 1..8)
        val db = writableDatabase
        db.beginTransaction()
        try {
            quarantineExpiredDesktopLeases(db, nowMillis)
            failTasksWithFailedDependencies(db, nowMillis)
            val active = db.rawQuery("SELECT COUNT(*) FROM core_desktop_leases WHERE device_id = ? AND lease_expires_at > ?",
                arrayOf(deviceId, nowMillis.toString())).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            if (active >= maxParallelTasks) { db.setTransactionSuccessful(); return null }
            val task = queryTask(db, taskId) ?: run { db.setTransactionSuccessful(); return null }
            if (task.taskType != DESKTOP_TASK_TYPE || task.state != GlafiraTaskState.QUEUED) {
                db.setTransactionSuccessful(); return null
            }
            if (hasUnresolvedTaskDependencies(db, taskId)) {
                db.setTransactionSuccessful(); return null
            }
            if (task.deadlineAtMillis != null && task.deadlineAtMillis <= nowMillis) {
                db.update(TABLE_TASKS, ContentValues().apply {
                    put("state", GlafiraTaskState.FAILED.databaseValue)
                    put("updated_at", nowMillis)
                    put("last_error", "DESKTOP_TASK_EXPIRED")
                }, "task_id = ? AND state = ?", arrayOf(taskId, GlafiraTaskState.QUEUED.databaseValue))
                db.setTransactionSuccessful()
                return null
            }
            val required = runCatching {
                val a = JSONObject(task.payload).getJSONArray("requiredCapabilities")
                (0 until a.length()).map { a.getString(it) }.toSet()
            }.getOrDefault(emptySet())
            if (!supportedCapabilities.containsAll(required)) { db.setTransactionSuccessful(); return null }
            val changed = db.update(TABLE_TASKS, ContentValues().apply {
                put("state", GlafiraTaskState.RUNNING.databaseValue)
                put("updated_at", nowMillis)
                put("attempt_count", task.attemptCount + 1)
            }, "task_id = ? AND state = ?", arrayOf(taskId, GlafiraTaskState.QUEUED.databaseValue))
            if (changed != 1) { db.setTransactionSuccessful(); return null }
            val leaseId = UUID.randomUUID().toString()
            val fencingToken = (task.attemptCount + 1).toLong()
            db.insertWithOnConflict("core_desktop_leases", null, ContentValues().apply {
                put("task_id", taskId); put("device_id", deviceId)
                put("lease_id", leaseId); put("fencing_token", fencingToken)
                put("lease_expires_at", nowMillis + DESKTOP_LEASE_MS)
                put("attempt_count", task.attemptCount + 1); put("progress_percent", 0); put("updated_at", nowMillis)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return GlafiraDesktopTaskLease(queryTask(db, taskId)!!, leaseId, fencingToken)
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun releaseDesktopLease(taskId: String, deviceId: String, leaseId: String, fencingToken: Long): Boolean {
        require(leaseId.matches(Regex("[A-Za-z0-9_-]{8,128}")) && fencingToken > 0)
        return writableDatabase.delete(
            "core_desktop_leases",
            "task_id = ? AND device_id = ? AND lease_id = ? AND fencing_token = ?",
            arrayOf(taskId, deviceId, leaseId, fencingToken.toString())
        ) == 1
    }

    @Synchronized
    fun updateDesktopTask(taskId: String, deviceId: String, leaseId: String, fencingToken: Long,
                          state: String, progressPercent: Int,
                          progressMessage: String?, result: String?, errorCode: String?,
                          nowMillis: Long = System.currentTimeMillis()): Boolean {
        require(leaseId.matches(Regex("[A-Za-z0-9_-]{8,128}")))
        require(fencingToken > 0)
        require(progressPercent in 0..100)
        require(progressMessage == null || progressMessage.length <= 500)
        require(result == null || result.length <= 24_000)
        require(errorCode == null || errorCode.matches(Regex("[A-Z0-9_]{1,64}")))
        require(state in setOf("ACCEPTED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"))
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (state in setOf("SUCCEEDED", "FAILED", "CANCELLED")) {
                val duplicate = db.query(
                    "core_desktop_results",
                    arrayOf("result_ciphertext", "error_code", "device_id", "lease_id", "fencing_token", "terminal_state"),
                    "task_id = ?", arrayOf(taskId), null, null, null, "1"
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else {
                        val storedResult = if (cursor.isNull(0)) null else runCatching {
                            GlafiraTaskPayloadCrypto.decrypt(cursor.getString(0), taskId + "|desktop-result")
                        }.getOrNull()
                        val storedError = if (cursor.isNull(1)) null else cursor.getString(1)
                        cursor.getString(2) == deviceId && cursor.getString(3) == leaseId &&
                            cursor.getLong(4) == fencingToken && cursor.getString(5) == state &&
                            storedResult == result && storedError == errorCode
                    }
                }
                if (duplicate != null) {
                    if (duplicate) db.setTransactionSuccessful()
                    return duplicate
                }
            }
            val lease = db.query("core_desktop_leases", arrayOf("device_id", "lease_id", "fencing_token", "lease_expires_at"),
                "task_id = ?", arrayOf(taskId), null, null, null, "1").use { cursor ->
                if (cursor.moveToFirst()) StoredDesktopLease(
                    cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3)
                ) else null
            } ?: return false
            if (lease.deviceId != deviceId || lease.leaseId != leaseId ||
                lease.fencingToken != fencingToken
            ) return false
            val task = queryTask(db, taskId) ?: return false
            val reconcilingUnknown = task.state == GlafiraTaskState.WAITING_FOR_OWNER &&
                task.lastError == "DESKTOP_EXECUTION_OUTCOME_UNKNOWN"
            if (state in setOf("SUCCEEDED", "FAILED", "CANCELLED")) {
                if (task.state != GlafiraTaskState.RUNNING && !reconcilingUnknown) return false
                val terminal = when (state) {
                    "SUCCEEDED" -> GlafiraTaskState.SUCCEEDED
                    "FAILED" -> GlafiraTaskState.FAILED
                    else -> GlafiraTaskState.CANCELLED
                }
                val changed = db.update(TABLE_TASKS, ContentValues().apply {
                    put("state", terminal.databaseValue); put("updated_at", nowMillis)
                    if (errorCode == null) putNull("last_error") else put("last_error", errorCode)
                }, "task_id = ? AND (state = ? OR (state = ? AND last_error = ?))",
                    arrayOf(taskId, GlafiraTaskState.RUNNING.databaseValue,
                        GlafiraTaskState.WAITING_FOR_OWNER.databaseValue, "DESKTOP_EXECUTION_OUTCOME_UNKNOWN"))
                if (changed != 1) return false
                db.insertWithOnConflict("core_desktop_results", null, ContentValues().apply {
                    put("task_id", taskId)
                    if (result == null) putNull("result_ciphertext")
                    else put("result_ciphertext", GlafiraTaskPayloadCrypto.encrypt(result, taskId + "|desktop-result"))
                    if (errorCode == null) putNull("error_code") else put("error_code", errorCode)
                    put("completed_at", nowMillis)
                    put("device_id", deviceId); put("lease_id", leaseId)
                    put("fencing_token", fencingToken); put("terminal_state", state)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                db.delete(
                    "core_desktop_leases",
                    "task_id = ? AND device_id = ? AND lease_id = ? AND fencing_token = ?",
                    arrayOf(taskId, deviceId, leaseId, fencingToken.toString())
                )
            } else {
                if (lease.expiresAtMillis <= nowMillis || task.state != GlafiraTaskState.RUNNING) return false
                val changed = db.update("core_desktop_leases", ContentValues().apply {
                    put("lease_expires_at", nowMillis + DESKTOP_LEASE_MS)
                    put("progress_percent", progressPercent)
                    if (progressMessage != null) put("progress_message", progressMessage)
                    put("updated_at", nowMillis)
                }, "task_id = ? AND device_id = ? AND lease_id = ? AND fencing_token = ?",
                    arrayOf(taskId, deviceId, leaseId, fencingToken.toString()))
                if (changed != 1) return false
            }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun desktopTaskResult(taskId: String): String? =
        readableDatabase.query("core_desktop_results", arrayOf("result_ciphertext"),
            "task_id = ?", arrayOf(taskId), null, null, null, "1").use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null
            else GlafiraTaskPayloadCrypto.decrypt(cursor.getString(0), taskId + "|desktop-result")
        }

    @Synchronized
    fun desktopTaskLeaseExpiry(taskId: String): Long? =
        readableDatabase.query("core_desktop_leases", arrayOf("lease_expires_at"),
            "task_id = ?", arrayOf(taskId), null, null, null, "1").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    private fun findByIdempotency(projectId: String, idempotencyKey: String): GlafiraTask? =
        readableDatabase.query(
            TABLE_TASKS,
            null,
            "project_id = ? AND idempotency_key = ?",
            arrayOf(projectId, idempotencyKey),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toTask() else null }

    private fun queryTask(db: SQLiteDatabase, taskId: String): GlafiraTask? =
        db.query(
            TABLE_TASKS,
            null,
            "task_id = ?",
            arrayOf(taskId),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toTask() else null }

    private fun queryTask(db: SQLiteDatabase, taskId: String, projectId: String): GlafiraTask? =
        db.query(
            TABLE_TASKS, null, "task_id = ? AND project_id = ?", arrayOf(taskId, projectId),
            null, null, null, "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toTask() else null }

    private fun GlafiraTask.toContentValues(): ContentValues {
        val aad = taskId + "|" + projectId
        val scopes = JSONArray().apply {
            requiredScopes.sorted().forEach { put(it) }
        }.toString()
        return ContentValues().apply {
            put("task_id", taskId)
            put("project_id", projectId)
            put("task_type", taskType)
            put("payload_ciphertext", GlafiraTaskPayloadCrypto.encrypt(payload, aad))
            put("origin", origin)
            put("priority", priority)
            put("state", state.databaseValue)
            put("created_at", createdAtMillis)
            put("updated_at", updatedAtMillis)
            put("attempt_count", attemptCount)
            if (deadlineAtMillis == null) putNull("deadline_at") else put("deadline_at", deadlineAtMillis)
            put("idempotency_key", idempotencyKey)
            put("required_scopes", scopes)
            if (retryAtMillis == null) putNull("retry_at") else put("retry_at", retryAtMillis)
            if (lastError == null) putNull("last_error") else put("last_error", lastError)
        }
    }

    private fun Cursor.toTask(): GlafiraTask {
        val taskId = getString(getColumnIndexOrThrow("task_id"))
        val projectId = getString(getColumnIndexOrThrow("project_id"))
        val scopeJson = JSONArray(getString(getColumnIndexOrThrow("required_scopes")))
        val scopes = buildSet {
            for (index in 0 until scopeJson.length()) add(scopeJson.getString(index))
        }
        val deadlineIndex = getColumnIndexOrThrow("deadline_at")
        val retryIndex = getColumnIndexOrThrow("retry_at")
        val errorIndex = getColumnIndexOrThrow("last_error")
        return GlafiraTask(
            taskId = taskId,
            projectId = projectId,
            taskType = getString(getColumnIndexOrThrow("task_type")),
            payload = GlafiraTaskPayloadCrypto.decrypt(
                getString(getColumnIndexOrThrow("payload_ciphertext")),
                taskId + "|" + projectId
            ),
            origin = getString(getColumnIndexOrThrow("origin")),
            priority = getInt(getColumnIndexOrThrow("priority")),
            state = GlafiraTaskState.fromDatabaseValue(getString(getColumnIndexOrThrow("state"))),
            createdAtMillis = getLong(getColumnIndexOrThrow("created_at")),
            updatedAtMillis = getLong(getColumnIndexOrThrow("updated_at")),
            attemptCount = getInt(getColumnIndexOrThrow("attempt_count")),
            deadlineAtMillis = if (isNull(deadlineIndex)) null else getLong(deadlineIndex),
            idempotencyKey = getString(getColumnIndexOrThrow("idempotency_key")),
            requiredScopes = scopes,
            retryAtMillis = if (isNull(retryIndex)) null else getLong(retryIndex),
            lastError = if (isNull(errorIndex)) null else getString(errorIndex)
        )
    }


    /**
     * Checkpoints, closes and validates the live database before returning it for immediate encryption.
     * The caller must hold the Core maintenance gate and keep this store monitor until the source is consumed.
     */
    @Synchronized
    internal fun prepareConsistentSnapshotSource(): File {
        val db = writableDatabase
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            if (cursor.moveToFirst()) {
                check(cursor.getInt(0) == 0) { "SQLite WAL checkpoint is busy; Core snapshot aborted." }
                if (cursor.columnCount >= 3 && cursor.getInt(1) >= 0) {
                    check(cursor.getInt(2) >= cursor.getInt(1)) {
                        "SQLite WAL checkpoint did not include every committed frame."
                    }
                }
            }
        }
        close()

        val source = appContext.getDatabasePath(DATABASE_NAME)
        check(source.isFile) { "Core task database does not exist." }
        val walFile = File(source.path + "-wal")
        check(!walFile.exists() || walFile.length() == 0L) {
            "SQLite WAL still contains data after closing the queue; Core snapshot aborted."
        }
        validateDatabaseSnapshot(source)
        return source
    }

    @Synchronized
    internal fun exportConsistentSnapshot(destination: File) {
        val source = prepareConsistentSnapshotSource()
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
        validateDatabaseSnapshot(destination)
    }

    /** Validates a decrypted local rollback database before any live files are replaced. */
    @Synchronized
    internal fun verifyConsistentSnapshot(snapshot: File) {
        require(snapshot.isFile && snapshot.length() > 0L) { "Core database snapshot is missing." }
        validateDatabaseSnapshot(snapshot)
    }

    /**
     * Installs a previously verified database snapshot with a same-directory rollback file.
     * The caller must hold GlafiraCoreMaintenanceGate and keep Core dispatch paused.
     */
    @Synchronized
    internal fun restoreConsistentSnapshot(snapshot: File) {
        require(snapshot.isFile && snapshot.length() > 0L) { "Core database snapshot is missing." }
        validateDatabaseSnapshot(snapshot)
        close()

        val target = appContext.getDatabasePath(DATABASE_NAME)
        val parent = requireNotNull(target.parentFile)
        check(parent.exists() || parent.mkdirs()) { "Cannot prepare the Core database directory." }
        val replacement = File(parent, "$DATABASE_NAME.restore-new")
        val rollback = File(parent, "$DATABASE_NAME.restore-rollback")
        replacement.delete()
        rollback.delete()
        snapshot.copyTo(replacement, overwrite = true)
        validateDatabaseSnapshot(replacement)

        if (target.exists()) {
            check(target.renameTo(rollback)) { "Cannot preserve the current Core database for rollback." }
        }
        if (!replacement.renameTo(target)) {
            if (rollback.exists()) rollback.renameTo(target)
            throw IllegalStateException("Cannot activate the restored Core database.")
        }

        try {
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            validateDatabaseSnapshot(target)
            val reopened = readableDatabase
            reopened.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Restored Core database failed its integrity check."
                }
            }
            rollback.delete()
        } catch (error: Exception) {
            close()
            target.delete()
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            if (rollback.exists() && !rollback.renameTo(target)) {
                error.addSuppressed(IllegalStateException("Core database rollback file could not be restored."))
            }
            throw error
        } finally {
            replacement.delete()
        }
    }

    private fun validateDatabaseSnapshot(file: File) {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                    "Core database snapshot failed SQLite integrity_check."
                }
            }
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) in 1..DATABASE_VERSION) {
                    "Core database snapshot schema version is incompatible."
                }
            }
        } finally {
            database.close()
        }
    }

    companion object {
        private const val DATABASE_NAME = "glafira_core_tasks.db"
        private const val DATABASE_VERSION = 12
        private const val DESKTOP_LEASE_MS = 10 * 60_000L
        const val DESKTOP_TASK_TYPE = "desktop.execute"
        private const val TABLE_TASKS = "core_tasks"
        private const val TABLE_CORE_RESULTS = "core_task_results"
        private const val TABLE_DEPENDENCIES = "core_task_dependencies"
        private const val TABLE_OWNERSHIP = "core_task_ownership"
    }
}
