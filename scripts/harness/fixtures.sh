#!/usr/bin/env bash
set -euo pipefail

echo "[harness] fixture checks"

python3 scripts/python/speech_fixture_check.py
(cd mod && ./gradlew plannerFixtures)
