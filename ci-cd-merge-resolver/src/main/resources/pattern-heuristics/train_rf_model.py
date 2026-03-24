#!/usr/bin/env python3
"""
Train per-chunk Random Forest model for merge-conflict pattern prediction (RQ1).

For each 10-fold CV split (chronological, reusing autoregressive_fold_assignment.json):
  - Features: same 40-column MERGE_COLS + CHUNK_COLS as train_autoregressive_model.py
  - Model: RandomForestClassifier(n_estimators=400, min_samples_leaf=1,
           max_features=0.3, max_depth=None, class_weight=None, n_jobs=-1, random_state=42+k)
  - NON rows excluded from training; all eval rows get predictions
  - Inference: 3-phase variant generation (greedy, sampling, permutation expansion)
  - Output: rf_predictions_fold{k}.csv (same merge_id,sequence format as ML-AR)

Usage:
    python3 train_rf_model.py [--data-dir DIR] [--cv-folds-dir DIR]
                               [--predictions-dir DIR] [--checkpoints-dir DIR]
                               [--variants N] [--folds K] [--max-rows N]
"""

import argparse
import csv
import itertools
import json
import math
import os
import random as py_random
import sys
from collections import defaultdict
from datetime import date

import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.preprocessing import StandardScaler

csv.field_size_limit(1_000_000)

# ---------------------------------------------------------------------------
# Label vocabulary (must match train_autoregressive_model.py)
# ---------------------------------------------------------------------------
ALL_16 = [
    "OURS", "THEIRS", "BASE", "EMPTY",
    "OURSTHEIRS", "THEIRSOURS", "OURSBASE", "BASEOURS",
    "THEIRSBASE", "BASETHEIRS",
    "OURSTHEIRSBASE", "OURSBASETHEIRS", "THEIRSOURSBASE",
    "THEIRSBASEOURS", "BASEOURSTHEIRS", "BASETHEIRSOURS",
]
L2I = {lbl: i for i, lbl in enumerate(ALL_16)}
I2L = {i: lbl for lbl, i in L2I.items()}
N_LABELS = len(ALL_16)   # 16

NON_TOKEN = "NON"

_TRIPLE = ["OURSTHEIRSBASE", "OURSBASETHEIRS", "THEIRSOURSBASE",
           "THEIRSBASEOURS", "BASEOURSTHEIRS", "BASETHEIRSOURS"]
_PERMS: dict[str, list[str]] = {
    "OURS":           ["OURS"],
    "THEIRS":         ["THEIRS"],
    "BASE":           ["BASE"],
    "EMPTY":          ["EMPTY"],
    "OURSTHEIRS":     ["OURSTHEIRS", "THEIRSOURS"],
    "THEIRSOURS":     ["OURSTHEIRS", "THEIRSOURS"],
    "OURSBASE":       ["OURSBASE", "BASEOURS"],
    "BASEOURS":       ["OURSBASE", "BASEOURS"],
    "THEIRSBASE":     ["THEIRSBASE", "BASETHEIRS"],
    "BASETHEIRS":     ["THEIRSBASE", "BASETHEIRS"],
    **{lbl: _TRIPLE for lbl in _TRIPLE},
}

# ---------------------------------------------------------------------------
# Feature columns (must match train_autoregressive_model.py)
# ---------------------------------------------------------------------------
LOG1P_MERGE = {
    "mergeAuthorContribution",
    "mergeRangeOursAuthorsCount", "mergeRangeTheirsAuthorsCount",
    "mergeRangeCommitCountOurs", "mergeRangeCommitCountTheirs",
    "changedFilesOURS", "changedFilesTHEIRS", "filesConflictingMerged",
    "filesCleanMerged", "fileCount",
    "num_chunks_in_merge",
}
LOG1P_CHUNK = {
    "lengthChunk", "lengthOURS", "lengthTHEIRS",
    "cyclomaticComplexityOURS", "cyclomaticComplexityTHEIRS", "cyclomaticComplexityFile",
    "lineCountUnmergedFile",
    "num_chunks_in_file", "chunkRankInFile",
    "lengthContextBefore", "lengthContextAfter",
}

