#!/usr/bin/env python3
"""
Regret curves: how close to the per-merge optimum each mode gets, over wall time.

For each merge:
  - Q*  = max raw quality across all variants in all modes for that merge
        = max (modules_passed + 1) * (tests_passed + 1)
        (Laplace-smoothed product; same shape as plot_results.combined_quality
        but unnormalised by the human baseline)
  - Q_best(t, mode) = max raw quality among variants in this mode whose result
                     became known by wall time t
  - regret(t, mode) = 1 - Q_best(t, mode) / Q*  in [0, 1]

Outputs (single PDF, one figure per page):
  1. Median regret curve per mode over relative wall time, with IQR band.
  2. Regret CDF at fixed time budgets (1x, 5x, 10x baseline).
  3. Per-mode AUC of the regret curve up to T_MAX (boxplot, lower = better).

Variants whose buildStatus is TIMEOUT but produced partial module/test counts
are credited for those partial results — a practitioner who watched a build time
out has still seen information about what compiled before the kill.

Usage:
  python plot_regret.py [variant_experiments_dir] [output_pdf] [impact_threshold]

Defaults:
  variant_experiments_dir = /home/lanpirot/data/bruteforcemerge/variant_experiments
  output_pdf              = regret_plots.pdf
  impact_threshold        = 1.25   (Q* / Q_hb >= 1.25 counts as "impact")
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages

# Reuse constants and data-loading helpers from the main plotting script.
import plot_results as pr
from plot_results import (
    MODES, MODE_LABELS, MODE_SHORT, MODE_COLORS, MODE_MARKERS,
    effective_baseline_secs, RELATIVE_TIME_CAP,
    module_stats, test_stats, load_all_data,
)


DEFAULT_VARIANT_DIR      = Path("/home/lanpirot/data/bruteforcemerge/variant_experiments")
DEFAULT_OUTPUT_PDF       = Path("regret_plots.pdf")
DEFAULT_IMPACT_THRESHOLD = 1.25  # Q* / Q_hb ratio that counts as "exploration found something better"

# Grid for evaluating the per-merge regret step function. 0.05 step is fine
# enough to render smooth medians without making the array large.
TIME_GRID_STEP = 0.05
TIME_GRID = np.arange(0.0, RELATIVE_TIME_CAP + TIME_GRID_STEP / 2, TIME_GRID_STEP)

# Time budgets (multiples of effective baseline duration) for the CDF chart.
CDF_BUDGETS = (1.0, 5.0, 10.0)

# AUC integration upper limit. Equal to the rightmost x of the curve chart.
T_MAX = RELATIVE_TIME_CAP



# ── Quality, finish-time, regret-curve construction ──────────────────────────

def raw_quality(variant: dict) -> float:
    """Raw combined quality of a variant: (modules_passed + 1) * (tests_passed + 1).

    Laplace-smoothed product; matches the shape of plot_results.combined_quality
    but unnormalised by the human baseline. TIMEOUT variants that produced
    partial module/test counts get those counts (module_stats / test_stats
    handle that already).
    """
    m_pass, _ = module_stats(variant)
    t_pass, _ = test_stats(variant)
    return float((m_pass + 1) * (t_pass + 1))


def variant_finish_seconds(variant: dict, hb_secs: float) -> float | None:
    """Wall-clock seconds since merge start at which this variant's result
    became known.

    For variantIndex==0 (the human baseline) the JSON has
    totalTimeSinceMergeStartSeconds=null, so fall back to hb_secs (the budget
    basis, which is the baseline build duration the JVM measured before
    exploration). For exploration variants, fall back to compilationResult
    .totalTime as a last resort if totalTimeSinceMergeStartSeconds is missing.
    """
    t = variant.get("totalTimeSinceMergeStartSeconds")
    if t is not None:
        return float(t)
    if variant.get("variantIndex", 0) == 0:
        return float(hb_secs) if hb_secs > 0 else None
    cr = variant.get("compilationResult")
    if cr is not None:
        own = cr.get("totalTime")
        if own is not None:
            return float(own)
    return None


def merge_hb_secs(commit: str, all_data: dict) -> float:
    """Read budgetBasisSeconds for this merge — same value across modes."""
    for m in MODES:
        merge = all_data[m].get(commit)
        if merge is None:
            continue
        b = float(merge.get("budgetBasisSeconds", 0))
        if b > 0:
            return b
    return 0.0


def q_star_for_merge(commit: str, all_data: dict) -> float:
    """Max raw quality across all variants in all modes for this merge.

    Includes the human-baseline variant (so a merge whose optimum is the human's
    own resolution gets Q* = Q_hb). Returns 0.0 if no variant exists at all.
    """
    q = 0.0
    seen = False
    for mode in MODES:
        merge = all_data[mode].get(commit)
        if not merge:
            continue
        for v in merge.get("variants", []):
            seen = True
            q = max(q, raw_quality(v))
    return q if seen else 0.0


def q_hb_for_merge(commit: str, all_data: dict) -> float:
    """Quality of the human-baseline variant (variantIndex == 0). 0.0 if missing."""
    bm = all_data.get("human_baseline", {}).get(commit)
    if not bm:
        return 0.0
    for v in bm.get("variants", []):
        if v.get("variantIndex", 0) == 0:
            return raw_quality(v)
    return 0.0


def regret_curve(variants: list, hb_secs: float, q_star: float
                 ) -> list[tuple[float, float]]:
    """Per-merge regret step function for one mode: list of (t_relative, regret).

    Starts at (0.0, 1.0) and drops each time a new variant arrives with
    strictly higher raw quality than the running best.
    """
    if hb_secs <= 0 or q_star <= 0:
        return [(0.0, 1.0)]
    eff = effective_baseline_secs(hb_secs)
    timed: list[tuple[float, float]] = []
    for v in variants:
        t = variant_finish_seconds(v, hb_secs)
        if t is None:
            continue
        timed.append((t, raw_quality(v)))
    timed.sort(key=lambda x: x[0])

    points: list[tuple[float, float]] = [(0.0, 1.0)]
    q_best = 0.0
    for finish_t, q in timed:
        if q > q_best:
            q_best = q
            t_rel = finish_t / eff
            points.append((t_rel, max(0.0, 1.0 - q_best / q_star)))
    return points


def auc_exact(points: list[tuple[float, float]], t_max: float) -> float:
    """Exact integral of the post-step regret function over [0, t_max].

    Trapezoidal integration on a fixed grid produces a systematic
    underestimate at each step (e.g. a single step from 1.0 to 0.0 at
    t=1.0 integrates to 0.975 instead of 1.0 with a 0.05 grid). This
    routine integrates the piecewise-constant function exactly: between
    consecutive points (t_i, r_i) and (t_{i+1}, r_{i+1}), regret is r_i.
    """
    if not points:
        return 0.0
    auc = 0.0
    for i in range(len(points)):
        t1, r1 = points[i]
        if t1 >= t_max:
            break
        t2 = points[i + 1][0] if i + 1 < len(points) else t_max
        t2 = min(t2, t_max)
        if t2 > t1:
            auc += r1 * (t2 - t1)
    return auc


def regret_at_grid(points: list[tuple[float, float]], grid: np.ndarray) -> np.ndarray:
    """Vectorised step-function evaluation at every grid point.

    Step semantics: regret(t) = the most recent point's regret with t' <= t.
    Uses np.searchsorted over the points' time axis for O(n_points + n_grid).
    """
    px = np.array([p[0] for p in points])
    py = np.array([p[1] for p in points])
    # idx_i = number of points with px <= grid[i]; subtract 1 for 0-indexed access.
    idx = np.searchsorted(px, grid, side="right") - 1
    idx = np.clip(idx, 0, len(py) - 1)
    return py[idx]


# ── Aggregation across merges ────────────────────────────────────────────────

def collect_per_mode_regret(all_data: dict, valid_commits: list[str],
                            impact_threshold: float = DEFAULT_IMPACT_THRESHOLD,
                            ) -> tuple[dict[str, np.ndarray], dict[str, np.ndarray],
                                       np.ndarray, np.ndarray]:
    """For each mode, build:
      - regret_grid[mode]  : ndarray (n_merges, len(TIME_GRID))
      - auc[mode]          : ndarray (n_merges,) with the merge's AUC over [0, T_MAX]
      - any_impact         : ndarray (n_merges,) bool — True iff Q* > Q_hb at all
      - strong_impact      : ndarray (n_merges,) bool — True iff Q*/Q_hb >= threshold
                             (or Q_hb == 0 with Q* > 0)
    Entries missing for a mode are NaN so np.nanmedian / np.nanpercentile work.
    """
    n = len(valid_commits)
    grids = {m: np.full((n, len(TIME_GRID)), np.nan) for m in MODES}
    aucs  = {m: np.full(n, np.nan) for m in MODES}
    any_impact    = np.zeros(n, dtype=bool)
    strong_impact = np.zeros(n, dtype=bool)

    for i, commit in enumerate(valid_commits):
        hb_secs = merge_hb_secs(commit, all_data)
        if hb_secs <= 0:
            continue
        q_star = q_star_for_merge(commit, all_data)
        if q_star <= 0:
            continue
        q_hb = q_hb_for_merge(commit, all_data)
        any_impact[i] = q_star > q_hb + 1e-9
        if q_hb <= 0:
            strong_impact[i] = q_star > 0
        else:
            strong_impact[i] = (q_star / q_hb) >= impact_threshold

        for mode in MODES:
            merge = all_data[mode].get(commit)
            if not merge:
                continue
            variants = merge.get("variants") or []
            if not variants:
                continue
            points = regret_curve(variants, hb_secs, q_star)
            row = regret_at_grid(points, TIME_GRID)
            grids[mode][i] = row
            aucs[mode][i]  = auc_exact(points, T_MAX)

    return grids, aucs, any_impact, strong_impact


# ── Plotting ─────────────────────────────────────────────────────────────────

def draw_median_regret(ax, grids: dict[str, np.ndarray], n_merges: int):
    """Page 1: median regret with IQR band per mode."""
    for mode in MODES:
        g = grids[mode]
        if np.all(np.isnan(g)):
            continue
        med = np.nanmedian(g, axis=0)
        q1  = np.nanpercentile(g, 25, axis=0)
        q3  = np.nanpercentile(g, 75, axis=0)
        color = MODE_COLORS[mode]
        ax.fill_between(TIME_GRID, q1, q3, color=color, alpha=0.12, zorder=2)
        ax.plot(TIME_GRID, med,
                color=color, marker=MODE_MARKERS[mode], markersize=4,
                markevery=max(1, len(TIME_GRID) // 20), linewidth=1.6,
                label=f"{MODE_LABELS[mode]} ({MODE_SHORT[mode]})", zorder=3)

    ax.axvline(1.0, color="grey", linestyle="--", linewidth=0.8, alpha=0.6)
    ax.set_xlabel(r"Relative time (1.0 = human baseline build duration)")
    ax.set_ylabel(r"Regret  $1 - Q_{\mathrm{best}}(t)\,/\,Q^{*}$")
    ax.set_title(rf"Median regret across {n_merges} merges (IQR band)")
    ax.set_xlim(0.0, T_MAX + 0.2)
    ax.set_ylim(0.0, 1.05)
    ax.grid(True, alpha=0.3)
    ax.legend(loc="upper right", fontsize=8)


def draw_regret_cdf(axes, grids: dict[str, np.ndarray]):
    """Page 2: regret CDF at fixed time budgets, one subplot per budget."""
    eps_grid = np.linspace(0.0, 1.0, 201)
    for ax, T in zip(axes, CDF_BUDGETS):
        ti = int(np.argmin(np.abs(TIME_GRID - T)))
        for mode in MODES:
            col = grids[mode][:, ti]
            col = col[~np.isnan(col)]
            if col.size == 0:
                continue
            cdf = np.array([(col <= e).mean() for e in eps_grid])
            ax.plot(eps_grid, cdf, color=MODE_COLORS[mode], linewidth=1.6,
                    marker=MODE_MARKERS[mode],
                    markevery=max(1, len(eps_grid) // 12), markersize=4,
                    label=f"{MODE_LABELS[mode]} ({MODE_SHORT[mode]})")
        ax.set_title(rf"At $t = {T:g}\times$ baseline")
        ax.set_xlabel(r"Regret threshold $\varepsilon$")
        ax.set_xlim(0.0, 1.0)
        ax.set_ylim(0.0, 1.05)
        ax.grid(True, alpha=0.3)
    axes[0].set_ylabel(r"$P(\mathrm{regret} \le \varepsilon)$")
    axes[-1].legend(loc="lower right", fontsize=8)


def draw_auc_boxplot(ax, aucs: dict[str, np.ndarray]):
    """Page 3: per-mode AUC distribution, boxplot. Lower = found best earlier
    and/or kept finding good variants throughout."""
    plot_modes = [m for m in MODES if not np.all(np.isnan(aucs[m]))]
    data = [aucs[m][~np.isnan(aucs[m])] for m in plot_modes]
    positions = list(range(len(plot_modes)))

    bp = ax.boxplot(data, positions=positions, widths=0.55,
                    patch_artist=True, showfliers=True,
                    flierprops=dict(markersize=3, alpha=0.5))
    for patch, m in zip(bp["boxes"], plot_modes):
        patch.set_facecolor(MODE_COLORS[m])
        patch.set_alpha(0.7)

    ax.set_xticks(positions)
    ax.set_xticklabels([f"{MODE_LABELS[m]}\n({MODE_SHORT[m]}, n={len(d)})"
                        for m, d in zip(plot_modes, data)])
    ax.set_ylabel(rf"AUC of regret curve over $[0, {T_MAX:g}]$ rel.\ baseline")
    ax.set_title("Cumulative regret per merge (lower = better)")
    ax.set_ylim(bottom=0)
    ax.grid(True, axis="y", alpha=0.3)
    # Reference: AUC = 0 means "found Q* immediately"; AUC = T_MAX means "never
    # found anything within the chart's time budget."
    ax.axhline(T_MAX, color="grey", linestyle="--", linewidth=0.8, alpha=0.4)


# ── Entry point ──────────────────────────────────────────────────────────────

def make_plots(variant_dir: Path, output_pdf: Path,
               impact_threshold: float = DEFAULT_IMPACT_THRESHOLD):
    print(f"Loading data from {variant_dir} ...", file=sys.stderr)
    all_data = load_all_data(variant_dir)
    counts = {MODE_SHORT[m]: len(all_data.get(m, {})) for m in MODES}
    print(f"  Per-mode merge counts: {counts}", file=sys.stderr)

    # Validity gate uses the 4 exploration modes — human_baseline never has a
    # non-baseline scoreable variant, so including it would empty the set.
    variant_modes = [m for m in MODES if m != "human_baseline"]
    valid_commits = pr._common_valid_commits(all_data, variant_modes)
    print(f"  Common valid merges across {len(variant_modes)} exploration modes: "
          f"{len(valid_commits)}", file=sys.stderr)
    if not valid_commits:
        print("No common merges — aborting.", file=sys.stderr)
        sys.exit(1)

    grids, aucs, any_impact, strong_impact = collect_per_mode_regret(
        all_data, valid_commits, impact_threshold)

    n_any    = int(any_impact.sum())
    n_strong = int(strong_impact.sum())
    print(f"  Any-impact merges (Q* > Q_hb)      : {n_any} of {len(valid_commits)}",
          file=sys.stderr)
    print(f"  Strong-impact merges (Q*/Q_hb >= {impact_threshold:.2f}): "
          f"{n_strong} of {len(valid_commits)}", file=sys.stderr)

    def _summary(label: str, mask: np.ndarray):
        print(f"\n{label} (n={int(mask.sum())}):", file=sys.stderr)
        for mode in MODES:
            col = aucs[mode][mask]
            col = col[~np.isnan(col)]
            if col.size == 0:
                continue
            print(f"  {MODE_SHORT[mode]:<3}  n={col.size:>3}  "
                  f"median={np.median(col):.3f}  "
                  f"q1={np.percentile(col, 25):.3f}  "
                  f"q3={np.percentile(col, 75):.3f}",
                  file=sys.stderr)

    all_mask = np.ones(len(valid_commits), dtype=bool)
    _summary("AUC summary — ALL merges", all_mask)
    if n_any > 0:
        _summary("AUC summary — ANY-IMPACT merges", any_impact)
    if n_strong > 0:
        _summary("AUC summary — STRONG-IMPACT merges", strong_impact)

    output_pdf = Path(output_pdf)
    output_pdf.parent.mkdir(parents=True, exist_ok=True)

    def _slice_grids(mask: np.ndarray) -> dict[str, np.ndarray]:
        return {m: grids[m][mask] for m in MODES}

    def _slice_aucs(mask: np.ndarray) -> dict[str, np.ndarray]:
        return {m: aucs[m][mask] for m in MODES}

    def _add_section(pdf, mask: np.ndarray, label: str):
        n = int(mask.sum())
        if n == 0:
            return
        g = _slice_grids(mask)
        a = _slice_aucs(mask)

        fig, ax = plt.subplots(figsize=(8, 5))
        draw_median_regret(ax, g, n)
        ax.set_title(rf"Median regret across {n} {label} (IQR band)")
        fig.tight_layout()
        pdf.savefig(fig, bbox_inches="tight")
        plt.close(fig)

        fig, axes = plt.subplots(1, len(CDF_BUDGETS),
                                 figsize=(4.5 * len(CDF_BUDGETS), 4),
                                 sharey=True)
        draw_regret_cdf(axes, g)
        fig.suptitle(rf"Regret CDF at fixed time budgets — {label} (n={n})")
        fig.tight_layout()
        pdf.savefig(fig, bbox_inches="tight")
        plt.close(fig)

        fig, ax = plt.subplots(figsize=(8, 4.5))
        draw_auc_boxplot(ax, a)
        ax.set_title(rf"Cumulative regret per merge — {label} (n={n}, lower = better)")
        fig.tight_layout()
        pdf.savefig(fig, bbox_inches="tight")
        plt.close(fig)

    with PdfPages(output_pdf) as pdf:
        _add_section(pdf, all_mask, "all merges")
        if n_any > 0:
            _add_section(pdf, any_impact, r"any-impact merges ($Q^{*} > Q_{\mathrm{hb}}$)")
        if n_strong > 0:
            _add_section(
                pdf, strong_impact,
                rf"strong-impact merges ($Q^{{*}}/Q_{{\mathrm{{hb}}}} \ge {impact_threshold:.2f}$)")

    print(f"\nWrote {output_pdf}", file=sys.stderr)


if __name__ == "__main__":
    args = sys.argv[1:]
    variant_dir = Path(args[0]) if len(args) >= 1 else DEFAULT_VARIANT_DIR
    output_pdf  = Path(args[1]) if len(args) >= 2 else DEFAULT_OUTPUT_PDF
    threshold   = float(args[2]) if len(args) >= 3 else DEFAULT_IMPACT_THRESHOLD
    make_plots(variant_dir, output_pdf, threshold)
