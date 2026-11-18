package com.micronizationlab.glafira

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Trace sanitization is allow-list based: arbitrary descriptions, prompts and payload
 * text are never accepted as attributes. Unknown keys and non-token values are dropped.
 */
internal object GlafiraTraceSanitizer {
    private val allowedAttributeKeys = setOf(
        "scopeType", "classification", "kind", "status", "errorCode", "reasonCode",
        "providerId", "modelId", "attempt", "retryCount", "durationMs", "bytes",
        "itemCount", "schemaVersion", "reviewVerdict", "independence", "operation",
        "queueDepth", "resultCode", "componentVersion", "toolId"
    )
    private val safeToken = Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}")
    private fun isSafeIdentifier(value: String) =
        value.matches(safeToken) && GlafiraSecretRedactor.redact(value) == value

    fun sanitize(event: CoreTraceEvent): CoreTraceEvent {
        require(!event.secretValuesStored) { "Trace cannot persist secret values" }
        require(isSafeIdentifier(event.eventId.value))
        require(isSafeIdentifier(event.correlationId.value))
        require(isSafeIdentifier(event.actor.id))
        require(event.projectId == null || isSafeIdentifier(event.projectId.value))
        require(event.taskId == null || isSafeIdentifier(event.taskId.value))
        require(event.parentTaskId == null || isSafeIdentifier(event.parentTaskId.value))
        require(event.causationId == null || isSafeIdentifier(event.causationId.value))

        var redacted = event.redactionApplied
        fun safe(value: String?): String? {
            if (value == null) return null
            val masked = GlafiraSecretRedactor.redact(value)
            if (masked != value || !masked.matches(safeToken)) {
                redacted = true
                return null
            }
            return masked
        }

        val attributes = linkedMapOf<String, String>()
        event.safeAttributes.forEach { (key, value) ->
            if (key !in allowedAttributeKeys) {
                redacted = true
            } else {
                val masked = GlafiraSecretRedactor.redact(value)
                if (masked != value || !masked.matches(safeToken)) {
                    redacted = true
                } else {
                    attributes[key] = masked
                }
            }
        }
        val evidence = event.evidenceRefs.mapNotNull(::safe)
        if (evidence.size != event.evidenceRefs.size) redacted = true
        return event.copy(
            component = safe(event.component) ?: "REDACTED",
            action = safe(event.action) ?: "REDACTED",
            targetType = safe(event.targetType),
            targetId = safe(event.targetId),
            resultCode = safe(event.resultCode),
            approvalRef = safe(event.approvalRef),
            evidenceRefs = evidence,
            safeAttributes = attributes,
            redactionApplied = redacted
        )
    }
}

