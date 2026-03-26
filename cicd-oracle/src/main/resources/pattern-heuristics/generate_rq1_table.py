#!/usr/bin/env python3
"""
Generate LaTeX RQ1 tables from cv_results.csv.

Writes two files next to the input CSV:
  RQ1-tab.tex     — aggregate rows weighted by merge count
  RQ1-tab-sz.tex  — aggregate rows weighted by merge count × chunk count

Usage: python3 generate_rq1_table.py <cv_results.csv> [variant_cap] [all_conflicts.csv]

all_conflicts.csv defaults to ../all_conflicts.csv relative to cv_results.csv.
"""
import csv
import sys
import os
import math
from collections import defaultdict

csv.field_size_limit(1_000_000)

MODES         = ['GLOBAL', 'GLOBAL_UNIFORM', 'HEURISTIC', 'ML_RF', 'ML_AUTOREGRESSIVE']
DISPLAY_NAMES = ['GLOBAL', 'UNIFORM',        'HEURISTIC', r'MESTRE\textsuperscript{\textbf{*}}', 'ML-AR']
BUCKET_ORDER  = [str(i) for i in range(1, 10)] + ['10--19', '20--49', '50+']
ML_MODE       = 'ML_AUTOREGRESSIVE'
RF_MODE       = 'ML_RF'


def bucket_of(n):
    if n <= 9:  return str(n)
    if n <= 19: return '10--19'
    if n <= 49: return '20--49'
    return '50+'


def weighted_median(values, weights):
    """Weighted median: smallest v where cumulative weight >= total/2."""
    if not values:
        return float('nan')
    pairs = sorted(zip(values, weights), key=lambda x: x[0])
    total = sum(w for _, w in pairs)
    cum = 0.0
    for v, w in pairs:
        cum += w
        if cum >= total / 2.0:
            return v
    return pairs[-1][0]


def fmt(v, decimals=1):
    return '--' if v != v else f'{v:.{decimals}f}'


def fmt_att(v):
    return '--' if v != v else str(math.ceil(v))


def bold(s):
    return rf'\textbf{{{s}}}'


def fmt_cell(v, decimals, is_best):
    s = fmt(v, decimals)
    return bold(s) if is_best else s


def fmt_att_cell(v, is_best):
    s = fmt_att(v)
    return bold(s) if is_best else s


def fmt_ceiling(v):
    """Format a ceiling percentage as gray italic."""
    s = fmt(v, 1)
    return rf'\textcolor{{gray}}{{\textit{{{s}}}}}'


def best_pct_mask(vals):
    """Bold all positions whose rounded display string matches the best."""
    displayed = [fmt(v, 1) for v in vals]
    valid = [s for s in displayed if s != '--']
    if not valid:
        return [False] * len(vals)
    mx = max(valid, key=float)
    return [s == mx for s in displayed]


def best_att_mask(vals):
    """Bold all positions whose ceil display matches the best (lowest) ceil."""
    ceiled = [math.ceil(v) if v == v else None for v in vals]
    valid = [c for c in ceiled if c is not None]
    if not valid:
        return [False] * len(vals)
    mn = min(valid)
    return [c == mn for c in ceiled]


