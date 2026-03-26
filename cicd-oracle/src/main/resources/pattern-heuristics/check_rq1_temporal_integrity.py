#!/usr/bin/env python3
"""
RQ1 temporal integrity checks.

  1. Fold membership   — eval folds are pairwise disjoint and cover all_conflicts exactly
  2. Temporal order    — max(commitTime fold k) < min(commitTime fold k+1) for all k
  3. JSON consistency  — autoregressive_fold_assignment.json matches eval CSVs
  4. Prediction cover  — prediction files contain exactly the eval merge IDs per fold
  5. Temporal perf     — Spearman rho(fold_index, hit_rate) must not be significantly
                         positive (positive = newer folds easier = leakage signal)

Usage:
  python3 check_rq1_temporal_integrity.py [--data-dir DIR]
"""
import csv
import json
import os
import sys
from collections import defaultdict
from pathlib import Path

csv.field_size_limit(1_000_000)

PASS = "PASS"
FAIL = "FAIL"
SKIP = "SKIP"


# ── helpers ───────────────────────────────────────────────────────────────────

def load_eval_fold_ids(cv_folds_dir: Path) -> list[set]:
    folds = []
    for k in range(10):
        path = cv_folds_dir / f"evaluation_fold{k}.csv"
        if not path.exists():
            raise FileNotFoundError(f"Missing: {path}")
        ids = set()
        with open(path, newline='') as f:
            for row in csv.DictReader(f):
                ids.add(row['merge_id'].strip())
        folds.append(ids)
    return folds


# ── check 1 ───────────────────────────────────────────────────────────────────

def check_fold_membership(all_conflicts_path: Path, cv_folds_dir: Path):
    """Eval folds must partition all_conflicts merge IDs exactly — no gaps, no overlaps."""
    print("\n── Check 1: Fold membership integrity ──────────────────────────────")

    all_ids = set()
    with open(all_conflicts_path, newline='') as f:
        for row in csv.DictReader(f):
            all_ids.add(row['merge_id'].strip())
    print(f"  all_conflicts.csv: {len(all_ids):,} unique merge IDs")

    folds = load_eval_fold_ids(cv_folds_dir)
    print(f"  Fold sizes: {[len(f) for f in folds]}  (total {sum(len(f) for f in folds):,})")

    ok = True
    for i in range(10):
        for j in range(i + 1, 10):
            overlap = folds[i] & folds[j]
            if overlap:
                print(f"  {FAIL} Folds {i} and {j} share {len(overlap):,} IDs: "
                      f"{sorted(overlap)[:3]} …")
                ok = False

    union = set().union(*folds)
    missing = all_ids - union
    extra   = union - all_ids
    if missing:
        print(f"  {FAIL} {len(missing):,} all_conflicts IDs not in any eval fold")
        ok = False
    if extra:
        print(f"  {FAIL} {len(extra):,} eval-fold IDs not in all_conflicts")
        ok = False
    if ok:
        print(f"  {PASS} All {len(all_ids):,} merge IDs appear in exactly one eval fold")
    return ok, folds


# ── check 2 ───────────────────────────────────────────────────────────────────

def check_temporal_order(all_conflicts_path: Path, folds: list[set]):
    """max(commitTime fold k) must be strictly less than min(commitTime fold k+1)."""
    print("\n── Check 2: Temporal monotonicity ──────────────────────────────────")

    merge_time: dict[str, str] = {}
    with open(all_conflicts_path, newline='') as f:
        for row in csv.DictReader(f):
            mid = row['merge_id'].strip()
            if mid not in merge_time:
                merge_time[mid] = row['commitTime'].strip()

    fold_ranges = []
    for fold_ids in folds:
        times = [merge_time[m] for m in fold_ids if m in merge_time]
        fold_ranges.append((min(times), max(times)) if times else (None, None))

    ok = True
    for k in range(9):
        _, hi      = fold_ranges[k]
        lo_next, _ = fold_ranges[k + 1]
        if hi is None or lo_next is None:
            continue
        if hi > lo_next:
            print(f"  {FAIL} Fold {k} max ({hi[:10]}) > fold {k+1} min ({lo_next[:10]}) "
                  f"— temporal interleaving")
            ok = False
        elif hi == lo_next:
            print(f"  WARN Fold {k}/{k+1} share boundary timestamp {hi[:10]} "
                  f"(same-day tie — acceptable)")

    if ok:
        print(f"  {PASS} Folds are strictly chronologically ordered")

    print(f"\n  {'fold':>5}  {'from':>12}  {'to':>12}  {'N':>6}")
    for k, (lo, hi) in enumerate(fold_ranges):
        print(f"  {k:>5}  {(lo or '?')[:10]:>12}  {(hi or '?')[:10]:>12}  "
              f"{len(folds[k]):>6}")
    return ok


