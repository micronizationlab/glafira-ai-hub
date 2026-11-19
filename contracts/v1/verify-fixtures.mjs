import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { readFile } from "node:fs/promises";

const schema = JSON.parse(await readFile(new URL("./glafira-task-agent.schema.json", import.meta.url), "utf8"));
const fixture = JSON.parse(await readFile(new URL("./fixtures/bridge-hmac-unicode.json", import.meta.url), "utf8"));

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
  // JavaScript String.length is UTF-16 code units, matching Kotlin String.length
  // and Swift String.utf16.count used by the current Bridge v1 implementations.
  return fields.map(value => `${value.length}:${value}`).join("");
}

const canonical = canonicalize(fixture.request);
assert.equal(canonical, fixture.canonicalUtf8, "Canonical Bridge bytes changed");
const digest = createHmac("sha256", Buffer.from(fixture.secretHex, "hex"))
  .update(Buffer.from(canonical, "utf8"))
  .digest("hex");
assert.equal(digest, fixture.hmacSha256, "Bridge HMAC golden vector mismatch");

// Payload insertion order must not affect the signature.
const reversed = {
  ...fixture.request,
  payload: Object.fromEntries(Object.entries(fixture.request.payload).reverse())
};
assert.equal(canonicalize(reversed), canonical, "Payload key order changed canonical bytes");

console.log("Contract schema structure and Bridge v1 Unicode HMAC golden vector: PASS");
