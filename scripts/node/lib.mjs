import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import process from "node:process";

export const repoRoot = new URL("../..", import.meta.url).pathname;
export const isWindows = process.platform === "win32";
export const npmCmd = isWindows ? "npm.cmd" : "npm";
export const nodeCmd = isWindows ? "node.exe" : "node";

export function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? repoRoot,
    stdio: options.stdio ?? "inherit",
    shell: false,
    env: { ...process.env, ...(options.env ?? {}) }
  });
  if (result.error) {
    if (options.allowFailure === true) {
      return result;
    }
    throw result.error;
  }
  if (result.status !== 0 && options.allowFailure !== true) {
    process.exit(result.status ?? 1);
  }
  return result;
}

export function runAgentScript(script, extraArgs = []) {
  return run(npmCmd, ["run", script, "--", ...extraArgs], {
    cwd: join(repoRoot, "agent")
  });
}

export function hasCommand(command, args = ["--version"]) {
  const result = spawnSync(command, args, {
    cwd: repoRoot,
    stdio: "ignore",
    shell: false
  });
  return result.status === 0;
}

export function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

export function fileExists(path) {
  return existsSync(path);
}

export function printStep(label) {
  console.log(`\n[harness] ${label}`);
}
