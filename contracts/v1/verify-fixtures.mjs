import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { readFile } from "node:fs/promises";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const readJson = async path =>
  JSON.parse(await readFile(new URL(path, import.meta.url), "utf8"));

const schema = await readJson("./glafira-task-agent.schema.json");
const signingFixture = await readJson("./fixtures/bridge-hmac-unicode.json");
const validMessages = await readJson("./fixtures/valid-messages.json");
const invalidMessages = await readJson("./fixtures/invalid-messages.json");
const lifecycleScenarios = await readJson("./fixtures/task-lifecycle-scenarios.json");
const ownershipSchema = await readJson("./dual-core-ownership.schema.json");
const ownershipFixture = await readJson("./fixtures/ownership-transfer-valid.json");

assert.equal(schema.$schema, "https://json-schema.org/draft/2020-12/schema");
for (const name of [
  "TaskEnvelope", "TaskResultEnvelope", "TaskStatusEvent", "AgentHeartbeat",
  "AgentEnrollmentRequest", "AgentEnrollmentDecision", "TaskLease"
]) {
  assert.ok(schema.$defs[name], `Missing schema definition: ${name}`);
}

function canonicalize(request) {
  const fields = [
    String(request.protocolVersion),
    request.requestId,
    request.idempotencyKey,
    request.deviceId,
    request.projectId,
    request.operation,
    String(request.issuedAtEpochMs),
    String(request.expiresAtEpochMs)
  ];
  for (const key of Object.keys(request.payload).sort()) {
    fields.push(key, request.payload[key]);
  }
  // String.length is UTF-16 code units, matching Kotlin String.length and
  // Swift String.utf16.count used by the current Bridge v1 implementations.
  return fields.map(value => `${value.length}:${value}`).join("");
}

const canonical = canonicalize(signingFixture.request);
assert.equal(canonical, signingFixture.canonicalUtf8, "Canonical Bridge bytes changed");
const digest = createHmac("sha256", Buffer.from(signingFixture.secretHex, "hex"))
  .update(Buffer.from(canonical, "utf8"))
  .digest("hex");
assert.equal(digest, signingFixture.hmacSha256, "Bridge HMAC golden vector mismatch");

const reversed = {
  ...signingFixture.request,
  payload: Object.fromEntries(Object.entries(signingFixture.request.payload).reverse())
};
assert.equal(canonicalize(reversed), canonical, "Payload key order changed canonical bytes");

const ownershipAjv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ownershipAjv);
const validateOwnership = ownershipAjv.compile(ownershipSchema);
const transfer = ownershipFixture.envelope;
assert.equal(validateOwnership(transfer), true,
  `Valid ownership transfer rejected: ${JSON.stringify(validateOwnership.errors)}`);

const ownershipCanonicalFields = [
  "schemaVersion", "transferId", "nonce", "taskId", "projectId",
  "oldOwnerCoreId", "newOwnerCoreId", "oldOwnerGeneration", "newOwnerGeneration",
  "stateVersion", "payloadSha256", "idempotencyKey", "effectRisk", "approvalRef",
  "issuedAt", "expiresAt", "signingKeyEpoch", "signingPublicKeySha256",
  "signatureAlgorithm"
];
const ownershipCanonical = ownershipCanonicalFields
  .map(key => transfer[key] === null ? "null" : String(transfer[key]))
  .map(value => `${value.length}:${value}`)
  .join("");
assert.equal(ownershipCanonical, ownershipFixture.canonicalUtf8,
  "Ownership transfer canonical field order or UTF-16 length-prefix encoding changed");

function assertInvalidOwnership(mutator, reason) {
  const candidate = structuredClone(transfer);
  mutator(candidate);
  assert.equal(validateOwnership(candidate), false, reason);
}
function assertInvalidOwnershipSemantics(mutator, reason) {
  const candidate = structuredClone(transfer);
  mutator(candidate);
  const approvalValid =
    !["EXTERNAL_SIDE_EFFECT", "FINANCIAL", "DESTRUCTIVE"].includes(candidate.effectRisk) ||
    candidate.approvalRef !== null;
  const valid = candidate.oldOwnerCoreId !== candidate.newOwnerCoreId &&
    candidate.newOwnerGeneration === candidate.oldOwnerGeneration + 1 &&
    Date.parse(candidate.expiresAt) > Date.parse(candidate.issuedAt) &&
    approvalValid;
  assert.equal(valid, false, reason);
}
assertInvalidOwnership(item => { item.unreviewedField = true; },
  "Unknown ownership transfer fields must be rejected");
assertInvalidOwnership(item => { item.newOwnerGeneration = 0; },
  "Non-positive ownership generation must be rejected");
assertInvalidOwnership(item => { item.payloadSha256 = "not-a-digest"; },
  "Malformed task payload digest must be rejected");
assertInvalidOwnershipSemantics(item => { item.newOwnerCoreId = item.oldOwnerCoreId; },
  "An owner cannot transfer a task to itself");
assertInvalidOwnershipSemantics(item => { item.newOwnerGeneration = item.oldOwnerGeneration + 2; },
  "Ownership generation must increment exactly once");
assertInvalidOwnershipSemantics(item => { item.expiresAt = item.issuedAt; },
  "Ownership transfer must expire after issue time");
assertInvalidOwnershipSemantics(item => {
  item.effectRisk = "FINANCIAL";
  item.approvalRef = null;
}, "Financial or destructive transfers require an explicit approval reference");

const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
const validate = ajv.compile(schema);
for (const [index, message] of validMessages.entries()) {
  assert.equal(validate(message), true,
    `Valid fixture ${index} rejected: ${JSON.stringify(validate.errors)}`);
}
for (const [index, message] of invalidMessages.entries()) {
  assert.equal(validate(message), false,
    `Invalid fixture ${index} unexpectedly passed schema validation`);
}

const scenarioIds = lifecycleScenarios.scenarios.map(scenario => scenario.id);
assert.equal(lifecycleScenarios.schemaVersion, 1, "Unsupported lifecycle fixture version");
assert.equal(new Set(scenarioIds).size, scenarioIds.length, "Duplicate lifecycle scenario ID");
const requiredScenarios = [
  "duplicate-delivery-001",
  "dependency-gate-001",
  "lease-fencing-001",
  "agent-revocation-001",
  "unknown-side-effect-001"
];
for (const id of requiredScenarios) {
  assert.ok(scenarioIds.includes(id), `Missing lifecycle scenario: ${id}`);
}
const scenario = id => lifecycleScenarios.scenarios.find(item => item.id === id);
assert.equal(scenario("duplicate-delivery-001").expect.executionCount, 1);
assert.equal(scenario("dependency-gate-001").expect.childClaimBeforeParentSuccess, false);
assert.equal(scenario("lease-fencing-001").expect.staleResultAccepted, false);
assert.equal(scenario("agent-revocation-001").expect.claimAccepted, false);
assert.equal(scenario("unknown-side-effect-001").expect.outcome, "OUTCOME_UNKNOWN");
assert.equal(scenario("unknown-side-effect-001").expect.retryable, false);
assert.equal(scenario("unknown-side-effect-001").expect.automaticReplayAllowed, false);

console.log(
  `Contract schema: ${validMessages.length} valid messages accepted, ${invalidMessages.length} invalid messages rejected.`
);
console.log(`Shared lifecycle scenarios: ${lifecycleScenarios.scenarios.length} invariants checked.`);
console.log("Bridge v1 Unicode HMAC golden vector: PASS");
console.log("Dual-Core ownership transfer schema and canonicalization fixtures: PASS");
