#!/usr/bin/env bash
set -euo pipefail

echo "[harness] check-all"

if [ -f mod/gradlew ]; then
  (cd mod && ./gradlew build)
elif [ -f gradlew ]; then
  ./gradlew build
elif [ -f mod/build.gradle ]; then
  ./scripts/harness/mod-static.sh
fi

./scripts/harness/fixtures.sh
python3 scripts/python/validate_recipe_catalog.py
(cd mod && ./gradlew recipeDependencyAudit)
./scripts/harness/dry-run.sh

echo "[harness] done"
