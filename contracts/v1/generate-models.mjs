import { execFileSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const schema = resolve(here, "glafira-task-agent.schema.json");
const outputRoot = resolve(here, "generated");
const targets = [
  ["kotlin", "GlafiraContracts.kt"],
  ["swift", "GlafiraContracts.swift"],
  ["csharp", "GlafiraContracts.cs"]
];

for (const [language, filename] of targets) {
  const directory = resolve(outputRoot, language);
  mkdirSync(directory, { recursive: true });
  const output = resolve(directory, filename);
  execFileSync(
    resolve(here, "../../node_modules/.bin/quicktype"),
    [
      "--src", schema,
      "--src-lang", "schema",
      "--lang", language,
      "--top-level", "GlafiraContractMessage",
      "--out", output
    ],
    { stdio: "inherit" }
  );
  console.log(`Generated ${language} contract DTOs: ${output}`);
}
