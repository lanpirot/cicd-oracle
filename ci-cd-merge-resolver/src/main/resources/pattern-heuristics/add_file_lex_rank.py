#!/usr/bin/env python3
"""
Add a file_lex_rank column to Java_chunks.csv.

file_lex_rank: 1-based lexicographic rank of file_path among all unique
file paths within the same merge_id.  Combined with chunk_id this gives
the full positional address of each chunk, which an autoregressive model
needs to generate patterns in a consistent order.

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

    if 'file_lex_rank' in header:
        print('Column file_lex_rank already present — overwriting it.')
        rank_col = header.index('file_lex_rank')
        for row in data:
            del row[rank_col]
        header.remove('file_lex_rank')

    merge_id_col  = header.index('merge_id')
    file_path_col = header.index('file_path')

    # Pass 1: collect sorted unique file paths per merge
    files_per_merge = defaultdict(set)
    for row in data:
        files_per_merge[row[merge_id_col]].add(row[file_path_col])

    rank_map = {
        mid: {fp: i + 1 for i, fp in enumerate(sorted(fps))}
        for mid, fps in files_per_merge.items()
    }

    # Pass 2: append column
    new_header = header + ['file_lex_rank']
    new_data   = [row + [str(rank_map[row[merge_id_col]][row[file_path_col]])]
                  for row in data]

    print(f'Writing {output_path} ...')
    tmp_path = output_path + '.tmp'
    with open(tmp_path, 'w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(new_header)
        writer.writerows(new_data)

    os.replace(tmp_path, output_path)
    print(f'Done. {len(new_data)} rows, {len(new_header)} columns.')


if __name__ == '__main__':
    main()
