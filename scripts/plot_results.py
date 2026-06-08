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

import numpy as np

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

# Variant runtime budget is max(10 * baseline, 300s) = 10 * max(baseline, 30s).
# Normalise relative time against this effective baseline (= budget / 10) so
# floor-budget projects don't drift past 10x. No additional clipping needed.
MIN_EFFECTIVE_BASELINE_SECS = 30.0
RELATIVE_TIME_CAP            = 10.0  # right edge of the chart (= budget / eff_hb)


def effective_baseline_secs(hb_secs: float) -> float:
    return max(hb_secs, MIN_EFFECTIVE_BASELINE_SECS)

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


def baseline_score_of(baseline_merge: dict) -> tuple | None:
    """
    Score the human-baseline build of a merge. The baseline lives in its own
    human_baseline/<commit>.json file as the single variant with variantIndex==0.
    """
    for v in baseline_merge.get("variants", []):
        if v.get("variantIndex") == 0:
            return variant_score(v)
    return None


def variant_timed_out(variant: dict) -> bool:
    """True if the variant didn't finish — either the runner killed it (variant.timedOut)
    or Maven recorded a TIMEOUT buildStatus."""
    if variant.get("timedOut"):
        return True
    cr = variant.get("compilationResult")
    return cr is not None and cr.get("buildStatus") == "TIMEOUT"


def is_impact_merge(merge: dict, baseline_score: tuple | None) -> bool:
    """
    A merge is 'impact' if at least one explored variant produced a different
    outcome than the human baseline. Comparison rules (only applied when the
    baseline itself is scoreable):
      - timed-out variants are skipped (no opinion — we don't know what they'd do)
      - non-timeout variants that still couldn't be scored (e.g. buildStatus == null)
        count as DIFFERENT — a scoreable baseline that produced no status under a
        different resolution is itself a regression
      - otherwise compare the (modulesPassed, testsPassed) tuple
    Mirrors Java filterImpactMerges modulo the null-compilation rule.
    """
    if merge.get("numConflictChunks", 0) == 0:
        return False
    if baseline_score is None:
        return False
    for v in merge.get("variants", []):
        if v.get("variantIndex") == 0:
            continue  # defensive — exploration variants start at index 1
        if variant_timed_out(v):
            continue
        score = variant_score(v)
        if score is None:
            return True  # didn't time out but produced no scoreable result → different
        if score != baseline_score:
            return True
    return False


def compute_statistics(all_data: dict) -> dict:
    """
    Compute cross-mode statistics (impact set, Java ratio data).
    Pulls the per-merge baseline from human_baseline/<commit>.json and compares
    it against variants from the first available exploration mode.
    Preference order: cache_parallel > parallel > no_optimization > cache_sequential.
    cache_parallel explores the largest variant set per merge so it gives the
    tightest bound on whether resolution choice is impactful.
    Returns a dict with keys: all_merges (list), impact_set (set of mergeCommit strings).
    """
    source_mode = next(
        (m for m in ["cache_parallel", "parallel", "no_optimization", "cache_sequential"]
         if all_data.get(m)),
        None,
    )
    if source_mode is None:
        return {"all_merges": [], "impact_set": set()}
    all_merges = list(all_data[source_mode].values())
    baselines = all_data.get("human_baseline", {})
    impact_set = set()
    for m in all_merges:
        commit = m["mergeCommit"]
        bscore = baseline_score_of(baselines[commit]) if commit in baselines else None
        if is_impact_merge(m, bscore):
            impact_set.add(commit)
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
                        hb_num: float, smooth: bool = False) -> list[tuple[float, float]]:
    """
    For a list of variant dicts, returns (relativeTime, relativeScore) markers
    where relativeTime  = totalTimeSinceMergeStartSeconds / budgetBasisSeconds
          relativeScore = metric_fn(v)[0] / hb_num  (1.0 = human baseline quality)

    If smooth=True, applies Laplace smoothing: rate = (num+1)/(hb_num+1).  This
    keeps the chart usable for merges whose human baseline scored 0 on this
    metric (e.g. projects with no tests) — the smoothed baseline still lands at
    1.0 by construction.
    Only variants that strictly improve on all previous variants are kept.
    metric_fn(variant) -> (numerator, denominator)
    """
    if human_baseline_secs <= 0:
        return []
    if not smooth and hb_num <= 0:
        return []

    eff_hb = effective_baseline_secs(human_baseline_secs)
    timed = [(get_finish_time(v), metric_fn(v)[0]) for v in variants]
    timed.sort(key=lambda x: x[0])

    markers = []
    best_rate = -1.0
    for finish, num in timed:
        rate = ((num + 1) / (hb_num + 1)) if smooth else (num / hb_num)
        if rate > best_rate:
            best_rate = rate
            markers.append((finish / eff_hb, rate))
    return markers


# ── Plot data assembly ─────────────────────────────────────────────────────────

def assemble_plot_data(all_data: dict, metric_fn, smooth: bool = False):
    """
    Returns dict: mode -> list of (relativeTime, relativeScore) improvement markers,
    where relativeScore is normalised so that 1.0 = human baseline score for that merge.
    Also returns dict: mode -> list of step-function series (each a list of (x,y) for one merge).

    If smooth=True, the metric uses Laplace smoothing — required when many
    merges have a baseline score of 0 on this metric (e.g. projects with no
    tests).  The baseline still lands at 1.0 by construction.
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
        if not smooth and hb_num <= 0:
            continue  # can't normalise against zero (smoothing handles it)

        for mode in MODES:
            if mode == "human_baseline":
                continue  # baseline is the normaliser — added once after the loop
            merge = all_data[mode].get(commit)
            if not merge:
                continue
            variants = merge.get("variants") or []
            if not variants:
                continue

            markers = improvement_markers(variants, metric_fn, hb_secs, hb_num,
                                          smooth=smooth)
            if markers:
                mode_markers[mode].extend(markers)
                mode_steps[mode].append([(0.0, 0.0)] + markers)

    # The human baseline is the normalisation reference, so it sits at exactly
    # (1.0, 1.0) by definition — a single implicit marker, not one scattered
    # per-merge point (whose x drifts below 1.0 for sub-30s baselines).
    if all_data.get("human_baseline"):
        mode_markers["human_baseline"] = [(1.0, 1.0)]

    return mode_markers, mode_steps


# ── Combined quality (modules × tests, Laplace-smoothed) ──────────────────────

def combined_quality(variant: dict, hb_modules: int, hb_tests: int) -> float:
    """Smoothed combined-quality score for a variant, relative to the human baseline.

    Y = (modules_passed + 1)/(hb_modules + 1) * (tests_passed + 1)/(hb_tests + 1)

    The +1 Laplace smoothing keeps the modules signal alive when tests_passed = 0
    (and vice versa) so a single zero factor doesn't annihilate the product.
    By construction the human baseline scores exactly 1.0.

    Mirrors chart 01: TIMEOUT variants with partial module/test successes still
    contribute their partial counts (module_stats / test_stats already handle
    that).  Missing compilationResult collapses to (0, 0).
    """
    m_pass, _ = module_stats(variant)
    t_pass, _ = test_stats(variant)
    return ((m_pass + 1) / (hb_modules + 1)) * ((t_pass + 1) / (hb_tests + 1))


def _per_merge_quality(all_data: dict, mode: str) -> dict:
    """Per-merge build quality comparing the human baseline to the *best*
    variant of ``mode`` (best = highest combined quality). Returns aligned
    lists: human/best module counts, human/best test counts, and the best
    variant's combined quality (1.0 = matches the human baseline)."""
    hb_data = all_data.get("human_baseline", {})
    mode_data = all_data.get(mode, {})
    out = {"hb_modules": [], "hb_tests": [],
           "best_modules": [], "best_tests": [], "best_combined": []}
    for commit, hb_merge in hb_data.items():
        merge = mode_data.get(commit)
        if merge is None:
            continue
        hb_v = next((v for v in hb_merge.get("variants", [])
                     if v.get("variantIndex") == 0), None)
        if hb_v is None:
            continue
        hb_m, _ = module_stats(hb_v)
        hb_t, _ = test_stats(hb_v)
        best, best_q = None, -1.0
        for v in merge.get("variants", []):
            if v.get("variantIndex", 0) == 0 or v.get("timedOut"):
                continue
            q = combined_quality(v, hb_m, hb_t)
            if q > best_q:
                best, best_q = v, q
        if best is None:
            continue
        bm, _ = module_stats(best)
        bt, _ = test_stats(best)
        out["hb_modules"].append(hb_m)
        out["hb_tests"].append(hb_t)
        out["best_modules"].append(bm)
        out["best_tests"].append(bt)
        out["best_combined"].append(best_q)
    return out


def combined_improvement_markers(variants: list, hb_secs: float,
                                 hb_modules: int, hb_tests: int) -> list[tuple[float, float]]:
    """(relativeTime, combinedQuality) Pareto frontier for one merge's variants."""
    if hb_secs <= 0:
        return []
    eff_hb = effective_baseline_secs(hb_secs)
    timed = [(get_finish_time(v), combined_quality(v, hb_modules, hb_tests))
             for v in variants]
    timed.sort(key=lambda x: x[0])
    markers = []
    best = -1.0
    for finish, score in timed:
        if score > best:
            best = score
            markers.append((finish / eff_hb, score))
    return markers