MERGE_COLS = [
    "mergeAuthorContribution",
    "authorsBothBranchesMergeRange",
    "authorsOnlyOneBranchMergeRange",
    "authorsMultipleContributorsMergeRange",
    "keyword_fix", "keyword_bug", "keyword_feature", "keyword_improv",
    "keyword_document", "keyword_refactor", "keyword_updat", "keyword_add",
    "keyword_remov", "keyword_us", "keyword_delet", "keyword_chang",
    "mergeRangeOursAuthorsCount",
    "mergeRangeTheirsAuthorsCount",
    "mergeRangeCommitCountOurs",
    "mergeRangeCommitCountTheirs",
    "changedFilesOURS", "changedFilesTHEIRS",
    "filesConflictingMerged",
    "filesCleanMerged",
    "fileCount",
    "branchDivergenceDays",
    "conclusionDelay",
    "mergeRangeDurationDays",
    "is_maven",
    "merge_time_days",
    "num_chunks_in_merge",
]
CHUNK_COLS = [
    "selfConflict",
    "cyclomaticComplexityOURS", "cyclomaticComplexityTHEIRS", "cyclomaticComplexityFile",
    "chunkPositionQuarter",
    "lengthContextBefore",
    "lengthContextAfter",
    "lengthChunk",
    "lengthRelativeOURSTHEIRS",
    "lengthOURS",
    "lengthTHEIRS",
    "lengthRelativeOURS",
    "lengthRelativeTHEIRS",
    "lineCountUnmergedFile",
    "file_lex_rank",
    "num_chunks_in_file",
    "chunkRankInFile",
]

EPOCH_ORIGIN = date(1970, 1, 1)


def _safe_float(v, default=0.0):
    try:
        return float(v) if v else default
    except (ValueError, TypeError):
        return default


def _log1p_if(col, cols_set, val):
    return math.log1p(max(val, 0.0)) if col in cols_set else val


def normalize_label(raw: str) -> str:
    return (raw
            .replace("CHUNK_", "")
            .replace("CANONICAL_", "")
            .replace("SEMICANONICAL_", "")
            .replace("NONCANONICAL", "NON")
            .replace("SEMI", ""))


# ---------------------------------------------------------------------------
# Data loading
# ---------------------------------------------------------------------------
def load_data(csv_path: str, max_rows: int = 0):
    """Load all_conflicts.csv into per-merge row lists."""
    rows_by_merge: dict[str, list[dict]] = defaultdict(list)
    merge_ids_in_order: list[str] = []
    seen_mids: set[str] = set()
    merge_times: list[float] = []

    with open(csv_path, newline="") as f:
        reader = csv.DictReader(f)
        for i, row in enumerate(reader):
            if max_rows > 0 and i >= max_rows:
                break
            mid = row["merge_id"]
            if mid not in seen_mids:
                seen_mids.add(mid)
                merge_ids_in_order.append(mid)
            rows_by_merge[mid].append(row)
            try:
                d = date.fromisoformat(row["commitTime"].strip().split(" ")[0])
                merge_times.append(float((d - EPOCH_ORIGIN).days))
            except Exception:
                pass

    merge_time_median = float(np.median(merge_times)) if merge_times else 0.0

    for mid in rows_by_merge:
        rows_by_merge[mid].sort(key=lambda r: (r.get("filename", ""),
                                               int(r.get("chunk_id", 0))))
    return rows_by_merge, merge_ids_in_order, merge_time_median


def build_feature_row(row: dict, merge_feat: list[float]) -> list[float]:
    """Return a single flat feature vector: merge_feat + chunk_feats."""
    cv = []
    for col in CHUNK_COLS:
        v = _safe_float(row.get(col, 0))
        cv.append(_log1p_if(col, LOG1P_CHUNK, v))
    return merge_feat + cv


def build_merge_feat(rows: list[dict], merge_time_median: float) -> list[float]:
    """Build the 31-dim merge-level feature vector."""
    r0 = rows[0]
    try:
        d = date.fromisoformat(r0.get("commitTime", "").strip().split(" ")[0])
        mt = float((d - EPOCH_ORIGIN).days)
    except Exception:
        mt = merge_time_median
    is_maven = 1.0 if r0.get("is_maven", "False") == "True" else 0.0
    n_chunks_merge = float(len(rows))

    vals = []
    for col in MERGE_COLS:
        if col == "merge_time_days":
            vals.append(mt)
        elif col == "is_maven":
            vals.append(is_maven)
        elif col == "num_chunks_in_merge":
            vals.append(_log1p_if(col, LOG1P_MERGE, n_chunks_merge))
        else:
            v = _safe_float(r0.get(col, 0))
            vals.append(_log1p_if(col, LOG1P_MERGE, v))
    return vals


# ---------------------------------------------------------------------------
# Variant generation (3-phase, mirrors generate_sequences from AR model)
# ---------------------------------------------------------------------------
_INF_BATCH = 256
_TEMP_MAX  = 8.0
_TEMP_BUMP = 1.5