# ── check 3 ───────────────────────────────────────────────────────────────────

def check_json_consistency(checkpoints_dir: Path, folds: list[set]):
    """autoregressive_fold_assignment.json must map every eval merge to its correct fold."""
    print("\n── Check 3: Fold-assignment JSON consistency ────────────────────────")

    json_path = checkpoints_dir / "autoregressive_fold_assignment.json"
    if not json_path.exists():
        print(f"  {SKIP} {json_path} not found")
        return None

    with open(json_path) as f:
        assignment: dict = json.load(f)
    print(f"  JSON contains {len(assignment):,} entries")

    ok = True
    all_fold_ids = set().union(*folds)

    for k, fold_ids in enumerate(folds):
        for mid in fold_ids:
            if mid not in assignment:
                print(f"  {FAIL} Fold {k} merge {mid!r} missing from JSON")
                ok = False
            elif assignment[mid] != k:
                print(f"  {FAIL} Merge {mid!r}: JSON says fold {assignment[mid]}, "
                      f"eval CSV says fold {k}")
                ok = False

    extra = set(assignment) - all_fold_ids
    if extra:
        print(f"  {FAIL} JSON has {len(extra):,} IDs not present in any eval fold")
        ok = False

    if ok:
        print(f"  {PASS} JSON matches all eval CSVs exactly")
    return ok


# ── check 4 ───────────────────────────────────────────────────────────────────

def check_prediction_coverage(predictions_dir: Path, folds: list[set]):
    """Each prediction file must contain exactly the merge IDs of its eval fold."""
    print("\n── Check 4: Prediction coverage ─────────────────────────────────────")

    ok = True
    for label, prefix in [("ML-AR", "autoregressive_predictions_fold"),
                           ("RF",    "rf_predictions_fold")]:
        absent, mismatch = [], []
        for k, fold_ids in enumerate(folds):
            path = predictions_dir / f"{prefix}{k}.csv"
            if not path.exists():
                absent.append(k)
                continue
            pred_ids = set()
            with open(path, newline='') as f:
                for row in csv.DictReader(f):
                    pred_ids.add(row['merge_id'].strip())
            missing = fold_ids - pred_ids
            extra   = pred_ids - fold_ids
            if missing or extra:
                mismatch.append((k, len(missing), len(extra)))

        if absent:
            print(f"  {SKIP} {label}: prediction files absent for folds {absent}")
        if mismatch:
            for k, nm, nx in mismatch:
                print(f"  {FAIL} {label} fold {k}: {nm:,} eval merges missing, "
                      f"{nx:,} unexpected entries")
            ok = False
        present = [k for k in range(10) if k not in absent]
        if present and not mismatch:
            print(f"  {PASS} {label}: folds {present} cover eval merge IDs exactly")
    return ok


# ── check 5 ───────────────────────────────────────────────────────────────────

