#!/usr/bin/env bash
set -euo pipefail

docker compose up -d

echo "Waiting for KoeCraft Agent..."

until curl -fsS http://localhost:8787/health >/dev/null 2>&1; do
  sleep 1
done

echo "KoeCraft Agent is ready."
echo "Launch Minecraft with the Fabric profile and run:"
echo "/koecraft connect localhost 8790"