def generate_rf_sequences(proba: np.ndarray, n_variants: int,
                           temperature: float, rng: py_random.Random) -> list[list[str]]:
    """
    Generate variant sequences from per-chunk probability matrix.

    proba: shape (N_chunks, 16) — independent per-chunk class probabilities
    Returns list of variant assignments (each a list of N_chunks pattern strings).
    """
    T = proba.shape[0]
    if T == 0:
        return []

    seen: set[str] = set()
    unique_seqs: list[list[str]] = []

    def _greedy(exclude_per_chunk: list[int] | None = None) -> list[str]:
        seq = []
        for i in range(T):
            p = proba[i].copy()
            if exclude_per_chunk is not None:
                p[exclude_per_chunk[i]] = 0.0
            total = p.sum()
            if total <= 0:
                p = np.ones(N_LABELS) / N_LABELS
            seq.append(I2L[int(p.argmax())])
        return seq

    def _sample(temp: float) -> list[str]:
        seq = []
        for i in range(T):
            p = proba[i].copy()
            if temp != 1.0:
                p = p ** (1.0 / temp)
            total = p.sum()
            if total <= 0:
                p = np.ones(N_LABELS) / N_LABELS
            else:
                p = p / total
            seq.append(I2L[rng.choices(range(N_LABELS), weights=p.tolist(), k=1)[0]])
        return seq

    # Phase 0: two greedy passes
    pass1 = _greedy()
    pass1_indices = [L2I[lbl] for lbl in pass1]
    for exclude in (None, pass1_indices):
        s = _greedy(exclude)
        k = "|".join(s)
        if k not in seen:
            seen.add(k)
            unique_seqs.append(s)

    # Phase 1: stochastic sampling with dedup + adaptive temperature
    cur_temp = temperature
    remaining = n_variants - len(unique_seqs)
    while remaining > 0:
        bs = min(remaining, _INF_BATCH)
        new_count = 0
        for _ in range(bs):
            s = _sample(cur_temp)
            k = "|".join(s)
            if k not in seen:
                seen.add(k)
                unique_seqs.append(s)
                new_count += 1
        if new_count < bs // 2 and cur_temp < _TEMP_MAX:
            cur_temp = min(cur_temp * _TEMP_BUMP, _TEMP_MAX)
        remaining -= bs

    # Phase 2: permutation expansion for short sequences
    for seq in list(unique_seqs):
        if len(seq) > 3:
            continue
        choices = [_PERMS[lbl] for lbl in seq]
        if all(len(c) == 1 for c in choices):
            continue
        for combo in itertools.product(*choices):
            k = "|".join(combo)
            if k not in seen:
                seen.add(k)
                unique_seqs.append(list(combo))

    return unique_seqs


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def parse_args():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data-dir",        type=str, default=None)
    p.add_argument("--cv-folds-dir",    type=str, default=None)
    p.add_argument("--checkpoints-dir", type=str, default=None)
    p.add_argument("--predictions-dir", type=str, default=None)
    p.add_argument("--variants",        type=int, default=1000)
    p.add_argument("--folds",           type=str, default="0,1,2,3,4,5,6,7,8,9")
    p.add_argument("--max-rows",        type=int, default=0,
                   help="Load only first N CSV rows (0=all). For pipeline tests.")
    return p.parse_args()


