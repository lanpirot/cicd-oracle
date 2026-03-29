#!/usr/bin/env python3
"""
Unified presentation layer for variant experiment results.
Compares 5 execution modes: human_baseline, no_optimization, cache_sequential, parallel, cache_parallel.

Charts produced (single PDF):
  1. Module build success rate over relative time
  2. Test success rate over relative time
  3. Java conflict file ratio vs. impact rate (bar chart)

All charts use LaTeX fonts for paper inclusion.
Set USE_LATEX = False if pdflatex / dvipng are not installed.

Usage:
  python plot_results.py [variant_experiments_dir] [output_pdf]
  python plot_results.py --mockup [output_pdf]

Defaults:
  variant_experiments_dir = /home/lanpirot/data/bruteforcemerge/variant_experiments
  output_pdf = plots.pdf
"""

import sys
import os
import json
import math
import itertools
from pathlib import Path
from collections import defaultdict

import latex_variables

import matplotlib
import matplotlib.ticker
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.backends.backend_pdf import PdfPages

try:
    from scipy.stats import wilcoxon, friedmanchisquare
    HAS_SCIPY = True
except ImportError:
    HAS_SCIPY = False
    print("Warning: scipy not available — statistical tests will be skipped.", file=sys.stderr)

# ── Paper font settings ────────────────────────────────────────────────────────
# Set USE_LATEX = False if pdflatex / dvipng are not installed on this machine.
USE_LATEX = True

plt.rcParams.update({
    "text.usetex":               USE_LATEX,
    "font.family":               "serif",
    "font.size":                 10,
    "axes.labelsize":            10,
    "axes.titlesize":            11,
    "legend.fontsize":           8,
    "xtick.labelsize":           9,
    "ytick.labelsize":           9,
    "figure.dpi":                200,
})

# ── Configuration ──────────────────────────────────────────────────────────────
DEFAULT_VARIANT_DIR = Path("/home/lanpirot/data/bruteforcemerge/variant_experiments")
DEFAULT_OUTPUT_PDF  = Path("plots.pdf")

MODES = ["human_baseline", "no_optimization", "cache_sequential", "parallel", "cache_parallel"]
MODE_LABELS = {
    "human_baseline":   "Human Baseline",
    "no_optimization":  "Sequential",
    "cache_sequential": "Sequential + Cache",
    "parallel":         "Parallel",
    "cache_parallel":   "Parallel + Cache",
}
MODE_SHORT = {
    "human_baseline":   "HB",
    "no_optimization":  "S",
    "cache_sequential": "S+",
    "parallel":         "P",
    "cache_parallel":   "P+",
}
# ColorBrewer Paired: hue = sequential(red) / parallel(green), shade = cache variant
MODE_COLORS = {
    "human_baseline":   "#a6cee3",   # light blue (neutral)
    "no_optimization":  "#e31a1c",   # red
    "cache_sequential": "#fb9a99",   # light red
    "parallel":         "#33a02c",   # dark green
    "cache_parallel":   "#b2df8a",   # light green
}
MODE_MARKERS = {
    "human_baseline":  "D",
    "no_optimization": "o",
    "cache_sequential": "v",
    "parallel":        "s",
    "cache_parallel":  "^",
}


# ── Impact detection (mirrors Java MergeStatistics.filterImpactMerges) ────────

def variant_score(variant: dict) -> tuple | None:
    """
    Returns (modules_passed, tests_passed) or None if the variant timed out
    or has no scoreable compilation result.
    Mirrors Java VariantScore.of().
    """
    cr = variant.get("compilationResult")
    if cr is None:
        return None
    status = cr.get("buildStatus")
    if status is None or status == "TIMEOUT":
        return None
    # New flat format: totalModules / successfulModules
    total_m = cr.get("totalModules")
    if total_m is not None:
        modules = cr.get("successfulModules", 0)
    else:
        # Legacy format: moduleResults array
        module_results = cr.get("moduleResults", [])
        if module_results:
            modules = sum(1 for m in module_results if m.get("status") == "SUCCESS")
        else:
            modules = 1 if status == "SUCCESS" else 0
    # Normalize: a successful single-module build counts as at least 1
    if status == "SUCCESS":
        modules = max(1, modules)
    tr = variant.get("testResults")
    tests = 0
    if tr:
        run = tr.get("runNum", 0)
        tests = max(0, run - tr.get("failuresNum", 0) - tr.get("errorsNum", 0))
    return (modules, tests)


def is_impact_merge(merge: dict) -> bool:
    """
    A merge is 'impact' if at least one non-baseline variant has a
    different score than the baseline (variantIndex 0).
    Mirrors Java filterImpactMerges.
    """
    if merge.get("numConflictChunks", 0) == 0:
        return False
    variants = merge.get("variants", [])
    baseline_score = None
    for v in variants:
        if v.get("variantIndex") == 0:
            baseline_score = variant_score(v)
            break
    if baseline_score is None:
        return False
    for v in variants:
        if v.get("variantIndex") == 0:
            continue
        score = variant_score(v)
        if score is None:
            continue  # timeout — excluded
        if score != baseline_score:
            return True
    return False


def compute_statistics(all_data: dict) -> dict:
    """
    Compute cross-mode statistics (impact set, Java ratio data).
    Uses the first available non-baseline mode for impact detection.
    Returns a dict with keys: all_merges (list), impact_set (set of mergeCommit strings).
    """
    source_mode = next(
        (m for m in ["no_optimization", "parallel", "cache_parallel"] if all_data.get(m)),
        None,
    )
    if source_mode is None:
        return {"all_merges": [], "impact_set": set()}
    all_merges = list(all_data[source_mode].values())
    impact_set = {m["mergeCommit"] for m in all_merges if is_impact_merge(m)}
    return {"all_merges": all_merges, "impact_set": impact_set}


# ── Data loading ───────────────────────────────────────────────────────────────

def load_mode_data(variant_dir: Path, mode: str) -> dict:
    """Load all JSON files for a mode. Returns dict: mergeCommit -> merge_json_dict.

    Each JSON file is a single per-merge record (flat format, no 'merges' wrapper).
    """
    mode_dir = variant_dir / mode
    if not mode_dir.exists():
        return {}
    merges = {}
    for json_file in mode_dir.glob("*.json"):
        try:
            with open(json_file) as f:
                data = json.load(f)
            mc = data.get("mergeCommit")
            if mc:
                merges[mc] = data
        except Exception as e:
            print(f"  Warning: could not read {json_file}: {e}", file=sys.stderr)
    return merges


def load_all_data(variant_dir: Path) -> dict:
    """Returns dict: mode -> {mergeCommit -> merge_dict}"""
    return {mode: load_mode_data(variant_dir, mode) for mode in MODES}


# ── Success rate helpers ───────────────────────────────────────────────────────

def module_stats(variant: dict) -> tuple[int, int]:
    """Returns (modulesPassed, totalModules) for a variant dict."""
    cr = variant.get("compilationResult")
    if cr is None:
        return 0, 0
    # New flat format: totalModules / successfulModules
    total_m = cr.get("totalModules")
    if total_m is not None:
        return cr.get("successfulModules", 0), max(1, total_m)
    # Legacy format: moduleResults array
    module_results = cr.get("moduleResults", [])
    if module_results:
        total = len(module_results)
        passed = sum(1 for m in module_results if m.get("status") == "SUCCESS")
        return passed, total
    # Single-module project
    build_status = cr.get("buildStatus", "FAILURE")
    passed = 1 if build_status == "SUCCESS" else 0
    return passed, 1


def test_stats(variant: dict) -> tuple[int, int]:
    """Returns (testsPassed, testsRun) for a variant dict."""
    tr = variant.get("testResults")
    if tr is None:
        return 0, 0
    run = tr.get("runNum", 0)
    failures = tr.get("failuresNum", 0)
    errors = tr.get("errorsNum", 0)
    passed = max(0, run - failures - errors)
    return passed, run


def get_finish_time(variant: dict, fallback_seconds: float = 0.0) -> float:
    """Returns totalTimeSinceMergeStartSeconds, falling back to compilationResult.totalTime."""
    t = variant.get("totalTimeSinceMergeStartSeconds")
    if t is not None:
        return float(t)
    cr = variant.get("compilationResult")
    if cr is not None:
        return float(cr.get("totalTime", fallback_seconds))
    return fallback_seconds


# ── Per-merge improvement markers ─────────────────────────────────────────────

def improvement_markers(variants: list, metric_fn, human_baseline_secs: float,
                        hb_num: float) -> list[tuple[float, float]]:
    """
    For a list of variant dicts, returns (relativeTime, relativeScore) markers
    where relativeTime  = totalTimeSinceMergeStartSeconds / budgetBasisSeconds
          relativeScore = metric_fn(v)[0] / hb_num  (1.0 = human baseline quality)
    Only variants that strictly improve on all previous variants are kept.
    metric_fn(variant) -> (numerator, denominator)
    """
    if human_baseline_secs <= 0 or hb_num <= 0:
        return []

    timed = [(get_finish_time(v), metric_fn(v)[0]) for v in variants]
    timed.sort(key=lambda x: x[0])

    markers = []
    best_rate = -1.0
    for finish, num in timed:
        rate = num / hb_num
        if rate > best_rate:
            best_rate = rate
            markers.append((finish / human_baseline_secs, rate))
    return markers