def build_tex(bucket_stats, agg, variant_cap, size_weighted, bucket_ceiling, agg_ceiling):
    sz_suffix   = '--sz' if size_weighted else ''
    weight_note = 'size-weighted' if size_weighted else 'count-weighted'

    lines = [
        r'\begin{table*}[ht]',
        r'\centering',
        (rf'\caption{{Target resolution hit rate and mean attempts (att.)\ '
         rf'within {variant_cap} variants ({weight_note})}}'),
        rf'\label{{tab:rq1{sz_suffix}}}',
        r'\begin{tabular}{l r' + r' @{\hspace{2em}} r r' * len(MODES) + r' @{\hspace{2em}} r r}',
        r'\toprule',
    ]

    # Header row 1 — mode names spanning two sub-columns each, then PERFECT (1 col)
    hdr1 = r'\textbf{\#chunks} & \textbf{N}'
    for name in DISPLAY_NAMES:
        hdr1 += rf' & \multicolumn{{2}}{{c}}{{\textbf{{{name}}}}}'
    hdr1 += r' & \multicolumn{2}{c}{\textcolor{gray}{\textit{Reachable}}}'
    lines.append(hdr1 + r' \\')

    # Header row 2 — sub-column labels
    lines.append(
        r' & ' + r' & \% & \textit{att}' * len(MODES) + r' & \textcolor{gray}{\textit{\%}} & \\'
    )
    lines.append(r'\midrule')

    # Bucket body rows
    for b in BUCKET_ORDER:
        total = bucket_stats.get((b, 'HEURISTIC'), {}).get('total', 0)
        if total == 0:
            continue
        pcts, atts = [], []
        for m in MODES:
            s = bucket_stats[(b, m)]
            pcts.append(100.0 * s['hits'] / s['total'] if s['total'] else float('nan'))
            atts.append(s['rank_sum'] / s['rank_count'] if s['rank_count'] else float('nan'))
        bp = best_pct_mask(pcts)
        ba = best_att_mask(atts)
        row = rf'{b} & \num{{{total}}}'
        for i in range(len(MODES)):
            row += f' & {fmt_cell(pcts[i], 1, bp[i])} & {fmt_att_cell(atts[i], ba[i])}'
        row += f' & {fmt_ceiling(bucket_ceiling.get(b, float("nan")))} &'
        lines.append(row + r' \\')

    lines.append(r'\midrule')

    # Aggregate rows: Mean then Median
    sw = size_weighted
    for row_label, use_mean in [('mean', True), ('median', False)]:
        pcts, atts = [], []
        for m in MODES:
            a = agg[m]
            if use_mean:
                pcts.append(a['mean_pct_sw']    if sw else a['mean_pct'])
                atts.append(a['mean_rk_sw']     if sw else a['mean_rk'])
            else:
                pcts.append(a['median_pct_sw']  if sw else a['median_pct'])
                atts.append(a['median_rk_sw']   if sw else a['median_rk'])
        bp = best_pct_mask(pcts)
        ba = best_att_mask(atts)
        row = rf'\textbf{{{row_label}}} & '
        for i in range(len(MODES)):
            row += f' & {fmt_cell(pcts[i], 1, bp[i])} & {fmt_att_cell(atts[i], ba[i])}'
        ceiling_val = agg_ceiling['mean'] if use_mean else agg_ceiling['median']
        row += f' & {fmt_ceiling(ceiling_val)} &'
        lines.append(row + r' \\')

    lines += [r'\bottomrule', r'\end{tabular}', r'\end{table*}']
    return '\n'.join(lines)


def compute_oneshot_accuracy(traj_path, modes):
    """
    One-shot chunk accuracy from cv_trajectory.csv.
    For each (fold, merge_id, mode): attempt=0 holds num_chunks (initial distance).
    attempt=1 is recorded only when the first variant improved. If absent, distance=num_chunks.
    Returns dict: mode -> {'pooled', 'mean', 'median'} as percentages.
    """
    initial = {}  # (fold, merge_id, mode) -> num_chunks
    at_one  = {}  # (fold, merge_id, mode) -> distance at attempt 1

    with open(traj_path, newline='') as f:
        for row in csv.DictReader(f):
            try:
                attempt  = int(row['attempt'])
                distance = int(row['distance'])
            except (ValueError, TypeError):
                continue
            mode = row['mode']
            if mode not in modes:
                continue
            key = (row['fold'], row['merge_id'], mode)
            if attempt == 0:
                initial[key] = distance
            elif attempt == 1:
                at_one[key] = distance

    mode_correct = defaultdict(list)
    mode_total   = defaultdict(list)
    for key, n in initial.items():
        _, _, mode = key
        if n == 0:
            continue
        dist1   = at_one.get(key, n)
        mode_correct[mode].append(n - dist1)
        mode_total[mode].append(n)

    result = {}
    for mode in modes:
        correct = mode_correct[mode]
        total   = mode_total[mode]
        if not total:
            result[mode] = dict(pooled=float('nan'), mean=float('nan'), median=float('nan'))
            continue
        pooled     = 100.0 * sum(correct) / sum(total)
        per_merge  = sorted(c / t for c, t in zip(correct, total))
        mean_acc   = 100.0 * sum(per_merge) / len(per_merge)
        mid        = len(per_merge) // 2
        median_acc = 100.0 * (per_merge[mid] if len(per_merge) % 2
                               else (per_merge[mid - 1] + per_merge[mid]) / 2)
        result[mode] = dict(pooled=pooled, mean=mean_acc, median=median_acc)
    return result