def check_temporal_performance(results_path: Path):
    """
    Spearman rho(fold_index, hit_rate) per mode.

    A significantly positive rho means newer folds are systematically *easier* —
    the suspicious direction for time leakage.  Under a clean chronological split
    performance should be stable or slightly declining (distribution shift, less
    training data for the earliest folds).

    Threshold: rho > 0.5 AND p < 0.025  →  flag as suspicious.

    The p-value threshold is Bonferroni-corrected for the two actual ML models
    (ML_AR, ML_RF).  Rule-based baselines (GLOBAL, HEURISTIC, etc.) are shown
    for context but are excluded from the leakage family: they cannot overfit
    to future data, so including them in the correction would inflate alpha.
    """
    print("\n── Check 5: Temporal performance Spearman correlation ───────────────")

    if not results_path.exists():
        print(f"  {SKIP} {results_path} not found — run PatternMatchEvaluator first")
        return None

    fold_stats: dict[str, dict[int, list]] = defaultdict(lambda: defaultdict(lambda: [0, 0]))
    with open(results_path, newline='') as f:
        for row in csv.DictReader(f):
            try:
                fold = int(row['fold'])
                hit  = 1 if row['min_distance'] == '0' else 0
                mode = row['mode']
                if not mode:
                    continue
            except (ValueError, KeyError):
                continue
            fold_stats[mode][fold][0] += hit
            fold_stats[mode][fold][1] += 1

    try:
        from scipy.stats import spearmanr
        def corr(xs, ys):
            rho, p = spearmanr(xs, ys)
            return float(rho), float(p)
    except ImportError:
        print("  Note: scipy not found — p-values unavailable (pip install scipy)")
        def corr(xs, ys):
            n = len(xs)
            rx = [sorted(xs).index(x) + 1 for x in xs]
            ry = [sorted(ys).index(y) + 1 for y in ys]
            d2 = sum((a - b) ** 2 for a, b in zip(rx, ry))
            rho = 1 - 6 * d2 / (n * (n * n - 1))
            return rho, None

    # Build per-fold hit rates for each mode
    mode_rates: dict[str, dict[int, float]] = {}
    for mode, data in fold_stats.items():
        ks = sorted(data)
        rates = {k: data[k][0] / data[k][1] for k in ks if data[k][1]}
        if len(rates) >= 3:
            mode_rates[mode] = rates

    # RANDOM is the difficulty baseline: it cannot leak, so its rho captures
    # genuine data-difficulty shift over time.  We test rho(model − random)
    # to isolate model-specific temporal advantage beyond mere difficulty drift.
    random_rates = mode_rates.get('RANDOM', {})

    print(f"  {'Mode':<22} {'rho(raw)':>9}  {'rho(−RAND)':>10}  {'p(−RAND)':>9}  verdict")
    print(f"  {'-'*22} {'-'*9}  {'-'*10}  {'-'*9}  -------")

    ok = True
    for mode in sorted(mode_rates):
        rates = mode_rates[mode]
        ks    = sorted(rates)

        rho_raw, _ = corr(ks, [rates[k] for k in ks])

        if mode == 'RANDOM':
            print(f"  {mode:<22} {rho_raw:>+9.3f}  {'(baseline)':>10}  {'':>9}  "
                  f"difficulty-drift reference")
            continue

        # Differential: model advantage over random per fold
        shared_ks = [k for k in ks if k in random_rates]
        if len(shared_ks) >= 3:
            diff = [rates[k] - random_rates[k] for k in shared_ks]
            rho_diff, p_diff = corr(shared_ks, diff)
            p_str = f"{p_diff:.4f}" if p_diff is not None else "     n/a"
        else:
            rho_diff, p_str = float('nan'), "     n/a"

        suspicious = (rho_diff == rho_diff and rho_diff > 0.5
                      and p_diff is not None and p_diff < 0.025)
        verdict = "SUSPICIOUS — model gains over random grow with time" if suspicious else "ok"
        if suspicious:
            ok = False
        print(f"  {mode:<22} {rho_raw:>+9.3f}  {rho_diff:>+10.3f}  {p_str:>9}  {verdict}")

    print()
    print("  rho(raw)   : correlation of absolute hit rate with fold index")
    print("  rho(−RAND) : correlation of (hit_rate − RANDOM) with fold index")
    print("  Leakage signal: rho(−RAND) significantly positive means the model")
    print("  gains extra advantage over random on newer folds — beyond difficulty drift.")
    return ok


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    import argparse
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data-dir", default=os.path.join(
        os.path.expanduser("~"), "data", "bruteforcemerge", "rq1"))
    ap.add_argument("--all-conflicts", default=None,
                    help="Path to all_conflicts.csv (default: data-dir/all_conflicts.csv)")
    args = ap.parse_args()

    data_dir        = Path(args.data_dir)
    cv_folds_dir    = data_dir / "cv_folds"
    checkpoints_dir = data_dir / "checkpoints"
    predictions_dir = data_dir / "predictions"
    results_path    = data_dir / "results" / "cv_results.csv"
    all_conflicts   = Path(args.all_conflicts) if args.all_conflicts \
                      else data_dir / "all_conflicts.csv"

    print(f"Data dir: {data_dir}")

    ok1, folds = check_fold_membership(all_conflicts, cv_folds_dir)
    results = {
        1: ok1,
        2: check_temporal_order(all_conflicts, folds),
        3: check_json_consistency(checkpoints_dir, folds),
        4: check_prediction_coverage(predictions_dir, folds),
        5: check_temporal_performance(results_path),
    }

    print("\n══ Summary ══════════════════════════════════════════════════════════")
    all_passed = True
    for i, r in results.items():
        status = PASS if r is True else (SKIP if r is None else FAIL)
        print(f"  Check {i}: {status}")
        if r is False:
            all_passed = False
    print()
    sys.exit(0 if all_passed else 1)


if __name__ == '__main__':
    main()