def assemble_combined_plot_data(all_data: dict):
    """Mirror of assemble_plot_data for the combined modules*tests quality.

    Filter mirrors chart 01: include any merge whose baseline compiled at least
    one module. Laplace smoothing handles the hb_tests = 0 edge naturally.
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

        hb_variants = baseline_merge.get("variants") or []
        hb_variant = next((v for v in hb_variants if v.get("variantIndex") == 0), None)
        if hb_variant is None:
            continue
        hb_modules, _ = module_stats(hb_variant)
        hb_tests, _   = test_stats(hb_variant)
        if hb_modules <= 0:
            continue

        for mode in MODES:
            if mode == "human_baseline":
                continue  # baseline is the normaliser — added once after the loop
            merge = all_data[mode].get(commit)
            if not merge:
                continue
            variants = merge.get("variants") or []
            if not variants:
                continue

            markers = combined_improvement_markers(variants, hb_secs, hb_modules, hb_tests)
            if markers:
                mode_markers[mode].extend(markers)
                mode_steps[mode].append([(0.0, 0.0)] + markers)

    # Human baseline is the normalisation reference: a single (1.0, 1.0) marker.
    if all_data.get("human_baseline"):
        mode_markers["human_baseline"] = [(1.0, 1.0)]

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
    ax.set_xlim(0, RELATIVE_TIME_CAP + 0.2)
    ax.set_ylim(bottom=0)
    ax.legend(loc="lower right", fontsize=8)
    ax.grid(True, alpha=0.3)


def draw_java_ratio_chart(ax, all_merges: list, impact_set: set):
    """
    Bar chart: impact rate by Java-conflict-file category.
    Three categories — chosen because real merges cluster bimodally at the
    extremes (ratio==0 or ratio==1):
      - "No Java conflicts" (ratio == 0): only XML/resources/build files conflict
      - "Mixed"             (0 < ratio < 1)
      - "All Java"          (ratio == 1)
    """
    labels  = ["No Java conflicts\n(ratio = 0)",
               r"Mixed" "\n" r"($0 < \mathrm{ratio} < 1$)",
               "All Java\n(ratio = 1)"]
    totals  = [0, 0, 0]
    impacts = [0, 0, 0]

    for merge in all_merges:
        total_files = merge.get("numConflictFiles", 0)
        if total_files == 0:
            continue
        java_files = merge.get("numJavaConflictFiles", 0)
        if java_files == 0:
            bucket = 0
        elif java_files == total_files:
            bucket = 2
        else:
            bucket = 1
        totals[bucket] += 1
        if merge.get("mergeCommit") in impact_set:
            impacts[bucket] += 1

    impact_rates = [impacts[i] / totals[i] if totals[i] > 0 else 0.0 for i in range(3)]

    x    = list(range(3))
    bars = ax.bar(x, impact_rates, color="#2c7bb6", edgecolor="white", linewidth=0.5,
                  zorder=3)

    for bar, imp, tot in zip(bars, impacts, totals):
        label = rf"${imp}/{tot}$" if USE_LATEX else f"{imp}/{tot}"
        ax.text(bar.get_x() + bar.get_width() / 2,
                bar.get_height() + 0.02,
                label, ha="center", va="bottom", fontsize=9)

    ax.set_xticks(x)
    ax.set_xticklabels(labels)
    ax.set_xlabel(r"Java conflict file ratio"
                  r" $\left(\frac{\mathrm{numJavaConflictFiles}}{\mathrm{numConflictFiles}}\right)$")
    ax.set_ylabel(r"Impact rate (fraction of merges)")
    ax.set_title(r"Java Conflict File Ratio vs.\ Impact Rate")
    ax.set_ylim(0, 1.15)
    ax.margins(x=0.15)
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
        ax.scatter(xs, vals, color="#222222", s=8, alpha=0.50,
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


# ── Smoothing helpers ─────────────────────────────────────────────────────────


def _lowess(xs: list[float], ys: list[float], frac: float = 0.3) -> list[float]:
    """
    LOWESS (locally weighted scatterplot smoothing).
    For each point, fits a weighted linear regression using a tri-cube
    kernel over the nearest `frac` fraction of points.
    Returns smoothed y-values at the same x positions.
    """
    import numpy as np
    xs = np.asarray(xs, dtype=float)
    ys = np.asarray(ys, dtype=float)
    n = len(xs)
    k = max(2, int(np.ceil(frac * n)))
    smoothed = np.empty(n)
    for i in range(n):
        dists = np.abs(xs - xs[i])
        idx = np.argsort(dists)[:k]
        max_dist = dists[idx[-1]]
        if max_dist == 0:
            smoothed[i] = np.median(ys[idx])
            continue
        u = dists[idx] / max_dist
        w = (1 - u ** 3) ** 3  # tri-cube kernel
        # Weighted linear regression: y = a + b*x
        xw = xs[idx]
        yw = ys[idx]
        sw = w.sum()
        mx = (w * xw).sum() / sw
        my = (w * yw).sum() / sw
        cov_xy = (w * (xw - mx) * (yw - my)).sum()
        var_x = (w * (xw - mx) ** 2).sum()
        if var_x > 0:
            b = cov_xy / var_x
            a = my - b * mx
            smoothed[i] = a + b * xs[i]
        else:
            smoothed[i] = my
    return smoothed.tolist()



# ── RQ2 mode comparison: statistical helpers ─────────────────────────────────

VARIANT_MODES = [m for m in MODES if m != "human_baseline"]


def _present_variant_modes(all_data: dict) -> list[str]:
    """Variant modes that actually have data. RQ3 runs a single best mode, so
    paired cross-mode charts must restrict to the present modes — otherwise the
    paired intersection across absent modes is empty and the boxplot positions
    desync. Genuinely multi-mode charts (cache speedup, 2x2 factorial) should
    additionally be skipped when fewer than two modes are present."""
    return [m for m in VARIANT_MODES if all_data.get(m)]


def _note_requires_modes(ax, all_data: dict, *, need: set | None = None) -> bool:
    """For charts that compare execution modes: render an explanatory note and
    return False when the required modes are absent (e.g. a single-mode RQ3
    run). ``need`` is a specific set of required modes; the default requires at
    least two present variant modes."""
    present = set(_present_variant_modes(all_data))
    ok = need.issubset(present) if need else len(present) >= 2
    if not ok:
        ax.text(0.5, 0.5,
                "Requires multiple execution modes\n(not applicable to a single-mode run)",
                transform=ax.transAxes, ha="center", va="center")
        ax.set_xticks([])
        ax.set_yticks([])
    return ok


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
            bbox=dict(boxstyle="round,pad=0.3", facecolor="wheat", alpha=1.0,
                      edgecolor="tan", linewidth=0.5))


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
    """Count non-baseline, non-timed-out variants.  Returns 0 when all timed out."""
    variants = merge.get("variants", [])
    if not variants:
        return None
    count = sum(1 for v in variants
                if v.get("variantIndex", 0) != 0 and not v.get("timedOut", False))
    return float(count)


def _variant_built_module(v: dict) -> bool:
    """True if the variant compiled at least one module successfully.
    Falls back to buildStatus=SUCCESS for single-module projects whose
    Reactor Summary has totalModules=0.
    """
    cr = v.get("compilationResult") or {}
    if (cr.get("successfulModules") or 0) >= 1:
        return True
    return cr.get("totalModules", 0) == 0 and cr.get("buildStatus") == "SUCCESS"


def _variant_ran_test(v: dict) -> bool:
    """True if the variant's surefire reports record at least one executed test."""
    tt = v.get("testResults") or {}
    return (tt.get("runNum") or 0) >= 1


def _count_variants_built_module(merge: dict) -> float | None:
    """Count non-baseline, non-timed-out variants that built ≥ 1 module."""
    variants = merge.get("variants", [])
    if not variants:
        return None
    count = sum(1 for v in variants
                if v.get("variantIndex", 0) != 0
                and not v.get("timedOut", False)
                and _variant_built_module(v))
    return float(count)


def _count_variants_ran_test(merge: dict) -> float | None:
    """Count non-baseline, non-timed-out variants that ran ≥ 1 test."""
    variants = merge.get("variants", [])
    if not variants:
        return None
    count = sum(1 for v in variants
                if v.get("variantIndex", 0) != 0
                and not v.get("timedOut", False)
                and _variant_ran_test(v))
    return float(count)


def _draw_variants_count_boxplot(ax, all_data: dict, metric, *,
                                 ylabel: str, title: str,
                                 valid_commits: list[str] | None = None):
    """Shared boxplot body for charts 05 / 05c / 05d. ``metric`` is the per-merge
    counting function (variants total / built $\geq$1 module / ran $\geq$1 test)."""
    groups = _collect_paired_metric(all_data, _present_variant_modes(all_data),
                                    metric, valid_commits)
    if not any(groups.values()):
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

    ax.set_yscale("symlog", linthresh=1)
    ax.set_xticks(positions)
    ax.set_xticklabels([f"{MODE_LABELS[m]} ({MODE_SHORT[m]})\nn={len(groups[m])}"
                        for m in plot_modes])
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.grid(True, axis="y", alpha=0.3, which="both")

    friedman_p = _friedman_test(groups, plot_modes)
    pairwise = _pairwise_wilcoxon(groups, plot_modes)
    summary = _significance_summary(friedman_p, pairwise)
    if summary:
        _annotate_significance(ax, summary)


def draw_variants_completed(ax, all_data: dict, valid_commits: list[str] | None = None):
    """Box plot (log scale): number of completed variants per mode."""
    multi = len(_present_variant_modes(all_data)) >= 2
    _draw_variants_count_boxplot(
        ax, all_data, _count_completed_variants,
        ylabel="Completed variants per merge (log scale)",
        title="Variant Throughput by Execution Mode" if multi else "Variant Throughput",
        valid_commits=valid_commits,
    )


def draw_variants_built_module(ax, all_data: dict, valid_commits: list[str] | None = None):
    """05c: Variants that compiled at least one module."""
    _draw_variants_count_boxplot(
        ax, all_data, _count_variants_built_module,
        ylabel="Variants that built $\geq$1 module per merge (log scale)",
        title="Variant Throughput ($\geq$1 module built)",
        valid_commits=valid_commits,
    )


def draw_variants_ran_test(ax, all_data: dict, valid_commits: list[str] | None = None):
    """05d: Variants that executed at least one test."""
    _draw_variants_count_boxplot(
        ax, all_data, _count_variants_ran_test,
        ylabel="Variants that ran $\geq$1 test per merge (log scale)",
        title="Variant Throughput ($\geq$1 test executed)",
        valid_commits=valid_commits,
    )


# ── 05e: Per-merge time-to-best, normalized by best mode ─────────────────────

def _time_to_best_seconds(merge: dict) -> float | None:
    """Raw seconds: time at which this mode's best-scoring variant finished
    (variantScore ties broken by earliest finish). None when the mode has no
    scoreable, non-timed-out, non-baseline variant for this merge."""
    best_score = None
    best_time = None
    for v in merge.get("variants", []):
        if v.get("variantIndex", 0) == 0 or v.get("timedOut", False):
            continue
        score = variant_score(v)
        if score is None:
            continue
        t = v.get("totalTimeSinceMergeStartSeconds")
        if t is None or t <= 0:
            continue
        if best_score is None or score > best_score or (score == best_score and t < best_time):
            best_score, best_time = score, t
    return best_time


