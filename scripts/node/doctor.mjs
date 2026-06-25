import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileExists, hasCommand, printStep, repoRoot, run, readJson } from "./lib.mjs";

printStep("KoeCraft environment doctor");

const rootPackage = readJson(join(repoRoot, "package.json"));
const gradleProps = readProperties(join(repoRoot, "mod", "gradle.properties"));

const checks = [];
checks.push(checkNode(rootPackage.engines?.node ?? ">=20"));
checks.push(checkJava());
checks.push(checkGradle());
checks.push(checkRipgrep());
checks.push({
  name: "Minecraft/Fabric pinned versions",
  ok: Boolean(gradleProps.minecraft_version && gradleProps.loader_version && gradleProps.fabric_version),
  detail: `minecraft=${gradleProps.minecraft_version ?? "missing"}, loader=${gradleProps.loader_version ?? "missing"}, fabric-api=${gradleProps.fabric_version ?? "missing"}`
});
checks.push({
  name: "UTF-8 Japanese fixtures",
  ok: readFileSync(join(repoRoot, "examples", "speech_fixtures.json"), "utf8").includes("暗いから松明置いて"),
  detail: "examples/speech_fixtures.json includes Japanese text"
});

for (const check of checks) {
  const mark = check.ok ? "OK" : "NG";
  console.log(`[${mark}] ${check.name}: ${check.detail}`);
}

const failed = checks.filter((check) => !check.ok);
if (failed.length > 0) {
  console.log("\nDoctor found issues. See docs/setup/mac-windows.md and docs/version-matrix.md.");
  process.exit(1);
}

console.log("\nDoctor passed.");

function checkNode(expected) {
  const major = Number(process.versions.node.split(".")[0]);
  return {
    name: "Node.js",
    ok: major >= 20,
    detail: `current=${process.versions.node}, expected=${expected}; Node is only used for optional maintenance scripts`
  };
}

function checkJava() {
  const result = run("java", ["-version"], { stdio: "pipe", allowFailure: true });
  const output = `${result.stderr?.toString() ?? ""}${result.stdout?.toString() ?? ""}`;
  const match = output.match(/version "([^"]+)"/);
  const version = match?.[1] ?? "not found";
  const major = version.startsWith("1.") ? Number(version.split(".")[1]) : Number(version.split(".")[0]);
  return {
    name: "Java Runtime",
    ok: Number.isFinite(major) && major >= 21,
    detail: `current=${version}, expected=21+ for Minecraft 1.21.x`
  };
}

function checkGradle() {
  const wrapper = process.platform === "win32"
    ? fileExists(join(repoRoot, "mod", "gradlew.bat"))
    : fileExists(join(repoRoot, "mod", "gradlew"));
  const globalGradle = hasCommand(process.platform === "win32" ? "gradle.cmd" : "gradle");
  return {
    name: "Gradle",
    ok: wrapper || globalGradle,
    detail: wrapper ? "mod Gradle Wrapper found" : globalGradle ? "global Gradle found" : "Gradle or mod/gradlew not found"
  };
}

function checkRipgrep() {
  const ok = hasCommand(process.platform === "win32" ? "rg.exe" : "rg", ["--version"]);
  return {
    name: "ripgrep",
    ok,
    detail: ok ? "rg is available for fast static checks" : "rg not found; bash mod-static may fail"
  };
}

function readProperties(path) {
  const props = {};
  const text = readFileSync(path, "utf8");
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#") || !trimmed.includes("=")) continue;
    const [key, ...value] = trimmed.split("=");
    props[key] = value.join("=");
  }
  return props;
}
