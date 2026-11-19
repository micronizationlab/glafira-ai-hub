package com.micronizationlab.glafira.desktop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopDryRunPreviewTest {
    private fun validJson(): JSONObject = JSONObject().apply {
        put("mode", "DRY_RUN")
        put("taskId", "task-12345678")
        put("projectId", "project-123")
        put("toolId", "desktop.execute")
        put("risk", "DRAFT")
        put("simulationStatus", "PREVIEW_ONLY")
        put("targetFields", JSONArray().put("projectId"))
        put("expectedChanges", JSONArray().put("Задание не запускается."))
        put("requiredPermissions", JSONArray().put("desktop.task.execute"))
        put("approvalPoints", JSONArray())
        put("authorizationStatus", "WAITING_FOR_OWNER")
        put("authorizationReason", JSONObject.NULL)
        put("planHash", "a".repeat(64))
        put("limitations", JSONArray().put("Preview only."))
    }

    @Test fun acceptsWellFormedRedactedPreview() {
        val preview = DesktopDryRunPreviewCodec.decode(validJson().toString())
        assertEquals("task-12345678", preview.taskId)
        assertEquals("PREVIEW_ONLY", preview.simulationStatus)
    }

    @Test fun rejectsLiveModeEnvelope() {
        val json = validJson().put("mode", "LIVE")
        assertThrows(IllegalArgumentException::class.java) {
            DesktopDryRunPreviewCodec.decode(json.toString())
        }
    }

    @Test fun rejectsMissingSchemaField() {
        val json = validJson().apply { remove("approvalPoints") }
        assertThrows(IllegalArgumentException::class.java) {
            DesktopDryRunPreviewCodec.decode(json.toString())
        }
    }

    @Test fun rejectsUnexpectedSchemaField() {
        val json = validJson().put("instruction", "do the work")
        assertThrows(IllegalArgumentException::class.java) {
            DesktopDryRunPreviewCodec.decode(json.toString())
        }
    }

    @Test fun rejectsMalformedPlanHash() {
        val json = validJson().put("planHash", "not-a-hash")
        assertThrows(IllegalArgumentException::class.java) {
            DesktopDryRunPreviewCodec.decode(json.toString())
        }
    }

    @Test fun rejectsUnknownRiskLevel() {
        val json = validJson().put("risk", "TRUST_ME")
        assertThrows(IllegalArgumentException::class.java) {
            DesktopDryRunPreviewCodec.decode(json.toString())
        }
    }

    @Test fun rejectsUnknownSimulationStatus() {
        val json = validJson().put("simulationStatus", "EXECUTED")
        assertThrows(IllegalArgumentException::class.java) {
            DesktopDryRunPreviewCodec.decode(json.toString())
        }
    }

    @Test fun rejectsUnknownAuthorizationStatus() {
        val json = validJson().put("authorizationStatus", "APPROVED")
        assertThrows(IllegalArgumentException::class.java) {
            DesktopDryRunPreviewCodec.decode(json.toString())
        }
    }

    @Test fun rejectsPayloadFieldNamesOutsideSafeAllowlist() {
        val json = validJson().put("targetFields", JSONArray().put("instruction"))
        assertThrows(IllegalArgumentException::class.java) {
            DesktopDryRunPreviewCodec.decode(json.toString())
        }
    }

    @Test fun rejectsMalformedOrOversizedStringLists() {
        val malformed = validJson().put("expectedChanges", JSONArray().put(42))
        assertThrows(IllegalArgumentException::class.java) {
            DesktopDryRunPreviewCodec.decode(malformed.toString())
        }
        val oversized = validJson().put("limitations", JSONArray().apply {
            repeat(17) { put("limit-$it") }
        })
        assertThrows(IllegalArgumentException::class.java) {
            DesktopDryRunPreviewCodec.decode(oversized.toString())
        }
    }
}
