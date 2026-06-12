#!/usr/bin/env python3
"""
RQ3 anti-correlation analysis: Hamming distance vs. merge quality.

Hypothesis: variants whose pattern assignment is closer to the human resolution
(lower Hamming distance) also tend to achieve higher build quality (more modules
built, more tests passing).  A significant anti-correlation strengthens the
rationale for using Hamming distance as the RQ1 evaluation criterion.

Quality metric mirrors plot_results.py chart 02c: the Laplace-smoothed product
((m+1)/(hb_m+1)) * ((t+1)/(hb_t+1)), so the human baseline scores exactly 1.0.

Inputs:
  - variant_experiments_dir/  (per-project JSON files, one subdir per mode;
                               must contain `human_baseline/` and exactly one
                               variant best-mode subdir, which is auto-detected)
  - all_conflicts.csv          (ground-truth per-chunk human patterns)

Output:
  - Console: pooled Spearman r, p-value; per-merge distribution summary
  - rq3_hamming_quality.pdf    (stacked quality-category shares + per-merge r histogram)

Usage:
  python rq3_hamming_quality_correlation.py [variant_experiments_dir] [all_conflicts_csv] [output_pdf]

Defaults:
  variant_experiments_dir = /home/lanpirot/data/bruteforcemerge/rq2
  all_conflicts_csv       = /home/lanpirot/data/bruteforcemerge/common/all_conflicts.csv
  output_pdf              = rq3_hamming_quality.pdf

Note: the default points at rq2/ as a stand-in until rq3 collection completes.
"""

import sys
import csv
import json
import math
import warnings
from pathlib import Path
from collections import defaultdict

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages

# Optional: scipy for Spearman correlation
try:
    from scipy.stats import spearmanr, ConstantInputWarning
    HAS_SCIPY = True
except ImportError:
    HAS_SCIPY = False
    print("Warning: scipy not available — Spearman r will not be computed.", file=sys.stderr)

csv.field_size_limit(1_000_000)

# ── Defaults ──────────────────────────────────────────────────────────────────

DEFAULT_VARIANT_DIR   = Path("/home/lanpirot/data/bruteforcemerge/rq2")
DEFAULT_CONFLICTS_CSV = Path("/home/lanpirot/data/bruteforcemerge/common/all_conflicts.csv")
DEFAULT_OUTPUT_PDF    = Path("rq3_hamming_quality.pdf")

# Only analyse the best mode (human_baseline provides the quality denominator,
# the variant mode provides the Hamming/quality pairs). The RQ3 best mode is
# configurable per run (-Drq3BestMode), so VARIANT_MODE is discovered from the
# data directory at runtime rather than hard-coded.
BASELINE_MODE = "human_baseline"
VARIANT_MODE  = ""  # set by detect_variant_mode() in main()

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

def _normalize_path(p: str) -> str:
    """Normalize a repo-relative path for matching: strip leading './', collapse
    separators, use forward slashes."""
    # NOT lstrip("./") — that strips *characters* and would mangle dotfile
    # paths like ".github/x" into "github/x".
    return str(Path(p)).removeprefix("./").lstrip("/")


def load_ground_truth(all_conflicts_csv: Path) -> dict[str, dict[str, list[str]]]:
    """
    Returns ground_truth[merge_commit_sha][filename] = [pattern_chunk0, ...]
    sorted by chunkIndex (ascending, 1-based from the DB).
    Filenames are normalized repo-relative paths.

    The dict is keyed by the merge commit SHA (CSV column `commitId`), which is
    what the variant JSONs carry as `mergeCommit`.  The numeric `merge_id`
    column is a DB primary key and does not match the JSON side.
    """
    # commit_sha -> filename -> list of (chunkIndex, pattern)
    raw: dict[str, dict[str, list[tuple[int, str]]]] = defaultdict(lambda: defaultdict(list))

    with open(all_conflicts_csv, newline="") as f:
        for row in csv.DictReader(f):
            commit_id = row.get("commitId", "").strip()
            filename  = row.get("filename", "").strip()
            y         = row.get("y_conflictResolutionResult", "").strip()
            try:
                idx = int(row.get("chunkIndex", "0"))
            except ValueError:
                idx = 0
            if commit_id and filename and y:
                raw[commit_id][_normalize_path(filename)].append((idx, y))

    # Sort by chunkIndex and drop the index
    ground_truth: dict[str, dict[str, list[str]]] = {}
    for commit_id, files in raw.items():
        ground_truth[commit_id] = {
            fname: [pat for _, pat in sorted(chunks)]
            for fname, chunks in files.items()
        }
    return ground_truth


