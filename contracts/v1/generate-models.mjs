import { execFileSync } from "node:child_process";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
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
  "TaskLease"
];

const targets = [
  { language: "kotlin", extension: "kt", extra: name => ["--framework", "jackson", "--package", `glafira.contracts.v1.generated.${name.toLowerCase()}`] },
  { language: "swift", extension: "swift", extra: () => ["--just-types"] },
  { language: "csharp", extension: "cs", extra: name => ["--namespace", `Glafira.Contracts.V1.Generated.${name}`] }
];

try {
  for (const definitionName of definitions) {
    const definition = schema.$defs?.[definitionName];
    if (!definition || definition.type !== "object") {
      throw new Error(`Missing object schema definition: ${definitionName}`);
    }

    const isolatedSchema = {
      $schema: schema.$schema,
      title: definitionName,
      $defs: schema.$defs,
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
} finally {
  rmSync(temporaryDirectory, { recursive: true, force: true });
}
