#!/usr/bin/env python3
"""
Unified presentation layer for variant experiment results.
Compares 4 execution modes: human_baseline, no_optimization, parallel, cache_parallel.

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
from pathlib import Path
from collections import defaultdict

import matplotlib
import matplotlib.ticker
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.backends.backend_pdf import PdfPages

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

MODES = ["human_baseline", "no_optimization", "parallel", "cache_parallel"]
MODE_LABELS = {
    "human_baseline":  "Human Baseline",
    "no_optimization": "No Optimisation",
    "parallel":        "Parallel Only",
    "cache_parallel":  "Cache + Parallel",
}
MODE_COLORS = {
    "human_baseline":  "#2c7bb6",
    "no_optimization": "#d7191c",
    "parallel":        "#1a9641",
    "cache_parallel":  "#ff7f00",
}
MODE_MARKERS = {
    "human_baseline":  "D",
    "no_optimization": "o",
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
    A merge is 'impact' if at least one non-human-baseline variant has a
    different score than the human baseline.  Mirrors Java filterImpactMerges.
    """
    if merge.get("numConflictChunks", 0) == 0:
        return False
    variants = (merge.get("variantsExecution") or {}).get("variants", [])
    baseline_score = None
    for v in variants:
        if v.get("variantName") == "human_baseline":
            baseline_score = variant_score(v)
            break
    if baseline_score is None:
        return False
    for v in variants:
        if v.get("variantName") == "human_baseline":
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
    """Load all JSON files for a mode. Returns dict: mergeCommit -> merge_json_dict."""
    mode_dir = variant_dir / mode
    if not mode_dir.exists():
        return {}
    merges = {}
    for json_file in mode_dir.glob("*.json"):
        try:
            with open(json_file) as f:
                data = json.load(f)
            for merge in data.get("merges", []):
                mc = merge.get("mergeCommit")
                if mc:
                    merges[mc] = merge
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
    module_results = cr.get("moduleResults", [])
    if module_results:
        total = len(module_results)
        passed = sum(1 for m in module_results if m.get("status") == "SUCCESS")
        return passed, total
    else:
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
    """Returns finishedAfterFirstVariantStartSeconds, falling back to compilationResult.totalTime."""
    t = variant.get("finishedAfterFirstVariantStartSeconds")
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
    where relativeTime  = finishedAfterFirstVariantStartSeconds / human_baseline_secs
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
        hb_secs = float(baseline_merge.get("humanBaselineSeconds", 0))
        if hb_secs <= 0:
            continue

        # Human baseline score = numerator of the single human_baseline variant
        hb_variants = (baseline_merge.get("variantsExecution") or {}).get("variants") or []
        if not hb_variants:
            continue
        hb_num, _ = metric_fn(hb_variants[0])
        if hb_num <= 0:
            continue  # can't normalise against zero

        for mode in MODES:
            merge = all_data[mode].get(commit)
            if not merge:
                continue
            variants = (merge.get("variantsExecution") or {}).get("variants") or []
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
            attempted = merge.get("numVariantsAttempted")
            if attempted is None:
                variants = (merge.get("variantsExecution") or {}).get("variants", [])
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
    ax.set_xticklabels([MODE_LABELS[m] for m in plot_modes])

    def _log10_fmt(y, _):
        exp = int(round(y))
        return (rf"$10^{{{exp}}}$" if USE_LATEX else f"1e{exp}")

    ax.yaxis.set_major_locator(matplotlib.ticker.MaxNLocator(integer=True))
    ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(_log10_fmt))

    # n= label above each violin
    y_max = max(max(v) for v in data)
    y_span = y_max - min(min(v) for v in data)
    for i, (vals, mode) in enumerate(zip(data, plot_modes)):
        n_lbl = rf"$n={len(vals)}$" if USE_LATEX else f"n={len(vals)}"
        ax.text(i, y_max + 0.04 * y_span, n_lbl,
                ha="center", va="bottom", fontsize=8)

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