# ── Hamming distance ──────────────────────────────────────────────────────────

# The DB stores the human resolution in a different vocabulary than the variant
# search space.  Translate GT labels to the variant pattern strings so they can
# be compared.  CHUNK_NONCANONICAL means the human did something outside the
# variant search space — represented as the sentinel "MANUAL", which never
# matches any variant pattern (per-chunk distance is always 1).
_GT_TO_PATTERN = {
    "CHUNK_CANONICAL_OURS":              "OURS",
    "CHUNK_CANONICAL_THEIRS":            "THEIRS",
    "CHUNK_CANONICAL_BASE":              "BASE",
    "CHUNK_NONCANONICAL":                "MANUAL",
    "CHUNK_SEMICANONICAL_EMPTY":         "EMPTY",
    "CHUNK_SEMICANONICAL_OURSTHEIRS":    "OURS:THEIRS",
    "CHUNK_SEMICANONICAL_OURSBASE":      "BASE:OURS",
    "CHUNK_SEMICANONICAL_BASETHEIRS":    "BASE:THEIRS",
    "CHUNK_SEMICANONICAL_OURSBASETHEIRS":"BASE:OURS:THEIRS",
}


def _normalize_pattern(pat: str) -> str:
    """Sort colon-separated components alphabetically so composite patterns
    compare order-insensitively (e.g. 'OURS:THEIRS' == 'THEIRS:OURS')."""
    if ":" in pat:
        return ":".join(sorted(pat.split(":")))
    return pat


def chunk_distance(gt_label: str, variant_pat: str) -> int:
    """Per-chunk distance under the spec: 0 if (translated) hb equals v,
    else 1.  hb=MANUAL (i.e. CHUNK_NONCANONICAL) always yields 1."""
    hb_pat = _GT_TO_PATTERN.get(gt_label, gt_label)
    if hb_pat == "MANUAL":
        return 1
    return 0 if _normalize_pattern(hb_pat) == _normalize_pattern(variant_pat) else 1


def hamming_distance(variant_patterns: dict[str, list[str]],
                     gt_patterns: dict[str, list[str]]) -> int | None:
    """
    Total Hamming distance between a variant's pattern assignment and the
    human ground truth, summed over chunks of files present on both sides.

    Per-chunk rule (see chunk_distance):
      * MANUAL on the hb side  → 1  (never reachable by any variant)
      * else                    → 0 if hb == v after pattern normalization,
                                  1 otherwise.
    Range for n matched chunks: 0..n.

    variant_patterns: conflictPatterns from the JSON variant
                      {filename -> [pattern_per_chunk]}
    gt_patterns:      ground_truth[commit_sha] as loaded above
                      (filenames already normalized by load_ground_truth)

    Returns None if the filenames don't overlap (can't compare).
    """
    total_dist = 0
    matched_chunks = 0

    for fname, variant_list in variant_patterns.items():
        gt_list = gt_patterns.get(_normalize_path(fname))
        if gt_list is None:
            continue  # file not in ground truth — skip

        for v_pat, gt_pat in zip(variant_list, gt_list):
            total_dist += chunk_distance(gt_pat, v_pat)
            matched_chunks += 1

    if matched_chunks == 0:
        return None
    return total_dist


# ── Quality score ─────────────────────────────────────────────────────────────

def _modules_passed(variant: dict) -> int:
    """Modules that built successfully for this variant (mirrors plot_results.module_stats)."""
    cr = variant.get("compilationResult")
    if cr is None:
        return 0
    total_m = cr.get("totalModules")
    if total_m is not None:
        m = cr.get("successfulModules", 0)
        # Single-module flat-format builds report totalModules=0 with
        # buildStatus=SUCCESS — count them as 1 (mirrors plot_results.module_stats).
        if cr.get("buildStatus") == "SUCCESS":
            m = max(1, m)
        return m
    module_results = cr.get("moduleResults", [])
    if module_results:
        return sum(1 for m in module_results if m.get("status") == "SUCCESS")
    return 1 if cr.get("buildStatus") == "SUCCESS" else 0


