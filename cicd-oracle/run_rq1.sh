#!/usr/bin/env bash
# run_rq1.sh — Convenience wrapper for the RQ1 pipeline.
# Delegates to src/main/resources/pattern-heuristics/run_rq1.sh.
#
# Usage (from cicd-oracle/):
#   ./run_rq1.sh                   # full pipeline from scratch
#   ./run_rq1.sh --from-step 4     # resume from a specific step
#
# Steps:
#   1  Extract CSVs from SQL dump              (~2 min)
#   2  Learn global pattern distribution       (~30 s)
#   3  Generate 10-fold CV splits              (~2 min)
#   4  Train autoregressive model + predict    (hours; GPU recommended)
#   5  Train Random Forest + predict           (~5-30 min)
#   6  Evaluate (RQ1PipelineRunner)            (~30 min)
#   7  Temporal integrity checks
#
# Prerequisites:
#   - GITHUB_TOKEN exported (needed by step 1 to probe GitHub for Maven projects):
#       echo 'export GITHUB_TOKEN=ghp_yourToken' >> ~/.bashrc && source ~/.bashrc
#   - .venv/ at the repo root with GPU-enabled PyTorch (auto-detected by the script):
#       cd .. && python3 -m venv .venv && source .venv/bin/activate
#       pip install torch --index-url https://download.pytorch.org/whl/cu121
#       pip install -r cicd-oracle/src/main/resources/pattern-heuristics/requirements.txt

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/src/main/resources/pattern-heuristics/run_rq1.sh" "$@"
