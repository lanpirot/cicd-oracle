#!/usr/bin/env bash
# run_rq3.sh — RQ3 pipeline: 500 merges, human_baseline + best variant mode.
#
# Requires RQ1 to have completed steps 1-4 first so that
# autoregressive_fold_assignment.json and per-fold checkpoints exist.
#
# Usage (from cicd-oracle/):
#   ./run_rq3.sh                              # resume — skip already-processed merges
#   ./run_rq3.sh --fresh                      # delete prior output and restart
#   ./run_rq3.sh --best-mode cache_parallel   # override default best mode (cache_parallel)

set -euo pipefail

FRESH_PROP=""
BEST_MODE_PROP=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --fresh)
            FRESH_PROP="-DfreshRun=true"; shift ;;
        --best-mode=*)
            BEST_MODE_PROP="-Drq3BestMode=${1#--best-mode=}"; shift ;;
        --best-mode)
            [[ -z "${2:-}" ]] && { echo "Usage: $0 [--fresh] [--best-mode MODE]" >&2; exit 1; }
            BEST_MODE_PROP="-Drq3BestMode=$2"; shift 2 ;;
        *)
            echo "Usage: $0 [--fresh] [--best-mode MODE]" >&2; exit 1 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== RQ3 pipeline (500 merges, human_baseline + best mode) ==="
[[ -n "$BEST_MODE_PROP" ]] && echo "  Best mode: ${BEST_MODE_PROP#-Drq3BestMode=}"
echo "Step 1: Build..."
mvn -q clean package -DskipTests

echo "Step 2: Run RQ3PipelineRunner..."
# shellcheck disable=SC2086
java $FRESH_PROP $BEST_MODE_PROP -cp "target/*:target/lib/*" ch.unibe.cs.mergeci.experiment.RQ3PipelineRunner
