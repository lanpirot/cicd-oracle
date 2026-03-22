#!/usr/bin/env python3
"""
10-fold cross-validation fold generator for pattern heuristics (RQ1).

For each fold k (0-9):
  - Trains on 9/10 of merge_ids → learnt_historical_pattern_distribution_train<k>.csv
  - Writes evaluation ground-truth → evaluation_fold<k>.csv

Usage:
    python3 learn_cv_folds.py
    python3 learn_cv_folds.py --data-dir ~/data/bruteforcemerge/rq1 \
                               --output-dir ~/data/bruteforcemerge/rq1/cv_folds

Input:  all_conflicts.csv  (from --data-dir, defaults to script directory)
Output: learnt_historical_pattern_distribution_train{k}.csv  (10 files)
        evaluation_fold{k}.csv                                (10 files)
        Written to --output-dir (defaults to --data-dir)
"""

import argparse
import csv
import os
import random
import sys

csv.field_size_limit(1000000)

# Import the shared pipeline function from the sibling script
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from learn_historical_pattern_distribution import run_learning_pipeline


def normalize_pattern(pattern):
    """Normalize a single pattern string (same transforms as learn_historical_pattern_distribution.py)."""
    return (pattern
            .replace('CHUNK_', '')
            .replace('CANONICAL_', '')
            .replace('SEMICANONICAL_', '')
            .replace('NONCANONICAL', 'NON')
            .replace('SEMI', ''))


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))

    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data-dir",   default=script_dir,
                   help="Directory containing all_conflicts.csv (default: script dir)")
    p.add_argument("--output-dir", default=None,
                   help="Directory for output CSVs (default: --data-dir)")
    args = p.parse_args()

    data_dir   = args.data_dir
    output_dir = args.output_dir or data_dir
    os.makedirs(output_dir, exist_ok=True)

    input_file = os.path.join(data_dir, 'all_conflicts.csv')

    # Step 1: Load input data
    with open(input_file, 'r') as f:
        reader = csv.reader(f)
        original_data = list(reader)
    print(f"Loaded {len(original_data)} rows from {input_file}")

    header = original_data[0]
    col_merge_id   = header.index('merge_id')
    col_file_path  = header.index('filename') if 'filename' in header else header.index('file_path')
    col_chunk_id   = header.index('chunk_id')
    col_resolution = header.index('y_conflictResolutionResult')

    # Step 2: Collect unique merge_ids in encounter order
    merge_ids = []
    seen = set()
    for row in original_data[1:]:  # skip header
        if len(row) > col_merge_id:
            mid = row[col_merge_id]
            if mid not in seen:
                seen.add(mid)
                merge_ids.append(mid)

    print(f"Found {len(merge_ids)} unique merge_ids")

    # Step 3: Shuffle with fixed seed and split into 10 equal folds
    rng = random.Random(42)
    rng.shuffle(merge_ids)

    n = len(merge_ids)
    fold_size = n // 10
    folds = []
    for k in range(10):
        start = k * fold_size
        end = start + fold_size if k < 9 else n  # last fold absorbs remainder
        folds.append(set(merge_ids[start:end]))

    print(f"Fold sizes: {[len(f) for f in folds]}")

    all_merge_ids_set = set(merge_ids)

    for k in range(10):
        eval_ids = folds[k]
        train_ids = all_merge_ids_set - eval_ids

        print(f"\n=== Fold {k}: {len(train_ids)} train, {len(eval_ids)} eval merge_ids ===")

        # --- Training CSV ---
        train_rows = [row for row in original_data[1:] if len(row) > col_merge_id and row[col_merge_id] in train_ids]
        all_files_data_train = [header] + train_rows

        train_output = os.path.join(output_dir, f'learnt_historical_pattern_distribution_train{k}.csv')
        print(f"Running pipeline on {len(train_rows)} training rows...")
        run_learning_pipeline(all_files_data_train, train_output)
        print(f"Written: {train_output}")

        # --- Eval CSV ---
        # Group rows by merge_id, preserving all columns
        merge_rows = {}
        for row in original_data[1:]:
            if len(row) > col_resolution:
                mid = row[col_merge_id]
                if mid in eval_ids:
                    if mid not in merge_rows:
                        merge_rows[mid] = []
                    merge_rows[mid].append(row)

        eval_output = os.path.join(output_dir, f'evaluation_fold{k}.csv')
        with open(eval_output, 'w', newline='') as f:
            writer = csv.writer(f)
            writer.writerow(['merge_id', 'num_chunks', 'total_resolution_pattern'])
            # Sort merge_ids numerically for deterministic output
            for mid in sorted(merge_rows.keys(), key=lambda x: int(x)):
                rows = merge_rows[mid]
                rows_sorted = sorted(rows, key=lambda r: (r[col_file_path], int(r[col_chunk_id])))
                patterns = [normalize_pattern(r[col_resolution]) for r in rows_sorted]
                num_chunks = len(patterns)
                # Use | as intra-field separator to avoid CSV quoting ambiguity
                total_pattern = '|'.join(patterns)
                writer.writerow([mid, num_chunks, total_pattern])

        print(f"Written: {eval_output} ({len(merge_rows)} merges)")

    print("\nDone! Generated 10 fold pairs.")


if __name__ == '__main__':
    main()
