#!/usr/bin/env python3
"""
Plots variant experiment results comparing 4 execution modes:
  - human_baseline
  - no_optimization
  - parallel
  - cache_parallel

Graph 1: Module build success rate over relative time
Graph 2: Test success rate over relative time

X-axis: relative time (1.0 = human baseline duration)
Y-axis: success rate as % of best possible for this merge

Usage:
  python plot_results.py [variant_experiments_dir] [output_pdf]

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
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from matplotlib.backends.backend_pdf import PdfPages

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

def improvement_markers(variants: list, metric_fn, human_baseline_secs: float) -> list[tuple[float, float]]:
    """
    For a list of variant dicts, returns (relativeTime, successRate%) markers
    where relativeTime = finishedAfterFirstVariantStartSeconds / human_baseline_secs,
    keeping only those variants that strictly improve on all previous variants.
    metric_fn(variant) -> (numerator, denominator)  [e.g. modulesPassed, totalModules]
    """
    if human_baseline_secs <= 0:
        return []

    # Sort by finish time
    timed = []
    for v in variants:
        finish = get_finish_time(v)
        num, denom = metric_fn(v)
        timed.append((finish, num, denom))
    timed.sort(key=lambda x: x[0])

    markers = []
    best_rate = -1.0
    for finish, num, denom in timed:
        if denom <= 0:
            rate = 0.0
        else:
            rate = 100.0 * num / denom
        if rate > best_rate:
            best_rate = rate
            rel_t = finish / human_baseline_secs
            markers.append((rel_t, rate))
    return markers


# ── Plot data assembly ─────────────────────────────────────────────────────────

def assemble_plot_data(all_data: dict, metric_fn):
    """
    Returns dict: mode -> list of (relativeTime, successRate%) improvement markers,
    one pair per (merge, improvement-point).
    Also returns dict: mode -> list of step-function series (each a list of (x,y) for one merge).
    """
    # Find all merges with both human_baseline data and at least one variant mode
    all_commits = set(all_data["human_baseline"].keys())

    mode_markers = defaultdict(list)
    mode_steps = defaultdict(list)  # per-merge step series

    for commit in all_commits:
        baseline_merge = all_data["human_baseline"].get(commit)
        if not baseline_merge:
            continue
        hb_secs = float(baseline_merge.get("humanBaselineSeconds", 0))
        if hb_secs <= 0:
            continue

        # Compute max denominator across ALL modes for this merge (normalisation)
        all_variants_for_merge = []
        for mode in MODES:
            merge = all_data[mode].get(commit)
            if merge:
                variants = (merge.get("variantsExecution") or {}).get("variants") or []
                all_variants_for_merge.extend(variants)

        # Max denominator for this merge
        max_denom = max((metric_fn(v)[1] for v in all_variants_for_merge), default=0)
        if max_denom <= 0:
            continue

        # Normalised metric: use max_denom as common denominator
        def normalised_metric(v):
            num, _ = metric_fn(v)
            return num, max_denom

        for mode in MODES:
            merge = all_data[mode].get(commit)
            if not merge:
                continue
            variants = (merge.get("variantsExecution") or {}).get("variants") or []
            if not variants:
                continue

            if mode == "human_baseline":
                # Special: single step at t=1
                v = variants[0]  # only one variant
                num, denom = normalised_metric(v)
                rate = 100.0 * num / max_denom if max_denom > 0 else 0.0
                # Step: (0, 0), (1.0, 0), (1.0, rate)
                mode_steps[mode].append([(0.0, 0.0), (1.0, 0.0), (1.0, rate)])
                mode_markers[mode].append((1.0, rate))
            else:
                markers = improvement_markers(variants, normalised_metric, hb_secs)
                if markers:
                    mode_markers[mode].extend(markers)
                    # Build step series for this merge: prepend (0, 0) for visual clarity
                    steps = [(0.0, 0.0)] + markers
                    mode_steps[mode].append(steps)

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

    # Vertical line at t=1 (human baseline reference)
    ax.axvline(x=1.0, color="grey", linestyle="--", linewidth=0.8, alpha=0.6)

    ax.set_xlabel("Relative time  (1 = human baseline duration)", fontsize=10)
    ax.set_ylabel(ylabel, fontsize=10)
    ax.set_title(title, fontsize=12)
    ax.set_xlim(left=0)
    ax.set_ylim(0, 105)
    ax.legend(loc="lower right", fontsize=8)
    ax.grid(True, alpha=0.3)


def make_plots(variant_dir: Path, output_pdf: Path):
    print(f"Loading data from: {variant_dir}")
    all_data = load_all_data(variant_dir)

    total = sum(len(v) for v in all_data.values())
    print(f"  Loaded {total} merge records across {len(MODES)} modes")

    print("Assembling module success data ...")
    module_mode_markers, module_mode_steps = assemble_plot_data(all_data, module_stats)

    print("Assembling test success data ...")
    test_mode_markers, test_mode_steps = assemble_plot_data(all_data, test_stats)

    print(f"Writing plots to: {output_pdf}")
    with PdfPages(output_pdf) as pdf:
        # Graph 1: Module build success rate
        fig, ax = plt.subplots(figsize=(10, 6))
        draw_graph(ax, module_mode_markers, module_mode_steps,
                   title="Module Build Success Rate vs. Relative Time",
                   ylabel="Module success rate  (% of max modules for merge)")
        pdf.savefig(fig, bbox_inches="tight")
        plt.close(fig)

        # Graph 2: Test success rate
        fig, ax = plt.subplots(figsize=(10, 6))
        draw_graph(ax, test_mode_markers, test_mode_steps,
                   title="Test Success Rate vs. Relative Time",
                   ylabel="Test success rate  (% of max tests run for merge)")
        pdf.savefig(fig, bbox_inches="tight")
        plt.close(fig)

    print("Done.")


# ── Mockup data generation (mirrors test_plot_results.py) ─────────────────────

def _mockup_module_results(passed: int, total: int, rng) -> list:
    return [{"moduleName": f"module-{i}",
             "status": "SUCCESS" if i < passed else "FAILURE",
             "timeElapsed": round(0.5 + rng.random() * 0.5, 3)}
            for i in range(total)]


def _mockup_variant(name, tests_passed, modules_passed, finish_secs,
                    total_tests, total_modules, rng) -> dict:
    return {
        "variantName": name,
        "compilationResult": {
            "moduleResults": _mockup_module_results(modules_passed, total_modules, rng),
            "buildStatus":   "SUCCESS" if modules_passed > 0 else "FAILURE",
            "totalTime":     round(finish_secs, 3),
        },
        "testResults": {
            "runNum":      total_tests,
            "failuresNum": total_tests - tests_passed,
            "errorsNum":   0, "skippedNum": 0,
            "elapsedTime": round(finish_secs * 0.4, 3),
        },
        "finishedAfterFirstVariantStartSeconds": round(finish_secs, 3),
    }


def generate_mockup(output_dir: Path, output_pdf: Path):
    """
    Generate mock JSON data (one merge, 4 modes) and produce the PDF.
    Mirrors the scenario from test_plot_results.py.
    """
    import json, random, tempfile, shutil
    rng = random.Random(42)

    HUMAN_SECS    = 17
    TOTAL_MODULES = 92
    TOTAL_TESTS   = 90
    PROJECT       = "mock-project"
    COMMIT        = "deadbeef" * 5
    VARIANT_COUNTS = {"cache_parallel": 100, "parallel": 20, "no_optimization": 6}

    def write_mode(mode, variants, exec_secs):
        d = output_dir / mode
        d.mkdir(parents=True, exist_ok=True)
        data = {"projectName": PROJECT, "merges": [{
            "mergeCommit": COMMIT, "humanBaselineSeconds": HUMAN_SECS,
            "totalExecutionTime": HUMAN_SECS + exec_secs,
            "variantsExecution": {"executionTimeSeconds": exec_secs, "variants": variants},
        }]}
        (d / f"{PROJECT}.json").write_text(json.dumps(data, indent=2))

    # human_baseline
    hb = _mockup_variant("human_baseline", 77, 79, HUMAN_SECS,
                         TOTAL_TESTS, TOTAL_MODULES, rng)
    write_mode("human_baseline", [hb], HUMAN_SECS)

    # variant modes
    for mode, count in VARIANT_COUNTS.items():
        variants, min_t, max_t = [], 0.9 * HUMAN_SECS, 3.0 * HUMAN_SECS
        for i in range(count):
            variants.append(_mockup_variant(
                f"{PROJECT}_{i}",
                rng.randint(0, TOTAL_TESTS),
                rng.randint(0, TOTAL_MODULES),
                rng.uniform(min_t, max_t),
                TOTAL_TESTS, TOTAL_MODULES, rng,
            ))
        exec_secs = int(max(v["finishedAfterFirstVariantStartSeconds"] for v in variants))
        write_mode(mode, variants, exec_secs)

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