def draw_time_to_best_normalized(ax, all_data: dict, valid_commits: list[str] | None = None,
                                 sentinel: float = 10.0):
    """05e: For each merge, divide each mode's time-to-best by the merge's
    fastest mode time. The fastest mode plots at 1.0; slower modes plot at
    ratio>1. Modes with no scoreable variant for that merge plot at ``sentinel``
    (default 10.0) so "not found" sits in the same visual space as the data,
    above a gray dashed reference line. A second-row x-tick label shows the
    "not found" count per mode."""
    # Normalising by the fastest mode is meaningless with a single mode (every
    # value collapses to 1.0), so skip for single-mode runs.
    if not _note_requires_modes(ax, all_data):
        ax.set_title("Per-merge Time-to-Best Across Modes")
        return

    if valid_commits is None:
        valid_commits = sorted(
            set.intersection(*(set(all_data.get(m, {}).keys()) for m in VARIANT_MODES))
        )

    groups = {m: [] for m in VARIANT_MODES}
    sentinel_groups = {m: [] for m in VARIANT_MODES}
    missing = {m: 0 for m in VARIANT_MODES}
    seen_commits = {m: 0 for m in VARIANT_MODES}

    for commit in valid_commits:
        per_mode = {}
        for m in VARIANT_MODES:
            data = all_data.get(m, {}).get(commit)
            if data is None:
                continue
            seen_commits[m] += 1
            per_mode[m] = _time_to_best_seconds(data)

        valid_times = [t for t in per_mode.values() if t is not None]
        if not valid_times:
            continue  # No mode found a best — entire merge is unrankable; skip.
        best_t = min(valid_times)

        for m in VARIANT_MODES:
            t = per_mode.get(m)
            if t is None:
                # Mode failed to find any scoreable variant for this merge.
                sentinel_groups[m].append(sentinel)
                missing[m] += 1
            else:
                groups[m].append(t / best_t)

    plot_modes = [m for m in VARIANT_MODES if (groups.get(m) or sentinel_groups.get(m))]
    if not plot_modes:
        ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")
        return

    positions = list(range(len(plot_modes)))

    # Box plot of valid ratios only — sentinels would distort the box statistics.
    valid_data = [groups[m] for m in plot_modes]
    box_positions = [p for p, d in zip(positions, valid_data) if d]
    box_data = [d for d in valid_data if d]
    if box_data:
        bp = ax.boxplot(box_data, positions=box_positions, widths=0.5,
                        patch_artist=True, showfliers=True,
                        flierprops=dict(markersize=3, alpha=0.5))
        for patch, m in zip(bp["boxes"],
                            [pm for pm, d in zip(plot_modes, valid_data) if d]):
            patch.set_facecolor(MODE_COLORS[m])
            patch.set_alpha(0.7)

    # Sentinel layer: scatter "not found" markers at y=sentinel with mild jitter.
    import numpy as _np
    rng = _np.random.default_rng(0)
    for p, m in zip(positions, plot_modes):
        n = len(sentinel_groups[m])
        if n == 0:
            continue
        xs = p + rng.uniform(-0.18, 0.18, n)
        ax.scatter(xs, [sentinel] * n, marker="x", s=28,
                   color=MODE_COLORS[m], alpha=0.8, linewidths=1.2,
                   zorder=3)

    ax.axhline(1.0, linestyle=":", color="black", alpha=0.4, lw=1)
    ax.axhline(sentinel, linestyle="--", color="gray", alpha=0.5, lw=1)
    ax.text(len(plot_modes) - 0.5, sentinel * 1.05,
            f"not found (sentinel = {sentinel:g})",
            fontsize=8, color="gray", ha="right", va="bottom")

    ax.set_yscale("log")
    ax.set_ylim(0.8, sentinel * 1.6)
    ax.set_xticks(positions)
    ax.set_xticklabels([
        f"{MODE_LABELS[m]} ({MODE_SHORT[m]})\nn={len(groups[m])}, missing={missing[m]}"
        for m in plot_modes])
    ax.set_ylabel("Time-to-best, normalized by fastest mode (1.0 = best per merge)")
    ax.set_title("Per-merge Time-to-Best Across Modes")
    ax.grid(True, axis="y", alpha=0.3, which="both")

    # Stats only on the valid-ratio subset (sentinel is a censoring marker, not data).
    valid_groups = {m: groups[m] for m in plot_modes if groups[m]}
    if len(valid_groups) >= 2:
        friedman_p = _friedman_test(valid_groups, list(valid_groups.keys()))
        pairwise = _pairwise_wilcoxon(valid_groups, list(valid_groups.keys()))
        summary = _significance_summary(friedman_p, pairwise)
        if summary:
            _annotate_significance(ax, summary)


def draw_time_to_best_in_baseline_units(ax, all_data: dict,
                                        valid_commits: list[str] | None = None,
                                        sentinel: float = 11.0):
    """05f: For each mode, time-to-best as a multiple of the merge's human-baseline
    build duration (the same X-axis basis as chart 7's "Variant Accumulation Over
    Time"). 1.0 = took as long as the human baseline. Modes that found no
    scoreable variant for that merge plot at ``sentinel`` (default 11.0, i.e. one
    tick past the typical 10× variant-budget ceiling) above a gray dashed line
    marked "not found"."""
    if valid_commits is None:
        valid_commits = sorted(
            set.intersection(*(set(all_data.get(m, {}).keys()) for m in VARIANT_MODES))
        )

    groups = {m: [] for m in VARIANT_MODES}
    sentinel_groups = {m: [] for m in VARIANT_MODES}
    missing = {m: 0 for m in VARIANT_MODES}

    for commit in valid_commits:
        # budgetBasisSeconds is the human-baseline build duration (module-normalised
        # for broken merges). Read it from any mode's JSON — it's an input to the
        # variant phase and identical across modes for the same commit.
        budget = None
        for m in VARIANT_MODES:
            data = all_data.get(m, {}).get(commit)
            if data is None:
                continue
            b = data.get("budgetBasisSeconds", 0)
            if b and b > 0:
                budget = b
                break
        if budget is None:
            continue  # No valid baseline duration — can't normalise this merge.

        for m in VARIANT_MODES:
            data = all_data.get(m, {}).get(commit)
            if data is None:
                continue
            t = _time_to_best_seconds(data)
            if t is None:
                sentinel_groups[m].append(sentinel)
                missing[m] += 1
            else:
                groups[m].append(t / budget)

    plot_modes = [m for m in VARIANT_MODES if (groups.get(m) or sentinel_groups.get(m))]
    if not plot_modes:
        ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")
        return

    positions = list(range(len(plot_modes)))

    valid_data = [groups[m] for m in plot_modes]
    box_positions = [p for p, d in zip(positions, valid_data) if d]
    box_data = [d for d in valid_data if d]
    if box_data:
        bp = ax.boxplot(box_data, positions=box_positions, widths=0.5,
                        patch_artist=True, showfliers=True,
                        flierprops=dict(markersize=3, alpha=0.5))
        for patch, m in zip(bp["boxes"],
                            [pm for pm, d in zip(plot_modes, valid_data) if d]):
            patch.set_facecolor(MODE_COLORS[m])
            patch.set_alpha(0.7)

    import numpy as _np
    rng = _np.random.default_rng(0)
    for p, m in zip(positions, plot_modes):
        n = len(sentinel_groups[m])
        if n == 0:
            continue
        xs = p + rng.uniform(-0.18, 0.18, n)
        ax.scatter(xs, [sentinel] * n, marker="x", s=28,
                   color=MODE_COLORS[m], alpha=0.8, linewidths=1.2,
                   zorder=3)

    ax.axhline(1.0, linestyle=":", color="black", alpha=0.4, lw=1)
    ax.axhline(sentinel, linestyle="--", color="gray", alpha=0.5, lw=1)
    ax.text(len(plot_modes) - 0.5, sentinel * 1.04,
            f"not found (sentinel = {sentinel:g})",
            fontsize=8, color="gray", ha="right", va="bottom")

    ax.set_yscale("log")
    ax.set_ylim(0.05, sentinel * 1.5)
    ax.set_xticks(positions)
    ax.set_xticklabels([
        f"{MODE_LABELS[m]} ({MODE_SHORT[m]})\nn={len(groups[m])}, missing={missing[m]}"
        for m in plot_modes])
    ax.set_ylabel("Time-to-best (multiples of human-baseline build duration)")
    ax.set_title("Per-merge Time-to-Best in Human-Baseline Units")
    ax.grid(True, axis="y", alpha=0.3, which="both")

    valid_groups = {m: groups[m] for m in plot_modes if groups[m]}
    if len(valid_groups) >= 2:
        friedman_p = _friedman_test(valid_groups, list(valid_groups.keys()))
        pairwise = _pairwise_wilcoxon(valid_groups, list(valid_groups.keys()))
        summary = _significance_summary(friedman_p, pairwise)
        if summary:
            _annotate_significance(ax, summary)


# ── RQ2 Chart: Variants per second (throughput rate) ─────────────────────────

def _variants_per_second(merge: dict) -> float | None:
    """Throughput: completed (non-baseline, non-timed-out) variants
    divided by the wall-clock time spent in the variant phase.
    A mode that produced 0 finished variants in a positive-length variant
    phase is a valid 0.0-throughput data point — keep it, don't skip."""
    elapsed = merge.get("variantsExecutionTimeSeconds", 0)
    if elapsed is None or elapsed <= 0:
        return None
    count = sum(1 for v in merge.get("variants", [])
                if v.get("variantIndex", 0) != 0 and not v.get("timedOut", False))
    return count / float(elapsed)


def draw_variants_per_second(ax, all_data: dict, valid_commits: list[str] | None = None):
    """Box plot (log scale): variants completed per second of variant-phase wall-clock.
    Uses the widest possible n (commits present in all 4 variant modes) — does NOT
    require a measurable baseline budget or scoreable variants the way the
    count-based throughput chart does."""
    present_modes = _present_variant_modes(all_data)
    if valid_commits is None and present_modes:
        valid_commits = sorted(
            set.intersection(*(set(all_data.get(m, {}).keys()) for m in present_modes))
        )
    groups = _collect_paired_metric(all_data, present_modes, _variants_per_second,
                                    valid_commits)
    if not any(groups.values()):
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
    ax.set_ylabel("Variants per second of variant-phase wall-clock (log scale)")
    ax.set_title("Variant Throughput Rate by Execution Mode"
                 if len(_present_variant_modes(all_data)) >= 2 else "Variant Throughput Rate")
    ax.grid(True, axis="y", alpha=0.3, which="both")

    friedman_p = _friedman_test(groups, plot_modes)
    pairwise = _pairwise_wilcoxon(groups, plot_modes)
    summary = _significance_summary(friedman_p, pairwise)
    if summary:
        _annotate_significance(ax, summary)


# ── RQ2 Chart: Cache warmup speedup ratio ────────────────────────────────────

def _quantile_slice(sorted_list: list, q: int, n_buckets: int) -> list:
    """Return the q-th of ``n_buckets`` equal slices of a sorted list (0 = fastest).
    Falls back to a single nearest element so very short lists still produce a
    non-empty slice for every bucket — useful when a merge has only a few variants."""
    n = len(sorted_list)
    if n == 0:
        return []
    a = (n * q) // n_buckets
    b = (n * (q + 1)) // n_buckets
    if a >= b:
        return [sorted_list[min(a, n - 1)]]
    return sorted_list[a:b]


