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

console.log(
  `Contract schema: ${validMessages.length} valid messages accepted, ${invalidMessages.length} invalid messages rejected.`
);
console.log("Bridge v1 Unicode HMAC golden vector: PASS");
