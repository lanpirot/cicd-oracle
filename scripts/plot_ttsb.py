#!/usr/bin/env python3
r"""
Tiered Time-to-Stable-Best (TtSB): when can a practitioner stop exploring
with confidence?

For each merge x mode, simulate a tiered stopping rule and record:
  - t_stop      : wall time (relative to baseline duration) at which the
                  practitioner stops
  - q_decision  : raw quality (= (m_pass+1)*(t_pass+1)) of the best variant
                  seen by t_stop
  - regret      : 1 - q_decision / Q* in [0, 1] (how far from the per-merge
                  optimum, where Q* = max raw quality across all variants in
                  all modes for that merge)
  - censored    : True if the rule never fired before the variant budget;
                  t_stop is set to T_PUNISHMENT = 2 * T_BUDGET so a non-
                  performing mode is *worse* than one that just barely fits

The practitioner does NOT know Q_hb a priori; HB is excluded from this analysis
entirely. Only the four exploration modes are simulated.

Tiers (per variant):
  T0 (none)         : m_pass == 0
  T1 (partial)      : 0 < m_pass < m_total
  T2 (full-compile) : m_pass == m_total, not (t_pass == t_run > 0)
  T3 (perfect)      : m_pass == m_total AND t_pass == t_run > 0

In-tier improvement = strictly higher raw quality. Tier upgrade resets the
"no improvement" counter. Lower-tier arrivals are ignored (no reset, no
increment). T3 stops immediately. T0 has no active rule -- only a tier
upgrade or budget timeout breaks out of T0.

Stopping rules:
  - Variants-K : stop after K consecutive at-or-above-current-tier arrivals
                 without strict improvement, while in T1/T2.
  - Time-tau   : stop after tau relative-time units have passed since the
                 last improvement, while in T1/T2.

Headline parameters: K=5 (T1=T2=5, T0=infinity, T3=0); tau=0.5 x baseline.
Sensitivity: K in {3, 10}, tau in {0.25, 1.0}.

Outputs (single PDF):
  1. Headline scatter (t_stop, regret) per mode -- variants-K K=5
  2. Headline scatter (t_stop, regret) per mode -- time-tau tau=0.5
  3. Boxplot of t_stop (4 modes x 2 rules)
  4. Boxplot of regret-at-stop (4 modes x 2 rules)
  5. Bar chart of censoring rate (4 modes x 2 rules)
  6. K sensitivity scatter for variants-K (K = 3, 5, 10)
  7. Tau sensitivity scatter for time-tau (tau = 0.25, 0.5, 1.0)

Usage:
  python plot_ttsb.py [variant_experiments_dir] [output_pdf]
"""
from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages

import plot_results as pr
from plot_results import (
    MODES, MODE_LABELS, MODE_SHORT, MODE_COLORS, MODE_MARKERS,
    effective_baseline_secs, RELATIVE_TIME_CAP,
    module_stats, test_stats, load_all_data,
)
from plot_regret import (
    raw_quality, merge_hb_secs, q_star_for_merge,
)


DEFAULT_VARIANT_DIR = Path("/home/lanpirot/data/bruteforcemerge/variant_experiments")
DEFAULT_OUTPUT_PDF  = Path("ttsb_plots.pdf")

# Time axis (relative-to-baseline units). Variants beyond T_BUDGET are
# considered post-budget and don't contribute to the rule. Censored merges
# get t_stop = T_PUNISHMENT (= 2x budget, so they're strictly worse than
# anyone who barely made it inside the budget).
T_BUDGET     = float(RELATIVE_TIME_CAP)   # = 10x baseline
T_PUNISHMENT = 2.0 * T_BUDGET             # = 20x baseline

# Headline + sensitivity sweeps for the two stopping rules.
K_HEADLINE   = 5
K_SWEEP      = (3, 5, 10)
TAU_HEADLINE = 0.5
TAU_SWEEP    = (0.25, 0.5, 1.0)

EXPLORATION_MODES = [m for m in MODES if m != "human_baseline"]


# ── Tier classification ──────────────────────────────────────────────────────

T_NONE, T_PARTIAL, T_FULL, T_PERFECT = 0, 1, 2, 3
TIER_LABELS = {T_NONE: "none", T_PARTIAL: "partial",
               T_FULL: "full-compile", T_PERFECT: "perfect"}