def main():
    args = parse_args()

    # ── Resolve paths ──────────────────────────────────────────────────────
    script_dir = os.path.dirname(os.path.abspath(__file__))
    data_dir   = args.data_dir or os.path.join(os.path.expanduser("~"),
                                                "data", "bruteforcemerge", "rq1")
    cv_folds_dir    = args.cv_folds_dir    or os.path.join(data_dir, "cv_folds")
    checkpoints_dir = args.checkpoints_dir or os.path.join(data_dir, "checkpoints")
    predictions_dir = args.predictions_dir or os.path.join(data_dir, "predictions")
    os.makedirs(predictions_dir, exist_ok=True)

    csv_path = os.path.join(data_dir, "all_conflicts.csv")
    fold_assignment_path = os.path.join(checkpoints_dir, "autoregressive_fold_assignment.json")

    if not os.path.exists(csv_path):
        print(f"ERROR: {csv_path} not found.", file=sys.stderr)
        sys.exit(1)
    if not os.path.exists(fold_assignment_path):
        print(f"ERROR: {fold_assignment_path} not found. "
              f"Run train_autoregressive_model.py (step 4) first.", file=sys.stderr)
        sys.exit(1)

    target_folds = [int(f) for f in args.folds.split(",")]

    # ── Load data ─────────────────────────────────────────────────────────
    print(f"Loading data from {csv_path} …", flush=True)
    rows_by_merge, merge_ids_in_order, merge_time_median = load_data(
        csv_path, max_rows=args.max_rows
    )
    print(f"  {len(merge_ids_in_order):,} unique merges", flush=True)

    # ── Load fold assignment ───────────────────────────────────────────────
    with open(fold_assignment_path) as f:
        fold_assignment: dict[str, int] = json.load(f)
    print(f"  Fold assignment loaded ({len(fold_assignment):,} merges)", flush=True)

    # Group merge IDs by fold
    fold_to_mids: dict[int, list[str]] = defaultdict(list)
    for mid in merge_ids_in_order:
        k = fold_assignment.get(mid)
        if k is not None:
            fold_to_mids[k].append(mid)

    # ── Pre-compute per-merge features ────────────────────────────────────
    print("Pre-computing features …", flush=True)
    merge_feats: dict[str, list[float]] = {}
    file_chunk_counts_cache: dict[str, dict[str, int]] = {}

    for mid in merge_ids_in_order:
        rows = rows_by_merge[mid]
        mf = build_merge_feat(rows, merge_time_median)
        merge_feats[mid] = mf

        fc: dict[str, int] = {}
        for row in rows:
            fp = row.get("filename", "")
            fc[fp] = fc.get(fp, 0) + 1
        file_chunk_counts_cache[mid] = fc

    def get_chunk_feat(row: dict, mid: str) -> list[float]:
        fc = file_chunk_counts_cache[mid]
        cv = []
        for col in CHUNK_COLS:
            if col == "num_chunks_in_file":
                v = float(fc.get(row.get("filename", ""), 1))
                cv.append(_log1p_if(col, LOG1P_CHUNK, v))
            else:
                v = _safe_float(row.get(col, 0))
                cv.append(_log1p_if(col, LOG1P_CHUNK, v))
        return cv

    print("  Done.", flush=True)

    # ── CV loop ────────────────────────────────────────────────────────────
    for fold in target_folds:
        out_path = os.path.join(predictions_dir, f"rf_predictions_fold{fold}.csv")
        if os.path.exists(out_path):
            print(f"Fold {fold}: predictions already exist, skipping.", flush=True)
            continue

        print(f"\nFold {fold} / 10 …", flush=True)
        eval_mids = set(fold_to_mids.get(fold, []))
        train_mids = [mid for mid in merge_ids_in_order if mid not in eval_mids]

        # ── Build training matrix ─────────────────────────────────────────
        X_train: list[list[float]] = []
        y_train: list[int] = []
        for mid in train_mids:
            mf = merge_feats[mid]
            for row in rows_by_merge[mid]:
                lbl = normalize_label(row.get("y_conflictResolutionResult", ""))
                if lbl == NON_TOKEN or lbl not in L2I:
                    continue   # exclude NON from training
                y_train.append(L2I[lbl])
                X_train.append(mf + get_chunk_feat(row, mid))

        if not X_train:
            print(f"  WARNING: no training rows for fold {fold}, skipping.", flush=True)
            continue

        X_tr = np.array(X_train, dtype=np.float32)
        y_tr = np.array(y_train, dtype=np.int32)
        print(f"  Training on {len(X_tr):,} chunks from {len(train_mids):,} merges …",
              flush=True)

        scaler = StandardScaler()
        X_tr_scaled = scaler.fit_transform(X_tr)

        rf = RandomForestClassifier(
            n_estimators=400,
            min_samples_leaf=1,
            max_features=0.3,
            max_depth=None,
            class_weight=None,
            n_jobs=-1,
            random_state=42 + fold,
        )
        rf.fit(X_tr_scaled, y_tr)
        print(f"  Training done. Classes: {list(rf.classes_)[:5]}…", flush=True)

        # Map RF class indices back to ALL_16 indices
        # rf.classes_ may not cover all 16 labels if some are absent in training
        rf_class_to_l2i = {cls: int(cls) for cls in rf.classes_}

        # ── Inference for eval merges ─────────────────────────────────────
        eval_mid_list = [m for m in merge_ids_in_order if m in eval_mids]
        rng = py_random.Random(42 + fold)

        print(f"  Generating predictions for {len(eval_mid_list):,} eval merges …",
              flush=True)

        with open(out_path, "w", newline="") as f:
            f.write("merge_id,sequence\n")
            for mid in eval_mid_list:
                rows = rows_by_merge[mid]
                mf = merge_feats[mid]
                X_eval = np.array([mf + get_chunk_feat(row, mid) for row in rows],
                                  dtype=np.float32)
                X_eval_scaled = scaler.transform(X_eval)

                # proba: (N_chunks, len(rf.classes_)) → map to full (N_chunks, 16)
                raw_proba = rf.predict_proba(X_eval_scaled)
                proba = np.zeros((len(rows), N_LABELS), dtype=np.float64)
                for col_idx, cls in enumerate(rf.classes_):
                    label_idx = rf_class_to_l2i[cls]
                    proba[:, label_idx] = raw_proba[:, col_idx]

                variants = generate_rf_sequences(proba, args.variants, 1.0, rng)
                for seq in variants:
                    f.write(f"{mid},{'|'.join(seq)}\n")

        print(f"  Written: {out_path}", flush=True)

    print("\nDone.", flush=True)


if __name__ == "__main__":
    main()
