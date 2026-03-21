#!/usr/bin/env -S /home/lanpirot/projects/merge++/bruteforce/ci-cd-merge-resolver/.venv/bin/python3
"""
Train & evaluate an autoregressive transformer model for merge-conflict
pattern prediction (RQ1).

Improvements over v1:
  - Train on ALL rows (not Maven-only) — is_maven is a feature, not a filter
  - Positional encoding (sinusoidal) on chunk step embeddings
  - NON (CHUNK_NONCANONICAL) masked from loss instead of replaced with noise
  - Log1p transform on skewed count/size features before standardisation
  - Bucket sampler: batches contain similarly-lengthed sequences, less padding
  - Canonical ordering at inference (no random compound-pattern shuffling)
  - Always-on deduplication + adaptive temperature at inference
  - Compound-pattern permutation expansion at inference
  - --max-rows for quick pipeline tests

Usage:
    cd src/main/resources/pattern-heuristics/
    python3 train_autoregressive_model.py          # full run
    python3 train_autoregressive_model.py --max-rows 1000 --epochs 3  # test
"""

import argparse
import csv
import itertools
import math
import os
import random
import sys
from collections import defaultdict
from datetime import date

import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import DataLoader, Dataset, Sampler

csv.field_size_limit(1_000_000)

# ---------------------------------------------------------------------------
# Label vocabulary
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

# Permutation siblings for compound patterns (unordered-set equivalents)
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

# Special token indices (all beyond the 16 output labels)
NON_IN_IDX  = N_LABELS      # 16 — input-only embedding for "prev label was NON"
BOS_IDX     = N_LABELS + 1  # 17 — beginning-of-sequence
PAD_IDX     = N_LABELS + 2  # 18 — sequence padding (loss-masked)
NON_TGT_IDX = N_LABELS + 3  # 19 — target sentinel for NON positions; replaced with GLOBAL sample during training
N_EMBED     = N_LABELS + 4  # 20 — total embeddings in label_embed

NON_TOKEN  = "NON"

def normalize_label(raw: str) -> str:
    return (raw
            .replace("CHUNK_", "")
            .replace("CANONICAL_", "")
            .replace("SEMICANONICAL_", "")
            .replace("NONCANONICAL", "NON")
            .replace("SEMI", ""))

# ---------------------------------------------------------------------------
# Feature columns
# ---------------------------------------------------------------------------
# Features that are heavily right-skewed → apply log1p before standardisation
LOG1P_MERGE = {
    "locTentativeFile", "authorsCountOURS", "authorsCountTHEIRS",
    "commitCountOURS", "commitCountTHEIRS",
    "changedFilesOURS", "changedFilesTHEIRS", "concurrentlyChangedFiles",
    "locAddedOURS", "locAddedTHEIRS", "locRemovedOURS", "locRemovedTHEIRS",
    "num_chunks_in_merge",
}
LOG1P_CHUNK = {
    "chunkAbsSize", "chunkAbsSizeOURS", "chunkAbsSizeTHEIRS",
    "cyclomaticComplexityOURS", "cyclomaticComplexityTHEIRS", "cyclomaticComplexityFile",
    "num_chunks_in_file",
}

MERGE_COLS = [
    "authorContribution", "commonDevelopers", "differentDevelopers",
    "multipleAuthors", "developersIntersection_code",
    "keywords_fix", "keywords_bug", "keywords_feature", "keywords_improve",
    "keywords_document", "keywords_refactor", "keywords_update", "keywords_add",
    "keywords_remove", "keywords_use", "keywords_delete", "keywords_change",
    "locTentativeFile", "authorsCountOURS", "authorsCountTHEIRS",
    "commitCountOURS", "commitCountTHEIRS",
    "changedFilesOURS", "changedFilesTHEIRS", "concurrentlyChangedFiles",
    "locAddedOURS", "locAddedTHEIRS", "locRemovedOURS", "locRemovedTHEIRS",
    "contributionDurationDays", "conclusionDelayDays", "mergeDurationDays",
    "is_maven",              # 0/1 feature (not a filter)
    "merge_time_days",       # engineered
    "devInt_ALL",            # one-hot from developersIntersection
    "num_chunks_in_merge",   # computed: total conflict chunks in this merge
]
CHUNK_COLS = [
    "selfConflict",
    "cyclomaticComplexityOURS", "cyclomaticComplexityTHEIRS", "cyclomaticComplexityFile",
    "chunkPositionQuarter",
    "chunkAbsSize", "chunkRelSize",
    "chunkAbsSizeOURS", "chunkAbsSizeTHEIRS",
    "chunkRelSizeOURS", "chunkRelSizeTHEIRS",
    "file_lex_rank",
    "num_chunks_in_file",    # computed: conflict chunks sharing this file
]
MERGE_DIM = len(MERGE_COLS)
CHUNK_DIM = len(CHUNK_COLS)