# ── Plot data assembly ─────────────────────────────────────────────────────────

def assemble_plot_data(all_data: dict, metric_fn):
    """
    Returns dict: mode -> list of (relativeTime, relativeScore) improvement markers,
    where relativeScore is normalised so that 1.0 = human baseline score for that merge.
    Also returns dict: mode -> list of step-function series (each a list of (x,y) for one merge).
    """
    all_commits = set(all_data["human_baseline"].keys())

    mode_markers = defaultdict(list)
    mode_steps   = defaultdict(list)

    for commit in all_commits:
        baseline_merge = all_data["human_baseline"].get(commit)
        if not baseline_merge:
            continue
        hb_secs = float(baseline_merge.get("budgetBasisSeconds", 0))
        if hb_secs <= 0:
            continue

        # Human baseline score = numerator of the variantIndex-0 variant
        hb_variants = baseline_merge.get("variants") or []
        hb_variant = next((v for v in hb_variants if v.get("variantIndex") == 0), None)
        if hb_variant is None:
            continue
        hb_num, _ = metric_fn(hb_variant)
        if hb_num <= 0:
            continue  # can't normalise against zero

        for mode in MODES:
            merge = all_data[mode].get(commit)
            if not merge:
                continue
            variants = merge.get("variants") or []
            if not variants:
                continue

            if mode == "human_baseline":
                # Always lands at (t=1.0, score=1.0) by definition
                mode_steps[mode].append([(0.0, 0.0), (1.0, 0.0), (1.0, 1.0)])
                mode_markers[mode].append((1.0, 1.0))
            else:
                markers = improvement_markers(variants, metric_fn, hb_secs, hb_num)
                if markers:
                    mode_markers[mode].extend(markers)
                    mode_steps[mode].append([(0.0, 0.0)] + markers)

    return mode_markers, mode_steps


# ── Plotting ───────────────────────────────────────────────────────────────────

def draw_graph(ax, mode_markers, mode_steps, title, ylabel):
    """Draw one graph (module or test) onto ax."""
    for mode in MODES:
        color = MODE_COLORS[mode]
        marker = MODE_MARKERS[mode]
        label = MODE_LABELS[mode]

        # Draw thin step lines per merge (light, semi-transparent)
        for steps in mode_steps.get(mode, []):
            xs = [p[0] for p in steps]
            ys = [p[1] for p in steps]
            ax.step(xs, ys, where="post", color=color, alpha=0.15, linewidth=0.7)

        # Draw improvement markers
        pts = mode_markers.get(mode, [])
        if pts:
            xs = [p[0] for p in pts]
            ys = [p[1] for p in pts]
            ax.scatter(xs, ys, c=color, marker=marker, s=25, zorder=5,
                       label=label, alpha=0.75, edgecolors="none")

    # Reference lines at the human baseline point
    ax.axvline(x=1.0, color="grey", linestyle="--", linewidth=0.8, alpha=0.6)
    ax.axhline(y=1.0, color="grey", linestyle="--", linewidth=0.8, alpha=0.6)

    ax.set_xlabel("Relative time  (1.0 = human baseline duration)", fontsize=10)
    ax.set_ylabel(ylabel, fontsize=10)
    ax.set_title(title, fontsize=12)
    ax.set_xlim(left=0)
    ax.set_ylim(bottom=0)
    ax.legend(loc="lower right", fontsize=8)
    ax.grid(True, alpha=0.3)


def draw_java_ratio_chart(ax, all_merges: list, impact_set: set):
    """
    Bar chart: fraction of conflict files that are Java vs. impact rate.
    5 equal-width buckets spanning [0, 1].
    """
    labels   = [r"$[0.0,\,0.2)$", r"$[0.2,\,0.4)$", r"$[0.4,\,0.6)$",
                r"$[0.6,\,0.8)$", r"$[0.8,\,1.0]$"]
    totals  = [0] * 5
    impacts = [0] * 5

    for merge in all_merges:
        total_files = merge.get("numConflictFiles", 0)
        if total_files == 0:
            continue
        java_files = merge.get("numJavaConflictFiles", 0)
        ratio  = java_files / total_files
        bucket = min(4, int(ratio / 0.2))
        totals[bucket] += 1
        if merge.get("mergeCommit") in impact_set:
            impacts[bucket] += 1

    impact_rates = [impacts[i] / totals[i] if totals[i] > 0 else 0.0 for i in range(5)]

    x    = list(range(5))
    bars = ax.bar(x, impact_rates, color="#2c7bb6", edgecolor="white", linewidth=0.5,
                  zorder=3)

    for bar, imp, tot in zip(bars, impacts, totals):
        label = rf"${imp}/{tot}$" if USE_LATEX else f"{imp}/{tot}"
        ax.text(bar.get_x() + bar.get_width() / 2,
                bar.get_height() + 0.02,
                label, ha="center", va="bottom", fontsize=8)

    ax.set_xticks(x)
    ax.set_xticklabels(labels)
    ax.set_xlabel(r"Java conflict file ratio"
                  r" $\left(\frac{\mathrm{numJavaConflictFiles}}{\mathrm{numConflictFiles}}\right)$")
    ax.set_ylabel(r"Impact rate (fraction of merges)")
    ax.set_title(r"Java Conflict File Ratio vs.\ Impact Rate")
    ax.set_ylim(0, 1.15)
    ax.yaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(xmax=1))
    ax.grid(True, axis="y", alpha=0.3, zorder=0)


def draw_search_space_coverage(ax, all_data: dict, num_patterns: int = 16):
    """
    Violin + jitter plot: log10 fraction of the naive search space (p^c) explored per merge.
    One violin per non-baseline execution mode.

    For each merge: fraction = min(1, numVariantsAttempted / p^c),
    where p = num_patterns and c = numConflictChunks.
    Merges with c=0 or zero variants are skipped.
    """
    import random as _random
    EXPLORATION_MODES = [m for m in MODES if m != "human_baseline"]

    mode_fractions: dict[str, list[float]] = {}
    for mode in EXPLORATION_MODES:
        fracs = []
        for merge in all_data.get(mode, {}).values():
            c = merge.get("numConflictChunks", 0)
            if c <= 0:
                continue
            variants = merge.get("variants", [])
            attempted = len(variants)
            if attempted <= 0:
                continue
            fraction = min(1.0, attempted / (num_patterns ** c))
            fracs.append(math.log10(fraction))
        if fracs:
            mode_fractions[mode] = fracs

    plot_modes = [m for m in EXPLORATION_MODES if m in mode_fractions]
    if not plot_modes:
        ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")
        return

    positions = list(range(len(plot_modes)))
    data      = [mode_fractions[m] for m in plot_modes]

    parts = ax.violinplot(data, positions=positions, showmedians=True, showextrema=True,
                          widths=0.6)
    for pc, mode in zip(parts["bodies"], plot_modes):
        pc.set_facecolor(MODE_COLORS[mode])
        pc.set_alpha(0.65)
        pc.set_edgecolor("none")
    for key in ("cmedians", "cmins", "cmaxes", "cbars"):
        if key in parts:
            parts[key].set_color("black")
            parts[key].set_linewidth(1.0)

    # Jittered individual-point overlay
    rng = _random.Random(0)
    for i, (vals, mode) in enumerate(zip(data, plot_modes)):
        xs = [i + rng.gauss(0, 0.07) for _ in vals]
        ax.scatter(xs, vals, color=MODE_COLORS[mode], s=8, alpha=0.40,
                   edgecolors="none", zorder=3)

    ax.set_xticks(positions)
    ax.set_xticklabels([f"{MODE_LABELS[m]}\nn={len(vals)}"
                        for m, vals in zip(plot_modes, data)])

    def _log10_fmt(y, _):
        exp = int(round(y))
        return (rf"$10^{{{exp}}}$" if USE_LATEX else f"1e{exp}")

    ax.yaxis.set_major_locator(matplotlib.ticker.MaxNLocator(integer=True))
    ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(_log10_fmt))

    space_expr = (rf"$p^c = {num_patterns}^c$" if USE_LATEX
                  else f"p^c = {num_patterns}^c")
    ax.set_ylabel(
        rf"Fraction of search space explored  ({space_expr} total)"
    )
    ax.set_title(
        (rf"Search Space Coverage  ($p={num_patterns}$ patterns, "
         rf"$c=$ conflict chunks per merge)")
        if USE_LATEX else
        f"Search Space Coverage  (p={num_patterns} patterns, c=conflict chunks per merge)"
    )
    ax.grid(True, axis="y", alpha=0.3)

    # Right y-axis: #conflicts whose full search space equals this fraction.
    # Transformation: c = -log10(fraction) / log10(p)  ↔  log10(fraction) = -c·log10(p)
    _log10_p = math.log10(num_patterns)
    ax2 = ax.secondary_yaxis(
        "right",
        functions=(
            lambda y: -y / _log10_p,   # log10-fraction  →  #conflicts
            lambda c: -c * _log10_p,   # #conflicts      →  log10-fraction
        ),
    )
    ax2.yaxis.set_major_locator(matplotlib.ticker.MaxNLocator(integer=True, min_n_ticks=3))
    ax2.set_ylabel(
        r"Equiv.\ \#\ conflicts $c$  (exhaustive at this fraction)"
        if USE_LATEX else
        "Equiv. # conflicts c  (exhaustive at this fraction)"
    )


