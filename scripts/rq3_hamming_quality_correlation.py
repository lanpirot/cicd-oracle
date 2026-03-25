#!/usr/bin/env python3
"""
RQ3 anti-correlation analysis: Hamming distance vs. merge quality.

Hypothesis: variants whose pattern assignment is closer to the human resolution
(lower Hamming distance) also tend to achieve higher build quality (more modules
built, more tests passing).  A significant anti-correlation strengthens the
rationale for using Hamming distance as the RQ1 evaluation criterion.

Inputs:
  - rq3_variant_experiments/  (per-project JSON files, one subdir per mode)
  - all_conflicts.csv          (ground-truth per-chunk human patterns)

Output:
  - Console: pooled Spearman r, p-value; per-merge distribution summary
  - rq3_hamming_quality.pdf    (scatter + violin plots)

Usage:
  python rq3_hamming_quality_correlation.py [rq3_dir] [all_conflicts_csv] [output_pdf]

Defaults:
  rq3_dir           = /home/lanpirot/data/bruteforcemerge/rq3_variant_experiments
  all_conflicts_csv = /home/lanpirot/data/bruteforcemerge/rq1/all_conflicts.csv
  output_pdf        = rq3_hamming_quality.pdf
"""

import sys
import csv
import json
import math
from pathlib import Path
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages

# Optional: scipy for Spearman correlation
try:
    from scipy.stats import spearmanr
    HAS_SCIPY = True
except ImportError:
    HAS_SCIPY = False
    print("Warning: scipy not available — Spearman r will not be computed.", file=sys.stderr)

csv.field_size_limit(1_000_000)

# ── Defaults ──────────────────────────────────────────────────────────────────

DEFAULT_RQ3_DIR       = Path("/home/lanpirot/data/bruteforcemerge/rq3_variant_experiments")
DEFAULT_CONFLICTS_CSV = Path("/home/lanpirot/data/bruteforcemerge/rq1/all_conflicts.csv")
DEFAULT_OUTPUT_PDF    = Path("rq3_hamming_quality.pdf")

# Only analyse the best mode (human_baseline provides the quality denominator,
# the variant mode provides the Hamming/quality pairs).
# TODO: adjust if RQ3 was run with a different best mode.
VARIANT_MODE = "cache_parallel"

USE_LATEX = True
plt.rcParams.update({
    "text.usetex":    USE_LATEX,
    "font.family":    "serif",
    "font.size":      10,
    "axes.labelsize": 10,
    "axes.titlesize": 11,
    "figure.dpi":     200,
})


# ── Ground-truth loader ───────────────────────────────────────────────────────

def load_ground_truth(all_conflicts_csv: Path) -> dict[str, dict[str, list[str]]]:
    """
    Returns ground_truth[merge_id][filename] = [pattern_chunk0, pattern_chunk1, ...]
    sorted by chunkIndex (ascending, 1-based from the DB).

    The variant's conflictPatterns[filename] list is assumed to follow the same
    chunkIndex order.  Verify this assumption before drawing conclusions.

    # TODO: confirm that VariantProjectBuilder writes conflictPatterns in the
    #       same chunkIndex order as all_conflicts.csv.  If not, a sort-key
    #       needs to be derived from the conflict file parsing order.
    """
    # merge_id -> filename -> list of (chunkIndex, pattern)
    raw: dict[str, dict[str, list[tuple[int, str]]]] = defaultdict(lambda: defaultdict(list))

    with open(all_conflicts_csv, newline="") as f:
        for row in csv.DictReader(f):
            merge_id  = row.get("merge_id", "").strip()
            filename  = row.get("filename", "").strip()
            y         = row.get("y_conflictResolutionResult", "").strip()
            try:
                idx = int(row.get("chunkIndex", "0"))
            except ValueError:
                idx = 0
            if merge_id and filename and y:
                raw[merge_id][filename].append((idx, y))

    # Sort by chunkIndex and drop the index
    ground_truth: dict[str, dict[str, list[str]]] = {}
    for merge_id, files in raw.items():
        ground_truth[merge_id] = {
            fname: [pat for _, pat in sorted(chunks)]
            for fname, chunks in files.items()
        }
    return ground_truth


# ── Hamming distance ──────────────────────────────────────────────────────────