class GlafiraSqliteTraceRepository(
    private val database: GlafiraCoreDatabase
) : TraceRepository {

    override suspend fun append(event: CoreTraceEvent) = withContext(Dispatchers.IO) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            appendInTransaction(db, event)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    internal fun appendInTransaction(db: SQLiteDatabase, event: CoreTraceEvent) {
        db.insertOrThrow(TABLE, null, GlafiraTraceSanitizer.sanitize(event).toValues())
    }

    override suspend fun query(query: TraceQuery, access: TraceAccessContext): TracePage =
        withContext(Dispatchers.IO) {
            if (access.allowedProjects.isEmpty()) return@withContext TracePage(emptyList())
            if (query.projectId != null && query.projectId !in access.allowedProjects) {
                return@withContext TracePage(emptyList())
            }
            val clauses = mutableListOf<String>()
            val args = mutableListOf<String>()
            if (query.projectId != null) {
                clauses += "project_id = ?"
                args += query.projectId.value
            } else {
                val ids = access.allowedProjects.map { it.value }
                clauses += "project_id IN (${ids.joinToString(",") { "?" }})"
                args.addAll(ids)
            }
            query.taskId?.let { clauses += "task_id = ?"; args += it.value }
            if (query.eventTypes.isNotEmpty()) {
                clauses += "event_type IN (${query.eventTypes.joinToString(",") { "?" }})"
                args.addAll(query.eventTypes.map { it.name })
            }
            query.after?.let { clauses += "occurred_at >= ?"; args += it.toString() }
            query.before?.let { clauses += "occurred_at <= ?"; args += it.toString() }

            val result = mutableListOf<CoreTraceEvent>()
            database.readableDatabase.query(
                TABLE, null, clauses.joinToString(" AND "), args.toTypedArray(),
                null, null, "occurred_at DESC, event_id DESC", query.limit.toString()
            ).use { cursor ->
                while (cursor.moveToNext()) result += cursor.toEvent()
            }
            TracePage(result)
        }

    private fun CoreTraceEvent.toValues() = ContentValues().apply {
        put("event_id", eventId.value)
        put("schema_version", schemaVersion)
        put("occurred_at", occurredAt.toString())
        put("recorded_at", recordedAt.toString())
        put("event_type", eventType.name)
        put("severity", severity.name)
        put("actor_id", actor.id)
        put("actor_type", actor.type.name)
        put("project_id", projectId?.value)
        put("task_id", taskId?.value)
        put("parent_task_id", parentTaskId?.value)
        put("correlation_id", correlationId.value)
        put("causation_id", causationId?.value)
        put("component", component)
        put("action", action)
        put("target_type", targetType)
        put("target_id", targetId)
        put("result_code", resultCode)
        put("approval_ref", approvalRef)
        put("evidence_refs", JSONArray(evidenceRefs).toString())
        put("data_classification", dataClassification.name)
        put("redaction_applied", if (redactionApplied) 1 else 0)
        put("duration_ms", durationMs)
        put("safe_attributes", JSONObject(safeAttributes).toString())
    }

    private fun Cursor.nullable(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.toEvent(): CoreTraceEvent {
        val refs = JSONArray(getString(getColumnIndexOrThrow("evidence_refs")))
        val evidence = buildList { for (i in 0 until refs.length()) add(refs.getString(i)) }
        val attributesJson = JSONObject(getString(getColumnIndexOrThrow("safe_attributes")))
        val attributes = linkedMapOf<String, String>()
        attributesJson.keys().forEach { key -> attributes[key] = attributesJson.getString(key) }
        val durationIndex = getColumnIndexOrThrow("duration_ms")
        return CoreTraceEvent(
            eventId = TraceEventIdentifier(getString(getColumnIndexOrThrow("event_id"))),
            schemaVersion = getInt(getColumnIndexOrThrow("schema_version")),
            occurredAt = Instant.parse(getString(getColumnIndexOrThrow("occurred_at"))),
            recordedAt = Instant.parse(getString(getColumnIndexOrThrow("recorded_at"))),
            eventType = CoreTraceEventType.valueOf(getString(getColumnIndexOrThrow("event_type"))),
            severity = CoreTraceSeverity.valueOf(getString(getColumnIndexOrThrow("severity"))),
            actor = CoreActor(
                getString(getColumnIndexOrThrow("actor_id")),
                CoreActorType.valueOf(getString(getColumnIndexOrThrow("actor_type")))
            ),
            projectId = nullable("project_id")?.let(::ProjectIdentifier),
            taskId = nullable("task_id")?.let(::TaskIdentifier),
            parentTaskId = nullable("parent_task_id")?.let(::TaskIdentifier),
            correlationId = CoreIdentifier(getString(getColumnIndexOrThrow("correlation_id"))),
            causationId = nullable("causation_id")?.let(::TraceEventIdentifier),
            component = getString(getColumnIndexOrThrow("component")),
            action = getString(getColumnIndexOrThrow("action")),
            targetType = nullable("target_type"),
            targetId = nullable("target_id"),
            resultCode = nullable("result_code"),
            approvalRef = nullable("approval_ref"),
            evidenceRefs = evidence,
            dataClassification = CoreDataClassification.valueOf(getString(getColumnIndexOrThrow("data_classification"))),
            redactionApplied = getInt(getColumnIndexOrThrow("redaction_applied")) != 0,
            secretValuesStored = false,
            durationMs = if (isNull(durationIndex)) null else getLong(durationIndex),
            safeAttributes = attributes
        )
    }

    companion object {
        private const val TABLE = "trace_events"
    }
}

/** Applies sanitization before every append, including memory writes in their transaction. */
class GlafiraTraceService(
    private val repository: GlafiraSqliteTraceRepository
) : TraceService {
    override suspend fun record(event: CoreTraceEvent) {
        repository.append(event)
    }

    override suspend fun query(query: TraceQuery, access: TraceAccessContext): TracePage =
        repository.query(query, access)

    internal fun recordInTransaction(db: SQLiteDatabase, event: CoreTraceEvent) {
        repository.appendInTransaction(db, event)
    }
}