# ── RQ2 mode comparison: statistical helpers ─────────────────────────────────

VARIANT_MODES = [m for m in MODES if m != "human_baseline"]


def _cliffs_delta(x: list[float], y: list[float]) -> float:
    """Cliff's delta effect size: proportion of (xi > yj) minus (xi < yj)."""
    if not x or not y:
        return 0.0
    n_more = n_less = 0
    for xi in x:
        for yj in y:
            if xi > yj:
                n_more += 1
            elif xi < yj:
                n_less += 1
    n = len(x) * len(y)
    return (n_more - n_less) / n if n else 0.0


def _cliffs_delta_label(d: float) -> str:
    """Interpret Cliff's delta magnitude (Romano et al. 2006 thresholds)."""
    ad = abs(d)
    if ad < 0.147:
        return "negligible"
    if ad < 0.33:
        return "small"
    if ad < 0.474:
        return "medium"
    return "large"


def _pairwise_wilcoxon(groups: dict[str, list[float]], mode_order: list[str],
                       ) -> list[tuple[str, str, float, float, str]]:
    """
    All-pairs Wilcoxon signed-rank tests with Bonferroni correction.
    Returns list of (modeA, modeB, p_corrected, cliff_d, effect_label).
    Only includes pairs where both modes have matching merge sets.
    """
    if not HAS_SCIPY:
        return []
    pairs = list(itertools.combinations(mode_order, 2))
    results = []
    n_pairs = len(pairs)
    for a, b in pairs:
        va, vb = groups.get(a, []), groups.get(b, [])
        if len(va) < 3 or len(vb) < 3 or len(va) != len(vb):
            continue
        diff = [x - y for x, y in zip(va, vb)]
        if all(d == 0 for d in diff):
            results.append((a, b, 1.0, 0.0, "negligible"))
            continue
        try:
            _, p = wilcoxon(va, vb)
        except ValueError:
            continue
        p_corr = min(1.0, p * n_pairs)
        d = _cliffs_delta(va, vb)
        results.append((a, b, p_corr, d, _cliffs_delta_label(d)))
    return results


def _friedman_test(groups: dict[str, list[float]], mode_order: list[str]) -> float | None:
    """Friedman chi-square test across modes. Returns p-value or None."""
    if not HAS_SCIPY:
        return None
    arrays = [groups[m] for m in mode_order if m in groups]
    if len(arrays) < 3 or any(len(a) < 3 for a in arrays):
        return None
    n = min(len(a) for a in arrays)
    arrays = [a[:n] for a in arrays]
    try:
        _, p = friedmanchisquare(*arrays)
        return float(p)
    except ValueError:
        return None


def _annotate_significance(ax, text: str):
    """Place a multi-line significance annotation in the upper-left, on top of all data."""
    ax.text(0.02, 0.98, text, transform=ax.transAxes, fontsize=7,
            verticalalignment="top", fontfamily="monospace", zorder=99,
            bbox=dict(boxstyle="round,pad=0.3", facecolor="wheat", alpha=0.8))


def _significance_summary(friedman_p: float | None,
                          pairwise: list[tuple[str, str, float, float, str]],
                          ) -> str:
    """Format Friedman + pairwise Wilcoxon results as a compact annotation string."""
    lines = []
    if friedman_p is not None:
        sig = "***" if friedman_p < 0.001 else "**" if friedman_p < 0.01 else "*" if friedman_p < 0.05 else "ns"
        lines.append(f"Friedman p={friedman_p:.4f} {sig}")
    for a, b, p, d, lbl in pairwise:
        sig = "***" if p < 0.001 else "**" if p < 0.01 else "*" if p < 0.05 else "ns"
        sa, sb = MODE_SHORT.get(a, a), MODE_SHORT.get(b, b)
        lines.append(f"{sa} vs {sb}: p={p:.4f}{sig}, d={d:+.2f} ({lbl})")
    return "\n".join(lines) if lines else ""


def _common_valid_commits(all_data: dict, modes: list[str]) -> list[str]:
    """
    Return the sorted list of merge commits present in ALL given modes where
    every mode has a valid budget and at least one scoreable non-baseline variant.
    This set is computed once and shared across all RQ2 charts so that every
    chart reports the same n.
    """
    common = None
    for mode in modes:
        commits = set(all_data.get(mode, {}).keys())
        common = commits if common is None else common & commits
    if not common:
        return []

    valid = []
    for commit in sorted(common):
        ok = True
        for mode in modes:
            merge = all_data[mode].get(commit)
            if merge is None:
                ok = False
                break
            budget = merge.get("budgetBasisSeconds", 0)
            if budget <= 0:
                ok = False
                break
            # At least one scoreable non-baseline variant
            has_scoreable = False
            for v in merge.get("variants", []):
                if v.get("variantIndex", 0) == 0 or v.get("timedOut", False):
                    continue
                if variant_score(v) is not None:
                    has_scoreable = True
                    break
            if not has_scoreable:
                ok = False
                break
        if ok:
            valid.append(commit)
    return valid


def _collect_paired_metric(all_data: dict, modes: list[str],
                           merge_metric_fn,
                           valid_commits: list[str] | None = None,
                           ) -> dict[str, list[float]]:
    """
    For each merge in valid_commits (or all common commits if None),
    compute a metric and return aligned lists (same merge order in each mode).
    merge_metric_fn(merge_dict) -> float or None (None = skip this merge).
    """
    if valid_commits is None:
        valid_commits = _common_valid_commits(all_data, modes)

    groups: dict[str, list[float]] = {m: [] for m in modes}

    for commit in valid_commits:
        values = {}
        skip = False
        for mode in modes:
            merge = all_data[mode].get(commit)
            if merge is None:
                skip = True
                break
            val = merge_metric_fn(merge)
            if val is None:
                skip = True
                break
            values[mode] = val
        if skip:
            continue
        for mode in modes:
            groups[mode].append(values[mode])

    return groups


# ── RQ2 Chart: Variants completed per mode ────────────────────────────────────

def _count_completed_variants(merge: dict) -> float | None:
    """Count non-baseline, non-timed-out variants."""
    variants = merge.get("variants", [])
    count = sum(1 for v in variants
                if v.get("variantIndex", 0) != 0 and not v.get("timedOut", False))
    return float(count) if count > 0 else None


def draw_variants_completed(ax, all_data: dict, valid_commits: list[str] | None = None):
    """Box plot (log scale): number of completed variants per mode."""
    groups = _collect_paired_metric(all_data, VARIANT_MODES, _count_completed_variants,
                                    valid_commits)
    if not groups:
        ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")
        return

    plot_modes = [m for m in VARIANT_MODES if groups.get(m)]
    data = [groups[m] for m in plot_modes]
    positions = list(range(len(plot_modes)))

    bp = ax.boxplot(data, positions=positions, widths=0.5, patch_artist=True,
                    showfliers=True, flierprops=dict(markersize=3, alpha=0.5))
    for patch, mode in zip(bp["boxes"], plot_modes):
        patch.set_facecolor(MODE_COLORS[mode])
        patch.set_alpha(0.7)

    ax.set_yscale("log")
    ax.set_xticks(positions)
    ax.set_xticklabels([f"{MODE_LABELS[m]} ({MODE_SHORT[m]})\nn={len(groups[m])}"
                        for m in plot_modes])
    ax.set_ylabel("Completed variants per merge (log scale)")
    ax.set_title("Variant Throughput by Execution Mode")
    ax.grid(True, axis="y", alpha=0.3, which="both")

    # Statistical tests
    friedman_p = _friedman_test(groups, plot_modes)
    pairwise = _pairwise_wilcoxon(groups, plot_modes)
    summary = _significance_summary(friedman_p, pairwise)
    if summary:
        _annotate_significance(ax, summary)


# ── RQ2 Chart: Cache warmup speedup ratio ────────────────────────────────────

