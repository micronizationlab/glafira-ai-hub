package com.micronizationlab.glafira

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Transactional, process-death-safe queue storage for GLAFIRA Core.
 * Payloads are encrypted before they enter SQLite; task metadata remains queryable.
 */
class GlafiraTaskStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION),
    GlafiraTaskQueue, GlafiraDryRunPreviewPersistence, GlafiraInterruptedTaskRecovery, GlafiraCoreTaskResultPersistence {
    private val appContext = context.applicationContext

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDesktopTables(db)
        if (oldVersion < 3) GlafiraDryRunPreviewSchema.create(db)
        if (oldVersion < 4) createHistorySchema(db)
        if (oldVersion < 5) createCoreTaskResultsSchema(db)
        if (oldVersion < 6) createTaskDependenciesSchema(db)
        if (newVersion > 6) throw IllegalStateException("A reviewed GLAFIRA task-queue migration is required.")
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
        db.execSQL("CREATE TABLE IF NOT EXISTS core_desktop_leases (task_id TEXT PRIMARY KEY NOT NULL REFERENCES $TABLE_TASKS(task_id) ON DELETE CASCADE, device_id TEXT NOT NULL, lease_expires_at INTEGER NOT NULL, attempt_count INTEGER NOT NULL, progress_percent INTEGER NOT NULL DEFAULT 0, progress_message TEXT, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS core_desktop_results (task_id TEXT PRIMARY KEY NOT NULL REFERENCES $TABLE_TASKS(task_id) ON DELETE CASCADE, result_ciphertext TEXT, error_code TEXT, completed_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_core_desktop_lease_device ON core_desktop_leases(device_id, lease_expires_at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS core_desktop_nodes (device_id TEXT PRIMARY KEY NOT NULL, last_seen_at INTEGER NOT NULL, capabilities_json TEXT NOT NULL, max_parallel_tasks INTEGER NOT NULL)")
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
            val existingHash = db.query(
                GlafiraDryRunPreviewSchema.TABLE, arrayOf("plan_hash"),
                "task_id = ?", arrayOf(taskId), null, null, null, "1"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (existingHash != null) {
                check(existingHash == plan.planHash) { "DRY_RUN_PLAN_HASH_CONFLICT" }
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
                "task_id = ? AND state IN ($placeholders)",
                arrayOf(taskId) + expectedStates.map { it.databaseValue }.toTypedArray()
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
        val selection = if (includeReviewed) null else "review_status = ?"
        val args = if (includeReviewed) null else arrayOf(GlafiraDryRunReviewStatus.PENDING.name)
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
        return writableDatabase.update(
            GlafiraDryRunPreviewSchema.TABLE,
            ContentValues().apply {
                put("review_status", decision.name)
                put("reviewed_at", nowMillis)
            },
            "task_id = ? AND plan_hash = ? AND review_status = ?",
            arrayOf(taskId, expectedPlanHash, GlafiraDryRunReviewStatus.PENDING.name)
        ) == 1
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
        val values = task.toContentValues()
        val insertedId = writableDatabase.insertWithOnConflict(
            TABLE_TASKS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
        if (insertedId != -1L) {
            return GlafiraTaskEnqueueResult(task, true)
        }

        val existing = findByIdempotency(task.projectId, task.idempotencyKey)
            ?: throw IllegalStateException("Task ID already exists with a different idempotency key.")
        return GlafiraTaskEnqueueResult(existing, false)
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
    fun resumeWaitingForNetwork(nowMillis: Long = System.currentTimeMillis()): Int =
        writableDatabase.update(TABLE_TASKS, ContentValues().apply {
            put("state", GlafiraTaskState.QUEUED.databaseValue)
            put("updated_at", nowMillis)
            putNull("retry_at")
            put("last_error", "NETWORK_AVAILABLE")
        }, "state = ? AND task_type != ?", arrayOf(GlafiraTaskState.WAITING_FOR_NETWORK.databaseValue, DESKTOP_TASK_TYPE))

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
        db.update(TABLE_TASKS, ContentValues().apply {
            put("state", GlafiraTaskState.FAILED.databaseValue)
            put("updated_at", nowMillis)
            putNull("retry_at")
            put("last_error", "TASK_DEADLINE_EXPIRED")
        }, "state IN ('queued','waiting_for_network','waiting_for_owner','retry_scheduled') AND deadline_at <= ? AND (last_error IS NULL OR last_error NOT IN ('CORE_EXECUTION_OUTCOME_UNKNOWN','DESKTOP_EXECUTION_OUTCOME_UNKNOWN'))",
            arrayOf(nowMillis.toString()))
    }

    /**
     * Atomically claims one eligible task and increments its attempt count.
     * A worker crash after this point is recovered by a later Core recovery pass.
     */
    @Synchronized
    override fun claimNext(nowMillis: Long): GlafiraTask? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            expirePendingTasks(db, nowMillis)
            failTasksWithFailedDependencies(db, nowMillis)
            val taskId = db.rawQuery(
                "SELECT task_id FROM $TABLE_TASKS t " +
                    "WHERE state IN (?, ?) AND task_type != ? " +
                    "AND (retry_at IS NULL OR retry_at <= ?) " +
                    "AND (deadline_at IS NULL OR deadline_at >= ?) " +
                    "AND NOT EXISTS (SELECT 1 FROM $TABLE_DEPENDENCIES d " +
                    "JOIN $TABLE_TASKS p ON p.task_id = d.depends_on_task_id " +
                    "WHERE d.task_id = t.task_id AND p.state != ?) " +
                    "ORDER BY priority DESC, created_at ASC LIMIT 1",
                arrayOf(
                    GlafiraTaskState.QUEUED.databaseValue,
                    GlafiraTaskState.RETRY_SCHEDULED.databaseValue,
                    DESKTOP_TASK_TYPE,
                    nowMillis.toString(),
                    nowMillis.toString(),
                    GlafiraTaskState.SUCCEEDED.databaseValue
                )
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

            if (taskId == null) {
                db.setTransactionSuccessful()
                return null
            }

            val candidate = queryTask(db, taskId)
                ?: throw IllegalStateException("Claim candidate disappeared inside a transaction.")
            val values = ContentValues().apply {
                put("state", GlafiraTaskState.RUNNING.databaseValue)
                put("updated_at", nowMillis)
                put("attempt_count", candidate.attemptCount + 1)
                putNull("retry_at")
            }
            val changed = db.update(
                TABLE_TASKS,
                values,
                "task_id = ? AND state = ?",
                arrayOf(taskId, candidate.state.databaseValue)
            )
            check(changed == 1) { "Task claim lost its transactional state check." }
            db.setTransactionSuccessful()
            return queryTask(db, taskId)
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
            val interrupted = db.query(TABLE_TASKS, null, "state = ? AND task_type != ?",
                arrayOf(GlafiraTaskState.RUNNING.databaseValue, DESKTOP_TASK_TYPE),
                null, null, null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTask()) } }
            for (task in interrupted) {
                val decision = GlafiraTaskRecoveryPolicy.interrupted(task, maxRetries)
                transition(task.taskId, setOf(GlafiraTaskState.RUNNING), decision.state, nowMillis,
                    retryAtMillis = if (decision.state == GlafiraTaskState.RETRY_SCHEDULED) nowMillis else null,
                    lastError = decision.reason)
            }
            db.setTransactionSuccessful()
            return interrupted.size
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
    ): Boolean {
        require(nowMillis > 0L && (retryAtMillis == null || retryAtMillis >= nowMillis))
        require(expectedStates.isNotEmpty()) { "At least one expected task state is required." }
        require(expectedStates.all { GlafiraTaskPolicy.canTransition(it, nextState) }) {
            "The requested GLAFIRA task state transition is not allowed."
        }
        require(
            (nextState == GlafiraTaskState.RETRY_SCHEDULED) == (retryAtMillis != null)
        ) { "Only retry_scheduled tasks may have a retry timestamp." }

        val values = ContentValues().apply {
            put("state", nextState.databaseValue)
            put("updated_at", nowMillis)
            if (retryAtMillis == null) putNull("retry_at") else put("retry_at", retryAtMillis)
            if (lastError.isNullOrBlank()) putNull("last_error") else put("last_error", lastError.take(2000))
        }
        val placeholders = expectedStates.joinToString(",") { "?" }
        val args = arrayOf(taskId) + expectedStates.map { it.databaseValue }.toTypedArray()
        return writableDatabase.update(
            TABLE_TASKS,
            values,
            "task_id = ? AND state IN ($placeholders)",
            args
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
    ): Boolean {
        require(taskId.isNotBlank() && nowMillis > 0L)
        require(expectedStates == setOf(GlafiraTaskState.RUNNING))
        require(result.length <= GlafiraCoreTaskResultPersistence.MAX_RESULT_CHARS) { "CORE_TASK_RESULT_TOO_LARGE" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val task = queryTask(db, taskId) ?: return false
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
                "task_id = ? AND state = ?",
                arrayOf(taskId, GlafiraTaskState.RUNNING.databaseValue)
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

    /** Propagates failed predecessor states through the entire acyclic task graph. */
    private fun failTasksWithFailedDependencies(db: SQLiteDatabase, nowMillis: Long) {
        val values = ContentValues().apply {
            put("state", GlafiraTaskState.FAILED.databaseValue)
            put("updated_at", nowMillis)
            put("last_error", "DEPENDENCY_FAILED")
            putNull("retry_at")
        }
        val selection = "state IN (?, ?) AND task_id IN (" +
            "SELECT d.task_id FROM $TABLE_DEPENDENCIES d " +
            "JOIN $TABLE_TASKS p ON p.task_id = d.depends_on_task_id " +
            "WHERE p.state IN (?, ?))"
        val args = arrayOf(
            GlafiraTaskState.QUEUED.databaseValue,
            GlafiraTaskState.RETRY_SCHEDULED.databaseValue,
            GlafiraTaskState.FAILED.databaseValue,
            GlafiraTaskState.CANCELLED.databaseValue
        )
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
                         maxParallelTasks: Int, nowMillis: Long = System.currentTimeMillis()): GlafiraTask? {
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
            db.insertWithOnConflict("core_desktop_leases", null, ContentValues().apply {
                put("task_id", taskId); put("device_id", deviceId)
                put("lease_expires_at", nowMillis + DESKTOP_LEASE_MS)
                put("attempt_count", task.attemptCount + 1); put("progress_percent", 0); put("updated_at", nowMillis)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return queryTask(db, taskId)
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun releaseDesktopLease(taskId: String, deviceId: String): Boolean =
        writableDatabase.delete("core_desktop_leases", "task_id = ? AND device_id = ?",
            arrayOf(taskId, deviceId)) == 1

    @Synchronized
    fun updateDesktopTask(taskId: String, deviceId: String, state: String, progressPercent: Int,
                          progressMessage: String?, result: String?, errorCode: String?,
                          nowMillis: Long = System.currentTimeMillis()): Boolean {
        require(progressPercent in 0..100)
        require(progressMessage == null || progressMessage.length <= 500)
        require(result == null || result.length <= 24_000)
        require(errorCode == null || errorCode.matches(Regex("[A-Z0-9_]{1,64}")))
        require(state in setOf("ACCEPTED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"))
        val db = writableDatabase
        db.beginTransaction()
        try {
            val lease = db.query("core_desktop_leases", arrayOf("device_id", "lease_expires_at"),
                "task_id = ?", arrayOf(taskId), null, null, null, "1").use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
            } ?: return false
            if (lease.first != deviceId) return false
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
                }, SQLiteDatabase.CONFLICT_REPLACE)
                db.delete("core_desktop_leases", "task_id = ?", arrayOf(taskId))
            } else {
                if (lease.second <= nowMillis || task.state != GlafiraTaskState.RUNNING) return false
                db.update("core_desktop_leases", ContentValues().apply {
                    put("lease_expires_at", nowMillis + DESKTOP_LEASE_MS)
                    put("progress_percent", progressPercent)
                    if (progressMessage != null) put("progress_message", progressMessage)
                    put("updated_at", nowMillis)
                }, "task_id = ? AND device_id = ?", arrayOf(taskId, deviceId))
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
     * Checkpoints SQLite and exports the closed, consistent database file.
     * The caller must hold GlafiraCoreMaintenanceGate for the whole snapshot operation.
     */
    @Synchronized
    internal fun exportConsistentSnapshot(destination: File) {
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
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
        validateDatabaseSnapshot(destination)
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
        private const val DATABASE_VERSION = 6
        private const val DESKTOP_LEASE_MS = 10 * 60_000L
        const val DESKTOP_TASK_TYPE = "desktop.execute"
        private const val TABLE_TASKS = "core_tasks"
        private const val TABLE_CORE_RESULTS = "core_task_results"
        private const val TABLE_DEPENDENCIES = "core_task_dependencies"
    }
}
