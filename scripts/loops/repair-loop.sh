#!/usr/bin/env bash
set -euo pipefail

MAX_ITERS="${MAX_ITERS:-3}"
CMD="${1:-make agent-check}"

for i in $(seq 1 "$MAX_ITERS"); do
  echo "[loop] iteration $i/$MAX_ITERS: $CMD"
  if bash -lc "$CMD"; then
    echo "[loop] success"
    exit 0
  fi
  echo "[loop] failed. Inspect logs and repair before next run."
  exit 1
done

echo "[loop] failed after $MAX_ITERS iterations"
exit 1