def build_oneshot_tex(oneshot, variant_cap):
    lines = [
        r'\begin{table*}[ht]',
        r'\centering',
        r'\caption{One-shot chunk accuracy (first variant only)}',
        r'\label{tab:rq1-oneshot}',
        r'\begin{tabular}{l' + 'r' * len(MODES) + '}',
        r'\toprule',
    ]
    hdr = r'\textbf{metric}'
    for name in DISPLAY_NAMES:
        hdr += rf' & \textbf{{{name}}}'
    lines.append(hdr + r' \\')
    lines.append(r'\midrule')

    for metric_key, label in [('pooled', 'pooled'), ('mean', 'mean'), ('median', 'median')]:
        vals = [oneshot[m][metric_key] for m in MODES]
        best = [fmt(v, 1) for v in vals]
        valid = [s for s in best if s != '--']
        mx   = max(valid, key=float) if valid else None
        row  = rf'\textbf{{{label}}}'
        for v, s in zip(vals, best):
            cell = bold(s) if s == mx else s
            row += f' & {cell}'
        lines.append(row + r' \\')

    lines += [r'\bottomrule', r'\end{tabular}', r'\end{table*}']
    return '\n'.join(lines)


def main():
    csv_path    = sys.argv[1]
    variant_cap = sys.argv[2] if len(sys.argv) > 2 else '?'
    out_dir     = os.path.dirname(os.path.abspath(csv_path))

    with open(csv_path, newline='') as f:
        rows = list(csv.DictReader(f))

    # ── bucket-level stats (table body, same for both table variants) ──────
    bucket_stats = defaultdict(lambda: {
        'total': 0, 'hits': 0, 'rank_sum': 0.0, 'rank_count': 0
    })

    # ── per-merge data: uid → mode → {hit, rank, n} ─────────────────────────
    merge_data = defaultdict(dict)

    for r in rows:
        try:
            n = int(r['num_chunks'])  # skip corrupt/truncated rows
        except (ValueError, TypeError):
            continue
        b   = bucket_of(n)
        m   = r['mode']
        hit = 1 if r['min_distance'] == '0' else 0
        rk  = int(r['rank_of_closest']) if hit else None

        s = bucket_stats[(b, m)]
        s['total'] += 1
        s['hits']  += hit
        if hit:
            s['rank_sum']   += rk
            s['rank_count'] += 1

        uid = (r['fold'], r['merge_id'])
        merge_data[uid][m] = {'hit': hit, 'rank': rk, 'n': n}

    # ── drop ML_AUTOREGRESSIVE column if cv_results.csv contains no ML rows ──
    if not any(r['mode'] == ML_MODE for r in rows):
        print('Note: no ML_AUTOREGRESSIVE rows in cv_results.csv — ML-AR column omitted.')
        global MODES, DISPLAY_NAMES
        pairs = [(m, d) for m, d in zip(MODES, DISPLAY_NAMES) if m != ML_MODE]
        MODES, DISPLAY_NAMES = map(list, zip(*pairs)) if pairs else ([], [])

    # ── drop ML_RF column if cv_results.csv contains no RF rows ──────────────
    if not any(r['mode'] == RF_MODE for r in rows):
        print('Note: no ML_RF rows in cv_results.csv — RF column omitted.')
        pairs = [(m, d) for m, d in zip(MODES, DISPLAY_NAMES) if m != RF_MODE]
        MODES, DISPLAY_NAMES = map(list, zip(*pairs)) if pairs else ([], [])

    # ── theoretical ceiling: merges with no CHUNK_NONCANONICAL chunk ────────
    all_conflicts_path = (sys.argv[3] if len(sys.argv) > 3
                          else os.path.join(out_dir, '..', '..', 'common', 'all_conflicts.csv'))
    all_conflicts_path = os.path.normpath(all_conflicts_path)
    merge_has_noncanonical = set()
    if os.path.exists(all_conflicts_path):
        with open(all_conflicts_path, newline='') as f:
            for r in csv.DictReader(f):
                if r.get('y_conflictResolutionResult') == 'CHUNK_NONCANONICAL':
                    merge_has_noncanonical.add(r['merge_id'])
    else:
        print(f'Warning: {all_conflicts_path} not found — PERFECT column will be blank.')

    bucket_ceiling_acc = defaultdict(lambda: [0, 0])  # bucket -> [solvable, total]
    ceiling_hits = []
    for uid, modes_dict in merge_data.items():
        _, merge_id = uid
        n = next(iter(modes_dict.values()))['n']
        b = bucket_of(n)
        solvable = int(merge_id not in merge_has_noncanonical)
        ceiling_hits.append(solvable)
        bucket_ceiling_acc[b][0] += solvable
        bucket_ceiling_acc[b][1] += 1
    bucket_ceiling = {
        b: 100.0 * v[0] / v[1] if v[1] else float('nan')
        for b, v in bucket_ceiling_acc.items()
    }
    agg_ceiling = dict(
        mean   = 100.0 * sum(ceiling_hits) / len(ceiling_hits) if ceiling_hits else float('nan'),
        median = 100.0 * weighted_median(ceiling_hits, [1] * len(ceiling_hits)) if ceiling_hits else float('nan'),
    )

    # ── per-mode aggregate stats ────────────────────────────────────────────
    agg = {}
    for m in MODES:
        hits, chunks, ranks, chunks_of_hits = [], [], [], []
        for uid, modes in merge_data.items():
            if m not in modes:
                continue
            d = modes[m]
            hits.append(d['hit'])
            chunks.append(d['n'])
            if d['hit']:
                ranks.append(d['rank'])
                chunks_of_hits.append(d['n'])

        n_total      = len(hits)
        total_chunks = sum(chunks)

        # Count-weighted
        mean_pct   = 100.0 * sum(hits) / n_total if n_total else float('nan')
        mean_rk    = sum(ranks) / len(ranks) if ranks else float('nan')
        median_pct = (100.0 * weighted_median(hits, [1] * len(hits))
                      if hits else float('nan'))
        median_rk  = (weighted_median(ranks, [1] * len(ranks))
                      if ranks else float('nan'))

        # Size-weighted (weight = num_chunks per merge)
        mean_pct_sw = (100.0 * sum(h * c for h, c in zip(hits, chunks)) / total_chunks
                       if total_chunks else float('nan'))
        mean_rk_sw  = (sum(r * c for r, c in zip(ranks, chunks_of_hits)) / sum(chunks_of_hits)
                       if chunks_of_hits else float('nan'))
        median_pct_sw = (100.0 * weighted_median(hits, chunks)
                         if hits else float('nan'))
        median_rk_sw  = (weighted_median(ranks, chunks_of_hits)
                         if chunks_of_hits else float('nan'))

        agg[m] = dict(
            mean_pct=mean_pct,     mean_rk=mean_rk,
            median_pct=median_pct, median_rk=median_rk,
            mean_pct_sw=mean_pct_sw,     mean_rk_sw=mean_rk_sw,
            median_pct_sw=median_pct_sw, median_rk_sw=median_rk_sw,
        )

    # ── temporal sanity check: per-fold hit rate for ML-AR ──────────────────
    if ML_MODE in MODES:
        fold_hits   = defaultdict(int)
        fold_totals = defaultdict(int)
        for (fold, merge_id), modes_dict in merge_data.items():
            if ML_MODE in modes_dict:
                fold_hits[fold]   += modes_dict[ML_MODE]['hit']
                fold_totals[fold] += 1
        print('\nTemporal sanity check — ML-AR hit rate per fold (fold 0 = oldest):')
        print(f"  {'fold':>6}  {'N':>6}  {'hit%':>6}")
        for fold in sorted(fold_totals.keys(), key=lambda x: int(x)):
            n_f = fold_totals[fold]
            pct = 100.0 * fold_hits[fold] / n_f if n_f else float('nan')
            print(f"  {fold:>6}  {n_f:>6}  {fmt(pct, 1):>6}")

    # ── write both table files ───────────────────────────────────────────────
    for sw, fname in [(False, 'RQ1-tab.tex'), (True, 'RQ1-tab-sz.tex')]:
        tex = build_tex(bucket_stats, agg, variant_cap, sw, bucket_ceiling, agg_ceiling)
        out = os.path.join(out_dir, fname)
        with open(out, 'w') as f:
            f.write(tex + '\n')
        print(f'Written: {out}')

    # ── one-shot chunk accuracy ──────────────────────────────────────────────
    traj_path = os.path.join(out_dir, 'cv_trajectory.csv')
    if os.path.exists(traj_path):
        oneshot = compute_oneshot_accuracy(traj_path, MODES)
        print('\nOne-shot chunk accuracy (first variant only):')
        print(f"  {'mode':<20} {'pooled':>8} {'mean':>8} {'median':>8}")
        for m, name in zip(MODES, DISPLAY_NAMES):
            o = oneshot[m]
            print(f"  {name:<20} {fmt(o['pooled'],1):>8} {fmt(o['mean'],1):>8} {fmt(o['median'],1):>8}")
        tex = build_oneshot_tex(oneshot, variant_cap)
        out = os.path.join(out_dir, 'RQ1-oneshot.tex')
        with open(out, 'w') as f:
            f.write(tex + '\n')
        print(f'Written: {out}')


if __name__ == '__main__':
    main()
