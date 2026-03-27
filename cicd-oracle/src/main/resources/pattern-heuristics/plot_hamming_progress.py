#!/usr/bin/env python3
"""
Plot "lowest Hamming distance so far" vs. number of attempts for UNIFORM, HEURISTIC, ML-AR.

Reads cv_trajectory.csv (produced by RQ1PipelineRunner) which records every
distance improvement event plus the initial state at attempt 0.

Produces two side-by-side plots saved as hamming_progress.pdf:
  Left:  Mean   lowest Hamming distance so far  ± 1 std-dev
  Right: Median lowest Hamming distance so far  + IQR band (25th–75th percentile)

Usage:
    python3 plot_hamming_progress.py [cv_trajectory.csv] [cv_results.csv]

Both files are looked up relative to this script's directory if not given.
cv_results.csv is used to determine the final (capped) distance for each merge.
ML-AR rows are read from cv_trajectory.csv / cv_results.csv like all other modes.
"""

import csv
import os
import sys
import numpy as np
import matplotlib
import matplotlib.pyplot as plt
from collections import defaultdict

COL_WIDTH_IN = 241.1475 / 72.27  # one column of a 2-column paper in inches

matplotlib.rcParams.update({
    'text.usetex':        True,
    'font.family':        'serif',
    'font.serif':         ['Computer Modern Roman'],
    'axes.labelsize':     9,
    'font.size':          9,
    'legend.fontsize':    8,
    'xtick.labelsize':    8,
    'ytick.labelsize':    8,
})

csv.field_size_limit(1_000_000)

# Modes from cv_trajectory.csv / cv_results.csv
ML_LABEL = r'\textsc{ML-Ar}'
CV_MODES = {
    'GLOBAL_UNIFORM':    r'\textsc{Uniform}',
    'HEURISTIC':         r'\textsc{Prior\#}',
    'ML_AUTOREGRESSIVE': ML_LABEL,
    'ML_RF':             r'\textsc{Mestre}',
}

COLORS  = {
    r'\textsc{Uniform}':   '#a6cee3',   # ColorBrewer Paired light blue
    r'\textsc{Prior\#}':   '#33a02c',   # ColorBrewer Paired dark green
    r'\textsc{ML-Ar}':     '#1f78b4',   # ColorBrewer Paired dark blue
    r'\textsc{Mestre}':    '#b2df8a',   # ColorBrewer Paired light green (backup slot)
}
# IQR band hatch patterns: /, \, - for the three methods
HATCHES = {
    r'\textsc{Uniform}':   '////',
    r'\textsc{Prior\#}':   '\\\\',
    r'\textsc{ML-Ar}':     '----',
    r'\textsc{Mestre}':    '....',
}


def load_trajectory(path):
    """
    Returns dict: (merge_key, mode) -> sorted list of (attempt, distance) improvement events.
    merge_key = (fold, merge_id).
    """
    traj = defaultdict(list)
    with open(path, newline='') as f:
        for row in csv.DictReader(f):
            mode = row['mode']
            if mode not in CV_MODES:
                continue
            key = (row['fold'], row['merge_id'])
            try:
                traj[(key, mode)].append((int(row['attempt']), int(row['distance'])))
            except (ValueError, TypeError):
                continue
    for k in traj:
        traj[k].sort()
    return traj


def load_final_state(results_path):
    """
    Returns dict: (merge_key, mode) -> (num_chunks, min_distance, variants_generated).
    """
    final = {}
    with open(results_path, newline='') as f:
        for row in csv.DictReader(f):
            mode = row['mode']
            if mode not in CV_MODES:
                continue
            key = (row['fold'], row['merge_id'])
            try:
                final[(key, mode)] = (
                    int(row['num_chunks']),
                    int(row['min_distance']),
                    int(row['variants_generated']),
                )
            except (ValueError, TypeError):
                continue
    return final


def best_so_far_at(events, attempt, num_chunks, min_distance, variants_generated):
    """
    Given improvement events for one merge+mode, return best distance at or before `attempt`.
    """
    best = num_chunks
    for a, d in events:
        if a <= attempt:
            best = d
        else:
            break
    return best


