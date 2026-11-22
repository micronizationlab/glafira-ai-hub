import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const schema = JSON.parse(readFileSync(resolve(here, "glafira-task-agent.schema.json"), "utf8"));
const outputRoot = resolve(here, "generated");
const temporaryDirectory = mkdtempSync(resolve(tmpdir(), "glafira-contracts-"));
const quicktype = resolve(here, "../../node_modules/.bin/quicktype");

const definitions = [
  "TaskEnvelope",
  "TaskResultEnvelope",
  "TaskStatusEvent",
  "AgentHeartbeat",
  "AgentEnrollmentRequest",
  "AgentEnrollmentDecision",
  "AgentRevocationRequest",
  "AgentRevocationDecision",
  "TaskLease"
];

const targets = [
  { language: "kotlin", extension: "kt", extra: name => ["--framework", "kotlinx", "--package", `glafira.contracts.v1.generated.${name.toLowerCase()}`] },
  { language: "swift", extension: "swift", extra: () => ["--access-level", "public"] },
  { language: "csharp", extension: "cs", extra: name => ["--namespace", `Glafira.Contracts.V1.Generated.${name}`] }
];

function referencedDefinitions(rootDefinition) {
  const pending = [rootDefinition];
  const names = new Set();
  while (pending.length > 0) {
    const value = pending.pop();
    if (Array.isArray(value)) {
      pending.push(...value);
      continue;
    }
    if (!value || typeof value !== "object") continue;
    if (typeof value.$ref === "string") {
      const match = /^#\/\$defs\/([^/]+)$/.exec(value.$ref);
      if (match && !names.has(match[1])) {
        const referenced = schema.$defs?.[match[1]];
        if (!referenced) throw new Error(`Missing referenced schema definition: ${match[1]}`);
        names.add(match[1]);
        pending.push(referenced);
      }
    }
    pending.push(...Object.values(value));
  }
  return Object.fromEntries([...names].sort().map(name => [name, schema.$defs[name]]));
}

try {
  for (const definitionName of definitions) {
    const definition = schema.$defs?.[definitionName];
    if (!definition || definition.type !== "object") {
      throw new Error(`Missing object schema definition: ${definitionName}`);
    }

    const isolatedSchema = {
      $schema: schema.$schema,
      title: definitionName,
      $defs: referencedDefinitions(definition),
      ...definition
    };
    const input = resolve(temporaryDirectory, `${definitionName}.schema.json`);
    writeFileSync(input, JSON.stringify(isolatedSchema, null, 2));

    for (const target of targets) {
      const directory = resolve(outputRoot, target.language);
      mkdirSync(directory, { recursive: true });
      const output = resolve(directory, `${definitionName}.${target.extension}`);
      execFileSync(
        quicktype,
        [
          "--src", input,
          "--src-lang", "schema",
          "--lang", target.language,
          "--top-level", definitionName,
          "--out", output,
          ...target.extra(definitionName)
        ],
        { stdio: "inherit" }
      );
      const generated = readFileSync(output, "utf8");
      if (!generated.includes(definitionName)) {
        throw new Error(`Generated ${target.language} file does not contain ${definitionName}`);
      }
      console.log(`Generated ${target.language} ${definitionName}`);
    }
  }

  // quicktype emits the same file-level JSON encoder/decoder helpers into every
  // standalone Swift message file. Keep one shared definition so the files
  // compile together as a single Swift module.
  const swiftDirectory = resolve(outputRoot, "swift");
  const swiftFiles = readdirSync(swiftDirectory)
    .filter(filename => filename.endsWith(".swift"))
    .sort();
  const helperMarker = "// MARK: - Helper functions for creating encoders and decoders";
  let sharedSwiftHelpersKept = false;
  for (const filename of swiftFiles) {
    const output = resolve(swiftDirectory, filename);
    const generated = readFileSync(output, "utf8");
    const markerIndex = generated.indexOf(helperMarker);
    if (markerIndex < 0) {
      throw new Error(`Swift DTO file is missing shared JSON helpers: ${filename}`);
    }
    if (!sharedSwiftHelpersKept) {
      sharedSwiftHelpersKept = true;
      continue;
    }
    writeFileSync(output, generated.slice(0, markerIndex).trimEnd() + "\n");
  }

  // Standalone quicktype invocations can emit generic module-level names such as `Status`
  // in more than one DTO file. Swift compiles all generated files as one module, so make
  // only colliding auxiliary types deterministic and message-scoped.
  const swiftTypeOwners = new Map();
  for (const filename of swiftFiles) {
    const generated = readFileSync(resolve(swiftDirectory, filename), "utf8");
    for (const match of generated.matchAll(/^public (?:struct|class|enum) ([A-Za-z_][A-Za-z0-9_]*)/gm)) {
      const owners = swiftTypeOwners.get(match[1]) ?? [];
      owners.push(filename);
      swiftTypeOwners.set(match[1], owners);
    }
  }
  for (const [typeName, owners] of swiftTypeOwners) {
    if (owners.length < 2) continue;
    for (const filename of owners) {
      const output = resolve(swiftDirectory, filename);
      const messageName = filename.slice(0, -".swift".length);
      const scopedName = `${messageName}${typeName}`;
      const generated = readFileSync(output, "utf8");
      writeFileSync(
        output,
        generated.replace(new RegExp(`\\b${typeName}\\b`, "g"), scopedName)
      );
    }
  }
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true });
}