def _tests_passed(variant: dict) -> int:
    tr = variant.get("testResults")
    if tr is None:
        return 0
    run = tr.get("runNum", 0)
    return max(0, run - tr.get("failuresNum", 0) - tr.get("errorsNum", 0))


def combined_quality(variant: dict, hb_modules: int, hb_tests: int) -> float | None:
    """
    Laplace-smoothed combined-quality score relative to the human baseline.

        Y = (m+1)/(hb_m+1) * (t+1)/(hb_t+1)

    The +1 smoothing keeps the modules signal alive when tests = 0 (and vice
    versa) so a single zero factor doesn't annihilate the product.  Human
    baseline scores exactly 1.0 by construction.  Returns None for variants
    without a scoreable compilationResult (TIMEOUT or missing).

    Mirrors plot_results.combined_quality (chart 02c).
    """
    cr = variant.get("compilationResult")
    if cr is None or cr.get("buildStatus") in (None, "TIMEOUT"):
        return None
    m = _modules_passed(variant)
    t = _tests_passed(variant)
    return ((m + 1) / (hb_modules + 1)) * ((t + 1) / (hb_tests + 1))


# ── Data loading ──────────────────────────────────────────────────────────────

def detect_variant_mode(variant_dir: Path) -> str:
    """Discover the single variant mode subdir RQ3 wrote alongside
    `human_baseline/`. The best mode varies per run (-Drq3BestMode), so it must
    be read from the directory rather than assumed (e.g. `no_optimization` for
    the S run, `cache_parallel` for P+)."""
    candidates = sorted(
        d.name for d in variant_dir.iterdir()
        if d.is_dir() and d.name != BASELINE_MODE and any(d.glob("*.json"))
    )
    if not candidates:
        sys.exit(f"Error: no variant mode subdir (other than {BASELINE_MODE}) "
                 f"with JSON found in {variant_dir}")
    if len(candidates) > 1:
        print(f"Warning: multiple variant modes {candidates}; using "
              f"'{candidates[0]}'.", file=sys.stderr)
    return candidates[0]


def load_merges(variant_dir: Path, mode: str) -> dict[str, dict]:
    """Returns {mergeCommit -> merge_dict} for the given mode.

    Each JSON file is a single per-merge record (flat format, no 'merges' wrapper).
    """
    mode_dir = variant_dir / mode
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
        mc = data.get("mergeCommit")
        if mc:
            merges[mc] = data
    return merges


def baseline_stats(baseline_merge: dict) -> tuple[int, int] | None:
    """(hb_modules, hb_tests) from variantIndex==0 of a human_baseline merge,
    or None if unscoreable / no modules built."""
    variants = baseline_merge.get("variants") or []
    v0 = next((v for v in variants if v.get("variantIndex") == 0), None)
    if v0 is None:
        return None
    hb_m = _modules_passed(v0)
    hb_t = _tests_passed(v0)
    if hb_m <= 0:  # mirrors plot_results.assemble_combined_plot_data filter
        return None
    return hb_m, hb_t


# ── Per-merge analysis ────────────────────────────────────────────────────────

def analyse_merge(merge: dict, gt: dict[str, list[str]],
                  hb_modules: int, hb_tests: int) -> list[tuple[int, float]]:
    """
    For one merge, return a list of (hamming_distance, combined_quality)
    pairs — one per non-timed-out, scoreable variant.  Hamming is the raw
    integer chunk-mismatch count, *not* normalized: a 1-chunk merge whose
    variant differs in 1 chunk is the same Hamming=1 as a 10-chunk merge
    whose variant differs in 1 chunk.  Normalization conflates the two.
    """
    pairs = []
    for v in merge.get("variants", []):
        if v.get("timedOut"):
            continue
        cp = v.get("conflictPatterns")
        if not cp:
            continue
        q = combined_quality(v, hb_modules, hb_tests)
        if q is None:
            continue
        dist = hamming_distance(cp, gt)
        if dist is None:
            continue
        pairs.append((dist, q))
    return pairs


# ── Correlation computation ───────────────────────────────────────────────────

