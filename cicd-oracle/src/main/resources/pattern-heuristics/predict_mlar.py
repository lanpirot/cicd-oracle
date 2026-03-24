#!/usr/bin/env python3
"""
predict_mlar.py  --  ML-AR inference for a single merge.

Streams one JSON line per generated variant to stdout:
  {"assignment": ["OURS", "THEIRS", ...]}

The correct fold checkpoint is chosen automatically from
autoregressive_fold_assignment.json (model trained without this merge's fold).

Usage:
  python3 predict_mlar.py --merge-id <id> --num-chunks <N>
  python3 predict_mlar.py --merge-id <id> --num-chunks <N> --variants 500
  python3 predict_mlar.py --merge-id <id> --num-chunks <N> \\
      --data-dir ~/data/bruteforcemerge/rq1 \\
      --checkpoints-dir ~/data/bruteforcemerge/rq1/checkpoints
"""

import argparse
import json
import os
import random
import sys
from pathlib import Path

import torch

_SCRIPT_DIR = Path(__file__).parent


def _import_train():
    sys.path.insert(0, str(_SCRIPT_DIR))
    import train_autoregressive_model as _m
    return _m


def parse_args():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--merge-id",   required=True, help="merge_id from all_conflicts.csv")
    p.add_argument("--num-chunks", type=int, required=True, help="Number of conflict chunks")
    p.add_argument("--variants",   type=int, default=1000, help="Max variants to generate")
    p.add_argument("--temp",       type=float, default=1.0, help="Sampling temperature")
    p.add_argument("--data-dir",   default=None,
                   help="Dir containing all_conflicts.csv (default: ~/data/bruteforcemerge/rq1)")
    p.add_argument("--checkpoints-dir", default=None,
                   help="Dir with .pt checkpoints (default: data-dir/checkpoints)")
    return p.parse_args()


def main():
    args = parse_args()

    data_dir        = Path(args.data_dir) if args.data_dir \
                      else Path.home() / "data/bruteforcemerge/rq1"
    checkpoints_dir = Path(args.checkpoints_dir) if args.checkpoints_dir \
                      else data_dir / "checkpoints"

    csv_path            = data_dir / "all_conflicts.csv"
    fold_assignment_path = checkpoints_dir / "autoregressive_fold_assignment.json"

    if not csv_path.exists():
        print(f"ERROR: all_conflicts.csv not found at {csv_path}", file=sys.stderr)
        sys.exit(1)

    m = _import_train()
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    # Load all data — required for the correct global merge_time_median
    rows_by_merge, _, merge_time_median, _ = m.load_data(str(csv_path))

    if args.merge_id not in rows_by_merge:
        print(f"ERROR: merge_id={args.merge_id} not found in {csv_path}", file=sys.stderr)
        sys.exit(1)

    merge_feat, chunk_feats, _, _ = m.extract_features(
        rows_by_merge[args.merge_id], merge_time_median
    )

    # Select the checkpoint trained without this merge's fold.
    # Both conditions are hard errors: using the wrong checkpoint would silently
    # evaluate the model on data it was trained on.
    if not fold_assignment_path.exists():
        print(f"ERROR: fold assignment file not found: {fold_assignment_path}", file=sys.stderr)
        print("Run train_autoregressive_model.py first to generate it.", file=sys.stderr)
        sys.exit(1)
    with open(fold_assignment_path) as f:
        assignment = json.load(f)
    if args.merge_id not in assignment:
        print(f"ERROR: merge_id={args.merge_id!r} has no entry in {fold_assignment_path}",
              file=sys.stderr)
        print("The fold assignment file may be stale — re-run train_autoregressive_model.py.",
              file=sys.stderr)
        sys.exit(1)
    fold_k = assignment[args.merge_id]

    ckpt_path = checkpoints_dir / f"autoregressive_model_fold{fold_k}.pt"
    if not ckpt_path.exists():
        print(f"ERROR: checkpoint not found: {ckpt_path}", file=sys.stderr)
        sys.exit(1)

    model, m_mean, m_std, c_mean, c_std = m.load_model_checkpoint(str(ckpt_path), device)

    sequences = m.generate_sequences(
        model, merge_feat, chunk_feats,
        m_mean, m_std, c_mean, c_std,
        n_variants=args.variants,
        temperature=args.temp,
        device=device,
        rng=random.Random(42),
    )

    for seq in sequences:
        print(json.dumps({"assignment": seq}), flush=True)


if __name__ == "__main__":
    main()
