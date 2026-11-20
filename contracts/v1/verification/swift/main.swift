import Foundation

let fixtureURL = URL(fileURLWithPath: "contracts/v1/fixtures/valid-messages.json")
let fixtureData = try Data(contentsOf: fixtureURL)
let rawMessages = try JSONSerialization.jsonObject(with: fixtureData) as! [[String: Any]]

func messageData(_ index: Int) throws -> Data {
    try JSONSerialization.data(withJSONObject: rawMessages[index])
}

let task = try TaskEnvelope(data: messageData(0))
precondition(task.taskID == "task-000001")

let result = try TaskResultEnvelope(data: messageData(1))
precondition(result.taskID == "task-000002")

let status = try TaskStatusEvent(data: messageData(2))
precondition(status.taskID == "task-000001")

let heartbeat = try AgentHeartbeat(data: messageData(3))
precondition(heartbeat.agentID == "agent-00001")

let enrollment = try AgentEnrollmentRequest(data: messageData(4))
precondition(enrollment.agentID == "agent-00001")

let decision = try AgentEnrollmentDecision(data: messageData(5))
precondition(decision.agentID == "agent-00001")

let lease = try TaskLease(data: messageData(6))
precondition(lease.taskID == "task-000001")

print("Swift contract fixture round-trip checks passed for all seven message families.")