def draw_cache_warmup_speedup(ax, all_data: dict, valid_commits: list[str] | None = None):
    """
    Box plot: per-merge speedup ratio (cold / median-warm) for each cache mode.
    Each data point is one merge: ratio of the cache-warmer's build time to
    the median build time of its warmed variants.
    """
    CACHE_MODES = ["cache_sequential", "cache_parallel"]

    box_data = []
    box_labels = []
    box_colors = []
    sig_lines = []

    for mode in CACHE_MODES:
        ratios = []
        cold_times = []
        warm_medians = []
        merges = all_data.get(mode, {})
        commits = valid_commits if valid_commits is not None else sorted(merges.keys())
        for commit in commits:
            merge = merges.get(commit)
            if merge is None:
                continue
            cold_t = None
            warm_ts = []
            for v in merge.get("variants", []):
                if v.get("variantIndex", 0) == 0 or v.get("timedOut", False):
                    continue
                own = v.get("ownExecutionSeconds")
                if own is None or own <= 0:
                    continue
                if v.get("isCacheWarmer", False):
                    cold_t = own
                else:
                    warm_ts.append(own)
            if cold_t is not None and warm_ts:
                warm_med = sorted(warm_ts)[len(warm_ts) // 2]
                ratios.append(cold_t / warm_med)
                cold_times.append(cold_t)
                warm_medians.append(warm_med)

        if ratios:
            box_data.append(ratios)
            box_labels.append(f"{MODE_LABELS[mode]} ({MODE_SHORT[mode]})\nn={len(ratios)}")
            box_colors.append(MODE_COLORS[mode])

            if len(cold_times) >= 3 and HAS_SCIPY:
                try:
                    _, p = wilcoxon(cold_times, warm_medians)
                    d = _cliffs_delta(cold_times, warm_medians)
                    med_ratio = sorted(ratios)[len(ratios) // 2]
                    sig = "***" if p < 0.001 else "**" if p < 0.01 else "*" if p < 0.05 else "ns"
                    sig_lines.append(
                        f"{MODE_SHORT[mode]}: median ratio={med_ratio:.2f}x, "
                        f"p={p:.4f}{sig}, d={d:+.2f} ({_cliffs_delta_label(d)})"
                    )
                except ValueError:
                    pass

    if not box_data:
        ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")
        return

    positions = list(range(len(box_data)))
    bp = ax.boxplot(box_data, positions=positions, widths=0.5, patch_artist=True,
                    showfliers=True, flierprops=dict(markersize=3, alpha=0.5))

    for patch, color in zip(bp["boxes"], box_colors):
        patch.set_facecolor(color)
        patch.set_alpha(0.7)

    # Reference line at ratio=1 (no speedup)
    ax.axhline(y=1.0, color="grey", linestyle="--", linewidth=0.8, alpha=0.6,
               label="no speedup")

    ax.set_yscale("log")
    ax.set_xticks(positions)
    ax.set_xticklabels(box_labels, fontsize=9)
    ax.set_ylabel("Speedup ratio (cold / median warm, log scale)")
    ax.set_title("Cache Warmup Effect: Per-Merge Speedup Ratio")
    ax.legend(loc="upper right", fontsize=8)
    ax.grid(True, axis="y", alpha=0.3, which="both")

    if sig_lines:
        _annotate_significance(ax, "\n".join(sig_lines))


# ── RQ2 helper: time to best variant (used by interaction plot + rank chart) ──

def _time_to_best_variant(merge: dict) -> float | None:
    """
    Wall-clock time (totalTimeSinceMergeStartSeconds) at which the mode's
    best-scoring variant finished, normalised by budgetBasisSeconds.
    Returns None if the merge has no scoreable non-baseline variant.
    """
    budget = merge.get("budgetBasisSeconds", 0)
    if budget <= 0:
        return None

    best_score = None
    best_time = None

    for v in merge.get("variants", []):
        if v.get("variantIndex", 0) == 0:
            continue
        if v.get("timedOut", False):
            continue
        score = variant_score(v)
        if score is None:
            continue
        t = v.get("totalTimeSinceMergeStartSeconds")
        if t is None:
            continue
        if best_score is None or score > best_score or (score == best_score and t < best_time):
            best_score = score
            best_time = t

    if best_time is None:
        return None
    return best_time / budget


# ── RQ2 Chart: Mode rank evolution over time ─────────────────────────────────

def _best_score_at_time(merge: dict, rel_time: float, budget: float) -> tuple | None:
    """
    Best variant_score achieved by this merge's variants up to
    rel_time * budget seconds.  Returns None if no variant finished by then.
    """
    cutoff = rel_time * budget
    best = None
    for v in merge.get("variants", []):
        if v.get("variantIndex", 0) == 0:
            continue
        if v.get("timedOut", False):
            continue
        t = v.get("totalTimeSinceMergeStartSeconds")
        if t is None or t > cutoff:
            continue
        score = variant_score(v)
        if score is None:
            continue
        if best is None or score > best:
            best = score
    return best


def draw_rank_evolution(ax, all_data: dict, valid_commits: list[str] | None = None):
    """
    Rank evolution chart: at each time point (multiples of baseline duration),
    rank the 4 variant modes by their best variant quality for each merge,
    then plot the median rank per mode over time.

    Y-axis: rank 1 (best) at top, 4 (worst) at bottom.
    """
    from scipy.stats import rankdata as _rankdata

    if valid_commits is None:
        valid_commits = _common_valid_commits(all_data, VARIANT_MODES)

    # Pair commits with their budgets
    valid_with_budget = []
    for commit in valid_commits:
        merge = all_data[VARIANT_MODES[0]][commit]
        budget = merge.get("budgetBasisSeconds", 0)
        if budget > 0:
            valid_with_budget.append((commit, budget))

    if not valid_with_budget:
        ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")
        return

    # Determine max relative time from the data
    max_rel = 0
    for commit, budget in valid_with_budget:
        for mode in VARIANT_MODES:
            merge = all_data[mode].get(commit, {})
            for v in merge.get("variants", []):
                t = v.get("totalTimeSinceMergeStartSeconds")
                if t is not None:
                    max_rel = max(max_rel, t / budget)
    max_rel = min(max_rel, 10.0)  # cap at 10x for readability

    time_points = [t * 0.25 for t in range(1, int(max_rel / 0.25) + 1)]
    if not time_points:
        ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")
        return

    # At each time point, rank the modes per merge, then take median rank
    import numpy as np
    mode_median_ranks = {m: [] for m in VARIANT_MODES}

    for rel_t in time_points:
        # Collect per-merge ranks
        all_ranks = {m: [] for m in VARIANT_MODES}

        for commit, budget in valid_with_budget:
            scores = {}
            any_score = False
            for mode in VARIANT_MODES:
                merge = all_data[mode].get(commit, {})
                s = _best_score_at_time(merge, rel_t, budget)
                scores[mode] = s
                if s is not None:
                    any_score = True

            if not any_score:
                continue

            # Convert scores to rankable values (None = worst)
            # variant_score returns (modules, tests) tuples; combine lexicographically
            score_vals = []
            for mode in VARIANT_MODES:
                s = scores[mode]
                if s is None:
                    score_vals.append(-1)  # worst possible
                else:
                    score_vals.append(s[0] * 1_000_000 + s[1])

            # Rank: highest score = rank 1 (negate for rankdata which ranks ascending)
            neg_vals = [-v for v in score_vals]
            ranks = _rankdata(neg_vals, method="average")
            for mode, rank in zip(VARIANT_MODES, ranks):
                all_ranks[mode].append(rank)

        for mode in VARIANT_MODES:
            if all_ranks[mode]:
                mode_median_ranks[mode].append(np.median(all_ranks[mode]))
            else:
                mode_median_ranks[mode].append(np.nan)

    # Plot
    for mode in VARIANT_MODES:
        ranks = mode_median_ranks[mode]
        ax.plot(time_points, ranks,
                color=MODE_COLORS[mode], marker=MODE_MARKERS[mode],
                markersize=7, linewidth=1.8, label=MODE_LABELS[mode],
                alpha=0.9, zorder=3)

    ax.set_xlabel("Relative time (1.0 = human baseline build duration)")
    ax.set_ylabel("Median rank across merges")
    ax.set_title("Mode Rank Evolution Over Time")
    ax.set_ylim(0.5, len(VARIANT_MODES) + 0.5)
    ax.invert_yaxis()  # rank 1 at top
    ax.set_yticks(range(1, len(VARIANT_MODES) + 1))
    ax.set_yticklabels([f"Rank {i}" for i in range(1, len(VARIANT_MODES) + 1)])
    ax.axvline(x=1.0, color="grey", linestyle="--", linewidth=0.8, alpha=0.6)
    ax.legend(loc="lower right", fontsize=8)
    ax.grid(True, alpha=0.3)


# ── RQ2 Chart: Technique decomposition (interaction plot + ART ANOVA) ────────

def _get_threads(merge: dict, mode: str) -> int:
    """Return the thread count for a merge.  Sequential modes → 1; parallel
    modes → the stored value (fallback 1 if missing/null)."""
    if mode in ("no_optimization", "cache_sequential"):
        return 1
    t = merge.get("threads")
    return int(t) if t and t > 0 else 1


def _get_modules(merge: dict) -> int:
    """Return the total module count for a merge from its baseline variant."""
    variants = merge.get("variants", [])
    if not variants:
        return 1
    cr = variants[0].get("compilationResult", {})
    return max(1, cr.get("totalModules", 1))


def _ancova_cache_threads(groups: dict[str, list[float]],
                          thread_counts: dict[str, list[int]],
                          module_counts: dict[str, list[int]] | None = None,
                          ) -> dict | None:
    """
    Rank-based ANCOVA for the 2×2 design with continuous covariates.

    Model:  metric ~ cache + threads + modules + cache:threads + cache:modules

    Uses ranked residuals (Conover & Iman, 1982): rank all observations,
    then run OLS on the ranks.  Returns dict with t/p for each term, or None.
    """
    if not HAS_SCIPY:
        return None

    needed = ["no_optimization", "cache_sequential", "parallel", "cache_parallel"]
    if any(m not in groups or len(groups[m]) < 3 for m in needed):
        return None

    import numpy as np
    from scipy.stats import rankdata, t as t_dist

    n = min(len(groups[m]) for m in needed)

    # Stack observations
    y_raw = []
    cache_vec = []
    threads_vec = []
    modules_vec = []
    has_modules = module_counts is not None and all(m in module_counts for m in needed)
    for mode in needed:
        c = 1 if mode in ("cache_sequential", "cache_parallel") else 0
        for i in range(n):
            y_raw.append(groups[mode][i])
            cache_vec.append(c)
            threads_vec.append(thread_counts[mode][i])
            if has_modules:
                modules_vec.append(module_counts[mode][i])

    y = np.array(y_raw, dtype=float)
    cache = np.array(cache_vec, dtype=float)
    threads = np.array(threads_vec, dtype=float)

    # Rank the response
    y_ranked = rankdata(y).astype(float)

    # Design matrix
    N = len(y_ranked)
    if has_modules:
        modules = np.array(modules_vec, dtype=float)
        X = np.column_stack([
            np.ones(N),
            cache,
            threads,
            modules,
            cache * threads,
            cache * modules,
        ])
        labels = ["intercept", "cache", "threads", "modules",
                  "cache:threads", "cache:modules"]
    else:
        X = np.column_stack([
            np.ones(N),
            cache,
            threads,
            cache * threads,
        ])
        labels = ["intercept", "cache", "threads", "cache:threads"]

    # OLS: beta = (X'X)^-1 X'y
    try:
        XtX_inv = np.linalg.inv(X.T @ X)
    except np.linalg.LinAlgError:
        return None

    beta = XtX_inv @ (X.T @ y_ranked)
    residuals = y_ranked - X @ beta
    df_resid = N - X.shape[1]
    if df_resid <= 0:
        return None

    mse = np.sum(residuals ** 2) / df_resid
    se = np.sqrt(np.diag(XtX_inv) * mse)

    results = {}
    for i, label in enumerate(labels):
        if i == 0:
            continue  # skip intercept
        if se[i] == 0:
            results[label] = {"t": float("inf"), "p": 0.0}
        else:
            t_val = beta[i] / se[i]
            p_val = 2.0 * (1.0 - t_dist.cdf(abs(t_val), df_resid))
            results[label] = {"t": round(float(t_val), 3), "p": round(float(p_val), 6)}

    # Report median threads for context
    par_threads = [t for m in ("parallel", "cache_parallel") for t in thread_counts.get(m, [])[:n]]
    results["_median_threads"] = int(np.median(par_threads)) if par_threads else 1
    if has_modules:
        all_modules = [m for mode in needed for m in module_counts.get(mode, [])[:n]]
        results["_median_modules"] = int(np.median(all_modules)) if all_modules else 1

    return results



def _collect_thread_counts(all_data: dict, modes: list[str],
                           valid_commits: list[str]) -> dict[str, list[int]]:
    """Collect per-merge thread counts aligned with _collect_paired_metric."""
    threads: dict[str, list[int]] = {m: [] for m in modes}
    for commit in valid_commits:
        skip = False
        vals = {}
        for mode in modes:
            merge = all_data.get(mode, {}).get(commit)
            if merge is None:
                skip = True
                break
            vals[mode] = _get_threads(merge, mode)
        if skip:
            continue
        for mode in modes:
            threads[mode].append(vals[mode])
    return threads


def _collect_module_counts(all_data: dict, modes: list[str],
                           valid_commits: list[str]) -> dict[str, list[int]]:
    """Collect per-merge module counts aligned with _collect_paired_metric.
    Module count is a merge-level property (same across modes), extracted
    from the baseline variant's compilationResult.totalModules."""
    modules: dict[str, list[int]] = {m: [] for m in modes}
    for commit in valid_commits:
        # Get module count from any mode that has this commit
        mod_count = None
        for mode in modes:
            merge = all_data.get(mode, {}).get(commit)
            if merge is None:
                mod_count = None
                break
            if mod_count is None:
                mod_count = _get_modules(merge)
        if mod_count is None:
            continue
        for mode in modes:
            modules[mode].append(mod_count)
    return modules


def draw_interaction_plot(ax, all_data: dict, metric_fn, metric_name: str, ylabel: str,
                         valid_commits: list[str] | None = None):
    """
    Interaction plot for the 2×2 factorial design {cache, no-cache} × {sequential, parallel}.
    Shows medians with bootstrap 95% CIs and rank-based ANCOVA p-values
    (cache + threads + cache:threads).
    """
    groups = _collect_paired_metric(all_data, VARIANT_MODES, metric_fn, valid_commits)
    if not groups:
        ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")
        return

    import numpy as np

    x_labels = ["Sequential (1 thread)", "Parallel"]
    x_pos = np.array([0.0, 1.0])

    factor_lines = [
        (("no_optimization", "parallel"), "No Cache", "#e31a1c", "o", -0.02),
        (("cache_sequential", "cache_parallel"), "Cache", "#33a02c", "s", 0.02),
    ]

    for (seq_mode, par_mode), label, color, marker, x_offset in factor_lines:
        medians = []
        ci_lo_list = []
        ci_hi_list = []
        for mode in [seq_mode, par_mode]:
            vals = np.array(groups.get(mode, []))
            if len(vals) == 0:
                medians.append(np.nan)
                ci_lo_list.append(np.nan)
                ci_hi_list.append(np.nan)
                continue
            med = np.median(vals)
            rng = np.random.default_rng(42)
            boot_meds = [np.median(rng.choice(vals, size=len(vals), replace=True))
                         for _ in range(5000)]
            ci_lo_list.append(np.percentile(boot_meds, 2.5))
            ci_hi_list.append(np.percentile(boot_meds, 97.5))
            medians.append(med)

        medians = np.array(medians)
        yerr = [medians - np.array(ci_lo_list), np.array(ci_hi_list) - medians]
        ax.errorbar(x_pos + x_offset, medians, yerr=yerr, marker=marker,
                    markersize=9, linewidth=2.2, capsize=6, capthick=1.5,
                    color=color, label=label, zorder=4)

    ax.set_xticks(x_pos)
    ax.set_xticklabels(x_labels)
    ax.set_ylabel(ylabel)
    ax.set_title(f"Technique Decomposition: {metric_name}")
    ax.legend(loc="best", fontsize=9)
    ax.set_xlim(-0.4, 1.4)
    ax.grid(True, axis="y", alpha=0.3)

    # Per-merge ANCOVA: cache + threads + modules + cache:threads + cache:modules
    common = sorted(set.intersection(*(set(all_data.get(m, {}).keys()) for m in VARIANT_MODES)))
    tc = _collect_thread_counts(all_data, VARIANT_MODES, common)
    mc = _collect_module_counts(all_data, VARIANT_MODES, common)
    ancova = _ancova_cache_threads(groups, tc, mc)
    ann_lines = []
    if ancova:
        med_t = ancova.pop("_median_threads", "?")
        med_m = ancova.pop("_median_modules", "?")
        ann_lines.append(f"Per-merge ANCOVA (med. threads={med_t}, modules={med_m}):")
        for term in ["cache", "threads", "modules", "cache:threads", "cache:modules"]:
            if term in ancova:
                e = ancova[term]
                p = e["p"]
                sig = "***" if p < 0.001 else "**" if p < 0.01 else "*" if p < 0.05 else "ns"
                ann_lines.append(f"  {term}: t={e['t']:.1f}, p={p:.4f} {sig}")
    if ann_lines:
        _annotate_significance(ax, "\n".join(ann_lines))
    else:
        _annotate_significance(ax, "ANCOVA: insufficient data")


# ── RQ2 Chart: Variant index vs. build time (diminishing marginal cost) ───────

def draw_variant_cost_curve(ax, all_data: dict, valid_commits: list[str] | None = None):
    """
    For each mode, plot variant ordinal index (x) vs. ownExecutionSeconds (y).
    Shows that RQ1 predictions order variants well (early variants are
    the most promising) and that each additional variant is cheap.

    Each merge contributes one line (thin, semi-transparent); a thick median
    line per mode summarises the trend.
    """
    import numpy as np

    SMOOTH_HALF = 5  # rolling mean window = ±5 positions (11-wide)
    max_x = 0       # track longest median curve for x-axis limit

    def _rolling_median(vals: list[float], half: int) -> list[float]:
        """Centred rolling median with shrinking window at edges."""
        n = len(vals)
        out = []
        for i in range(n):
            lo = max(0, i - half)
            hi = min(n, i + half + 1)
            window = sorted(vals[lo:hi])
            out.append(window[len(window) // 2])
        return out

    for mode in VARIANT_MODES:
        color = MODE_COLORS[mode]
        marker = MODE_MARKERS[mode]
        label = MODE_LABELS[mode]

        # Collect per-merge series: ordinal -> ownExecutionSeconds
        all_series = []
        merges = all_data.get(mode, {})
        commits = valid_commits if valid_commits is not None else sorted(merges.keys())
        for commit in commits:
            merge = merges.get(commit)
            if merge is None:
                continue
            budget = merge.get("budgetBasisSeconds", 0)
            if budget <= 0:
                continue
            series = []
            for v in merge.get("variants", []):
                if v.get("variantIndex", 0) == 0:
                    continue
                own = v.get("ownExecutionSeconds")
                if own is None:
                    continue
                # Normalise by baseline so projects are comparable
                series.append(own / budget)
            if series:
                all_series.append(series)

        if not all_series:
            continue

        # Median curve: at each ordinal position, take median across merges
        max_len = max(len(s) for s in all_series)
        median_curve = []
        for i in range(max_len):
            vals = [s[i] for s in all_series if i < len(s)]
            if len(vals) >= 3:  # need at least 3 merges at this position
                median_curve.append(np.median(vals))
            else:
                break

        if median_curve:
            smoothed = _rolling_median(median_curve, SMOOTH_HALF)
            # Trim the last SMOOTH_HALF points (incomplete window)
            plot_len = max(1, len(smoothed) - SMOOTH_HALF)
            xs = list(range(1, plot_len + 1))
            ax.plot(xs, smoothed[:plot_len], color=color, linewidth=2.2,
                    marker=marker, markersize=4,
                    markevery=max(1, plot_len // 15),
                    label=label, zorder=4, alpha=0.9)
            max_x = max(max_x, plot_len)

    ax.set_xlabel("Variant ordinal (1 = first variant tried)")
    ax.set_ylabel("Build time (relative to baseline, 1.0 = baseline duration)")
    ax.set_title("Marginal Cost of Additional Variants")
    ax.axhline(y=1.0, color="grey", linestyle="--", linewidth=0.8, alpha=0.6,
               label="baseline build time")
    ax.set_xlim(left=0, right=max_x + 1 if max_x > 0 else None)
    ax.set_ylim(bottom=0, top=2.5)
    ax.legend(loc="upper right", fontsize=8)
    ax.grid(True, alpha=0.3)


def _save_fig(fig, results_dir: Path, name: str, combined_pdf=None):
    """Save a figure as an individual PDF and optionally append to a combined PdfPages."""
    path = results_dir / f"{name}.pdf"
    fig.savefig(path, bbox_inches="tight", pad_inches=0.02)
    if combined_pdf is not None:
        combined_pdf.savefig(fig, bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)


def make_plots(variant_dir: Path, output_pdf: Path, rq: str = "auto"):
    """
    Generate charts.  output_pdf is treated as a path hint:
      - If it has a parent directory other than '.', results go into that directory.
      - Otherwise, results go into <variant_dir>/results/.
    Individual PDFs are saved alongside a combined all_plots.pdf.

    rq: "rq2", "rq3", or "auto" (detect from variant_dir name).
    Charts 01-03 (success-over-time, Java ratio) are RQ3-only.
    Charts 04-09 (mode comparison, technique decomposition) are shared.
    """
    # Detect RQ from directory name if auto
    if rq == "auto":
        dir_name = variant_dir.name.lower()
        if "rq2" in dir_name:
            rq = "rq2"
        elif "rq3" in dir_name:
            rq = "rq3"
        else:
            rq = "rq2"  # default
    print(f"Pipeline: {rq.upper()}")

    # Determine results directory
    if output_pdf.parent != Path("."):
        results_dir = output_pdf.parent
    else:
        results_dir = variant_dir / "results"
    results_dir.mkdir(parents=True, exist_ok=True)
    combined_path = results_dir / "all_plots.pdf"

    print(f"Loading data from: {variant_dir}")
    all_data = load_all_data(variant_dir)

    total = sum(len(v) for v in all_data.values())
    print(f"  Loaded {total} merge records across {len(MODES)} modes")

    stats = None
    module_mode_markers = module_mode_steps = None
    test_mode_markers = test_mode_steps = None

    if rq == "rq3":
        print("Assembling module success data ...")
        module_mode_markers, module_mode_steps = assemble_plot_data(all_data, module_stats)
        print("Assembling test success data ...")
        test_mode_markers, test_mode_steps = assemble_plot_data(all_data, test_stats)

    print("Computing impact statistics ...")
    stats = compute_statistics(all_data)

    print(f"Writing results to: {results_dir}")
    with PdfPages(combined_path) as pdf:

        # ── RQ3-only charts ──────────────────────────────────────────────
        if rq == "rq3":
            # Chart 1: Module build success rate vs. relative time
            fig, ax = plt.subplots(figsize=(10, 6))
            draw_graph(ax, module_mode_markers, module_mode_steps,
                       title=r"Module Build Success Rate vs.\ Relative Time",
                       ylabel=r"Modules built (relative to human baseline $= 1.0$)")
            _save_fig(fig, results_dir, "01_module_success_rate", pdf)

            # Chart 2: Test success rate vs. relative time
            fig, ax = plt.subplots(figsize=(10, 6))
            draw_graph(ax, test_mode_markers, test_mode_steps,
                       title=r"Test Success Rate vs.\ Relative Time",
                       ylabel=r"Tests passed (relative to human baseline $= 1.0$)")
            _save_fig(fig, results_dir, "02_test_success_rate", pdf)

            # Chart 3: Java conflict file ratio vs. impact rate
            if stats["all_merges"]:
                fig, ax = plt.subplots(figsize=(8, 5))
                draw_java_ratio_chart(ax, stats["all_merges"], stats["impact_set"])
                _save_fig(fig, results_dir, "03_java_ratio_impact", pdf)

        # ── Shared charts (RQ2 + RQ3) ────────────────────────────────────

        # Chart 4: Search space coverage
        fig, ax = plt.subplots(figsize=(9, 6))
        draw_search_space_coverage(ax, all_data)
        _save_fig(fig, results_dir, "04_search_space_coverage", pdf)

        # Chart 5: Variants completed per mode
        print("Drawing variant throughput chart ...")
        fig, ax = plt.subplots(figsize=(9, 6))
        draw_variants_completed(ax, all_data)
        _save_fig(fig, results_dir, "05_variant_throughput", pdf)

        # Chart 6: Cache warmup speedup ratio
        print("Drawing cache warmup speedup chart ...")
        fig, ax = plt.subplots(figsize=(9, 6))
        draw_cache_warmup_speedup(ax, all_data)
        _save_fig(fig, results_dir, "06_cache_warmup_speedup", pdf)

        # Chart 7: Mode rank evolution over time
        print("Drawing rank evolution chart ...")
        fig, ax = plt.subplots(figsize=(10, 5))
        draw_rank_evolution(ax, all_data)
        _save_fig(fig, results_dir, "07_rank_evolution", pdf)

        # Chart 8: Variant cost curve (variant# vs. build time)
        print("Drawing variant cost curve ...")
        fig, ax = plt.subplots(figsize=(10, 6))
        draw_variant_cost_curve(ax, all_data)
        _save_fig(fig, results_dir, "08_variant_cost_curve", pdf)

        # Chart 9a: Technique decomposition — variant throughput
        print("Drawing technique decomposition charts ...")
        fig, ax = plt.subplots(figsize=(8, 6))
        draw_interaction_plot(ax, all_data, _count_completed_variants,
                             "Variant Throughput",
                             "Completed variants per merge (median)")
        _save_fig(fig, results_dir, "09a_decomposition_throughput", pdf)

        # Chart 9b: Technique decomposition — time to best variant
        fig, ax = plt.subplots(figsize=(8, 6))
        draw_interaction_plot(ax, all_data, _time_to_best_variant,
                             "Time to Best Variant",
                             "Relative time (1.0 = baseline, median)")
        _save_fig(fig, results_dir, "09b_decomposition_time_to_best", pdf)

    # Write console summary to text file
    _write_console_summary(results_dir, all_data, stats, rq)

    print(f"Results written to: {results_dir}")
    print("Done.")


def _write_console_summary(results_dir: Path, all_data: dict, stats: dict,
                           rq: str = "rq2"):
    """Write a text summary of key statistics alongside the PDFs."""
    path = results_dir / "summary.txt"
    lines = []
    lines.append("=" * 72)
    lines.append(f"  {rq.upper()} Results Summary")
    lines.append("=" * 72)

    # Data overview
    lines.append(f"\nMerge counts per mode:")
    for mode in MODES:
        n = len(all_data.get(mode, {}))
        lines.append(f"  {MODE_LABELS.get(mode, mode):25s}: {n} merges")

    # Variant counts
    lines.append(f"\nVariant counts per mode (non-baseline modes):")
    for mode in VARIANT_MODES:
        total = sum(len(m.get("variants", [])) for m in all_data.get(mode, {}).values())
        lines.append(f"  {MODE_LABELS.get(mode, mode):25s}: {total} total variants")

    # Budget exhaustion
    lines.append(f"\nBudget exhaustion per mode:")
    for mode in VARIANT_MODES:
        merges = all_data.get(mode, {})
        n_total = len(merges)
        if n_total == 0:
            continue
        n_exhausted = sum(1 for m in merges.values() if m.get("budgetExhausted"))
        pct = 100 * n_exhausted / n_total
        lines.append(f"  {MODE_LABELS.get(mode, mode):25s}: "
                     f"{n_exhausted}/{n_total} ({pct:.0f}%)")

    # Impact
    if stats.get("impact_set"):
        n_all = len(stats["all_merges"])
        n_impact = len(stats["impact_set"])
        lines.append(f"\nImpact merges: {n_impact}/{n_all} ({100*n_impact/n_all:.0f}%)")

    # Paired statistical tests (throughput)
    groups_throughput = _collect_paired_metric(all_data, VARIANT_MODES, _count_completed_variants)
    if groups_throughput:
        lines.append(f"\n--- Variant Throughput (Friedman + pairwise Wilcoxon) ---")
        fp = _friedman_test(groups_throughput, VARIANT_MODES)
        if fp is not None:
            lines.append(f"  Friedman p={fp:.6f}")
        for a, b, p, d, lbl in _pairwise_wilcoxon(groups_throughput, VARIANT_MODES):
            sig = "***" if p < 0.001 else "**" if p < 0.01 else "*" if p < 0.05 else "ns"
            lines.append(f"  {MODE_LABELS[a]} vs {MODE_LABELS[b]}: "
                         f"p={p:.6f}{sig}, Cliff's d={d:+.3f} ({lbl})")

    # Rank ANCOVA (per-merge: throughput ~ cache + threads + modules + interactions)
    common = sorted(set.intersection(*(set(all_data.get(m, {}).keys()) for m in VARIANT_MODES)))
    tc = _collect_thread_counts(all_data, VARIANT_MODES, common)
    mc = _collect_module_counts(all_data, VARIANT_MODES, common)
    ancova = _ancova_cache_threads(groups_throughput, tc, mc)
    if ancova:
        med_t = ancova.pop("_median_threads", "?")
        med_m = ancova.pop("_median_modules", "?")
        lines.append(f"\n--- Rank ANCOVA (cache + threads + modules + interactions, "
                     f"median threads={med_t}, median modules={med_m}) on throughput ---")
        for term in ["cache", "threads", "modules", "cache:threads", "cache:modules"]:
            if term in ancova:
                e = ancova[term]
                lines.append(f"  {term}: t={e['t']:.3f}, p={e['p']:.6f}")
    else:
        lines.append(f"\n--- Rank ANCOVA (per-merge): skipped (covariates have no variance) ---")

    # Cross-mode sanity check
    lines.append(_cross_mode_sanity_check(all_data))

    lines.append("")
    path.write_text("\n".join(lines))
    print(f"  Summary written to: {path}")

    _export_latex_variables(all_data, stats, rq)


def _export_latex_variables(all_data: dict, stats: dict, rq: str):
    """Export key statistics to the shared LaTeX variables CSV."""
    prefix = "rqTwo" if rq == "rq2" else "rqThree" if rq == "rq3" else rq

    lvars = {}

    # Per-mode merge and variant counts
    for mode in MODES:
        short = MODE_SHORT.get(mode, mode)
        n = len(all_data.get(mode, {}))
        lvars[f"{prefix}{short}MergeCount"] = (str(n), f"Merges in {MODE_LABELS.get(mode, mode)}")

    for mode in VARIANT_MODES:
        short = MODE_SHORT.get(mode, mode)
        total = sum(len(m.get("variants", [])) for m in all_data.get(mode, {}).values())
        lvars[f"{prefix}{short}VariantCount"] = (str(total), f"Total variants in {MODE_LABELS.get(mode, mode)}")
        merges = all_data.get(mode, {})
        n_total = len(merges)
        if n_total > 0:
            n_exhausted = sum(1 for m in merges.values() if m.get("budgetExhausted"))
            pct = round(100 * n_exhausted / n_total)
            lvars[f"{prefix}{short}BudgetExhaustedPct"] = (
                str(pct), f"Budget exhaustion % in {MODE_LABELS.get(mode, mode)}")

    # Impact
    if stats.get("impact_set"):
        n_all = len(stats["all_merges"])
        n_impact = len(stats["impact_set"])
        lvars[f"{prefix}ImpactCount"] = (str(n_impact), "Impact merges")
        lvars[f"{prefix}TotalMerges"] = (str(n_all), "Total merges across modes")
        lvars[f"{prefix}ImpactPct"] = (str(round(100 * n_impact / n_all)), "Impact rate (%)")

    latex_variables.put_all(lvars)
    print(f"  LaTeX variables exported ({len(lvars)} entries)")


def _cross_mode_sanity_check(all_data: dict) -> str:
    """
    Verify that identical variants produce identical build outcomes across modes.
    Mirrors Java CrossModeSanityChecker: zero tolerance for compilation mismatches,
    1% threshold for test mismatches (flaky tests).
    """
    MAX_TEST_MISMATCH_RATE = 0.01
    mode_names = [m for m in VARIANT_MODES if all_data.get(m)]
    if len(mode_names) < 2:
        return "\n--- Cross-Mode Sanity Check: skipped (< 2 modes) ---"

    # Find all merge commits present in any mode
    all_commits = set()
    for mode in mode_names:
        all_commits.update(all_data[mode].keys())

    variants_compared = 0
    compilation_mismatches = 0
    test_mismatches = 0
    test_deviations = []
    # Per-merge breakdown: commit -> {"project", "comp_mismatches", "test_mismatches", "variants_compared"}
    per_merge_stats: dict[str, dict] = {}

    for commit in sorted(all_commits):
        # Collect modes that have this merge
        present = {}
        project_name = None
        for mode in mode_names:
            merge = all_data[mode].get(commit)
            if merge and merge.get("variants"):
                present[mode] = {v["variantIndex"]: v for v in merge["variants"]}
                if project_name is None:
                    project_name = merge.get("projectName", commit[:8])
        if len(present) < 2:
            continue

        mstats = {"project": project_name, "comp_mismatches": 0,
                  "test_mismatches": 0, "variants_compared": 0}

        # Collect all variant indices across modes
        all_indices = set()
        for vmap in present.values():
            all_indices.update(vmap.keys())

        for idx in sorted(all_indices):
            # Collect non-timed-out variants for this index
            comparable = []
            for mode in present:
                v = present[mode].get(idx)
                if v and not v.get("timedOut", False):
                    comparable.append(v)
            if len(comparable) < 2:
                continue

            variants_compared += 1
            mstats["variants_compared"] += 1
            ref = comparable[0]

            # Compilation status
            ref_status = (ref.get("compilationResult") or {}).get("buildStatus")
            comp_mismatch = False
            for other in comparable[1:]:
                other_status = (other.get("compilationResult") or {}).get("buildStatus")
                if ref_status != other_status:
                    comp_mismatch = True
            if comp_mismatch:
                compilation_mismatches += 1
                mstats["comp_mismatches"] += 1

            # Test counts
            ref_tr = ref.get("testResults") or {}
            test_mismatch = False
            worst_dev = 0.0
            for other in comparable[1:]:
                other_tr = other.get("testResults") or {}
                d_run  = abs(ref_tr.get("runNum", 0)      - other_tr.get("runNum", 0))
                d_fail = abs(ref_tr.get("failuresNum", 0) - other_tr.get("failuresNum", 0))
                d_err  = abs(ref_tr.get("errorsNum", 0)   - other_tr.get("errorsNum", 0))
                if d_run + d_fail + d_err > 0:
                    test_mismatch = True
                    max_tests = max(ref_tr.get("runNum", 0), other_tr.get("runNum", 0))
                    dev = (d_run + d_fail + d_err) / max_tests if max_tests > 0 else 1.0
                    worst_dev = max(worst_dev, dev)
            if test_mismatch:
                test_mismatches += 1
                test_deviations.append(worst_dev)
                mstats["test_mismatches"] += 1

        per_merge_stats[commit] = mstats

    test_rate = test_mismatches / variants_compared if variants_compared > 0 else 0.0
    max_dev = max(test_deviations) if test_deviations else 0.0
    med_dev = sorted(test_deviations)[len(test_deviations) // 2] if test_deviations else 0.0
    passed = compilation_mismatches == 0 and test_rate <= MAX_TEST_MISMATCH_RATE

    out = []
    out.append(f"\n--- Cross-Mode Sanity Check: {'PASSED' if passed else 'FAILED'} ---")
    out.append(f"  Modes compared:             {', '.join(MODE_LABELS[m] for m in mode_names)}")
    out.append(f"  Variant positions compared: {variants_compared}")
    if compilation_mismatches == 0:
        out.append(f"  Compilation outcomes:       OK (all consistent)")
    else:
        out.append(f"  FAIL — Compilation mismatches: {compilation_mismatches}/{variants_compared} "
                   f"({100.0 * compilation_mismatches / variants_compared:.1f}%)")
    if test_mismatches == 0:
        out.append(f"  Test counts:                OK (all consistent)")
    else:
        verdict = "OK (within threshold)" if test_rate <= MAX_TEST_MISMATCH_RATE else "FAIL"
        out.append(f"  Test count mismatches: {verdict} — {test_mismatches}/{variants_compared} "
                   f"({100.0 * test_rate:.2f}%, threshold {MAX_TEST_MISMATCH_RATE * 100:.0f}%)")
        out.append(f"  Deviation magnitude:        median={med_dev * 100:.1f}%, max={max_dev * 100:.1f}%")

    # Per-merge breakdown for merges with any mismatch
    flagged = {c: s for c, s in per_merge_stats.items()
               if s["comp_mismatches"] > 0 or s["test_mismatches"] > 0}
    if flagged:
        out.append(f"\n  Per-merge breakdown ({len(flagged)} merge(s) with mismatches):")
        for commit, s in sorted(flagged.items(), key=lambda x: x[1]["test_mismatches"], reverse=True):
            parts = []
            if s["comp_mismatches"] > 0:
                parts.append(f"comp={s['comp_mismatches']}")
            if s["test_mismatches"] > 0:
                parts.append(f"test={s['test_mismatches']}")
            out.append(f"    {s['project']}  {commit[:8]}  "
                       f"{' '.join(parts)}/{s['variants_compared']} variants compared")

    return "\n".join(out)


# ── Mockup data generation ─────────────────────────────────────────────────────

# Three merges with different project sizes / build times.
# human_tests / human_modules represent a good-but-not-perfect resolution.
_MOCKUP_MERGES = [
    # Small single-module project, fast build
    dict(commit="aa"*20, human_secs=45,  total_tests=90,  total_modules=1,
         human_tests=88,  human_modules=1),
    # Medium multi-module project
    dict(commit="bb"*20, human_secs=120, total_tests=200, total_modules=15,
         human_tests=150, human_modules=12),
    # Large multi-module project, slow build
    dict(commit="cc"*20, human_secs=280, total_tests=400, total_modules=10,
         human_tests=320, human_modules=8),
]

_MOCKUP_VARIANT_COUNTS = {"cache_parallel": 100, "cache_sequential": 15, "parallel": 20, "no_optimization": 6}


def _mockup_module_results(passed: int, total: int, rng) -> list:
    return [{"moduleName": f"module-{i}",
             "status": "SUCCESS" if i < passed else "FAILURE",
             "timeElapsed": round(0.3 + rng.random() * 0.4, 2)}
            for i in range(total)]


def _mockup_variant(index, tests_passed, modules_passed, finish_secs,
                    total_tests, total_modules, rng) -> dict:
    return {
        "variantIndex": index,
        "compilationResult": {
            "moduleResults": _mockup_module_results(modules_passed, total_modules, rng),
            "buildStatus":   "SUCCESS" if modules_passed > 0 else "FAILURE",
            "totalTime":     round(finish_secs, 2),
        },
        "testResults": {
            "runNum":      total_tests,
            "failuresNum": total_tests - tests_passed,
            "errorsNum":   0, "skippedNum": 0,
            "elapsedTime": round(finish_secs * 0.4, 2),
        },
        "totalTimeSinceMergeStartSeconds": round(finish_secs, 2),
    }


def _plausible_results(total_tests, total_modules, human_tests, human_modules, rng):
    """
    Returns (tests_passed, modules_passed) for a variant.
    ~35 % of variants fail to compile (0, 0).
    The rest cluster around the human-resolution level with Gaussian variance,
    so occasionally a variant beats the human and occasionally it does much worse.
    """
    if rng.random() < 0.35:           # compile failure
        return 0, 0
    mf = min(1.0, max(0.0, rng.gauss(human_modules / total_modules, 0.25)))
    mp = round(mf * total_modules)
    if mp == 0:
        return 0, 0
    tf = min(1.0, max(0.0, rng.gauss(human_tests / total_tests, 0.20)))
    return round(tf * total_tests), mp


def _plausible_finish(mode, human_secs, cumulative, rng):
    """
    Returns (finish_time_for_this_variant, updated_cumulative).

    no_optimization  – sequential: each variant adds to a running total,
                       so finish times grow linearly.
    parallel         – each variant's own build time (they all start at t=0);
                       variance ≈ ±12 % of the human baseline.
    cache_parallel   – like parallel but ~45 % faster on average due to
                       Maven's local-repository cache being warm.
    """
    if mode == "cache_parallel":
        t = max(5.0, rng.gauss(human_secs * 0.55, human_secs * 0.08))
        return round(t, 2), cumulative
    elif mode == "parallel":
        t = max(5.0, rng.gauss(human_secs, human_secs * 0.12))
        return round(t, 2), cumulative
    else:   # no_optimization
        t = max(5.0, rng.gauss(human_secs, human_secs * 0.12))
        cumulative += t
        return round(cumulative, 2), cumulative


def generate_mockup(output_dir: Path, output_pdf: Path):
    """
    Generate mock JSON data (3 merges × 4 modes) and produce the PDF.
    The single-merge scenario from test_plot_results.py is preserved as the
    first merge; two additional merges with different sizes are added.
    """
    import json, random
    rng = random.Random(42)
    PROJECT = "mock-project"

    # mode -> list of per-merge dicts (all merges land in the same JSON file)
    mode_merges: dict = {m: [] for m in ["human_baseline"] + list(_MOCKUP_VARIANT_COUNTS)}

    for sc in _MOCKUP_MERGES:
        commit        = sc["commit"]
        human_secs    = sc["human_secs"]
        total_tests   = sc["total_tests"]
        total_modules = sc["total_modules"]
        human_tests   = sc["human_tests"]
        human_modules = sc["human_modules"]

        num_conflict_files      = rng.randint(1, 6)
        num_java_conflict_files = rng.randint(0, num_conflict_files)
        num_conflict_chunks     = rng.randint(1, 8)

        def merge_dict(mode_name, exec_secs, variants):
            return {
                "processed":             True,
                "mode":                  mode_name,
                "mergeCommit":           commit,
                "projectName":           PROJECT,
                "budgetBasisSeconds":    human_secs,
                "totalExecutionTime":    human_secs + exec_secs,
                "numConflictFiles":      num_conflict_files,
                "numJavaConflictFiles":  num_java_conflict_files,
                "numConflictChunks":     num_conflict_chunks,
                "variants":              variants,
            }

        # human_baseline: one perfect-resolution variant per merge
        hb = _mockup_variant(0, human_tests, human_modules,
                             human_secs, total_tests, total_modules, rng)
        mode_merges["human_baseline"].append(merge_dict("human_baseline", human_secs, [hb]))

        # variant modes
        for mode, count in _MOCKUP_VARIANT_COUNTS.items():
            variants, cum = [], 0.0
            for i in range(count):
                tp, mp   = _plausible_results(total_tests, total_modules,
                                               human_tests, human_modules, rng)
                finish, cum = _plausible_finish(mode, human_secs, cum, rng)
                variants.append(_mockup_variant(i + 1, tp, mp, finish,
                                                total_tests, total_modules, rng))
            exec_secs = round(max(v["totalTimeSinceMergeStartSeconds"]
                                  for v in variants))
            mode_merges[mode].append(merge_dict(mode, exec_secs, variants))

    # Write one JSON file per merge per mode
    for mode, merges in mode_merges.items():
        d = output_dir / mode
        d.mkdir(parents=True, exist_ok=True)
        for merge in merges:
            commit = merge["mergeCommit"]
            (d / f"{commit}.json").write_text(json.dumps(merge, indent=2))

    make_plots(output_dir, output_pdf)


# ── Entry point ────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--mockup":
        _out_pdf = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("mockup_plots.pdf")
        import tempfile, shutil
        _tmp = tempfile.mkdtemp(prefix="plot_mockup_")
        try:
            generate_mockup(Path(_tmp), _out_pdf)
            print(f"PDF written to: {_out_pdf.resolve()}")
        finally:
            shutil.rmtree(_tmp)
    else:
        # Parse optional --rq flag from any position
        args = [a for a in sys.argv[1:] if not a.startswith("--")]
        rq = "auto"
        for a in sys.argv[1:]:
            if a in ("--rq2", "--rq3"):
                rq = a[2:]
        variant_dir = Path(args[0]) if len(args) > 0 else DEFAULT_VARIANT_DIR
        output_pdf  = Path(args[1]) if len(args) > 1 else DEFAULT_OUTPUT_PDF
        make_plots(variant_dir, output_pdf, rq=rq)