def build_curves(traj, final, x_values):
    """
    For each mode, build a 2-D array: rows = merges, cols = x_values.
    """
    all_labels = list(CV_MODES.values())
    curves = {label: [] for label in all_labels}

    all_keys = set()
    for (mk, _) in traj:
        all_keys.add(mk)
    for (mk, _) in final:
        all_keys.add(mk)

    raw_mode_of = {v: k for k, v in CV_MODES.items()}

    for mk in all_keys:
        for label in all_labels:
            raw_mode = raw_mode_of[label]
            key = (mk, raw_mode)
            if key not in final:
                continue
            num_chunks, min_distance, variants_generated = final[key]
            events = traj.get(key, [])

            row = [best_so_far_at(events, x, num_chunks, min_distance, variants_generated)
                   for x in x_values]
            curves[label].append(row)

    return {label: np.array(rows, dtype=float)
            for label, rows in curves.items() if rows}


def compute_center_and_bands(mat, use_mean):
    lo = np.percentile(mat, 25, axis=0)
    hi = np.percentile(mat, 75, axis=0)
    if use_mean:
        return mat.mean(axis=0), lo, hi
    else:
        return np.median(mat, axis=0), lo, hi


def _fill_hatched(ax, x, lo, hi, color, hatch):
    """Fill IQR band with light color + diagonal/horizontal hatch."""
    ax.fill_between(x, lo, hi,
                    facecolor=color, alpha=0.10,
                    hatch=hatch, edgecolor=color, linewidth=0.0)


def add_zoom_inset(ax, curves, x_values, use_mean):
    centers = {}
    for label, mat in curves.items():
        centers[label], _, _ = compute_center_and_bands(mat, use_mean)

    limit_vals = [c[-1] for c in centers.values()]
    lo_lim = min(limit_vals)
    hi_lim = max(limit_vals)
    gap    = hi_lim - lo_lim
    margin = 1.0 if gap > 0.5 else 0.25
    y_lo   = max(0.0, lo_lim - margin) + 0.2
    y_hi   = hi_lim + margin + 0.4

    x_inset_start = 1.6

    axins = ax.inset_axes([0.35, 0.45, 0.65, 0.55])

    for label, mat in curves.items():
        color  = COLORS[label]
        center, _, _ = compute_center_and_bands(mat, use_mean)
        axins.plot(x_values, center, color=color, linewidth=1.4)

    axins.set_xscale('log')
    axins.set_xlim(x_inset_start, x_values[-1])
    axins.set_ylim(y_lo, y_hi)
    axins.tick_params(labelsize=6)
    axins.set_xticklabels([])
    axins.grid(True, linestyle='--', linewidth=0.4, alpha=0.5)

    ax.indicate_inset_zoom(axins, edgecolor='0.4', linewidth=0.8)


def plot_panel(ax, curves, x_values, use_mean):
    for label, mat in curves.items():
        color  = COLORS[label]
        hatch  = HATCHES[label]
        center, lo, hi = compute_center_and_bands(mat, use_mean)
        ax.plot(x_values, center, label=label, color=color, linewidth=1.8)

    ylabel = 'Expected best Hamming distance' if use_mean else 'Median best Hamming distance'
    ax.set_xlabel('Number of attempts')
    ax.set_ylabel(ylabel)
    ax.legend(loc='lower right', ncol=2)
    ax.grid(True, linestyle='--', linewidth=0.5, alpha=0.6)
    ax.set_xscale('log')
    ax.set_xlim(left=1)
    ax.set_ylim(bottom=0)

    add_zoom_inset(ax, curves, x_values, use_mean)


def main():
    script_dir   = os.path.dirname(os.path.abspath(__file__))
    traj_path    = sys.argv[1] if len(sys.argv) > 1 else os.path.join(script_dir, 'cv_trajectory.csv')
    results_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(script_dir, 'cv_results.csv')

    print(f'Loading trajectory from {traj_path} ...')
    traj  = load_trajectory(traj_path)
    print(f'Loading final states from {results_path} ...')
    final = load_final_state(results_path)

    max_attempts = max(vg for (_, _), (_, _, vg) in final.items())
    x_values = np.arange(1, max_attempts + 1)

    print(f'Building curves over {len(x_values)} x-points (max={max_attempts}) ...')
    curves = build_curves(traj, final, x_values)

    for use_mean, fname in [(True,  'hamming_progress_mean.pdf'),
                             (False, 'hamming_progress_median.pdf')]:
        fig, ax = plt.subplots(figsize=(COL_WIDTH_IN, COL_WIDTH_IN * 0.85))
        plot_panel(ax, curves, x_values, use_mean=use_mean)
        fig.tight_layout()
        out_path = os.path.join(os.path.dirname(os.path.abspath(traj_path)), fname)
        fig.savefig(out_path, bbox_inches='tight', pad_inches=0)
        plt.close(fig)
        print(f'Written: {out_path}')


if __name__ == '__main__':
    main()
