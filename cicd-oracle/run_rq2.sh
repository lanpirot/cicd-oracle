#!/usr/bin/env bash
# run_rq2.sh — RQ2 pipeline: 50 Maven projects × 1 merge, all 5 experiment modes.
#
# Requires RQ1 to have completed steps 1-4 first so that
# autoregressive_fold_assignment.json and per-fold checkpoints exist.
#
# Usage (from cicd-oracle/):
#   ./run_rq2.sh           # resume — skip already-processed merges
#   ./run_rq2.sh --fresh   # delete prior output and restart from scratch

set -euo pipefail

FRESH_PROP=""
for arg in "$@"; do
    case "$arg" in
        --fresh) FRESH_PROP="-DfreshRun=true" ;;
        *) echo "Usage: $0 [--fresh]" >&2; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== RQ2 pipeline (50 repos x 1 merge, all 5 modes) ==="
echo "Step 1: Build..."
mvn -q clean package -DskipTests

echo "Step 2: Run RQ2PipelineRunner..."
# shellcheck disable=SC2086
java $FRESH_PROP -cp "target/*:target/lib/*" ch.unibe.cs.mergeci.experiment.RQ2PipelineRunner