ACTIVE_TIERS = (T_PARTIAL, T_FULL)  # tiers where the K/tau rule applies


def variant_tier(variant: dict) -> int:
    """Tier of a single variant in {T_NONE, T_PARTIAL, T_FULL, T_PERFECT}."""
    m_pass, m_total = module_stats(variant)
    if m_pass == 0 or m_total == 0:
        return T_NONE
    if m_pass < m_total:
        return T_PARTIAL
    t_pass, t_run = test_stats(variant)
    if t_run > 0 and t_pass == t_run:
        return T_PERFECT
    return T_FULL


# ── TtSB simulation ──────────────────────────────────────────────────────────

@dataclass
class TtSBResult:
    t_stop: float        # in relative-time units; T_PUNISHMENT iff censored
    q_decision: float    # raw quality of best variant seen by t_stop
    final_tier: int
    censored: bool


def _arrival_time_rel(variant: dict, eff_hb_secs: float) -> float | None:
    """Relative arrival time of a variant. Skip if not recorded."""
    t = variant.get("totalTimeSinceMergeStartSeconds")
    if t is None:
        return None
    return float(t) / eff_hb_secs


def simulate_ttsb(variants: list, hb_secs: float,
                  rule: str, threshold: float) -> TtSBResult:
    """Run the tiered stopping rule over one mode's variants for one merge.

    rule = "K"   : threshold = K (consecutive non-improvement arrivals)
    rule = "tau" : threshold = tau (relative-time units without improvement)
    """
    if hb_secs <= 0:
        return TtSBResult(T_PUNISHMENT, 0.0, T_NONE, True)

    eff = effective_baseline_secs(hb_secs)

    # Order arrivals strictly by recorded arrival time. Skip the human-baseline
    # variant (variantIndex == 0) and any variant without an arrival timestamp.
    timed: list[tuple[float, dict]] = []
    for v in variants:
        if v.get("variantIndex", 0) == 0:
            continue
        t_rel = _arrival_time_rel(v, eff)
        if t_rel is None:
            continue
        timed.append((t_rel, v))
    timed.sort(key=lambda x: x[0])

    current_tier        = T_NONE
    q_best              = 0.0
    k_counter           = 0     # for variants-K
    last_improvement_t  = 0.0   # for time-tau (relative time)
    t_stop: float | None = None

    for t_rel, v in timed:
        # If the variant arrives past the natural budget, abandon the loop --
        # the post-loop check will decide whether tau closed inside the budget.
        if t_rel > T_BUDGET:
            break

        # tau-rule pre-check: if no variant arrived inside the open tau window,
        # the practitioner stops *before* this variant lands.
        if rule == "tau" and current_tier in ACTIVE_TIERS:
            window_end = last_improvement_t + threshold
            if t_rel > window_end:
                t_stop = window_end
                break

        v_tier = variant_tier(v)
        v_q    = raw_quality(v)

        if v_tier < current_tier:
            # Lower-tier arrival: ignored entirely (matches "once at some-tests
            # I'd ignore non-compiling variants still").
            continue

        if v_tier > current_tier:
            current_tier        = v_tier
            q_best              = max(q_best, v_q)
            k_counter           = 0
            last_improvement_t  = t_rel
            if current_tier == T_PERFECT:
                t_stop = t_rel
                break
            continue

        # v_tier == current_tier. Does this variant strictly improve in-tier?
        if v_q > q_best:
            q_best              = v_q
            k_counter           = 0
            last_improvement_t  = t_rel
        else:
            if rule == "K" and current_tier in ACTIVE_TIERS:
                k_counter += 1
                if k_counter >= threshold:
                    t_stop = t_rel
                    break

    # Post-loop: check whether the tau window closed naturally inside budget.
    if t_stop is None and rule == "tau" and current_tier in ACTIVE_TIERS:
        window_end = last_improvement_t + threshold
        if window_end <= T_BUDGET:
            t_stop = window_end

    if t_stop is None:
        return TtSBResult(T_PUNISHMENT, q_best, current_tier, True)
    return TtSBResult(t_stop, q_best, current_tier, False)


# ── Aggregation across merges ────────────────────────────────────────────────

