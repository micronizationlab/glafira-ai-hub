using System.Text.Json;
using Glafira.Contracts.V1.Generated.AgentEnrollmentDecision;
using Glafira.Contracts.V1.Generated.AgentEnrollmentRequest;
using Glafira.Contracts.V1.Generated.AgentHeartbeat;
using Glafira.Contracts.V1.Generated.TaskEnvelope;
using Glafira.Contracts.V1.Generated.TaskLease;
using Glafira.Contracts.V1.Generated.TaskResultEnvelope;
using Glafira.Contracts.V1.Generated.TaskStatusEvent;

var fixture = File.ReadAllText("contracts/v1/fixtures/valid-messages.json");
var messages = JsonDocument.Parse(fixture).RootElement.EnumerateArray().ToArray();

var task = TaskEnvelope.FromJson(messages[0].GetRawText());
if (task.TaskId != "task-000001") throw new InvalidOperationException("TaskEnvelope fixture mismatch.");

var result = TaskResultEnvelope.FromJson(messages[1].GetRawText());
if (result.TaskId != "task-000002") throw new InvalidOperationException("TaskResultEnvelope fixture mismatch.");

var status = TaskStatusEvent.FromJson(messages[2].GetRawText());
if (status.TaskId != "task-000001") throw new InvalidOperationException("TaskStatusEvent fixture mismatch.");

var heartbeat = AgentHeartbeat.FromJson(messages[3].GetRawText());
if (heartbeat.AgentId != "agent-00001") throw new InvalidOperationException("AgentHeartbeat fixture mismatch.");

var enrollment = AgentEnrollmentRequest.FromJson(messages[4].GetRawText());
if (enrollment.AgentId != "agent-00001") throw new InvalidOperationException("AgentEnrollmentRequest fixture mismatch.");

var decision = AgentEnrollmentDecision.FromJson(messages[5].GetRawText());
if (decision.AgentId != "agent-00001") throw new InvalidOperationException("AgentEnrollmentDecision fixture mismatch.");

var lease = TaskLease.FromJson(messages[6].GetRawText());
if (lease.TaskId != "task-000001") throw new InvalidOperationException("TaskLease fixture mismatch.");

Console.WriteLine("C# contract fixture round-trip checks passed for all seven message families.");
