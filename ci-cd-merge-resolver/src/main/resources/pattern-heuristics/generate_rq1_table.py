#!/usr/bin/env python3
"""
Generate LaTeX RQ1 tables from cv_results.csv.

Writes two files next to the input CSV:
  RQ1-tab.tex     — aggregate rows weighted by merge count
  RQ1-tab-sz.tex  — aggregate rows weighted by merge count × chunk count

Usage: python3 generate_rq1_table.py <cv_results.csv> [variant_cap]
"""
import csv
import sys
import os
import math
from collections import defaultdict

csv.field_size_limit(1_000_000)

MODES         = ['RANDOM', 'GLOBAL', 'GLOBAL_UNIFORM', 'HEURISTIC', 'ML_AUTOREGRESSIVE']
DISPLAY_NAMES = ['RANDOM', 'GLOBAL', 'UNIFORM',         'HEURISTIC', 'ML-AR']
BUCKET_ORDER  = [str(i) for i in range(1, 10)] + ['10--19', '20--49', '50+']
ML_MODE       = 'ML_AUTOREGRESSIVE'


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


def build_tex(bucket_stats, agg, variant_cap, size_weighted):
    sz_suffix   = '--sz' if size_weighted else ''
    weight_note = 'size-weighted' if size_weighted else 'count-weighted'

    lines = [
        r'\begin{table*}[ht]',
        r'\centering',
        (rf'\caption{{RQ1: Pattern heuristic cross-validation '
         rf'(cap\,=\,{variant_cap} variants, {weight_note})}}'),
        rf'\label{{tab:rq1{sz_suffix}}}',
        r'\begin{tabular}{lr' + '|rr' * len(MODES) + '}',
        r'\hline',
    ]

    # Header row 1 — mode names spanning two sub-columns each
    hdr1 = r'\textbf{\#chunks} & \textbf{N}'
    for i, name in enumerate(DISPLAY_NAMES):
        sep = 'c|' if i < len(DISPLAY_NAMES) - 1 else 'c'
        hdr1 += rf' & \multicolumn{{2}}{{{sep}}}{{\textbf{{{name}}}}}'
    lines.append(hdr1 + r' \\')

    # Header row 2 — sub-column labels
    lines.append(
        r' & ' + r' & \% & \textit{att}' * len(MODES) + r' \\'
    )
    lines.append(r'\hline')

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
        row = f'{b} & {total}'
        for i in range(len(MODES)):
            row += f' & {fmt_cell(pcts[i], 1, bp[i])} & {fmt_att_cell(atts[i], ba[i])}'
        lines.append(row + r' \\')

    lines.append(r'\hline')

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
        lines.append(row + r' \\')

    lines += [r'\hline', r'\end{tabular}', r'\end{table*}']
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
        if not r.get('num_chunks'):  # skip partial/truncated rows (file written mid-run)
            continue
        n   = int(r['num_chunks'])
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
        MODES         = [m for m in MODES         if m != ML_MODE]
        DISPLAY_NAMES = DISPLAY_NAMES[:len(MODES)]

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

    # ── write both table files ───────────────────────────────────────────────
    for sw, fname in [(False, 'RQ1-tab.tex'), (True, 'RQ1-tab-sz.tex')]:
        tex = build_tex(bucket_stats, agg, variant_cap, sw)
        out = os.path.join(out_dir, fname)
        with open(out, 'w') as f:
            f.write(tex + '\n')
        print(f'Written: {out}')


if __name__ == '__main__':
    main()