def collect_ttsb(all_data: dict, valid_commits: list[str],
                 rule: str, threshold: float
                 ) -> tuple[dict[str, list[TtSBResult]], dict[str, list[float]]]:
    """For each exploration mode: list of TtSBResult and parallel list of regrets.

    Both lists are indexed in valid_commits order. Q_star is computed across
    all modes including HB (so we know what was achievable a posteriori).
    """
    results = {m: [] for m in EXPLORATION_MODES}
    regrets = {m: [] for m in EXPLORATION_MODES}

    for commit in valid_commits:
        hb_secs = merge_hb_secs(commit, all_data)
        if hb_secs <= 0:
            continue
        q_star = q_star_for_merge(commit, all_data)
        if q_star <= 0:
            continue

        for mode in EXPLORATION_MODES:
            merge = all_data[mode].get(commit)
            if not merge or not merge.get("variants"):
                r = TtSBResult(T_PUNISHMENT, 0.0, T_NONE, True)
            else:
                r = simulate_ttsb(merge["variants"], hb_secs, rule, threshold)
            results[mode].append(r)
            regrets[mode].append(max(0.0, 1.0 - r.q_decision / q_star))

    return results, regrets


# ── Plotting ─────────────────────────────────────────────────────────────────

def _scatter_panel(ax, results: dict[str, list[TtSBResult]],
                   regrets: dict[str, list[float]], title: str):
    """Scatter (t_stop, regret) per mode with bigger median markers.

    Censored points cluster at x = T_PUNISHMENT. A vertical reference line
    marks the natural budget T_BUDGET; everything to its right was censored.
    """
    for mode in EXPLORATION_MODES:
        rs = results[mode]
        if not rs:
            continue
        xs = np.array([r.t_stop  for r in rs])
        ys = np.array(regrets[mode])
        color = MODE_COLORS[mode]
        ax.scatter(xs, ys, c=color, marker=MODE_MARKERS[mode], s=18,
                   alpha=0.45, edgecolors="none", zorder=2)
        # Median marker per mode (drawn on top, larger, with edge).
        med_x, med_y = float(np.median(xs)), float(np.median(ys))
        ax.scatter([med_x], [med_y], c=color, marker=MODE_MARKERS[mode],
                   s=45, edgecolors="black", linewidths=0.6,
                   label=f"{MODE_LABELS[mode]} ({MODE_SHORT[mode]}) — "
                         f"med ({med_x:.2f}, {med_y:.2f})",
                   zorder=5)

    ax.axvline(T_BUDGET, color="grey", linestyle="--", linewidth=0.8, alpha=0.6)
    ax.text(T_BUDGET + 0.1, 1.02, "budget", color="grey", fontsize=8,
            ha="left", va="bottom")
    ax.text(T_PUNISHMENT, 1.02, "censored", color="grey", fontsize=8,
            ha="right", va="bottom")
    ax.set_xlim(-0.5, T_PUNISHMENT + 1.0)
    ax.set_ylim(-0.05, 1.10)
    ax.set_xlabel(r"$t_\mathrm{stop}$ (relative to baseline; "
                  rf"censored = {T_PUNISHMENT:.0f})")
    ax.set_ylabel(r"Regret at stop  $1 - Q_\mathrm{decision}/Q^{*}$")
    ax.set_title(title)
    ax.grid(True, alpha=0.3)
    ax.legend(loc="center right", fontsize=7, framealpha=0.9)


def draw_headline_scatters(pdf, results_K, regrets_K, results_tau, regrets_tau):
    fig, axes = plt.subplots(1, 2, figsize=(13, 5.2))
    _scatter_panel(axes[0], results_K, regrets_K,
                   rf"Variants-K stopping rule, $K = {K_HEADLINE}$")
    _scatter_panel(axes[1], results_tau, regrets_tau,
                   rf"Time-$\tau$ stopping rule, $\tau = {TAU_HEADLINE}\times$ baseline")
    fig.suptitle("Headline: where does each mode land at TtSB?")
    fig.tight_layout()
    pdf.savefig(fig, bbox_inches="tight")
    plt.close(fig)


