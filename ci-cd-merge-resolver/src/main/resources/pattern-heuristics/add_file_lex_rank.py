#!/usr/bin/env python3
"""
Add file_lex_rank and chunkRankInFile columns to Java_chunks.csv.

file_lex_rank:    1-based lexicographic rank of file_path among all unique
                  file paths within the same merge_id.
chunkRankInFile:  1-based rank of the chunk within its (merge_id, file_path)
                  group, ordered by chunk_id.  Together with file_lex_rank
                  this gives the full positional address of each chunk.

Usage:
    python3 add_file_lex_rank.py [input.csv] [output.csv]

Defaults to Java_chunks.csv in the script directory, writing
back to the same file (in-place via a temp file).
"""

import csv
import os
import sys
from collections import defaultdict

csv.field_size_limit(1_000_000)


def main():
    script_dir  = os.path.dirname(os.path.abspath(__file__))
    input_path  = sys.argv[1] if len(sys.argv) > 1 else os.path.join(script_dir, 'Java_chunks.csv')
    output_path = sys.argv[2] if len(sys.argv) > 2 else input_path

    print(f'Reading {input_path} ...')
    with open(input_path, newline='') as f:
        rows = list(csv.reader(f))

    header = rows[0]
    data   = rows[1:]

    for col in ('file_lex_rank', 'chunkRankInFile'):
        if col in header:
            print(f'Column {col} already present — overwriting it.')
            idx = header.index(col)
            for row in data:
                del row[idx]
            header.remove(col)

    merge_id_col  = header.index('merge_id')
    file_path_col = header.index('file_path')
    chunk_id_col  = header.index('chunk_id')

    # Pass 1: collect sorted unique file paths per merge (for file_lex_rank)
    files_per_merge = defaultdict(set)
    for row in data:
        files_per_merge[row[merge_id_col]].add(row[file_path_col])

    file_rank_map = {
        mid: {fp: i + 1 for i, fp in enumerate(sorted(fps))}
        for mid, fps in files_per_merge.items()
    }

    # Pass 1b: collect chunk_ids per (merge_id, file_path) (for chunkRankInFile)
    chunks_per_file = defaultdict(list)
    for row in data:
        key = (row[merge_id_col], row[file_path_col])
        chunks_per_file[key].append(int(row[chunk_id_col]))

    chunk_rank_map = {
        key: {cid: i + 1 for i, cid in enumerate(sorted(cids))}
        for key, cids in chunks_per_file.items()
    }

    # Pass 2: append both columns
    new_header = header + ['file_lex_rank', 'chunkRankInFile']
    new_data   = [
        row
        + [str(file_rank_map[row[merge_id_col]][row[file_path_col]])]
        + [str(chunk_rank_map[(row[merge_id_col], row[file_path_col])][int(row[chunk_id_col])])]
        for row in data
    ]

    print(f'Writing {output_path} ...')
    tmp_path = output_path + '.tmp'
    with open(tmp_path, 'w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(new_header)
        writer.writerows(new_data)

    os.replace(tmp_path, output_path)
    print(f'Done. {len(new_data)} rows, {len(new_header)} columns (added file_lex_rank, chunkRankInFile).')


if __name__ == '__main__':
    main()
