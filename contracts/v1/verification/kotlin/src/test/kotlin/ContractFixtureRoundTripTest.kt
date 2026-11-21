package glafira.contracts.v1.verification

import glafira.contracts.v1.generated.agentenrollmentdecision.AgentEnrollmentDecision
import glafira.contracts.v1.generated.agentenrollmentrequest.AgentEnrollmentRequest
import glafira.contracts.v1.generated.agentheartbeat.AgentHeartbeat
import glafira.contracts.v1.generated.agentrevocationdecision.AgentRevocationDecision
import glafira.contracts.v1.generated.agentrevocationrequest.AgentRevocationRequest
import glafira.contracts.v1.generated.taskenvelope.TaskEnvelope
import glafira.contracts.v1.generated.tasklease.TaskLease
import glafira.contracts.v1.generated.taskresultenvelope.TaskResultEnvelope
import glafira.contracts.v1.generated.taskstatusevent.TaskStatusEvent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Test

class ContractFixtureRoundTripTest {
    @Test
    fun allNineMessageFamiliesDecodeAndReencode() {
        val json = Json { explicitNulls = false }
        val fixture = javaClass.getResourceAsStream("/valid-messages.json")!!
            .bufferedReader()
            .use { it.readText() }
        val messages = json.parseToJsonElement(fixture).jsonArray

        val task = json.decodeFromString<TaskEnvelope>(messages[0].toString())
        assertEquals("task-000001", task.taskID)
        assertEquals("task-000001", json.decodeFromString<TaskEnvelope>(
            json.encodeToString(TaskEnvelope.serializer(), task)
        ).taskID)

        val result = json.decodeFromString<TaskResultEnvelope>(messages[1].toString())
        assertEquals("task-000002", result.taskID)

        val status = json.decodeFromString<TaskStatusEvent>(messages[2].toString())
        assertEquals("task-000001", status.taskID)

        val heartbeat = json.decodeFromString<AgentHeartbeat>(messages[3].toString())
        assertEquals("agent-00001", heartbeat.agentID)

        val enrollment = json.decodeFromString<AgentEnrollmentRequest>(messages[4].toString())
        assertEquals("agent-00001", enrollment.agentID)

        val decision = json.decodeFromString<AgentEnrollmentDecision>(messages[5].toString())
        assertEquals("agent-00001", decision.agentID)

        val revocation = json.decodeFromString<AgentRevocationRequest>(messages[6].toString())
        assertEquals("enroll-001", revocation.enrollmentID)

        val revocationDecision = json.decodeFromString<AgentRevocationDecision>(messages[7].toString())
        assertEquals(7L, revocationDecision.lastAcceptedFencingToken)

        val lease = json.decodeFromString<TaskLease>(messages[8].toString())
        assertEquals("task-000001", lease.taskID)
    }
}