def hamming_distance(variant_patterns: dict[str, list[str]],
                     gt_patterns: dict[str, list[str]]) -> int | None:
    """
    Compute the total Hamming distance between a variant's pattern assignment
    and the human ground truth.

    variant_patterns: conflictPatterns from the JSON variant
                      {filename -> [pattern_per_chunk]}
    gt_patterns:      ground_truth[merge_id] as loaded above

    Returns None if the filenames don't overlap (can't compare).

    # TODO: filename normalisation — the JSON may use relative paths while
    #       all_conflicts.csv stores bare filenames or repo-relative paths.
    #       Add a normalisation step here if mismatches occur.
    """
    total_dist = 0
    matched_chunks = 0

    for fname, variant_list in variant_patterns.items():
        # Try exact match first, then basename fallback
        gt_list = gt_patterns.get(fname)
        if gt_list is None:
            basename = Path(fname).name
            gt_list = next(
                (v for k, v in gt_patterns.items() if Path(k).name == basename),
                None
            )
        if gt_list is None:
            continue  # file not in ground truth — skip

        for v_pat, gt_pat in zip(variant_list, gt_list):
            total_dist += 0 if v_pat == gt_pat else 1
            matched_chunks += 1

    if matched_chunks == 0:
        return None
    return total_dist


# ── Quality score ─────────────────────────────────────────────────────────────

def quality_score(variant: dict) -> tuple[int, int] | None:
    """
    Returns (modules_passed, tests_passed) or None if timed out / unscoreable.
    Mirrors plot_results.variant_score().
    """
    cr = variant.get("compilationResult")
    if cr is None or cr.get("buildStatus") == "TIMEOUT":
        return None
    module_results = cr.get("moduleResults", [])
    if module_results:
        modules = sum(1 for m in module_results if m.get("status") == "SUCCESS")
    else:
        modules = 1 if cr.get("buildStatus") == "SUCCESS" else 0
    if cr.get("buildStatus") == "SUCCESS":
        modules = max(1, modules)
    tr = variant.get("testResults")
    tests = 0
    if tr:
        run = tr.get("runNum", 0)
        tests = max(0, run - tr.get("failuresNum", 0) - tr.get("errorsNum", 0))
    return (modules, tests)


def combined_score(modules: int, tests: int) -> float:
    """
    Single scalar for ranking: (modules, tests) lexicographically, flattened.
    # TODO: consider normalising by human baseline values per merge so scores
    #       are comparable across projects of different sizes.
    """
    return modules * 1_000_000 + tests


# ── Data loading ──────────────────────────────────────────────────────────────

def load_rq3_merges(rq3_dir: Path, mode: str) -> dict[str, dict]:
    """Returns {mergeCommit -> merge_dict} for the given mode."""
    mode_dir = rq3_dir / mode
    if not mode_dir.exists():
        print(f"Warning: {mode_dir} does not exist.", file=sys.stderr)
        return {}
    merges = {}
    for json_file in sorted(mode_dir.glob("*.json")):
        try:
            with open(json_file) as f:
                data = json.load(f)
        except Exception as e:
            print(f"Warning: could not read {json_file}: {e}", file=sys.stderr)
            continue
        for merge in data.get("merges", []):
            mc = merge.get("mergeCommit")
            if mc:
                merges[mc] = merge
    return merges


# ── Per-merge analysis ────────────────────────────────────────────────────────

def analyse_merge(merge: dict, gt: dict[str, list[str]]) -> list[tuple[int, float]]:
    """
    For one merge, return a list of (hamming_distance, quality_score) pairs —
    one per non-timed-out, scoreable variant.
    """
    variants = (merge.get("variantsExecution") or {}).get("variants", [])
    pairs = []
    for v in variants:
        if v.get("timedOut"):
            continue
        cp = v.get("conflictPatterns")
        if not cp:
            continue  # human_baseline or cache-warmer with no patterns stored
        qs = quality_score(v)
        if qs is None:
            continue
        dist = hamming_distance(cp, gt)
        if dist is None:
            continue
        pairs.append((dist, combined_score(*qs)))
    return pairs


# ── Correlation computation ───────────────────────────────────────────────────

def spearman_r(xs: list[float], ys: list[float]) -> tuple[float, float] | None:
    """Returns (r, p_value) or None if scipy unavailable / too few points."""
    if not HAS_SCIPY or len(xs) < 3:
        return None
    r, p = spearmanr(xs, ys)
    return float(r), float(p)


# ── Plotting ──────────────────────────────────────────────────────────────────