def spearman_r(xs: list[float], ys: list[float]) -> tuple[float, float] | None:
    """Returns (r, p_value) or None if scipy unavailable, too few points, or
    one of the inputs is constant (Spearman is undefined)."""
    if not HAS_SCIPY or len(xs) < 3:
        return None
    with warnings.catch_warnings():
        # Constant input yields NaN, which the caller-facing None below covers.
        warnings.simplefilter("ignore", ConstantInputWarning)
        r, p = spearmanr(xs, ys)
    if math.isnan(r) or math.isnan(p):
        return None
    return float(r), float(p)


# ── Plotting ──────────────────────────────────────────────────────────────────

# Quality categories for the Hamming-vs-quality stacked shares, listed
# bottom-up (best category at the bottom of the stack).  The combined-quality
# distribution is strongly bimodal — ~45% of variants land near 0 and ~36% at
# exactly 1.0 — so percentile bands or medians just flip between the two modes;
# the *share* of variants per category changes smoothly with distance instead.
QUALITY_CATEGORIES = [          # (lower bound, label, RdYlGn colour)
    (0.999, "comparable or better ($q \\geq 1$)",   "#1a9850"),
    (0.5,   "most builds ($0.5 \\leq q < 1$)",      "#a6d96a"),
    (0.1,   "partial builds ($0.1 \\leq q < 0.5$)", "#fdae61"),
    (0.0,   "(almost) nothing builds ($q < 0.1$)",  "#d73027"),
]

# Consecutive Hamming distances are merged (left to right) until each x-bin
# holds at least this many (merge, variant) pairs.  A binomial share over
# >= 1000 samples has a standard error below ~1.6%, so the share curves are
# stable per bin; the sparse far tail merges into the last bin.
MIN_PAIRS_PER_BIN = 1000

# The far distance tail (top few % of pairs) stems from a handful of large
# merges whose chunk-rich variants dominate any pooled bin they fall into,
# producing between-merge artifacts (e.g. a lone "builds fine at h=19" bump).
# Cut the chart at this pair-percentile of the distance distribution.
TAIL_CUT_PERCENTILE = 95


def plot_quality_shares(ax, all_pairs: list[tuple[int, float]], r_pooled: float | None):
    """Stacked share chart: Hamming distance (x, binned) vs. quality-category
    share (y).

    All variants are pooled and grouped into Hamming-distance bins of at least
    MIN_PAIRS_PER_BIN pairs; each bin is split into the QUALITY_CATEGORIES and
    the category shares are stacked (best at the bottom), plotted at the bin's
    pair-weighted mean distance.
    """
    import numpy as np

    cutoff = int(np.percentile([h for h, _ in all_pairs], TAIL_CUT_PERCENTILE))
    by_h: dict[int, list[float]] = defaultdict(list)
    for h, q in all_pairs:
        if h <= cutoff:
            by_h[h].append(q)

    bins: list[tuple[list[int], list[float]]] = []   # (hamming value ×count, qualities)
    cur_h: list[int] = []
    cur_q: list[float] = []
    for h in sorted(by_h):
        cur_h.extend([h] * len(by_h[h]))
        cur_q.extend(by_h[h])
        if len(cur_q) >= MIN_PAIRS_PER_BIN:
            bins.append((cur_h, cur_q))
            cur_h, cur_q = [], []
    if cur_q:
        if bins:
            bins[-1] = (bins[-1][0] + cur_h, bins[-1][1] + cur_q)
        else:
            bins.append((cur_h, cur_q))

    centers = np.array([np.mean(hs) for hs, _ in bins])
    qs_per_bin = [np.array(qb) for _, qb in bins]
    shares = []
    for lo, _, _ in QUALITY_CATEGORIES:
        upper = shares[-1][0] if shares else math.inf
        shares.append((lo, [((qb >= lo) & (qb < upper)).mean() for qb in qs_per_bin]))

    ax.stackplot(centers, [s for _, s in shares],
                 labels=[lbl if USE_LATEX else lbl.replace("$", "").replace("\\geq", ">=").replace("\\leq", "<=")
                         for _, lbl, _ in QUALITY_CATEGORIES],
                 colors=[c for _, lbl, c in QUALITY_CATEGORIES],
                 alpha=0.85, linewidth=0)

    ax.set_xlim(centers[0], centers[-1])
    ax.set_ylim(0, 1)
    ax.set_xlabel("Hamming distance to human resolution"
                  f" (bins of $\\geq$ {MIN_PAIRS_PER_BIN} variants; "
                  f"top {100 - TAIL_CUT_PERCENTILE}\\% tail $h > {cutoff}$ omitted)" if USE_LATEX
                  else "Hamming distance to human resolution"
                  f" (bins of >= {MIN_PAIRS_PER_BIN} variants; "
                  f"top {100 - TAIL_CUT_PERCENTILE}% tail h > {cutoff} omitted)")
    ax.set_ylabel("Share of variants")
    title = f"Hamming Distance vs. Build Quality ({VARIANT_MODE} variants, pooled)"
    if r_pooled is not None:
        title += f"\nSpearman $r = {r_pooled:.3f}$" if USE_LATEX else f"\nSpearman r = {r_pooled:.3f}"
    ax.set_title(title)
    # Reverse the legend so its order matches the visual stack (red on top).
    handles, labels = ax.get_legend_handles_labels()
    ax.legend(handles[::-1], labels[::-1], loc="upper right", framealpha=0.95)
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