EPOCH_ORIGIN = date(1970, 1, 1)

def _safe_float(v, default=0.0):
    try:
        return float(v) if v else default
    except (ValueError, TypeError):
        return default

def _log1p_if(col, cols_set, val):
    return math.log1p(max(val, 0.0)) if col in cols_set else val

def parse_merge_time(s: str, fallback: float) -> float:
    try:
        return float((date.fromisoformat(s.strip().split(" ")[0]) - EPOCH_ORIGIN).days)
    except Exception:
        return fallback

def extract_features(rows: list[dict], merge_time_median: float):
    """
    Returns:
      merge_feat:   np.float32 (MERGE_DIM,)
      chunk_feats:  np.float32 (T, CHUNK_DIM)
      input_labels: list[int]  — L2I or NON_IN_IDX (for prev_label embedding input)
      target_labels:list[int]  — L2I or PAD_IDX (NON masked from loss)
    """
    r0 = rows[0]
    mt = parse_merge_time(r0.get("merge_time", ""), merge_time_median)
    dev_int_all = 1.0 if r0.get("developersIntersection", "") == "ALL" else 0.0
    is_maven    = 1.0 if r0.get("is_maven", "False") == "True" else 0.0

    # Computed features (not present as CSV columns)
    n_chunks_merge = float(len(rows))
    file_chunk_counts: dict[str, int] = {}
    for row in rows:
        fp = row.get("file_path", "")
        file_chunk_counts[fp] = file_chunk_counts.get(fp, 0) + 1

    merge_vals = []
    for col in MERGE_COLS:
        if col == "merge_time_days":
            merge_vals.append(mt)
        elif col == "devInt_ALL":
            merge_vals.append(dev_int_all)
        elif col == "is_maven":
            merge_vals.append(is_maven)
        elif col == "num_chunks_in_merge":
            merge_vals.append(_log1p_if(col, LOG1P_MERGE, n_chunks_merge))
        else:
            v = _safe_float(r0.get(col, 0))
            merge_vals.append(_log1p_if(col, LOG1P_MERGE, v))

    chunk_list, in_labels, tgt_labels = [], [], []
    for row in rows:
        cv = []
        for col in CHUNK_COLS:
            if col == "num_chunks_in_file":
                v = float(file_chunk_counts.get(row.get("file_path", ""), 1))
                cv.append(_log1p_if(col, LOG1P_CHUNK, v))
            else:
                v = _safe_float(row.get(col, 0))
                cv.append(_log1p_if(col, LOG1P_CHUNK, v))
        chunk_list.append(cv)

        lbl = normalize_label(row.get("y_conflictResolutionResult", ""))
        if lbl == NON_TOKEN or lbl not in L2I:
            in_labels.append(NON_IN_IDX)
            tgt_labels.append(NON_TGT_IDX)  # replaced with GLOBAL sample during training; fails eval
        else:
            idx = L2I[lbl]
            in_labels.append(idx)
            tgt_labels.append(idx)

    return (
        np.array(merge_vals,  dtype=np.float32),
        np.array(chunk_list,  dtype=np.float32),
        in_labels,
        tgt_labels,
    )

