#!/usr/bin/env python3
"""
Pairwise head-to-head comparison of exploration modes by best variant quality
at end of variant budget.

For each merge, compute best (modules_passed, tests_passed) score per mode.
Comparison is lexicographic — more modules wins; ties broken by more tests.
Then for each ordered pair of modes count how often mode A's best is strictly
worse than mode B's.

Output: 4x4 matrix (rows = mode A, cells = "A strictly loses to B" count)
plus a per-pair breakdown of (A worse / A better / tied). Prints to stdout
and appends a section to <variant_dir>/results/summary.txt.

Usage:
  python head_to_head.py [variant_experiments_dir]
"""
from __future__ import annotations

import sys
from pathlib import Path

import plot_results as pr
from plot_results import (
    MODES, MODE_LABELS, MODE_SHORT,
    module_stats, test_stats, load_all_data,
)


DEFAULT_VARIANT_DIR = Path("/home/lanpirot/data/bruteforcemerge/variant_experiments")
EXPLORATION_MODES   = [m for m in MODES if m != "human_baseline"]


def best_score(merge: dict) -> tuple[int, int]:
    """Best (modules_passed, tests_passed) over non-baseline variants.

    Includes timed-out variants whose partial counts are recorded — they
    still represent information visible to a practitioner.
    Returns (0, 0) when nothing is scoreable.
    """
    best = (0, 0)
    for v in merge.get("variants", []):
        if v.get("variantIndex", 0) == 0:
            continue
        m, _ = module_stats(v)
        t, _ = test_stats(v)
        if (m, t) > best:
            best = (m, t)
    return best


def render_report(scores: dict[str, dict[str, tuple[int, int]]],
                  commits: list[str]) -> str:
    """Render the head-to-head matrix and per-pair breakdown as plain text."""
    n = len(commits)
    out: list[str] = []
    out.append("")
    out.append("=" * 78)
    out.append("Head-to-head: how often does mode A's best variant strictly lose to mode B's?")
    out.append(f"  best = max (modules_passed, tests_passed) lex; "
               f"n = {n} common-valid merges")
    out.append("=" * 78)
    out.append("")

    # 4x4 matrix: rows = A, columns = B, entry = count of merges where A < B.
    col_w  = max(len(MODE_SHORT[m]) for m in EXPLORATION_MODES) + 4
    header = "loses to ->".rjust(12) + "".join(
        MODE_SHORT[m].rjust(col_w) for m in EXPLORATION_MODES)
    out.append(header)
    for a in EXPLORATION_MODES:
        cells = [MODE_SHORT[a].rjust(12)]
        for b in EXPLORATION_MODES:
            if a == b:
                cells.append("-".rjust(col_w))
            else:
                losses = sum(1 for c in commits if scores[a][c] < scores[b][c])
                cells.append(str(losses).rjust(col_w))
        out.append("".join(cells))
    out.append("")

    # Pairwise breakdown for each unordered pair: A vs B counts on a single line.
    out.append("Pairwise breakdown (n = {n}):".format(n=n))
    out.append("  format:  A vs B  ->  A worse | A better | tied")
    out.append("")
    seen = set()
    for a in EXPLORATION_MODES:
        for b in EXPLORATION_MODES:
            if a == b or (b, a) in seen:
                continue
            seen.add((a, b))
            a_lt = sum(1 for c in commits if scores[a][c] < scores[b][c])
            a_gt = sum(1 for c in commits if scores[a][c] > scores[b][c])
            a_eq = sum(1 for c in commits if scores[a][c] == scores[b][c])
            out.append(f"  {MODE_SHORT[a]:>3} vs {MODE_SHORT[b]:<3}  ->  "
                       f"A worse = {a_lt:>3} | A better = {a_gt:>3} | tied = {a_eq:>3}")

    # Per-mode totals: how many losses, wins, and ties does each mode rack up
    # across all 3 head-to-heads with the other modes? (each mode is in 3 pairs.)
    out.append("")
    out.append("Per-mode totals across the 3 other modes (sum of pairwise tallies):")
    col_w = 8
    out.append("  mode  " + "losses".rjust(col_w) + "wins".rjust(col_w)
               + "ties".rjust(col_w) + "  (out of " + str(3 * n) + " comparisons)")
    for a in EXPLORATION_MODES:
        losses = sum(1 for b in EXPLORATION_MODES if b != a
                     for c in commits if scores[a][c] < scores[b][c])
        wins   = sum(1 for b in EXPLORATION_MODES if b != a
                     for c in commits if scores[a][c] > scores[b][c])
        ties   = sum(1 for b in EXPLORATION_MODES if b != a
                     for c in commits if scores[a][c] == scores[b][c])
        out.append(f"  {MODE_SHORT[a]:<5} " + str(losses).rjust(col_w)
                   + str(wins).rjust(col_w) + str(ties).rjust(col_w))

    # All-modes-tied count: merges where no mode differentiates from any other.
    all_tied = sum(
        1 for c in commits
        if len({scores[m][c] for m in EXPLORATION_MODES}) == 1)
    out.append("")
    out.append(f"Merges where all 4 modes produced the same best score: "
               f"{all_tied} of {n}")

    return "\n".join(out)


def main(variant_dir: Path):
    variant_dir = Path(variant_dir)
    print(f"Loading data from {variant_dir} ...", file=sys.stderr)
    all_data = load_all_data(variant_dir)
    commits = pr._common_valid_commits(all_data, EXPLORATION_MODES)
    print(f"  Common valid merges across {len(EXPLORATION_MODES)} exploration modes: "
          f"{len(commits)}", file=sys.stderr)
    if not commits:
        print("No common merges -- aborting.", file=sys.stderr)
        sys.exit(1)

    scores = {m: {c: best_score(all_data[m][c]) for c in commits}
              for m in EXPLORATION_MODES}

    report = render_report(scores, commits)
    print(report)

    summary_path = variant_dir / "results" / "summary.txt"
    if summary_path.exists():
        with open(summary_path, "a") as f:
            f.write(report + "\n")
        print(f"\nAppended to {summary_path}", file=sys.stderr)
    else:
        print(f"\n(summary.txt not found at {summary_path}; not appended)",
              file=sys.stderr)


if __name__ == "__main__":
    args = sys.argv[1:]
    variant_dir = Path(args[0]) if len(args) >= 1 else DEFAULT_VARIANT_DIR
    main(variant_dir)