def draw_cache_warmup_speedup(ax, all_data: dict, valid_commits: list[str] | None = None,
                              quantile: int | None = None, n_buckets: int = 4,
                              pair_filter: tuple[str, str] | None = None,
                              title: str | None = None,
                              annotate_significance: bool = True):
    """
    Box plot: per-merge cache speedup, comparing the median per-variant build
    time in a cache mode against the corresponding no-cache mode for the same
    merge. Excludes donor variants from the cache mode (donors pay a one-off
    warming cost that's amortised across the rest; including them would just
    measure warmer overhead, not cache benefit). Compares apples-to-apples:
    Sequential+Cache vs Sequential, Parallel+Cache vs Parallel.

    Ratio = median(no-cache mode own_t) / median(cache mode non-donor own_t).
    >1.0 → cache mode is faster (the headline claim caching is supposed to make).
    <1.0 → cache overhead exceeds savings (e.g. when build-cache rarely hits).

    When ``quantile`` is set, both cache and base time lists for each merge are
    sorted ascending and only the q-th of ``n_buckets`` slices is kept before
    medians are taken — so the ratio reflects speedups within one speed band.
    Ranking is per-mode within a merge; we compare the same band across cache
    and no-cache. ``quantile=None`` keeps the all-variants view.

    ``pair_filter`` restricts the chart to a single (cache_mode, base_mode) pair
    so the small-multiples layout can render one box per panel.
    """
    PAIRS = [("cache_sequential", "no_optimization", "S+ / S"),
             ("cache_parallel",   "parallel",        "P+ / P")]
    if pair_filter is not None:
        PAIRS = [p for p in PAIRS if (p[0], p[1]) == pair_filter]

    box_data = []
    box_labels = []
    box_colors = []
    sig_lines = []

    for cache_mode, base_mode, label in PAIRS:
        ratios = []
        base_medians = []
        cache_medians = []
        cache_merges = all_data.get(cache_mode, {})
        base_merges  = all_data.get(base_mode, {})
        if valid_commits is not None:
            commits = valid_commits
        else:
            commits = sorted(set(cache_merges.keys()) & set(base_merges.keys()))
        for commit in commits:
            cmerge = cache_merges.get(commit)
            bmerge = base_merges.get(commit)
            if cmerge is None or bmerge is None:
                continue
            cache_warm = []
            for v in cmerge.get("variants", []):
                if v.get("variantIndex", 0) == 0 or v.get("timedOut", False):
                    continue
                if v.get("isCacheDonor", False) or v.get("isCacheWarmer", False):
                    continue  # exclude donor: it pays warming cost
                own = v.get("ownExecutionSeconds")
                if own is not None and own > 0:
                    cache_warm.append(own)
            base_times = []
            for v in bmerge.get("variants", []):
                if v.get("variantIndex", 0) == 0 or v.get("timedOut", False):
                    continue
                own = v.get("ownExecutionSeconds")
                if own is not None and own > 0:
                    base_times.append(own)
            if cache_warm and base_times:
                if quantile is not None:
                    cache_warm = _quantile_slice(sorted(cache_warm), quantile, n_buckets)
                    base_times = _quantile_slice(sorted(base_times), quantile, n_buckets)
                    if not cache_warm or not base_times:
                        continue
                cw_med = sorted(cache_warm)[len(cache_warm) // 2]
                bt_med = sorted(base_times)[len(base_times) // 2]
                if cw_med > 0:
                    ratios.append(bt_med / cw_med)
                    base_medians.append(bt_med)
                    cache_medians.append(cw_med)

        if ratios:
            box_data.append(ratios)
            box_labels.append(f"Cache Speedup ({label})\nn={len(ratios)}")
            box_colors.append(MODE_COLORS[cache_mode])

            if len(base_medians) >= 3 and HAS_SCIPY:
                try:
                    _, p = wilcoxon(base_medians, cache_medians)
                    d = _cliffs_delta(base_medians, cache_medians)
                    med_ratio = sorted(ratios)[len(ratios) // 2]
                    sig = "***" if p < 0.001 else "**" if p < 0.01 else "*" if p < 0.05 else "ns"
                    sig_lines.append(
                        f"{label}: med={med_ratio:.2f}x, "
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

    # Reference line at ratio=1 (no speedup); above the line = cache faster.
    ax.axhline(y=1.0, color="grey", linestyle="--", linewidth=0.8, alpha=0.6,
               label="no speedup (ratio = 1)")

    ax.set_xticks(positions)
    ax.set_xticklabels(box_labels, fontsize=9)
    ax.set_ylabel("Speedup ratio = no-cache median ÷ cache-warm median")
    if title is None:
        title = "Cache vs No-Cache: Per-Merge Speedup Ratio (warm variants only)"
    ax.set_title(title)

    # Cap y at 8.0 — anything above is an extreme outlier (visible as flier dots);
    # without the cap the box bodies get squashed against the bottom.
    ax.set_ylim(0.0, 8.0)

    # Direction labels inside the plot, hugging the left y-axis, so readers
    # immediately see which side of y=1 favours cache. Use mathtext for the
    # comparison operators so they render consistently regardless of backend.
    ax.text(0.01, 0.97, r"↑  ratio $>$ 1: cache faster",
            transform=ax.transAxes, ha="left", va="top", fontsize=9,
            color="#2e7d32")
    ax.text(0.01, 0.03, r"↓  ratio $<$ 1: cache slower",
            transform=ax.transAxes, ha="left", va="bottom", fontsize=9,
            color="#b71c1c")

    ax.legend(loc="upper right", fontsize=8)
    ax.grid(True, axis="y", alpha=0.3, which="both")

    # Significance summary in the top-right, dropped down enough to clear the
    # "no speedup" legend entry above it.
    if sig_lines and annotate_significance:
        ax.text(0.98, 0.86, "\n".join(sig_lines),
                transform=ax.transAxes, fontsize=7,
                ha="right", va="top", fontfamily="monospace", zorder=99,
                bbox=dict(boxstyle="round,pad=0.3", facecolor="wheat", alpha=1.0,
                          edgecolor="tan", linewidth=0.5))


def _cache_speedup_ratios_by_quartile(all_data: dict, cache_mode: str, base_mode: str,
                                      valid_commits: list[str] | None,
                                      n_buckets: int = 4) -> list[list[float]]:
    """For one mode pair, return a list of length ``n_buckets`` where entry ``q`` is
    the list of per-merge speedup ratios computed only on variants in the q-th
    speed bucket. Per-merge: sort cache-warm and base own_t lists ascending,
    take the q-th equal slice from each, take medians, ratio = base/cache."""
    cache_merges = all_data.get(cache_mode, {})
    base_merges  = all_data.get(base_mode, {})
    commits = (valid_commits if valid_commits is not None
               else sorted(set(cache_merges.keys()) & set(base_merges.keys())))
    out: list[list[float]] = [[] for _ in range(n_buckets)]
    for commit in commits:
        cmerge = cache_merges.get(commit)
        bmerge = base_merges.get(commit)
        if cmerge is None or bmerge is None:
            continue
        cache_warm = []
        for v in cmerge.get("variants", []):
            if v.get("variantIndex", 0) == 0 or v.get("timedOut", False):
                continue
            if v.get("isCacheDonor", False) or v.get("isCacheWarmer", False):
                continue
            own = v.get("ownExecutionSeconds")
            if own is not None and own > 0:
                cache_warm.append(own)
        base_times = []
        for v in bmerge.get("variants", []):
            if v.get("variantIndex", 0) == 0 or v.get("timedOut", False):
                continue
            own = v.get("ownExecutionSeconds")
            if own is not None and own > 0:
                base_times.append(own)
        if not cache_warm or not base_times:
            continue
        cache_warm.sort()
        base_times.sort()
        for q in range(n_buckets):
            cw = _quantile_slice(cache_warm, q, n_buckets)
            bt = _quantile_slice(base_times, q, n_buckets)
            if not cw or not bt:
                continue
            cw_med = sorted(cw)[len(cw) // 2]
            bt_med = sorted(bt)[len(bt) // 2]
            if cw_med > 0:
                out[q].append(bt_med / cw_med)
    return out


def draw_cache_warmup_speedup_quartiles(fig, all_data: dict,
                                        valid_commits: list[str] | None = None,
                                        n_buckets: int = 4):
    """Two panels (one per mode pair). Each panel shows ``n_buckets`` box plots
    side by side — one box per quartile of variants per merge sorted by build
    time (fastest → slowest)."""
    if len(_present_variant_modes(all_data)) < 2:
        ax = fig.add_subplot(111)
        ax.text(0.5, 0.5, "Requires multiple execution modes\n(not applicable to a single-mode run)",
                transform=ax.transAxes, ha="center", va="center")
        ax.set_xticks([]); ax.set_yticks([])
        ax.set_title("Cache Warmup Speedup")
        return

    PAIRS = [
        ("cache_sequential", "no_optimization", "Sequential: S+ / S"),
        ("cache_parallel",   "parallel",        "Parallel: P+ / P"),
    ]
    quartile_labels = [
        "Q1\n(fastest 1/4)",
        "Q2",
        "Q3",
        f"Q{n_buckets}\n(slowest 1/4)",
    ]
    if n_buckets != 4:
        quartile_labels = ([f"Q1\n(fastest 1/{n_buckets})"]
                           + [f"Q{i+1}" for i in range(1, n_buckets - 1)]
                           + [f"Q{n_buckets}\n(slowest 1/{n_buckets})"])

    gs = fig.add_gridspec(len(PAIRS), 1, hspace=0.45)
    axes = []
    for r, (cache_mode, base_mode, panel_label) in enumerate(PAIRS):
        sharey = axes[0] if axes else None
        ax = fig.add_subplot(gs[r, 0], sharey=sharey)
        axes.append(ax)

        ratios = _cache_speedup_ratios_by_quartile(
            all_data, cache_mode, base_mode, valid_commits, n_buckets=n_buckets)

        positions = list(range(n_buckets))
        non_empty = [(p, r_) for p, r_ in zip(positions, ratios) if r_]
        if non_empty:
            ps, data = zip(*non_empty)
            bp = ax.boxplot(data, positions=list(ps), widths=0.5, patch_artist=True,
                            showfliers=True, flierprops=dict(markersize=3, alpha=0.5))
            for patch in bp["boxes"]:
                patch.set_facecolor(MODE_COLORS[cache_mode])
                patch.set_alpha(0.7)
        else:
            ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")

        ax.axhline(y=1.0, color="grey", linestyle="--", linewidth=0.8, alpha=0.6,
                   label="no speedup (ratio = 1)")
        ax.set_xticks(positions)
        ax.set_xticklabels([f"{lbl}\nn={len(ratios[i])}" for i, lbl in enumerate(quartile_labels)],
                           fontsize=8)
        ax.set_xlim(-0.5, n_buckets - 0.5)
        ax.set_ylim(0.0, 8.0)
        ax.set_ylabel("Speedup ratio = no-cache med ÷ cache-warm med")
        ax.set_title(f"{panel_label}: per-merge speedup by variant-time quartile",
                     fontsize=10)
        ax.grid(True, axis="y", alpha=0.3, which="both")

        # Direction labels — only on the first panel keep clutter low; second
        # panel uses the same y-axis interpretation.
        if r == 0:
            ax.text(0.005, 0.97, r"↑  ratio $>$ 1: cache faster",
                    transform=ax.transAxes, ha="left", va="top", fontsize=8,
                    color="#2e7d32")
            ax.text(0.005, 0.03, r"↓  ratio $<$ 1: cache slower",
                    transform=ax.transAxes, ha="left", va="bottom", fontsize=8,
                    color="#b71c1c")
            ax.legend(loc="upper right", fontsize=8)


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


def _best_variant_index(merge: dict) -> int | None:
    """
    variantIndex of the merge's best-scoring non-baseline variant.
    Ties on score are broken by the lowest variantIndex (i.e. earliest
    in the canonical run order).
    """
    best_score = None
    best_idx = None
    for v in merge.get("variants", []):
        idx = v.get("variantIndex", 0)
        if idx == 0:
            continue
        if v.get("timedOut", False):
            continue
        score = variant_score(v)
        if score is None:
            continue
        if (best_score is None
                or score > best_score
                or (score == best_score and idx < best_idx)):
            best_score = score
            best_idx = idx
    return best_idx


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
    if not _note_requires_modes(ax, all_data):
        ax.set_title("Mode Rank Evolution Over Time")
        return

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


# ── RQ2 Chart: Variant accumulation over time ────────────────────────────────

def _variants_completed_at_time(merge: dict, rel_time: float, budget: float) -> int:
    """Count of non-baseline, non-timed-out variants that finished by
    rel_time * budget seconds."""
    cutoff = rel_time * budget
    count = 0
    for v in merge.get("variants", []):
        if v.get("variantIndex", 0) == 0:
            continue
        if v.get("timedOut", False):
            continue
        t = v.get("totalTimeSinceMergeStartSeconds")
        if t is None or t > cutoff:
            continue
        count += 1
    return count


def draw_variant_accumulation(ax, all_data: dict, *, aggregate: str = "median",
                              valid_commits: list[str] | None = None):
    """
    Per-merge accumulated variants completed by time t, aggregated across merges.

    X-axis: multiples of human baseline build duration.
    Y-axis: median (or mean) number of variants completed per merge.
    """
    import numpy as np

    if valid_commits is None:
        valid_commits = _common_valid_commits(all_data, VARIANT_MODES)

    valid_with_budget = []
    for commit in valid_commits:
        merge = all_data[VARIANT_MODES[0]][commit]
        budget = merge.get("budgetBasisSeconds", 0)
        if budget > 0:
            valid_with_budget.append((commit, budget))

    if not valid_with_budget:
        ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")
        return

    max_rel = 0.0
    for commit, budget in valid_with_budget:
        for mode in VARIANT_MODES:
            merge = all_data[mode].get(commit, {})
            for v in merge.get("variants", []):
                if v.get("timedOut", False):
                    continue
                t = v.get("totalTimeSinceMergeStartSeconds")
                if t is not None:
                    max_rel = max(max_rel, t / budget)
    max_rel = min(max_rel, 10.0)

    step = 0.05
    n_steps = int(max_rel / step) + 1
    time_points = [k * step for k in range(1, n_steps + 1)]
    if not time_points:
        ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")
        return

    agg_fn = np.median if aggregate == "median" else np.mean
    mode_values = {m: [] for m in VARIANT_MODES}

    for rel_t in time_points:
        per_mode_counts = {m: [] for m in VARIANT_MODES}
        for commit, budget in valid_with_budget:
            for mode in VARIANT_MODES:
                merge = all_data[mode].get(commit, {})
                per_mode_counts[mode].append(
                    _variants_completed_at_time(merge, rel_t, budget)
                )
        for mode in VARIANT_MODES:
            if per_mode_counts[mode]:
                mode_values[mode].append(agg_fn(per_mode_counts[mode]))
            else:
                mode_values[mode].append(np.nan)

    for mode in VARIANT_MODES:
        ax.plot(time_points, mode_values[mode],
                color=MODE_COLORS[mode], linewidth=1.8,
                label=MODE_LABELS[mode], alpha=0.9, zorder=3)

    ax.set_xlabel("Relative time (1.0 = human baseline build duration)")
    ax.set_ylabel(f"{aggregate.capitalize()} variants completed per merge")
    ax.set_title(f"Variant Accumulation Over Time ({aggregate})")
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

    ss_resid = float(np.sum(residuals ** 2))

    results = {}
    for i, label in enumerate(labels):
        if i == 0:
            continue  # skip intercept
        if se[i] == 0:
            results[label] = {"t": float("inf"), "p": 0.0, "eta2p": 1.0}
        else:
            t_val = beta[i] / se[i]
            p_val = 2.0 * (1.0 - t_dist.cdf(abs(t_val), df_resid))
            # Partial eta-squared: t^2 / (t^2 + df_resid)
            eta2p = float(t_val ** 2 / (t_val ** 2 + df_resid))
            results[label] = {"t": round(float(t_val), 3),
                              "p": round(float(p_val), 6),
                              "eta2p": round(eta2p, 4)}

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
    # The 2x2 factorial requires all four variant modes; a single-mode RQ3 run
    # cannot populate the design.
    if not _note_requires_modes(ax, all_data,
                                need={"no_optimization", "parallel",
                                      "cache_sequential", "cache_parallel"}):
        ax.set_title(f"Technique Decomposition: {metric_name}")
        return

    groups = _collect_paired_metric(all_data, VARIANT_MODES, metric_fn, valid_commits)
    if not groups:
        ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")
        return

    import numpy as np

    x_labels = ["Sequential (1 thread)", "Parallel"]
    x_pos = np.array([0.0, 1.0])

    factor_lines = [
        (("no_optimization", "parallel"), "No Cache", "#e31a1c", "o", -0.02),
        (("cache_sequential", "cache_parallel"), "Cache", "#1f78b4", "s", 0.02),
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
                eta2p = e.get("eta2p", 0.0)
                ann_lines.append(
                    f"  {term}: t={e['t']:.1f}, p={p:.4f}{sig}, eta2p={eta2p:.3f}"
                )
    if ann_lines:
        _annotate_significance(ax, "\n".join(ann_lines))
    else:
        _annotate_significance(ax, "ANCOVA: insufficient data")


# ── RQ2 Chart: Variant index vs. build time (diminishing marginal cost) ───────

def draw_variant_cost_curve(ax, all_data: dict, valid_commits: list[str] | None = None,
                            per_thread: bool = False):
    """
    For each mode, plot variant ordinal index (x) vs. ownExecutionSeconds (y).
    Shows that RQ1 predictions order variants well (early variants are
    the most promising) and that each additional variant is cheap.

    If per_thread=True, divide each parallel variant's time by its merge's
    thread count, showing the per-core cost.

    Each merge contributes one line (thin, semi-transparent); a thick median
    line per mode summarises the trend.
    """
    # The per-thread view only makes sense with a parallel mode (dividing a
    # sequential time by threads=1 just duplicates the normal chart).
    if per_thread and not (set(_present_variant_modes(all_data)) & {"parallel", "cache_parallel"}):
        ax.text(0.5, 0.5, "Per-thread view requires a parallel mode\n(not applicable to a single-mode run)",
                transform=ax.transAxes, ha="center", va="center")
        ax.set_xticks([]); ax.set_yticks([])
        ax.set_title("Marginal Cost of Additional Variants (Per Thread)")
        return

    import numpy as np

    max_x = 0       # track longest median curve for x-axis limit
    max_y = 0.0     # track tallest plotted value for dynamic y-axis

    for mode in VARIANT_MODES:
        color = MODE_COLORS[mode]
        marker = MODE_MARKERS[mode]
        label = MODE_LABELS[mode]
        is_parallel = mode in ("parallel", "cache_parallel")

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
            threads = _get_threads(merge, mode) if per_thread and is_parallel else 1
            series = []
            for v in merge.get("variants", []):
                if v.get("variantIndex", 0) == 0:
                    continue
                own = v.get("ownExecutionSeconds")
                if own is None:
                    continue
                # Normalise by baseline so projects are comparable
                series.append(own / budget / threads)
            if series:
                all_series.append(series)

        if not all_series:
            continue

        # Median at each ordinal position, then LOWESS smoothing.
        max_len = max(len(s) for s in all_series)
        median_xs = []
        median_ys = []
        for i in range(max_len):
            vals = [s[i] for s in all_series if i < len(s)]
            if len(vals) >= 3:  # need at least 3 merges at this position
                median_xs.append(i + 1)
                median_ys.append(np.median(vals))
            else:
                break

        if len(median_ys) >= 3:
            plotted = _lowess(median_xs, median_ys, frac=0.2)
            ax.plot(median_xs, plotted, color=color, linewidth=2.2,
                    marker=marker, markersize=4,
                    markevery=max(1, len(plotted) // 15),
                    label=label, zorder=4, alpha=0.9)
            max_x = max(max_x, median_xs[-1])
            max_y = max(max_y, max(plotted))

    ax.set_xlabel("Variant ordinal (1 = first variant tried)")
    if per_thread:
        ax.set_ylabel("Build time per thread (relative to baseline)")
        ax.set_title("Marginal Cost of Additional Variants (Per Thread)")
        y_top = 0.25  # fixed crop for the per-thread (parallel) view
    else:
        ax.set_ylabel("Build time (relative to baseline, 1.0 = baseline duration)")
        ax.set_title("Marginal Cost of Additional Variants")
        # Fit the data with 15% headroom rather than pinning to a fixed ceiling:
        # variants are typically far cheaper than the baseline, so a fixed 1.5
        # top would squeeze the whole curve into a sliver at the bottom.
        y_top = max(max_y * 1.15, 0.05)
    ax.set_xlim(left=0, right=max_x + 1 if max_x > 0 else None)
    ax.set_ylim(bottom=0, top=y_top)
    # Baseline reference at y=1: draw the line only when it falls inside the
    # data range; otherwise annotate, since an off-range line just wastes space.
    if y_top >= 1.0:
        ax.axhline(y=1.0, color="grey", linestyle="--", linewidth=0.8, alpha=0.6,
                   label="baseline build time")
    elif max_y > 0:
        factor = 1.0 / max_y
        ax.annotate((rf"baseline (1.0) $\approx$ {factor:.0f}$\times$ the costliest variant"
                     if USE_LATEX else f"baseline (1.0) ~ {factor:.0f}x the costliest variant"),
                    xy=(0.98, 0.92), xycoords="axes fraction", ha="right",
                    fontsize=8, color="grey")
    ax.legend(loc="upper right", fontsize=8)
    ax.grid(True, alpha=0.3)


def draw_cache_variant_times(ax, all_data: dict, valid_commits: list[str] | None = None):
    """
    Like the marginal cost curve but for cache modes only, split by hadWarmCacheReady.
    Four series: S+ warm, S+ cold, P+ warm, P+ cold.
    """
    if not _note_requires_modes(ax, all_data,
                                need={"cache_sequential", "cache_parallel"}):
        ax.set_title("Cache Effect on Variant Build Time")
        return

    import numpy as np

    max_x = 0
    max_y = 0.0

    # (mode, hadWarmCacheReady, label, color, marker, zorder)
    # Cold lines drawn first; warm lines on top so they're visible.
    series_defs = [
        ("cache_sequential", False, "S+ (no cache)",    "#e31a1c", "o", 4),
        ("cache_parallel",   False, "P+ (no cache)",    "#33a02c", "s", 4),
        ("cache_sequential", True,  "S+ (warm cache)",  "#fb9a99", "v", 5),
        ("cache_parallel",   True,  "P+ (warm cache)",  "#b2df8a", "^", 5),
    ]

    for mode, warm_ready, label, color, marker, z in series_defs:
        # Collect per-merge: variantIndex -> normalised build time,
        # only for variants matching the hadWarmCacheReady flag.
        # key = variantIndex, value = list of normalised times across merges
        from collections import defaultdict as _defaultdict
        index_vals: dict[int, list[float]] = _defaultdict(list)
        n_merges = 0
        merges = all_data.get(mode, {})
        commits = valid_commits if valid_commits is not None else sorted(merges.keys())
        for commit in commits:
            merge = merges.get(commit)
            if merge is None:
                continue
            budget = merge.get("budgetBasisSeconds", 0)
            if budget <= 0:
                continue
            found = False
            for v in merge.get("variants", []):
                idx = v.get("variantIndex", 0)
                if idx == 0:
                    continue
                if v.get("hadWarmCacheReady", v.get("didUseCache", False)) != warm_ready:
                    continue
                own = v.get("ownExecutionSeconds")
                if own is None:
                    continue
                index_vals[idx].append(own / budget)
                found = True
            if found:
                n_merges += 1

        if not index_vals:
            continue

        # Median at each variant index with >= 3 data points, then LOWESS
        median_xs = []
        median_ys = []
        for idx in sorted(index_vals):
            vals = index_vals[idx]
            if len(vals) >= 3:
                median_xs.append(idx)
                median_ys.append(np.median(vals))

        if len(median_ys) >= 3:
            plotted = _lowess(median_xs, median_ys, frac=0.3)
            ax.plot(median_xs, plotted, color=color, linewidth=2.2,
                    marker=marker, markersize=4,
                    markevery=max(1, len(plotted) // 15),
                    label=f"{label} (n={n_merges})", zorder=z, alpha=0.9)
            max_x = max(max_x, median_xs[-1])
            max_y = max(max_y, max(plotted))

    ax.set_xlabel("Variant ordinal (1 = first variant tried)")
    ax.set_ylabel("Build time (relative to baseline, 1.0 = baseline duration)")
    ax.set_title("Cache Effect on Variant Build Time")
    ax.axhline(y=1.0, color="grey", linestyle="--", linewidth=0.8, alpha=0.6,
               label="baseline build time")
    ax.set_xlim(left=0, right=max_x + 1 if max_x > 0 else None)
    y_top = max(max_y * 1.15, 1.5)
    ax.set_ylim(bottom=0, top=y_top)
    ax.legend(loc="upper right", fontsize=8)
    ax.grid(True, alpha=0.3)


# ── Setup overhead (Charts 10a, 10b) ─────────────────────────────────────────

_OVERHEAD_MODES = ["no_optimization", "cache_sequential", "parallel", "cache_parallel"]


def _load_first_batch_overhead(variant_dir: Path):
    """
    Load (absolute_wait_s, fraction) for every first-batch variant
    (variantIndex 1..#threads) across all modes.
    Returns dict: mode -> list of (abs_wait, frac).
    """
    result = {}
    for mode in _OVERHEAD_MODES:
        mode_dir = variant_dir / mode
        if not mode_dir.is_dir():
            continue
        points = []
        for fp in sorted(mode_dir.glob("*.json")):
            with open(fp) as fh:
                data = json.load(fh)
            variants = data.get("variants", [])
            threads = data.get("threads", 1)
            for v in variants:
                idx = v.get("variantIndex", 0)
                if idx < 1 or idx > threads:
                    continue
                total = v.get("totalTimeSinceMergeStartSeconds", 0)
                own = v.get("ownExecutionSeconds", 0)
                if total <= 0:
                    continue
                wait = total - own
                points.append((wait, wait / total))
        if points:
            result[mode] = points
    return result


def _draw_overhead_violin(ax, overhead_data, value_index, ylabel, ylim=None):
    """Draw a violin for setup overhead. value_index: 0=absolute, 1=fraction."""
    distributions = []
    positions = []
    colors = []
    labels = []
    for i, mode in enumerate(_OVERHEAD_MODES):
        if mode not in overhead_data:
            continue
        ys = [p[value_index] for p in overhead_data[mode]]
        distributions.append(ys)
        positions.append(i + 1)
        colors.append(MODE_COLORS[mode])
        labels.append(MODE_LABELS[mode])

    if not distributions:
        return

    parts = ax.violinplot(distributions, positions=positions,
                          showmeans=True, showmedians=True, showextrema=False)
    for i, pc in enumerate(parts["bodies"]):
        pc.set_facecolor(colors[i])
        pc.set_edgecolor("black")
        pc.set_linewidth(0.5)
        pc.set_alpha(0.7)
    parts["cmeans"].set_color("black")
    parts["cmeans"].set_linewidth(1)
    parts["cmedians"].set_color("black")
    parts["cmedians"].set_linewidth(0.8)
    parts["cmedians"].set_linestyle("--")

    ax.set_xticks(positions)
    ax.set_xticklabels(labels, rotation=20, ha="right")
    ax.set_ylabel(ylabel)
    if ylim is not None:
        ax.set_ylim(ylim)
    ax.axhline(y=0, color="gray", linewidth=0.5, linestyle="--")

    for i, ys in enumerate(distributions):
        y_bot = ylim[0] if ylim else ax.get_ylim()[0]
        span = (ylim[1] - ylim[0]) if ylim else (ax.get_ylim()[1] - ax.get_ylim()[0])
        ax.text(positions[i], y_bot + 0.01 * span,
                f"n={len(ys)}", ha="center", va="bottom", fontsize=7, color="gray")


def draw_best_variant_index_box(ax, all_data: dict):
    """
    Boxplot of the variantIndex of each merge's best-scoring non-baseline
    variant, per mode. Ties on score are broken by lowest variantIndex.
    Answers: how deep into the trial sequence do you have to go before
    the best variant appears?
    """
    box_data = []
    box_labels = []
    box_colors = []

    for mode in VARIANT_MODES:
        merges = all_data.get(mode, {})
        indices = []
        for merge in merges.values():
            idx = _best_variant_index(merge)
            if idx is not None:
                indices.append(idx)
        if not indices:
            continue
        box_data.append(indices)
        box_labels.append(f"{MODE_LABELS[mode]}\nn={len(indices)}")
        box_colors.append(MODE_COLORS[mode])

    if not box_data:
        ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")
        return

    positions = list(range(len(box_data)))
    bp = ax.boxplot(box_data, positions=positions, widths=0.5, patch_artist=True,
                    showfliers=True, flierprops=dict(markersize=3, alpha=0.5))

    for patch, color in zip(bp["boxes"], box_colors):
        patch.set_facecolor(color)
        patch.set_alpha(0.7)

    medians = [sorted(d)[len(d) // 2] for d in box_data]
    for pos, med in zip(positions, medians):
        ax.text(pos, med, f"  med={med}",
                ha="left", va="center", fontsize=8, color="black")

    ax.set_xticks(positions)
    ax.set_xticklabels(box_labels, fontsize=9)
    ax.set_ylabel("variantIndex of best variant (lower = found earlier in run order)")
    ax.set_title("Best-Variant Position in Run Order (per merge)")
    ax.grid(True, axis="y", alpha=0.3)


def draw_quality_distributions(fig, all_data: dict):
    """Three boxplot panels comparing the human baseline to the best variant of
    each present mode: #modules built, #tests passed, and combined quality
    (1.0 = matches the human). Means shown as white dots; y-axes are symlog so
    the wide count ranges stay legible."""
    import numpy as np
    modes = _present_variant_modes(all_data)
    axes = fig.subplots(1, 3)
    if not modes:
        for ax in axes:
            ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")
        return

    perq = {m: _per_merge_quality(all_data, m) for m in modes}

    def _boxes(ax, series, ylabel, title, ref=None):
        data   = [np.asarray(s, dtype=float) for s, _, _ in series if len(s)]
        labels = [l for s, l, _ in series if len(s)]
        colors = [c for s, _, c in series if len(s)]
        if not data:
            ax.text(0.5, 0.5, "No data", transform=ax.transAxes, ha="center")
            ax.set_title(title)
            return
        positions = list(range(len(data)))
        bp = ax.boxplot(data, positions=positions, widths=0.6, patch_artist=True,
                        showmeans=True,
                        meanprops=dict(marker="o", markerfacecolor="white",
                                       markeredgecolor="black", markersize=4),
                        flierprops=dict(markersize=2, alpha=0.3))
        for patch, c in zip(bp["boxes"], colors):
            patch.set_facecolor(c)
            patch.set_alpha(0.7)
        ax.set_xticks(positions)
        ax.set_xticklabels([f"{l}\nn={len(d)}" for l, d in zip(labels, data)])
        ax.set_yscale("symlog", linthresh=1)
        ax.set_ylim(bottom=0)  # counts/quality are non-negative — drop the unused negative half
        ax.set_ylabel(ylabel)
        ax.set_title(title)
        ax.grid(True, axis="y", alpha=0.3, which="both")
        if ref is not None:
            ax.axhline(ref, color="grey", linestyle="--", linewidth=0.8, alpha=0.7)

    hb_label = MODE_LABELS["human_baseline"]
    hb_color = MODE_COLORS["human_baseline"]
    base = modes[0]
    _boxes(axes[0],
           [(perq[base]["hb_modules"], hb_label, hb_color)]
           + [(perq[m]["best_modules"], MODE_LABELS[m], MODE_COLORS[m]) for m in modes],
           "Modules built (symlog)", "Modules Built: Human vs. Best Variant")
    _boxes(axes[1],
           [(perq[base]["hb_tests"], hb_label, hb_color)]
           + [(perq[m]["best_tests"], MODE_LABELS[m], MODE_COLORS[m]) for m in modes],
           "Tests passed (symlog)", "Tests Passed: Human vs. Best Variant")
    _boxes(axes[2],
           [(perq[m]["best_combined"], MODE_LABELS[m], MODE_COLORS[m]) for m in modes],
           "combined quality (symlog, 1.0 = human)",
           "Combined Quality: Best Variant", ref=1.0)
    fig.tight_layout()


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

    combined_mode_markers = combined_mode_steps = None

    if rq == "rq3":
        print("Assembling module success data ...")
        module_mode_markers, module_mode_steps = assemble_plot_data(all_data, module_stats)
        print("Assembling test success data ...")
        # Tests need Laplace smoothing — many baselines pass 0 tests
        # (projects with no test suite), which would otherwise drop the
        # whole merge from the chart.
        test_mode_markers, test_mode_steps = assemble_plot_data(
            all_data, test_stats, smooth=True)
        print("Assembling combined-quality data ...")
        combined_mode_markers, combined_mode_steps = assemble_combined_plot_data(all_data)

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

            # Chart 2: Test success rate vs. relative time (Laplace-smoothed)
            fig, ax = plt.subplots(figsize=(10, 6))
            draw_graph(ax, test_mode_markers, test_mode_steps,
                       title=r"Test Success Rate vs.\ Relative Time",
                       ylabel=(r"Tests passed (Laplace-smoothed, "
                               r"relative to human baseline $= 1.0$)"))
            _save_fig(fig, results_dir, "02_test_success_rate", pdf)

            # Chart 2c: Combined quality (modules x tests, Laplace-smoothed)
            fig, ax = plt.subplots(figsize=(10, 6))
            draw_graph(ax, combined_mode_markers, combined_mode_steps,
                       title=r"Combined Quality (Modules $\times$ Tests) vs.\ Relative Time",
                       ylabel=r"Combined quality (relative to human baseline $= 1.0$)")
            _save_fig(fig, results_dir, "02c_combined_quality", pdf)

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

        # Chart 5b: Variants per second (throughput rate)
        print("Drawing variant throughput rate chart ...")
        fig, ax = plt.subplots(figsize=(9, 6))
        draw_variants_per_second(ax, all_data)
        _save_fig(fig, results_dir, "05b_variants_per_second", pdf)

        # Chart 5c: Variants that built at least one module
        print("Drawing variant throughput chart ($\geq$1 module built) ...")
        fig, ax = plt.subplots(figsize=(9, 6))
        draw_variants_built_module(ax, all_data)
        _save_fig(fig, results_dir, "05c_variants_built_module", pdf)

        # Chart 5d: Variants that ran at least one test
        print("Drawing variant throughput chart ($\geq$1 test executed) ...")
        fig, ax = plt.subplots(figsize=(9, 6))
        draw_variants_ran_test(ax, all_data)
        _save_fig(fig, results_dir, "05d_variants_ran_test", pdf)

        # Chart 5e: Per-merge time-to-best, normalized by fastest mode
        print("Drawing time-to-best chart (normalized per merge) ...")
        fig, ax = plt.subplots(figsize=(9, 6))
        draw_time_to_best_normalized(ax, all_data)
        _save_fig(fig, results_dir, "05e_time_for_best_variant", pdf)

        # Chart 5f: Time-to-best in multiples of human-baseline duration
        print("Drawing time-to-best chart (human-baseline units) ...")
        fig, ax = plt.subplots(figsize=(9, 6))
        draw_time_to_best_in_baseline_units(ax, all_data)
        _save_fig(fig, results_dir, "05f_time_for_best_variant_hb_units", pdf)

        # Chart 6: Cache warmup speedup ratio — 2x4 grid (mode pair × quartile)
        print("Drawing cache warmup speedup chart ...")
        fig = plt.figure(figsize=(13, 7.5))
        draw_cache_warmup_speedup_quartiles(fig, all_data)
        _save_fig(fig, results_dir, "06_cache_warmup_speedup", pdf)

        # Chart 7: Mode rank evolution over time
        print("Drawing rank evolution chart ...")
        fig, ax = plt.subplots(figsize=(10, 5))
        draw_rank_evolution(ax, all_data)
        _save_fig(fig, results_dir, "07_rank_evolution", pdf)

        # Chart 7a: Variant accumulation over time (median)
        print("Drawing variant accumulation chart (median) ...")
        fig, ax = plt.subplots(figsize=(10, 5))
        draw_variant_accumulation(ax, all_data, aggregate="median")
        _save_fig(fig, results_dir, "07a_variant_accumulation_median", pdf)

        # Chart 7b: Variant accumulation over time (mean)
        print("Drawing variant accumulation chart (mean) ...")
        fig, ax = plt.subplots(figsize=(10, 5))
        draw_variant_accumulation(ax, all_data, aggregate="mean")
        _save_fig(fig, results_dir, "07b_variant_accumulation_mean", pdf)

        # Chart 8: Variant cost curve (variant# vs. build time)
        print("Drawing variant cost curve ...")
        fig, ax = plt.subplots(figsize=(10, 6))
        draw_variant_cost_curve(ax, all_data)
        _save_fig(fig, results_dir, "08_variant_cost_curve", pdf)

        # Chart 8a: Variant cost curve per thread (parallel times / thread count)
        print("Drawing variant cost curve (per thread) ...")
        fig, ax = plt.subplots(figsize=(10, 6))
        draw_variant_cost_curve(ax, all_data, per_thread=True)
        _save_fig(fig, results_dir, "08a_variant_cost_per_thread", pdf)

        # Chart 8b: Cache effect on variant build times
        print("Drawing cache variant time chart ...")
        fig, ax = plt.subplots(figsize=(10, 6))
        draw_cache_variant_times(ax, all_data)
        _save_fig(fig, results_dir, "08b_cache_variant_times", pdf)

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

        # Charts 10a/10b: Setup overhead (reads JSON files directly)
        print("Drawing setup overhead charts ...")
        overhead_data = _load_first_batch_overhead(variant_dir)
        if overhead_data:
            # Chart 10a: Absolute setup wait time
            fig, ax = plt.subplots(figsize=(5, 4))
            _draw_overhead_violin(ax, overhead_data, 0,
                                  ylabel="Setup wait time (seconds)")
            ax.set_title("(a) Absolute setup overhead per variant")
            fig.tight_layout()
            _save_fig(fig, results_dir, "10a_setup_overhead_absolute", pdf)

            # Chart 10b: Relative overhead fraction
            fig, ax = plt.subplots(figsize=(5, 4))
            _draw_overhead_violin(ax, overhead_data, 1,
                                  ylabel=("Setup overhead fraction\n"
                                          + (r"$\frac{\mathrm{totalTime} - \mathrm{ownExecution}}"
                                             r"{\mathrm{totalTime}}$" if USE_LATEX else
                                             "(totalTime - ownExecution) / totalTime")),
                                  ylim=(-0.05, 1.05))
            ax.set_title("(b) Relative setup overhead per variant")
            fig.tight_layout()
            _save_fig(fig, results_dir, "10b_setup_overhead_relative", pdf)

        # Chart 11: Best variant position in run order (RQ3-only — uses
        # the much larger merge set to characterise where in the run order
        # the winner typically appears).
        if rq == "rq3":
            print("Drawing best-variant index chart ...")
            fig, ax = plt.subplots(figsize=(10, 6))
            draw_best_variant_index_box(ax, all_data)
            _save_fig(fig, results_dir, "11_best_variant_index", pdf)

        # Chart 12: Build-quality distributions — human baseline vs best variant
        # (modules, tests, combined quality).
        print("Drawing build-quality distribution charts ...")
        fig = plt.figure(figsize=(13, 4.5))
        draw_quality_distributions(fig, all_data)
        _save_fig(fig, results_dir, "12_quality_distributions", pdf)

        # Charts 13a/13b: RQ3 Hamming-distance vs build-quality. Imported from
        # the standalone analysis so the figures join the combined collection.
        # Needs the ground-truth all_conflicts.csv (a sibling 'common/' dir);
        # skipped with a note if it is unavailable.
        if rq == "rq3":
            conflicts_csv = variant_dir.parent.parent / "common" / "all_conflicts.csv"
            if conflicts_csv.exists():
                try:
                    import rq3_hamming_quality_correlation as hq
                    print("Drawing Hamming-distance vs quality charts ...")
                    all_pairs, per_merge_r, pooled_r, _ = hq.compute_pairs(
                        variant_dir, conflicts_csv)
                    if all_pairs:
                        fig, ax = plt.subplots(figsize=(8, 5))
                        hq.plot_scatter(ax, all_pairs, pooled_r)
                        _save_fig(fig, results_dir, "13a_hamming_quality_scatter", pdf)
                    if per_merge_r:
                        fig, ax = plt.subplots(figsize=(8, 4))
                        hq.plot_per_merge_r(ax, per_merge_r)
                        _save_fig(fig, results_dir, "13b_hamming_per_merge_r", pdf)
                except Exception as e:
                    print(f"  (Hamming charts skipped: {e})")
            else:
                print(f"  (Hamming charts skipped: {conflicts_csv} not found)")

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

    # Thread counts in parallel modes
    import numpy as np
    lines.append(f"\nThread counts (parallel modes):")
    for mode in ["parallel", "cache_parallel"]:
        merges = all_data.get(mode, {})
        thread_vals = [m.get("threads") for m in merges.values() if m.get("threads")]
        if thread_vals:
            arr = np.array(thread_vals, dtype=float)
            lines.append(f"  {MODE_LABELS.get(mode, mode):25s}: "
                         f"mean={np.mean(arr):.1f}, median={np.median(arr):.0f}, "
                         f"std={np.std(arr):.1f}, n={len(arr)}")

    # Cache effectiveness in caching modes
    lines.append(f"\nCache effectiveness (caching modes):")
    for mode in ["cache_sequential", "cache_parallel"]:
        merges = all_data.get(mode, {})
        n_total = len(merges)
        n_with_cache = 0
        warm_ratios = []
        all_ratios = []  # includes merges where no variant used the cache
        for m in merges.values():
            variants = m.get("variants", [])
            non_baseline = [v for v in variants if v.get("variantIndex", 0) != 0]
            if not non_baseline:
                continue
            did_use = [v for v in non_baseline if v.get("hadWarmCacheReady", v.get("didUseCache", False))]
            ratio = len(did_use) / len(non_baseline)
            all_ratios.append(ratio)
            if did_use:
                n_with_cache += 1
                warm_ratios.append(ratio)
        lines.append(f"  {MODE_LABELS.get(mode, mode):25s}: "
                     f"{n_with_cache}/{n_total} merges with working cache")
        if warm_ratios:
            arr = np.array(warm_ratios)
            lines.append(f"    warm-cache variant ratio (successful merges only): "
                         f"mean={np.mean(arr):.3f}, median={np.median(arr):.3f}, "
                         f"std={np.std(arr):.3f}, n={len(arr)}"
                         f"  (includes the cache warmer itself)")
        if all_ratios:
            arr = np.array(all_ratios)
            lines.append(f"    warm-cache variant ratio (all merges):             "
                         f"mean={np.mean(arr):.3f}, median={np.median(arr):.3f}, "
                         f"std={np.std(arr):.3f}, n={len(arr)}"
                         f"  (includes the cache warmer itself)")

    # Impact
    if stats.get("impact_set"):
        n_all = len(stats["all_merges"])
        n_impact = len(stats["impact_set"])
        lines.append(f"\nImpact merges: {n_impact}/{n_all} ({100*n_impact/n_all:.0f}%)")

    # Build quality: human baseline vs best variant of each mode
    lines.append(f"\nBuild quality (per merge: human baseline vs. best variant):")

    def _mm(vals):
        a = np.array(vals, dtype=float)
        return f"mean={a.mean():6.2f}, median={np.median(a):6.2f}"

    any_quality = False
    for mode in VARIANT_MODES:
        if not all_data.get(mode):
            continue
        q = _per_merge_quality(all_data, mode)
        n = len(q["best_combined"])
        if n == 0:
            continue
        any_quality = True
        lines.append(f"  {MODE_LABELS.get(mode, mode)} (n={n} merges):")
        lines.append(f"    #modules built  : human [{_mm(q['hb_modules'])}]  "
                     f"best-variant [{_mm(q['best_modules'])}]")
        lines.append(f"    #tests passed   : human [{_mm(q['hb_tests'])}]  "
                     f"best-variant [{_mm(q['best_tests'])}]")
        # Human combined quality is exactly 1.0 by construction (it is the
        # normaliser); show it alongside the best variant for a like-for-like read.
        lines.append(f"    combined quality: human [{_mm([1.0] * n)}]  "
                     f"best-variant [{_mm(q['best_combined'])}]")
        n_ge = sum(1 for x in q["best_combined"] if x >= 1.0)
        n_gt = sum(1 for x in q["best_combined"] if x > 1.0)
        lines.append(f"    best variant matched or beat human: "
                     f"{n_ge}/{n} ({100*n_ge/n:.0f}%)")
        lines.append(f"    best variant beat human:            "
                     f"{n_gt}/{n} ({100*n_gt/n:.0f}%)")
    if not any_quality:
        lines.append("  (no scoreable merges)")

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
                eta2p = e.get("eta2p", 0.0)
                lines.append(f"  {term}: t={e['t']:.3f}, p={e['p']:.6f}, eta2p={eta2p:.4f}")
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


# Per-mode axis flags used by the sanity-check rate vote.
_MODE_AXES = {
    "no_optimization":  {"parallel": False, "cache": False},
    "cache_sequential": {"parallel": False, "cache": True},
    "parallel":         {"parallel": True,  "cache": False},
    "cache_parallel":   {"parallel": True,  "cache": True},
}
_RATE_DECIMALS = 4


def _compute_pass_rate(test_results: dict | None):
    """Return passed/run rounded to 4 dp, or None when no tests ran."""
    if not test_results:
        return None
    run = test_results.get("runNum", 0)
    if run <= 0:
        return None
    passed = (run - test_results.get("failuresNum", 0)
                  - test_results.get("errorsNum", 0)
                  - test_results.get("skippedNum", 0))
    return round(passed / run, _RATE_DECIMALS)


def _all_axis_match(modes, parallel, cache):
    """All modes match the requested parallel/cache flags (None = don't care)."""
    for m in modes:
        a = _MODE_AXES.get(m)
        if a is None:
            return False
        if parallel is not None and a["parallel"] != parallel:
            return False
        if cache is not None and a["cache"] != cache:
            return False
    return True


def _axis_blamed_side(g1, g2):
    """If a 2-vs-2 split is axis-aligned, return the more-instrumented side; else None."""
    # Parallelism axis
    if _all_axis_match(g1, True, None) and _all_axis_match(g2, False, None):
        return g1
    if _all_axis_match(g2, True, None) and _all_axis_match(g1, False, None):
        return g2
    # Cache axis
    if _all_axis_match(g1, None, True) and _all_axis_match(g2, None, False):
        return g1
    if _all_axis_match(g2, None, True) and _all_axis_match(g1, None, False):
        return g2
    return None


def _attribute_flaky(rates: dict, flaky_by_mode: dict, asymmetric: bool) -> str:
    """
    Apply the axis-aware vote to {mode -> rate}, accumulating into flaky_by_mode.
    Returns a tag describing the classification:
      "agree" | "chaos" | "minority" | "axis" | "diagonal" | "tie"

    Rules:
      - All modes agree → no credit ("agree").
      - ≥3 finished modes with all distinct rates → ignore as inherently flaky ("chaos").
        Covers 1-1-1 (3 modes) and 1-1-1-1 (4 modes); a 2-1-1 split still has a
        size-2 majority and is NOT chaos.
      - Single-minority outlier vs clear majority → minority gets +1 ("minority").
      - 4-mode 2-vs-2 axis-aligned split → blame the more-instrumented side +1 each
        when asymmetric=True ("axis"); otherwise +0.5 each across all four.
      - 4-mode 2-vs-2 diagonal split, or 2-mode disagreement → +0.5 each ("diagonal"/"tie").
    """
    groups: dict = {}
    for m, r in rates.items():
        groups.setdefault(r, []).append(m)
    if len(groups) <= 1:
        return "agree"

    if len(rates) >= 3 and len(groups) == len(rates):
        return "chaos"

    sorted_groups = sorted(groups.values(), key=len, reverse=True)
    largest = len(sorted_groups[0])
    second = len(sorted_groups[1]) if len(sorted_groups) > 1 else 0

    if len(rates) == 4 and len(groups) == 2 and largest == 2:
        g1, g2 = sorted_groups[0], sorted_groups[1]
        blamed = _axis_blamed_side(g1, g2)
        if blamed is not None and asymmetric:
            for m in blamed:
                flaky_by_mode[m] = flaky_by_mode.get(m, 0.0) + 1.0
            return "axis"
        for m in rates.keys():
            flaky_by_mode[m] = flaky_by_mode.get(m, 0.0) + 0.5
        return "diagonal"

    if largest == second:
        for m in rates.keys():
            flaky_by_mode[m] = flaky_by_mode.get(m, 0.0) + 0.5
        return "tie"

    for grp in sorted_groups[1:]:
        for m in grp:
            flaky_by_mode[m] = flaky_by_mode.get(m, 0.0) + 1.0
    return "minority"


def _cross_mode_sanity_check(all_data: dict) -> str:
    """
    Verify that identical variants produce identical build outcomes across modes.

    Compilation has zero tolerance — any cross-mode disagreement is a hard failure.
    Test outcomes are normalised to passed/run rounded to 4 d.p. and compared per
    variant position. Disagreements are attributed to specific modes via an
    axis-aware vote with a chaos exclusion (variants where ≥3 modes produce ≥3
    distinct rates are treated as inherently flaky and ignored). The pass gate
    enforces a 1% per-mode flaky budget.

    Set ``SANITY_ASYMMETRIC=false`` in the environment to switch axis splits
    from "blame the more-instrumented side" (default) to symmetric half-credit.
    """
    import os

    MAX_TEST_MISMATCH_RATE = 0.01
    asymmetric = os.environ.get("SANITY_ASYMMETRIC", "true").lower() != "false"

    mode_names = [m for m in VARIANT_MODES if all_data.get(m)]
    if len(mode_names) < 2:
        return "\n--- Cross-Mode Sanity Check: skipped (< 2 modes) ---"

    all_commits = set()
    for mode in mode_names:
        all_commits.update(all_data[mode].keys())

    variants_compared = 0
    compilation_mismatches = 0
    test_count_mismatches = 0
    flaky_by_mode = {m: 0.0 for m in mode_names}
    comp_blame_by_mode = {m: 0.0 for m in mode_names}
    comp_chaos_count = 0
    per_merge_stats: dict = {}
    per_project: dict = {}  # project -> {"compared": int, "chaos": int}

    for commit in sorted(all_commits):
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

        proj_key = project_name or "(unknown)"
        proj = per_project.setdefault(proj_key, {"compared": 0, "chaos": 0})
        mstats = {"project": project_name, "comp_mismatches": 0,
                  "test_mismatches": 0, "variants_compared": 0,
                  "chaos_count": 0}

        all_indices = set()
        for vmap in present.values():
            all_indices.update(vmap.keys())

        for idx in sorted(all_indices):
            comparable = []
            comparable_modes = []
            for mode in present:
                v = present[mode].get(idx)
                if v and not v.get("timedOut", False):
                    comparable.append(v)
                    comparable_modes.append(mode)
            if len(comparable) < 2:
                continue

            variants_compared += 1
            mstats["variants_compared"] += 1
            proj["compared"] += 1

            # Compilation: zero tolerance gate, but only on the SUCCESS-vs-FAIL
            # axis. Non-SUCCESS statuses (FAILURE, SCAN_FAILURE, etc.) are
            # collapsed to a single "FAIL" bucket — the research question is
            # "did this mode fail to build something the others built?" not "did
            # SCAN_FAILURE turn into FAILURE under different scheduling?". The
            # latter is a diagnostic shift on a merge that's broken regardless
            # of pipeline. The same collapsed statuses then feed the axis-aware
            # vote, so the headline count and per-mode blame stay consistent.
            comp_statuses = {}
            for v, mode in zip(comparable, comparable_modes):
                st = (v.get("compilationResult") or {}).get("buildStatus")
                if st:
                    comp_statuses[mode] = "SUCCESS" if st == "SUCCESS" else "FAIL"
            if len(set(comp_statuses.values())) > 1:
                compilation_mismatches += 1
                mstats["comp_mismatches"] += 1
            if len(comp_statuses) >= 2:
                ctag = _attribute_flaky(comp_statuses, comp_blame_by_mode, asymmetric)
                if ctag == "chaos":
                    comp_chaos_count += 1

            # Count-based test mismatch (informational, kept for diagnostic detail).
            ref_tr = comparable[0].get("testResults") or {}
            count_mismatch = any(
                (abs(ref_tr.get("runNum", 0)      - (v.get("testResults") or {}).get("runNum", 0)) +
                 abs(ref_tr.get("failuresNum", 0) - (v.get("testResults") or {}).get("failuresNum", 0)) +
                 abs(ref_tr.get("errorsNum", 0)   - (v.get("testResults") or {}).get("errorsNum", 0))) > 0
                for v in comparable[1:]
            )
            if count_mismatch:
                test_count_mismatches += 1
                mstats["test_mismatches"] += 1

            # Rate-based axis-aware vote.
            rates = {}
            for v, mode in zip(comparable, comparable_modes):
                r = _compute_pass_rate(v.get("testResults"))
                if r is not None:
                    rates[mode] = r
            if len(rates) >= 2:
                tag = _attribute_flaky(rates, flaky_by_mode, asymmetric)
                if tag == "chaos":
                    mstats["chaos_count"] += 1
                    proj["chaos"] += 1

        per_merge_stats[commit] = mstats

    mode_rate = {m: (c / variants_compared if variants_compared else 0.0)
                 for m, c in flaky_by_mode.items()}
    per_mode_ok = all(r <= MAX_TEST_MISMATCH_RATE for r in mode_rate.values())
    passed = compilation_mismatches == 0 and per_mode_ok

    out = []
    out.append(f"\n--- Cross-Mode Sanity Check: {'PASSED' if passed else 'FAILED'} "
               f"({'asymmetric' if asymmetric else 'symmetric'} attribution) ---")
    out.append(f"  Modes compared:             {', '.join(MODE_LABELS[m] for m in mode_names)}")
    out.append(f"  Variant positions compared: {variants_compared}")

    if compilation_mismatches == 0:
        out.append(f"  Compilation outcomes:       OK (all consistent)")
    else:
        out.append(f"  FAIL — Compilation mismatches: {compilation_mismatches}/{variants_compared} "
                   f"({100.0 * compilation_mismatches / variants_compared:.1f}%)")
        out.append(f"  Per-mode compilation blame (axis-aware vote, zero-tolerance gate):")
        for mode in mode_names:
            credit = comp_blame_by_mode[mode]
            rate = credit / variants_compared if variants_compared else 0.0
            out.append(f"    {MODE_LABELS[mode]:20s}        "
                       f"{credit:6.1f} / {variants_compared} ({rate * 100:5.2f}%)")
        if comp_chaos_count:
            out.append(f"    (compilation chaos cases ignored: {comp_chaos_count})")

    if test_count_mismatches > 0:
        out.append(f"  Test count mismatches:      "
                   f"{test_count_mismatches}/{variants_compared} positions (informational)")

    out.append(f"  Per-mode flaky budget (rate-vote, threshold {MAX_TEST_MISMATCH_RATE * 100:.0f}%):")
    for mode in mode_names:
        credit = flaky_by_mode[mode]
        rate = mode_rate[mode]
        verdict = "OK  " if rate <= MAX_TEST_MISMATCH_RATE else "FAIL"
        out.append(f"    {MODE_LABELS[mode]:20s} {verdict}  "
                   f"{credit:6.1f} / {variants_compared} ({rate * 100:5.2f}%)")

    # Per-project flakiness ranking — by chaos rate. Highly flaky projects are
    # the ones whose throughput numbers should be treated with caution.
    proj_ranked = []
    for project, s in per_project.items():
        if s["compared"] == 0 or s["chaos"] == 0:
            continue
        proj_ranked.append((project, s["chaos"], s["compared"],
                            s["chaos"] / s["compared"]))
    proj_ranked.sort(key=lambda x: (x[3], x[1]), reverse=True)
    if proj_ranked:
        out.append(f"\n  Top-flakiest projects by chaos rate (≥3 modes producing ≥3 distinct rates):")
        for project, chaos, compared, rate in proj_ranked[:10]:
            out.append(f"    {project[:42]:42s}  {rate * 100:5.1f}%  ({chaos}/{compared} variants)")
        if len(proj_ranked) > 10:
            out.append(f"    ... and {len(proj_ranked) - 10} more projects with chaos cases")

    flagged_comp = {c: s for c, s in per_merge_stats.items() if s["comp_mismatches"] > 0}
    if flagged_comp:
        out.append(f"\n  Per-merge compilation mismatches ({len(flagged_comp)} merge(s)):")
        for commit, s in sorted(flagged_comp.items(),
                                key=lambda x: x[1]["comp_mismatches"], reverse=True):
            out.append(f"    {s['project']}  {commit[:8]}  "
                       f"comp={s['comp_mismatches']}/{s['variants_compared']} variants")

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
