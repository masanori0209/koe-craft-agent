#!/usr/bin/env bash
set -euo pipefail

echo "[harness] mod build"

if [ ! -f mod/build.gradle ]; then
  echo "[harness] mod/build.gradle not found" >&2
  exit 1
fi

if ! command -v java >/dev/null 2>&1 || ! java -version >/dev/null 2>&1; then
  echo "[harness] Java Runtime is required for Fabric mod build but was not found" >&2
  exit 1
fi

if [ -x mod/gradlew ]; then
  (cd mod && ./gradlew build)
elif command -v gradle >/dev/null 2>&1; then
  (cd mod && gradle build)
else
  echo "[harness] Gradle or mod/gradlew is required for Fabric mod build but was not found" >&2
  exit 1
fi