def plot_scatter(ax, all_pairs: list[tuple[int, float]], r_pooled: float | None):
    """Scatter plot: Hamming distance (x) vs. quality score (y), all variants pooled."""
    xs = [p[0] for p in all_pairs]
    ys = [p[1] for p in all_pairs]
    ax.scatter(xs, ys, s=4, alpha=0.25, color="#1f78b4", edgecolors="none")
    ax.set_xlabel("Hamming distance to human resolution")
    ax.set_ylabel("Quality score (modules $\\times 10^6$ + tests)")
    title = "Hamming Distance vs. Build Quality (RQ3 variants, pooled)"
    if r_pooled is not None:
        title += f"\nSpearman $r = {r_pooled:.3f}$" if USE_LATEX else f"\nSpearman r = {r_pooled:.3f}"
    ax.set_title(title)
    ax.grid(True, alpha=0.3)


def plot_per_merge_r(ax, per_merge_r: list[float]):
    """Histogram of per-merge Spearman r values."""
    ax.hist(per_merge_r, bins=20, color="#33a02c", edgecolor="white", linewidth=0.5)
    median_r = sorted(per_merge_r)[len(per_merge_r) // 2]
    ax.axvline(median_r, color="black", linestyle="--", linewidth=1.0,
               label=f"median $r = {median_r:.2f}$" if USE_LATEX else f"median r = {median_r:.2f}")
    ax.set_xlabel("Spearman $r$ per merge" if USE_LATEX else "Spearman r per merge")
    ax.set_ylabel("Number of merges")
    ax.set_title("Per-merge Hamming–Quality Correlation Distribution")
    ax.legend()
    ax.grid(True, alpha=0.3)


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    rq3_dir       = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_RQ3_DIR
    conflicts_csv = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_CONFLICTS_CSV
    output_pdf    = Path(sys.argv[3]) if len(sys.argv) > 3 else DEFAULT_OUTPUT_PDF

    print(f"Loading ground truth from {conflicts_csv} ...")
    ground_truth = load_ground_truth(conflicts_csv)
    print(f"  {len(ground_truth):,} merges with ground-truth patterns.")

    print(f"Loading RQ3 data from {rq3_dir} / {VARIANT_MODE} ...")
    merges = load_rq3_merges(rq3_dir, VARIANT_MODE)
    print(f"  {len(merges):,} merges loaded.")

    all_pairs: list[tuple[int, float]] = []
    per_merge_r: list[float] = []
    skipped_no_gt = 0
    skipped_too_few = 0

    for commit, merge in merges.items():
        gt = ground_truth.get(commit)
        if gt is None:
            skipped_no_gt += 1
            continue
        pairs = analyse_merge(merge, gt)
        if len(pairs) < 3:
            skipped_too_few += 1
            continue
        all_pairs.extend(pairs)
        result = spearman_r([p[0] for p in pairs], [p[1] for p in pairs])
        if result is not None:
            per_merge_r.append(result[0])

    print(f"  Skipped (no ground truth): {skipped_no_gt}")
    print(f"  Skipped (< 3 scoreable variants): {skipped_too_few}")
    print(f"  Total (merge, variant) pairs analysed: {len(all_pairs):,}")

    # Pooled correlation
    pooled_r = None
    if all_pairs:
        result = spearman_r([p[0] for p in all_pairs], [p[1] for p in all_pairs])
        if result is not None:
            pooled_r, pooled_p = result
            print(f"\nPooled Spearman r = {pooled_r:.4f}  (p = {pooled_p:.2e})")
            print("  Negative r = anti-correlation: lower Hamming → higher quality (supports RQ1 criterion).")

    if per_merge_r:
        n = len(per_merge_r)
        neg = sum(1 for r in per_merge_r if r < 0)
        print(f"\nPer-merge Spearman r: n={n}, "
              f"negative={neg} ({100*neg/n:.1f}%), "
              f"median={sorted(per_merge_r)[n//2]:.3f}")

    if not all_pairs:
        print("No data to plot.")
        return

    print(f"\nWriting plots to {output_pdf} ...")
    with PdfPages(output_pdf) as pdf:
        fig, ax = plt.subplots(figsize=(8, 5))
        plot_scatter(ax, all_pairs, pooled_r)
        pdf.savefig(fig, bbox_inches="tight")
        plt.close(fig)

        if per_merge_r:
            fig, ax = plt.subplots(figsize=(8, 4))
            plot_per_merge_r(ax, per_merge_r)
            pdf.savefig(fig, bbox_inches="tight")
            plt.close(fig)

    print("Done.")


if __name__ == "__main__":
    main()