# ---------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------
def _sinusoidal_pe(max_len: int, d_model: int, device) -> torch.Tensor:
    """Precompute sinusoidal positional encoding: (max_len, d_model)."""
    pe  = torch.zeros(max_len, d_model, device=device)
    pos = torch.arange(max_len, device=device).unsqueeze(1).float()
    div = torch.exp(torch.arange(0, d_model, 2, device=device).float()
                    * (-math.log(10000.0) / d_model))
    pe[:, 0::2] = torch.sin(pos * div)
    pe[:, 1::2] = torch.cos(pos * div)[:, :d_model // 2]
    return pe


class AutoregressiveModel(nn.Module):
    def __init__(self, merge_dim: int, chunk_dim: int,
                 d_model: int = 128, nhead: int = 4, num_layers: int = 2,
                 dropout: float = 0.1, max_seq: int = 210, n_embed: int = N_EMBED):
        super().__init__()
        self.d_model = d_model

        self.merge_encoder = nn.Sequential(
            nn.Linear(merge_dim, d_model * 2),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(d_model * 2, d_model),
        )

        label_emb_dim = d_model // 4
        self.label_embed = nn.Embedding(n_embed, label_emb_dim, padding_idx=PAD_IDX)
        self.chunk_proj   = nn.Linear(chunk_dim, d_model - label_emb_dim)

        # Positional encoding (position = chunk index within the merge sequence)
        self.register_buffer("pe", _sinusoidal_pe(max_seq, d_model, torch.device("cpu")))

        enc_layer = nn.TransformerEncoderLayer(
            d_model=d_model, nhead=nhead,
            dim_feedforward=d_model * 4,
            dropout=dropout, batch_first=True,
        )
        self.transformer = nn.TransformerEncoder(enc_layer, num_layers)
        self.out_head = nn.Linear(d_model, N_LABELS)

    def encode_step(self, prev_labels, chunk_feats):
        """prev_labels: (B,T) long; chunk_feats: (B,T,chunk_dim) → (B,T,d_model)"""
        lbl = self.label_embed(prev_labels)   # (B, T, label_emb_dim)
        ch  = self.chunk_proj(chunk_feats)     # (B, T, d_model - label_emb_dim)
        return torch.cat([lbl, ch], dim=-1)    # (B, T, d_model)

    def forward(self, merge_feats, prev_labels, chunk_feats, src_key_padding_mask=None):
        """
        merge_feats:          (B, merge_dim)
        prev_labels:          (B, T)  — BOS at step 0, then input_labels (shifted right)
        chunk_feats:          (B, T, chunk_dim)
        src_key_padding_mask: (B, T+1) True = ignore
        Returns logits:       (B, T, N_LABELS)
        """
        B, T, _ = chunk_feats.shape
        ctx  = self.merge_encoder(merge_feats).unsqueeze(1)   # (B, 1, d_model)
        step = self.encode_step(prev_labels, chunk_feats)      # (B, T, d_model)

        # Add positional encoding to step embeddings (position 0 = first chunk)
        step = step + self.pe[:T].unsqueeze(0)

        seq  = torch.cat([ctx, step], dim=1)                  # (B, T+1, d_model)
        sz   = T + 1
        causal = torch.triu(torch.ones(sz, sz, device=merge_feats.device,
                                       dtype=torch.bool), diagonal=1)
        out    = self.transformer(seq, mask=causal,
                                  src_key_padding_mask=src_key_padding_mask)
        return self.out_head(out[:, 1:, :])                   # (B, T, N_LABELS)

# ---------------------------------------------------------------------------
# Dataset + Bucket Sampler
# ---------------------------------------------------------------------------
class MergeDataset(Dataset):
    def __init__(self, merges, max_seq: int):
        """merges: list of (merge_feat, chunk_feats, in_labels, tgt_labels) tuples."""
        self.merges  = merges
        self.max_seq = max_seq
        # Pre-compute lengths for bucket sampler
        self.lengths = [min(len(tgt), max_seq) for _, _, _, tgt in merges]

    def __len__(self):
        return len(self.merges)

    def __getitem__(self, idx):
        mf, cf, in_lbl, tgt_lbl = self.merges[idx]
        T = min(len(tgt_lbl), self.max_seq)
        in_lbl  = in_lbl[:T]
        tgt_lbl = tgt_lbl[:T]
        cf      = cf[:T]
        return (
            torch.tensor(mf,      dtype=torch.float32),
            torch.tensor(cf,      dtype=torch.float32),
            torch.tensor(in_lbl,  dtype=torch.long),
            torch.tensor(tgt_lbl, dtype=torch.long),
        )


class BucketSampler(Sampler):
    """Sort indices by sequence length, then shuffle within buckets of size ~bucket_size."""
    def __init__(self, lengths, batch_size, seed=42):
        self.lengths    = lengths
        self.batch_size = batch_size
        self.seed       = seed
        self._epoch     = 0

    def set_epoch(self, epoch):
        self._epoch = epoch

    def __len__(self):
        return len(self.lengths)

    def __iter__(self):
        rng = random.Random(self.seed + self._epoch)
        # Sort by length, then shuffle within buckets of ~100 batches
        indices = sorted(range(len(self.lengths)), key=lambda i: self.lengths[i])
        bucket  = self.batch_size * 100
        buckets = [indices[i:i + bucket] for i in range(0, len(indices), bucket)]
        for b in buckets:
            rng.shuffle(b)
        flat = [i for b in buckets for i in b]
        # Final shuffle at the batch level so bucket boundaries don't repeat
        batches = [flat[i:i + self.batch_size] for i in range(0, len(flat), self.batch_size)]
        rng.shuffle(batches)
        return iter(i for batch in batches for i in batch)


def collate_fn(batch):
    mfs, cfs, in_lbls, tgt_lbls = zip(*batch)
    B         = len(batch)
    chunk_dim = cfs[0].shape[1] if cfs[0].ndim == 2 and cfs[0].shape[0] > 0 else CHUNK_DIM
    T_max     = max(cf.shape[0] for cf in cfs)

    merge_out = torch.stack(mfs)
    chunk_out = torch.zeros(B, T_max, chunk_dim)
    tgt_out   = torch.full((B, T_max), PAD_IDX, dtype=torch.long)
    prev_out  = torch.full((B, T_max), PAD_IDX, dtype=torch.long)
    pad_mask  = torch.ones(B, T_max + 1, dtype=torch.bool)

    for i, (mf, cf, in_lbl, tgt_lbl) in enumerate(zip(mfs, cfs, in_lbls, tgt_lbls)):
        T = tgt_lbl.shape[0]
        chunk_out[i, :T] = cf
        tgt_out[i, :T]   = tgt_lbl
        # prev_labels: BOS at position 0, then input_labels (not target_labels!)
        # This preserves NON_IN_IDX for positions after a NON chunk.
        prev_out[i, 0]   = BOS_IDX
        if T > 1:
            prev_out[i, 1:T] = in_lbl[:T - 1]
        pad_mask[i, :T + 1] = False   # context token + real positions attend

    return merge_out, chunk_out, tgt_out, prev_out, pad_mask

# ---------------------------------------------------------------------------
# Scaler
# ---------------------------------------------------------------------------
def fit_scaler(merges):
    merge_data = np.stack([m for m, _, _, _ in merges])
    chunk_data = np.concatenate([c for _, c, _, _ in merges], axis=0)
    m_mean = merge_data.mean(0).astype(np.float32)
    m_std  = merge_data.std(0).astype(np.float32);  m_std[m_std < 1e-8] = 1.0
    c_mean = chunk_data.mean(0).astype(np.float32)
    c_std  = chunk_data.std(0).astype(np.float32);  c_std[c_std < 1e-8] = 1.0
    return m_mean, m_std, c_mean, c_std

def apply_scaler(merges, m_mean, m_std, c_mean, c_std):
    return [((mf - m_mean) / m_std,
             (cf - c_mean) / c_std,
             in_l, tgt_l)
            for mf, cf, in_l, tgt_l in merges]

# ---------------------------------------------------------------------------
# GLOBAL distribution (for NON-position supervision during training)
# ---------------------------------------------------------------------------
def load_global_weights(data_dir: str) -> np.ndarray:
    """Read row 0 of learnt_historical_pattern_distribution.csv → prob weights over ALL_16."""
    path = os.path.join(data_dir, "learnt_historical_pattern_distribution.csv")
    weights = np.zeros(N_LABELS, dtype=np.float64)
    with open(path, newline='') as f:
        for row in csv.reader(f):
            if row and row[0] == '0':
                for entry in row[1].split('|'):
                    prob_str, rest = entry.split('*(', 1)
                    label = rest.rstrip(')').split('*', 1)[1]
                    if label in L2I:
                        weights[L2I[label]] += float(prob_str)
                break
    total = weights.sum()
    return (weights / total).astype(np.float32) if total > 0 else np.full(N_LABELS, 1.0 / N_LABELS, dtype=np.float32)


# ---------------------------------------------------------------------------
# Training
# ---------------------------------------------------------------------------
def train_model(train_merges, max_seq: int, d_model: int, epochs: int,
                device: torch.device, seed: int = 42,
                global_weights: np.ndarray | None = None) -> tuple:
    torch.manual_seed(seed)
    np.random.seed(seed)

    m_mean, m_std, c_mean, c_std = fit_scaler(train_merges)
    train_norm = apply_scaler(train_merges, m_mean, m_std, c_mean, c_std)

    ds      = MergeDataset(train_norm, max_seq)
    sampler = BucketSampler(ds.lengths, batch_size=256, seed=seed)
    dl      = DataLoader(ds, batch_size=256, sampler=sampler,
                         collate_fn=collate_fn, num_workers=0)

    model = AutoregressiveModel(MERGE_DIM, CHUNK_DIM, d_model=d_model,
                                max_seq=max_seq + 10).to(device)
    opt    = torch.optim.Adam(model.parameters(), lr=3e-4, weight_decay=1e-4)
    warmup = torch.optim.lr_scheduler.LinearLR(
        opt, start_factor=0.1, end_factor=1.0, total_iters=3)
    cosine = torch.optim.lr_scheduler.CosineAnnealingLR(
        opt, T_max=max(epochs - 3, 1), eta_min=1e-5)
    sched  = torch.optim.lr_scheduler.SequentialLR(
        opt, schedulers=[warmup, cosine], milestones=[3])
    # PAD_IDX = sequence padding, masked from loss.
    # NON_TGT_IDX = replaced with GLOBAL sample below; never reaches loss as-is.
    crit  = nn.CrossEntropyLoss(ignore_index=PAD_IDX)

    model.train()
    for epoch in range(epochs):
        sampler.set_epoch(epoch)
        total_loss, batches = 0.0, 0
        for merge_feats, chunk_feats, labels, prev_labels, pad_mask in dl:
            merge_feats = merge_feats.to(device)
            chunk_feats = chunk_feats.to(device)
            labels      = labels.clone().to(device)
            prev_labels = prev_labels.to(device)
            pad_mask    = pad_mask.to(device)

            # Replace NON_TGT_IDX sentinels with samples from the GLOBAL distribution
            non_mask = (labels == NON_TGT_IDX)
            if global_weights is not None and non_mask.any():
                n_non   = int(non_mask.sum())
                sampled = np.random.choice(N_LABELS, size=n_non, p=global_weights)
                labels[non_mask] = torch.from_numpy(sampled).to(device)
            else:
                labels[non_mask] = PAD_IDX   # fall back to masking if no weights

            logits = model(merge_feats, prev_labels, chunk_feats, pad_mask)
            B, T, C = logits.shape
            loss = crit(logits.reshape(B * T, C), labels.reshape(B * T))

            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            total_loss += loss.item(); batches += 1

        sched.step()
        print(f"  epoch {epoch+1}/{epochs}  loss={total_loss/max(batches,1):.4f}",
              flush=True)

    return model, m_mean, m_std, c_mean, c_std

# ---------------------------------------------------------------------------
# Inference  (batched, dedup + adaptive temperature + permutation expansion)
# ---------------------------------------------------------------------------
_INF_BATCH = 256
_TEMP_MAX  = 8.0
_TEMP_BUMP = 1.5   # multiply temperature when >50% of a batch are duplicates

@torch.no_grad()
def generate_sequences(model, merge_feat_np, chunk_feats_np,
                        m_mean, m_std, c_mean, c_std,
                        n_variants: int, max_seq: int,
                        temperature: float, device: torch.device,
                        rng: random.Random) -> list[list[str]]:
    """
    Batched autoregressive generation with:
    - Always-on deduplication
    - Adaptive temperature (bumped when >50% of a batch are duplicates)
    - Compound-pattern permutation expansion (post-generation)
    """
    model.eval()
    T = min(len(chunk_feats_np), max_seq)
    if T == 0:
        return []

    mf      = torch.tensor((merge_feat_np - m_mean) / m_std,
                           dtype=torch.float32).unsqueeze(0).to(device)
    cf_raw  = torch.tensor((chunk_feats_np[:T] - c_mean) / c_std,
                           dtype=torch.float32).unsqueeze(0).to(device)
    ctx     = model.merge_encoder(mf)                          # (1, d_model)
    cf_proj = model.chunk_proj(cf_raw)                         # (1, T, proj_dim)
    pe      = model.pe[:T].unsqueeze(0)                        # (1, T, d_model)

    def _run_batch(batch_size: int, temp: float) -> list[list[int]]:
        seqs: list[list[int]] = [[] for _ in range(batch_size)]
        ctx_b = ctx.expand(batch_size, -1).unsqueeze(1)        # (B, 1, d_model)

        for t in range(T):
            cf_t = cf_proj[:, t:t+1, :].expand(batch_size, -1, -1)
            prev_idx = (
                torch.full((batch_size, 1), BOS_IDX, dtype=torch.long, device=device)
                if t == 0
                else torch.tensor([[s[t - 1]] for s in seqs], dtype=torch.long, device=device)
            )
            lbl_emb_t = model.label_embed(prev_idx)
            step_t    = torch.cat([lbl_emb_t, cf_t], dim=-1) + pe[:, t:t+1, :].expand(batch_size, -1, -1)

            if t == 0:
                seq_in = torch.cat([ctx_b, step_t], dim=1)
            else:
                hist_idx = torch.tensor(
                    [[BOS_IDX] + s[:t - 1] for s in seqs],
                    dtype=torch.long, device=device,
                )
                hist_lbl  = model.label_embed(hist_idx)
                hist_cf   = cf_proj[:, :t, :].expand(batch_size, -1, -1)
                hist_step = torch.cat([hist_lbl, hist_cf], dim=-1) + pe[:, :t, :].expand(batch_size, -1, -1)
                seq_in    = torch.cat([ctx_b, hist_step, step_t], dim=1)

            sz     = seq_in.shape[1]
            causal = torch.triu(torch.ones(sz, sz, device=device, dtype=torch.bool), diagonal=1)
            out    = model.transformer(seq_in, mask=causal)
            logits = model.out_head(out[:, -1, :])

            if temp != 1.0:
                logits = logits / temp
            probs = torch.softmax(logits, dim=-1).cpu().numpy()
            for i in range(batch_size):
                seqs[i].append(rng.choices(range(N_LABELS), weights=probs[i].tolist(), k=1)[0])

        return seqs

    # Phase 1: generate n_variants model samples with dedup + adaptive temperature
    seen: set[str] = set()
    unique_seqs: list[list[str]] = []
    cur_temp = temperature
    remaining = n_variants
    while remaining > 0:
        bs = min(remaining, _INF_BATCH)
        new_count = 0
        for int_seq in _run_batch(bs, cur_temp):
            s = [I2L[idx] for idx in int_seq]
            k = "|".join(s)
            if k not in seen:
                seen.add(k)
                unique_seqs.append(s)
                new_count += 1
        # More than half were duplicates → bump temperature
        if new_count < bs // 2 and cur_temp < _TEMP_MAX:
            cur_temp = min(cur_temp * _TEMP_BUMP, _TEMP_MAX)
        remaining -= bs

    # Phase 2: expand with compound-pattern permutations (only for short sequences)
    # 1 chunk: max 6, 2 chunks: max 36, 3 chunks: max 216 — all trivially safe
    for seq in list(unique_seqs):   # iterate over a snapshot
        if len(seq) > 3:
            continue
        choices = [_PERMS[lbl] for lbl in seq]
        if all(len(c) == 1 for c in choices):
            continue                        # no compounds — nothing to expand
        for combo in itertools.product(*choices):
            k = "|".join(combo)
            if k not in seen:
                seen.add(k)
                unique_seqs.append(list(combo))

    return unique_seqs

# ---------------------------------------------------------------------------
# Checkpoint
# ---------------------------------------------------------------------------
def save_model_checkpoint(model, m_mean, m_std, c_mean, c_std,
                          d_model: int, path: str):
    torch.save({
        "model_state": model.state_dict(),
        "m_mean": m_mean, "m_std": m_std,
        "c_mean": c_mean, "c_std": c_std,
        "d_model": d_model,
        "merge_dim": MERGE_DIM, "chunk_dim": CHUNK_DIM,
    }, path)

def load_model_checkpoint(path: str, device: torch.device, max_seq: int = 210):
    ckpt    = torch.load(path, map_location=device, weights_only=False)
    n_embed = ckpt["model_state"]["label_embed.weight"].shape[0]
    model   = AutoregressiveModel(
        ckpt["merge_dim"], ckpt["chunk_dim"],
        d_model=ckpt["d_model"], max_seq=max_seq, n_embed=n_embed,
    ).to(device)
    model.load_state_dict(ckpt["model_state"])
    model.eval()
    return model, ckpt["m_mean"], ckpt["m_std"], ckpt["c_mean"], ckpt["c_std"]

# ---------------------------------------------------------------------------
# Data loading and fold split
# ---------------------------------------------------------------------------
def load_data(csv_path: str, max_rows: int = 0):
    """
    Load Java_chunks_bruteforce.csv.
    max_rows: if > 0, stop after this many CSV rows (for quick tests).
    Returns rows_by_merge, merge_ids_in_order, merge_time_median.
    """
    rows_by_merge      = defaultdict(list)
    merge_ids_in_order = []
    seen_mids          = set()
    merge_times        = []

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
                d = date.fromisoformat(row["merge_time"].strip().split(" ")[0])
                merge_times.append(float((d - EPOCH_ORIGIN).days))
            except Exception:
                pass

    merge_time_median = float(np.median(merge_times)) if merge_times else 0.0

    for mid in rows_by_merge:
        rows_by_merge[mid].sort(key=lambda r: (r.get("file_path", ""),
                                                int(r.get("chunk_id", 0))))
    return rows_by_merge, merge_ids_in_order, merge_time_median


def make_folds(merge_ids_in_order: list[str], n_folds: int = 10):
    """
    Replicate the exact fold split from learn_cv_folds.py (seed=42, merge_id-level).
    All chunks of a merge always stay in the same fold — never split.

    RQ2/RQ3 convention: merge X is in fold k → use autoregressive_model_fold{k}.pt
    (the model trained WITHOUT fold k).
    """
    ids = list(merge_ids_in_order)
    random.Random(42).shuffle(ids)
    n, fold_size = len(ids), len(ids) // n_folds
    return [
        set(ids[k * fold_size : (k * fold_size + fold_size) if k < n_folds - 1 else n])
        for k in range(n_folds)
    ]


def save_fold_assignment(folds: list[set], path: str):
    import json
    assignment = {mid: k for k, fold_set in enumerate(folds) for mid in fold_set}
    with open(path, "w") as f:
        json.dump(assignment, f)
    print(f"Fold assignment saved: {path} ({len(assignment)} merges)")

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def parse_args():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--epochs",    type=int,   default=40)
    p.add_argument("--d-model",   type=int,   default=128)
    p.add_argument("--nhead",     type=int,   default=4)
    p.add_argument("--layers",    type=int,   default=2)
    p.add_argument("--variants",  type=int,   default=1000)
    p.add_argument("--max-seq",   type=int,   default=200)
    p.add_argument("--temp",      type=float, default=1.0)
    p.add_argument("--folds",     type=str,   default="0,1,2,3,4,5,6,7,8,9")
    p.add_argument("--data-dir",  type=str,   default=None)
    p.add_argument("--max-rows",  type=int,   default=0,
                   help="Load only first N CSV rows (0=all). For pipeline tests.")
    p.add_argument("--inference-only", action="store_true",
                   help="Skip training; load existing fold checkpoints and re-run inference.")
    return p.parse_args()


def main():
    args      = parse_args()
    script_dir = os.path.dirname(os.path.abspath(__file__))
    data_dir   = args.data_dir or script_dir
    csv_path   = os.path.join(data_dir, "Java_chunks_bruteforce.csv")

    folds_to_run = [int(x) for x in args.folds.split(",")]
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Device: {device}")
    print(f"Epochs:{args.epochs}  d_model:{args.d_model}  layers:{args.layers}  "
          f"variants:{args.variants}  max_seq:{args.max_seq}  "
          f"max_rows:{args.max_rows or 'all'}", flush=True)

    global_weights = None
    if not args.inference_only:
        try:
            global_weights = load_global_weights(data_dir)
            print(f"Global weights loaded ({N_LABELS} labels, top: "
                  f"{ALL_16[int(global_weights.argmax())]}={global_weights.max():.3f})", flush=True)
        except Exception as e:
            print(f"Warning: could not load global weights ({e}); NON positions will be masked.", flush=True)

    print("Loading data…", flush=True)
    rows_by_merge, merge_ids_in_order, merge_time_median = load_data(
        csv_path, max_rows=args.max_rows
    )
    print(f"  {len(merge_ids_in_order):,} unique merges", flush=True)

    folds      = make_folds(merge_ids_in_order)
    all_mid_set = set(merge_ids_in_order)

    fold_assignment_path = os.path.join(data_dir, "autoregressive_fold_assignment.json")
    if not os.path.exists(fold_assignment_path):
        save_fold_assignment(folds, fold_assignment_path)

    print("Extracting features…", flush=True)
    features_by_mid = {
        mid: extract_features(rows_by_merge[mid], merge_time_median)
        for mid in merge_ids_in_order
    }
    print("  Done.", flush=True)

    for fold_k in folds_to_run:
        print(f"\n{'='*60}\n=== Fold {fold_k} ===\n{'='*60}", flush=True)

        eval_ids  = folds[fold_k]
        train_ids = all_mid_set - eval_ids

        # Train on ALL rows (no Maven filter — is_maven is a feature)
        train_merges = [features_by_mid[mid] for mid in train_ids]
        if args.inference_only:
            print(f"  Eval: {len(eval_ids):,} merges  (inference-only, skipping training)",
                  flush=True)
        else:
            print(f"  Train: {len(train_merges):,} merges | Eval: {len(eval_ids):,} merges",
                  flush=True)

        ckpt_path = os.path.join(data_dir, f"autoregressive_model_fold{fold_k}.pt")
        if args.inference_only:
            print(f"  Loading checkpoint: {ckpt_path}", flush=True)
            model, m_mean, m_std, c_mean, c_std = load_model_checkpoint(
                ckpt_path, device, max_seq=args.max_seq + 10
            )
        else:
            model, m_mean, m_std, c_mean, c_std = train_model(
                train_merges, args.max_seq, args.d_model, args.epochs, device,
                seed=42 + fold_k, global_weights=global_weights,
            )
            save_model_checkpoint(model, m_mean, m_std, c_mean, c_std, args.d_model, ckpt_path)
            print(f"  Model saved: {ckpt_path}", flush=True)

        out_path = os.path.join(data_dir, f"autoregressive_predictions_fold{fold_k}.csv")
        inf_rng  = random.Random(42 + fold_k)
        eval_mids_sorted = sorted(eval_ids, key=lambda x: int(x))
        print(f"  Generating predictions for {len(eval_mids_sorted):,} eval merges…",
              flush=True)

        with open(out_path, "w", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(["merge_id", "sequence"])
            for i, mid in enumerate(eval_mids_sorted):
                if (i + 1) % 500 == 0:
                    print(f"    {i+1}/{len(eval_mids_sorted)}", flush=True)
                mf, cf, _, _ = features_by_mid[mid]
                seqs = generate_sequences(
                    model, mf, cf, m_mean, m_std, c_mean, c_std,
                    n_variants=args.variants, max_seq=args.max_seq,
                    temperature=args.temp, device=device, rng=inf_rng,
                )
                for seq_strs in seqs:
                    writer.writerow([mid, "|".join(seq_strs)])

        print(f"  Written: {out_path}", flush=True)

    print("\nAll folds done.", flush=True)


if __name__ == "__main__":
    main()
