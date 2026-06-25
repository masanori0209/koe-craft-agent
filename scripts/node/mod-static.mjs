import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { fileExists, printStep, repoRoot } from "./lib.mjs";

printStep("mod static checks");

const requiredFiles = [
  "mod/build.gradle",
  "mod/gradlew",
  "mod/gradlew.bat",
  "mod/gradle/wrapper/gradle-wrapper.jar",
  "mod/gradle/wrapper/gradle-wrapper.properties",
  "mod/src/main/resources/fabric.mod.json",
  "mod/src/main/java/dev/koecraft/agentmod/KoeCraftAgentClient.java",
  "mod/src/main/java/dev/koecraft/agentmod/KoeCraftExecutorServer.java",
  "mod/src/main/java/dev/koecraft/agentmod/SurvivalActionExecutor.java"
];

for (const file of requiredFiles) {
  if (!fileExists(join(repoRoot, file))) {
    console.error(`[harness] missing required mod file: ${file}`);
    process.exit(1);
  }
}

const javaFiles = walk(join(repoRoot, "mod", "src", "main", "java")).filter((file) => file.endsWith(".java"));
const forbiddenCommandApis = /sendCommand|executeCommand|requestCommandCompletions/;
for (const file of javaFiles) {
  const text = readFileSync(file, "utf8");
  if (forbiddenCommandApis.test(text)) {
    console.error(`[harness] forbidden command execution path found: ${file}`);
    process.exit(1);
  }
}

console.log("[harness] mod static checks passed");

function walk(dir) {
  const entries = readdirSync(dir).map((entry) => join(dir, entry));
  return entries.flatMap((entry) => (statSync(entry).isDirectory() ? walk(entry) : [entry]));
}