def _boxplot(ax, groups: dict[str, list[float]], ylabel: str, title: str,
             ymax: float | None = None):
    plot_modes = [m for m in EXPLORATION_MODES if groups.get(m)]
    data = [groups[m] for m in plot_modes]
    pos  = list(range(len(plot_modes)))
    bp = ax.boxplot(data, positions=pos, widths=0.55, patch_artist=True,
                    showfliers=True, flierprops=dict(markersize=3, alpha=0.5))
    for patch, m in zip(bp["boxes"], plot_modes):
        patch.set_facecolor(MODE_COLORS[m])
        patch.set_alpha(0.7)
    ax.set_xticks(pos)
    ax.set_xticklabels([f"{MODE_LABELS[m]}\n({MODE_SHORT[m]})" for m in plot_modes])
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    if ymax is not None:
        ax.set_ylim(0, ymax)
    else:
        ax.set_ylim(bottom=0)
    ax.grid(True, axis="y", alpha=0.3)


def draw_boxplots_t_stop(pdf, results_K, results_tau):
    fig, axes = plt.subplots(1, 2, figsize=(13, 4.5), sharey=True)
    _boxplot(axes[0],
             {m: [r.t_stop for r in results_K[m]] for m in EXPLORATION_MODES},
             r"$t_\mathrm{stop}$ (rel. baseline)",
             rf"Variants-K, $K = {K_HEADLINE}$",
             ymax=T_PUNISHMENT + 1.0)
    _boxplot(axes[1],
             {m: [r.t_stop for r in results_tau[m]] for m in EXPLORATION_MODES},
             "",
             rf"Time-$\tau$, $\tau = {TAU_HEADLINE}\times$",
             ymax=T_PUNISHMENT + 1.0)
    for ax in axes:
        ax.axhline(T_BUDGET, color="grey", linestyle="--", linewidth=0.8, alpha=0.6)
    fig.suptitle("Stopping time per mode (lower = stops earlier)")
    fig.tight_layout()
    pdf.savefig(fig, bbox_inches="tight")
    plt.close(fig)


def draw_boxplots_regret(pdf, regrets_K, regrets_tau):
    fig, axes = plt.subplots(1, 2, figsize=(13, 4.5), sharey=True)
    _boxplot(axes[0], regrets_K,
             "Regret at stop", rf"Variants-K, $K = {K_HEADLINE}$",
             ymax=1.05)
    _boxplot(axes[1], regrets_tau,
             "", rf"Time-$\tau$, $\tau = {TAU_HEADLINE}\times$",
             ymax=1.05)
    fig.suptitle(r"Regret at TtSB per mode (lower = closer to $Q^{*}$)")
    fig.tight_layout()
    pdf.savefig(fig, bbox_inches="tight")
    plt.close(fig)


def draw_censoring_bars(pdf, results_K, results_tau):
    fig, ax = plt.subplots(figsize=(10, 4.5))
    pos    = np.arange(len(EXPLORATION_MODES))
    width  = 0.38
    rates_K = [np.mean([r.censored for r in results_K[m]]) for m in EXPLORATION_MODES]
    rates_t = [np.mean([r.censored for r in results_tau[m]]) for m in EXPLORATION_MODES]
    b1 = ax.bar(pos - width/2, rates_K, width,
                color=[MODE_COLORS[m] for m in EXPLORATION_MODES],
                edgecolor="black", linewidth=0.6, alpha=0.85,
                label=rf"Variants-K ($K = {K_HEADLINE}$)")
    b2 = ax.bar(pos + width/2, rates_t, width,
                color=[MODE_COLORS[m] for m in EXPLORATION_MODES],
                edgecolor="black", linewidth=0.6, alpha=0.45, hatch="//",
                label=rf"Time-$\tau$ ($\tau = {TAU_HEADLINE}\times$)")
    for bars, rates in ((b1, rates_K), (b2, rates_t)):
        for bar, r in zip(bars, rates):
            ax.text(bar.get_x() + bar.get_width() / 2,
                    bar.get_height() + 0.01, f"{r:.0%}",
                    ha="center", va="bottom", fontsize=8)
    ax.set_xticks(pos)
    ax.set_xticklabels([f"{MODE_LABELS[m]}\n({MODE_SHORT[m]})" for m in EXPLORATION_MODES])
    ax.set_ylabel("Censoring rate (fraction of merges where the rule never fired)")
    ax.set_title("How often does each mode fail to reach confidence by budget?")
    ax.set_ylim(0, 1.05)
    ax.grid(True, axis="y", alpha=0.3)
    ax.legend(loc="upper right", fontsize=9)
    fig.tight_layout()
    pdf.savefig(fig, bbox_inches="tight")
    plt.close(fig)


