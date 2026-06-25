#!/usr/bin/env bash
set -euo pipefail

echo "[harness] mod static checks"

required_files=(
  "mod/build.gradle"
  "mod/gradlew"
  "mod/gradlew.bat"
  "mod/gradle/wrapper/gradle-wrapper.jar"
  "mod/gradle/wrapper/gradle-wrapper.properties"
  "mod/src/main/resources/fabric.mod.json"
  "mod/src/main/java/dev/koecraft/agentmod/KoeCraftAgentClient.java"
  "mod/src/main/java/dev/koecraft/agentmod/KoeCraftExecutorServer.java"
  "mod/src/main/java/dev/koecraft/agentmod/SurvivalActionExecutor.java"
)

for file in "${required_files[@]}"; do
  if [ ! -f "$file" ]; then
    echo "[harness] missing required mod file: $file" >&2
    exit 1
  fi
done

if rg -n 'sendCommand|executeCommand|requestCommandCompletions' mod/src/main/java; then
  echo "[harness] forbidden command execution path found in mod source" >&2
  exit 1
fi

echo "[harness] mod static checks passed"