def make_plots(variant_dir: Path, output_pdf: Path):
    print(f"Loading data from: {variant_dir}")
    all_data = load_all_data(variant_dir)

    total = sum(len(v) for v in all_data.values())
    print(f"  Loaded {total} merge records across {len(MODES)} modes")

    print("Assembling module success data ...")
    module_mode_markers, module_mode_steps = assemble_plot_data(all_data, module_stats)

    print("Assembling test success data ...")
    test_mode_markers, test_mode_steps = assemble_plot_data(all_data, test_stats)

    print("Computing impact statistics ...")
    stats = compute_statistics(all_data)

    print(f"Writing plots to: {output_pdf}")
    with PdfPages(output_pdf) as pdf:
        # Chart 1: Module build success rate vs. relative time
        fig, ax = plt.subplots(figsize=(10, 6))
        draw_graph(ax, module_mode_markers, module_mode_steps,
                   title="Module Build Success Rate vs.\ Relative Time",
                   ylabel=r"Modules built (relative to human baseline $= 1.0$)")
        pdf.savefig(fig, bbox_inches="tight")
        plt.close(fig)

        # Chart 2: Test success rate vs. relative time
        fig, ax = plt.subplots(figsize=(10, 6))
        draw_graph(ax, test_mode_markers, test_mode_steps,
                   title="Test Success Rate vs.\ Relative Time",
                   ylabel=r"Tests passed (relative to human baseline $= 1.0$)")
        pdf.savefig(fig, bbox_inches="tight")
        plt.close(fig)

        # Chart 3: Java conflict file ratio vs. impact rate
        if stats["all_merges"]:
            fig, ax = plt.subplots(figsize=(8, 5))
            draw_java_ratio_chart(ax, stats["all_merges"], stats["impact_set"])
            pdf.savefig(fig, bbox_inches="tight")
            plt.close(fig)

        # Chart 4: Search space coverage (fraction of p^c explored per merge)
        fig, ax = plt.subplots(figsize=(9, 6))
        draw_search_space_coverage(ax, all_data)
        pdf.savefig(fig, bbox_inches="tight")
        plt.close(fig)

    print("Done.")


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

_MOCKUP_VARIANT_COUNTS = {"cache_parallel": 100, "parallel": 20, "no_optimization": 6}


def _mockup_module_results(passed: int, total: int, rng) -> list:
    return [{"moduleName": f"module-{i}",
             "status": "SUCCESS" if i < passed else "FAILURE",
             "timeElapsed": round(0.3 + rng.random() * 0.4, 2)}
            for i in range(total)]


def _mockup_variant(name, tests_passed, modules_passed, finish_secs,
                    total_tests, total_modules, rng) -> dict:
    return {
        "variantName": name,
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
        "finishedAfterFirstVariantStartSeconds": round(finish_secs, 2),
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

        def merge_dict(exec_secs, variants):
            return {
                "mergeCommit":           commit,
                "humanBaselineSeconds":  human_secs,
                "totalExecutionTime":    human_secs + exec_secs,
                "numConflictFiles":      num_conflict_files,
                "numJavaConflictFiles":  num_java_conflict_files,
                "numConflictChunks":     num_conflict_chunks,
                "numVariantsAttempted":  len(variants),
                "variantsExecution":     {"executionTimeSeconds": exec_secs,
                                          "variants": variants},
            }

        # human_baseline: one perfect-resolution variant per merge
        hb = _mockup_variant("human_baseline", human_tests, human_modules,
                             human_secs, total_tests, total_modules, rng)
        mode_merges["human_baseline"].append(merge_dict(human_secs, [hb]))

        # variant modes
        for mode, count in _MOCKUP_VARIANT_COUNTS.items():
            variants, cum = [], 0.0
            for i in range(count):
                tp, mp   = _plausible_results(total_tests, total_modules,
                                               human_tests, human_modules, rng)
                finish, cum = _plausible_finish(mode, human_secs, cum, rng)
                variants.append(_mockup_variant(f"{PROJECT}_{i}", tp, mp, finish,
                                                total_tests, total_modules, rng))
            exec_secs = round(max(v["finishedAfterFirstVariantStartSeconds"]
                                  for v in variants))
            mode_merges[mode].append(merge_dict(exec_secs, variants))

    # Write one JSON file per mode
    for mode, merges in mode_merges.items():
        d = output_dir / mode
        d.mkdir(parents=True, exist_ok=True)
        (d / f"{PROJECT}.json").write_text(
            json.dumps({"projectName": PROJECT, "merges": merges}, indent=2))

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
        variant_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_VARIANT_DIR
        output_pdf  = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_OUTPUT_PDF
        make_plots(variant_dir, output_pdf)