def draw_sensitivity_scatters(pdf, all_data, valid_commits, *, rule: str,
                              sweep: tuple, label_fn):
    fig, axes = plt.subplots(1, len(sweep), figsize=(5.2 * len(sweep), 5),
                             sharey=True)
    for ax, threshold in zip(axes, sweep):
        results, regrets = collect_ttsb(all_data, valid_commits, rule, threshold)
        _scatter_panel(ax, results, regrets, label_fn(threshold))
    rule_name = "Variants-K" if rule == "K" else r"Time-$\tau$"
    fig.suptitle(f"{rule_name} sensitivity sweep")
    fig.tight_layout()
    pdf.savefig(fig, bbox_inches="tight")
    plt.close(fig)


# ── Entry point ──────────────────────────────────────────────────────────────

def _print_summary(label: str, results: dict[str, list[TtSBResult]],
                   regrets: dict[str, list[float]]):
    print(f"\n{label}", file=sys.stderr)
    header = "  {:<3}  {:>4}  {:>10}  {:>10}  {:>10}".format(
        "mode", "n", "med t_stop", "med regret", "% censored")
    print(header, file=sys.stderr)
    for mode in EXPLORATION_MODES:
        rs = results[mode]
        if not rs:
            continue
        ts  = np.array([r.t_stop for r in rs])
        rgs = np.array(regrets[mode])
        cen = np.mean([r.censored for r in rs])
        print("  {:<3}  {:>4}  {:>10.2f}  {:>10.3f}  {:>10.0%}".format(
            MODE_SHORT[mode], len(rs), np.median(ts), np.median(rgs), cen),
            file=sys.stderr)


def make_plots(variant_dir: Path, output_pdf: Path):
    print(f"Loading data from {variant_dir} ...", file=sys.stderr)
    all_data = load_all_data(variant_dir)
    valid_commits = pr._common_valid_commits(all_data, EXPLORATION_MODES)
    print(f"  Common valid merges across {len(EXPLORATION_MODES)} exploration modes: "
          f"{len(valid_commits)}", file=sys.stderr)
    if not valid_commits:
        print("No common merges -- aborting.", file=sys.stderr)
        sys.exit(1)

    results_K,   regrets_K   = collect_ttsb(all_data, valid_commits, "K",   K_HEADLINE)
    results_tau, regrets_tau = collect_ttsb(all_data, valid_commits, "tau", TAU_HEADLINE)

    _print_summary(f"Variants-K, K = {K_HEADLINE}",   results_K,   regrets_K)
    _print_summary(f"Time-tau,   tau = {TAU_HEADLINE}", results_tau, regrets_tau)

    output_pdf = Path(output_pdf)
    output_pdf.parent.mkdir(parents=True, exist_ok=True)

    with PdfPages(output_pdf) as pdf:
        draw_headline_scatters(pdf, results_K, regrets_K, results_tau, regrets_tau)
        draw_boxplots_t_stop(pdf, results_K, results_tau)
        draw_boxplots_regret(pdf, regrets_K, regrets_tau)
        draw_censoring_bars(pdf, results_K, results_tau)
        draw_sensitivity_scatters(
            pdf, all_data, valid_commits, rule="K", sweep=K_SWEEP,
            label_fn=lambda K: rf"Variants-K, $K = {K}$")
        draw_sensitivity_scatters(
            pdf, all_data, valid_commits, rule="tau", sweep=TAU_SWEEP,
            label_fn=lambda tau: rf"Time-$\tau$, $\tau = {tau:g}\times$ baseline")

    print(f"\nWrote {output_pdf}", file=sys.stderr)


if __name__ == "__main__":
    args = sys.argv[1:]
    variant_dir = Path(args[0]) if len(args) >= 1 else DEFAULT_VARIANT_DIR
    output_pdf  = Path(args[1]) if len(args) >= 2 else DEFAULT_OUTPUT_PDF
    make_plots(variant_dir, output_pdf)