# ── Analysis ──────────────────────────────────────────────────────────────────

def compute_pairs(variant_dir: Path, conflicts_csv: Path):
    """Compute Hamming-vs-quality data for the variant best-mode in
    ``variant_dir`` against the human ground truth. Sets the module-level
    VARIANT_MODE (used by plot titles) and returns
    ``(all_pairs, per_merge_r, pooled_r, stats)``. Reused by main() and by
    plot_results.py so the two Hamming charts can join the combined collection."""
    global VARIANT_MODE
    VARIANT_MODE = detect_variant_mode(variant_dir)

    ground_truth = load_ground_truth(conflicts_csv)
    merges    = load_merges(variant_dir, VARIANT_MODE)
    baselines = load_merges(variant_dir, BASELINE_MODE)

    all_pairs: list[tuple[int, float]] = []
    per_merge_r: list[float] = []
    stats = {"merges": len(merges), "ground_truth": len(ground_truth),
             "no_gt": 0, "no_baseline": 0, "too_few": 0}

    for commit, merge in merges.items():
        gt = ground_truth.get(commit)
        if gt is None:
            stats["no_gt"] += 1
            continue
        baseline_merge = baselines.get(commit)
        if baseline_merge is None:
            stats["no_baseline"] += 1
            continue
        hb = baseline_stats(baseline_merge)
        if hb is None:
            stats["no_baseline"] += 1
            continue
        pairs = analyse_merge(merge, gt, hb[0], hb[1])
        if len(pairs) < 3:
            stats["too_few"] += 1
            continue
        all_pairs.extend(pairs)
        result = spearman_r([p[0] for p in pairs], [p[1] for p in pairs])
        if result is not None:
            per_merge_r.append(result[0])

    pooled_r = None
    if all_pairs:
        result = spearman_r([p[0] for p in all_pairs], [p[1] for p in all_pairs])
        if result is not None:
            pooled_r = result[0]
    return all_pairs, per_merge_r, pooled_r, stats


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    variant_dir   = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_VARIANT_DIR
    conflicts_csv = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_CONFLICTS_CSV
    output_pdf    = Path(sys.argv[3]) if len(sys.argv) > 3 else DEFAULT_OUTPUT_PDF

    print(f"Loading ground truth from {conflicts_csv} and variant data from {variant_dir} ...")
    all_pairs, per_merge_r, pooled_r, stats = compute_pairs(variant_dir, conflicts_csv)
    print(f"Detected variant mode: {VARIANT_MODE}")
    print(f"  {stats['ground_truth']:,} merges with ground-truth patterns; "
          f"{stats['merges']:,} variant merges loaded.")
    print(f"  Skipped (no ground truth): {stats['no_gt']}")
    print(f"  Skipped (no/unscoreable human baseline): {stats['no_baseline']}")
    print(f"  Skipped (< 3 scoreable variants): {stats['too_few']}")
    print(f"  Total (merge, variant) pairs analysed: {len(all_pairs):,}")

    if pooled_r is not None:
        print(f"\nPooled Spearman r = {pooled_r:.4f}")
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
        plot_quality_shares(ax, all_pairs, pooled_r)
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
