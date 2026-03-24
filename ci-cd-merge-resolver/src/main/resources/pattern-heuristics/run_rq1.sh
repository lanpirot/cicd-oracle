#!/usr/bin/env bash
# run_rq1.sh — Full RQ1 pipeline: extract → heuristics → folds → train → evaluate
#
# Usage (from repo root or any directory):
#   ./src/main/resources/pattern-heuristics/run_rq1.sh [--from-step N]
#
#   --from-step 1  (default) SQL extraction → everything
#   --from-step 2  skip SQL extraction; start from heuristics learning
#   --from-step 3  skip to fold generation
#   --from-step 4  skip to ML-AR model training + inference
#   --from-step 5  skip to RF model training + inference
#   --from-step 6  skip to Java evaluation only

set -euo pipefail

# ── Resolve paths ─────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAVEN_MODULE="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
DATA_DIR="$HOME/data/bruteforcemerge/rq1"
CV_FOLDS_DIR="$DATA_DIR/cv_folds"
RESULTS_DIR="$DATA_DIR/results"

# ── Python: prefer repo venv (has GPU-enabled PyTorch) ────────────────────────
PYTHON="python3"
for candidate in \
    "$SCRIPT_DIR/.venv/bin/python3" \
    "$SCRIPT_DIR/.venv/bin/python" \
    "$MAVEN_MODULE/../.venv/bin/python3" \
    "$MAVEN_MODULE/../.venv/bin/python" \
    "$MAVEN_MODULE/.venv/bin/python3" \
    "$MAVEN_MODULE/.venv/bin/python"; do
    if [[ -x "$candidate" ]]; then
        PYTHON="$candidate"
        break
    fi
done

# ── Parse arguments ───────────────────────────────────────────────────────────
FROM_STEP=1
for arg in "$@"; do
    case "$arg" in
        --from-step) ;;                  # handled by next iteration
        [0-9]) FROM_STEP="$arg" ;;       # bare number (shouldn't happen with --)
        *)
            if [[ "$arg" =~ ^--from-step=([0-9]+)$ ]]; then
                FROM_STEP="${BASH_REMATCH[1]}"
            elif [[ -n "${PREV_ARG:-}" && "$PREV_ARG" == "--from-step" ]]; then
                FROM_STEP="$arg"
            else
                echo "Usage: $0 [--from-step N]  (N = 1..6)" >&2; exit 1
            fi
            ;;
    esac
    PREV_ARG="$arg"
done

# ── Helpers ───────────────────────────────────────────────────────────────────
step_header() {
    echo
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Step $1 / 6 — $2"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
}

skip_if_before() {
    [[ "$FROM_STEP" -le "$1" ]]
}

# ── Print config ──────────────────────────────────────────────────────────────
echo "RQ1 pipeline  (from step $FROM_STEP)"
echo "  Python:      $PYTHON"
echo "  Data dir:    $DATA_DIR"
echo "  Maven module: $MAVEN_MODULE"

# ── Step 1: Extract CSVs from SQL dump ────────────────────────────────────────
if skip_if_before 1; then
    step_header 1 "Extract CSVs from SQL dump  (~2 min)"
    "$PYTHON" "$SCRIPT_DIR/extract_from_sql_dump.py"
fi

# ── Step 2: Learn global pattern distribution ─────────────────────────────────
if skip_if_before 2; then
    step_header 2 "Learn global pattern distribution  (~30 s)"
    "$PYTHON" "$SCRIPT_DIR/learn_historical_pattern_distribution.py" \
        --data-dir "$DATA_DIR"
fi

# ── Step 3: Generate chronological 10-fold CV splits ─────────────────────────
if skip_if_before 3; then
    step_header 3 "Generate chronological 10-fold CV splits  (~2 min)"
    mkdir -p "$CV_FOLDS_DIR"
    "$PYTHON" "$SCRIPT_DIR/learn_cv_folds.py" \
        --data-dir   "$DATA_DIR" \
        --output-dir "$CV_FOLDS_DIR"
fi

# ── Step 4: Train autoregressive model + generate predictions ─────────────────
if skip_if_before 4; then
    step_header 4 "Train autoregressive model + generate predictions  (hours; GPU recommended)"
    "$PYTHON" "$SCRIPT_DIR/train_autoregressive_model.py" \
        --data-dir "$DATA_DIR"
fi

# ── Step 5: Train Random Forest + generate predictions ────────────────────────
if skip_if_before 5; then
    step_header 5 "Train Random Forest + generate predictions  (~5-30 min)"
    "$PYTHON" "$SCRIPT_DIR/train_rf_model.py" \
        --data-dir "$DATA_DIR"
fi

# ── Step 6: Evaluate with PatternMatchEvaluator ───────────────────────────────
if skip_if_before 6; then
    step_header 6 "Evaluate (PatternMatchEvaluator → cv_results.csv + tables + plots)"
    cd "$MAVEN_MODULE"
    mvn -q compile exec:java \
        -Dexec.mainClass="ch.unibe.cs.mergeci.experiment.PatternMatchEvaluator"
fi

# ── Done ──────────────────────────────────────────────────────────────────────
echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Done.  Results in $RESULTS_DIR"
echo "  Check temporal sanity: fold 9 (newest) should not"
echo "  perform much worse than folds 0–8 in the printout"
echo "  above (ML-AR hit rate per fold)."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
