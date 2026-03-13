#!/usr/bin/env python3

import csv
import os
from collections import Counter

# Increase the field size limit for CSV reader
csv.field_size_limit(1000000)

def filter_java_files(input_file, output_file):
    """Filter rows to include only chunks from .java files."""
    with open(input_file, 'r') as infile, open(output_file, 'w', newline='') as outfile:
        reader = csv.reader(infile)
        writer = csv.writer(outfile)
        header = next(reader)
        writer.writerow(header)
        for row in reader:
            if row[6].endswith('.java'):
                writer.writerow(row)

def merge_chunks_by_merge_id(input_file, output_file):
    """Group chunks by merge_id and concatenate their strategies."""
    counts = {}
    strategies = {}
    
    with open(input_file, 'r') as infile:
        reader = csv.reader(infile)
        header = next(reader)
        for row in reader:
            merge_id = row[3]
            strategy = row[8]
            if merge_id not in counts:
                counts[merge_id] = 0
                strategies[merge_id] = []
            counts[merge_id] += 1
            strategies[merge_id].append(strategy)
    
    with open(output_file, 'w', newline='') as outfile:
        writer = csv.writer(outfile)
        writer.writerow(['merge_id', 'number_of_chunks', 'strategies'])
        for merge_id in counts:
            writer.writerow([merge_id, counts[merge_id], '_'.join(strategies[merge_id])])

def replace_noncanonical(input_file, output_file):
    """Replace NONCANONICAL with NON."""
    with open(input_file, 'r') as infile, open(output_file, 'w', newline='') as outfile:
        reader = csv.reader(infile)
        writer = csv.writer(outfile)
        for row in reader:
            row[2] = row[2].replace('NONCANONICAL', 'NON')
            writer.writerow(row)

def remove_prefixes(input_file, output_file):
    """Remove CANONICAL_, SEMICANONICAL_, CHUNK_, and SEMI prefixes."""
    with open(input_file, 'r') as infile, open(output_file, 'w', newline='') as outfile:
        reader = csv.reader(infile)
        writer = csv.writer(outfile)
        for row in reader:
            row[2] = row[2].replace('CANONICAL_', '').replace('SEMICANONICAL_', '').replace('CHUNK_', '').replace('SEMI', '')
            writer.writerow(row)

def group_strategies_by_chunk_count(input_file, output_file):
    """Group strategies by number_of_chunks."""
    result = {}
    
    with open(input_file, 'r') as infile:
        reader = csv.reader(infile)
        next(reader)
        for row in reader:
            key = row[1]
            if key not in result:
                result[key] = []
            result[key].append(row[2])
    
    with open(output_file, 'w', newline='') as outfile:
        writer = csv.writer(outfile)
        for key in sorted(result.keys(), key=int):
            writer.writerow([key, '|'.join(result[key])])

def sort_substrategies(input_file, output_file):
    """Sort substrategies alphabetically."""
    with open(input_file, 'r') as infile, open(output_file, 'w', newline='') as outfile:
        reader = csv.reader(infile)
        writer = csv.writer(outfile)
        for row in reader:
            if len(row) < 2:
                writer.writerow(row)
                continue
            number_of_chunks = row[0]
            strategies = row[1].split('|')
            sorted_strategies = []
            for strategy in strategies:
                parts = strategy.split('_')
                parts_sorted = sorted(parts)
                sorted_strategy = '_'.join(parts_sorted)
                sorted_strategies.append(sorted_strategy)
            writer.writerow([number_of_chunks, '|'.join(sorted_strategies)])

def count_and_rank_strategies(input_file, output_file):
    """Count and rank substrategies."""
    with open(input_file, 'r') as infile, open(output_file, 'w', newline='') as outfile:
        reader = csv.reader(infile)
        writer = csv.writer(outfile)
        for row in reader:
            if len(row) < 2:
                writer.writerow(row)
                continue
            number_of_chunks = row[0]
            strategies = row[1].split('|')
            strategy_counts = Counter(strategies)
            sorted_strategies = sorted(strategy_counts.items(), key=lambda x: (-x[1], x[0]))
            ranked_strategies = [f"{count}*{strategy}" for strategy, count in sorted_strategies]
            writer.writerow([number_of_chunks, '|'.join(ranked_strategies)])

def summarize_strategies(input_file, output_file):
    """Summarize each strategy by counting its substrategies."""
    summarized_strategies = []
    with open(input_file, 'r') as infile:
        reader = csv.reader(infile)
        for row in reader:
            if len(row) < 2:
                continue
            number_of_chunks = row[0]
            strategies = row[1].split('|')
            
            summarized = []
            for strategy in strategies:
                if '*' not in strategy:
                    summarized.append(strategy)
                    continue
                count, substrategies = strategy.split('*', 1)
                substrategies = substrategies.split('_')
                substrategy_counts = Counter(substrategies)
                sorted_substrategies = sorted(substrategy_counts.items(), key=lambda x: (-x[1], x[0]))
                summarized_strategy = f"{count}*({'_'.join([f'{cnt}*{sub}' for sub, cnt in sorted_substrategies])})"
                summarized.append(summarized_strategy)
            summarized_strategies.append([number_of_chunks, '|'.join(summarized)])
    
    with open(output_file, 'w', newline='') as outfile:
        writer = csv.writer(outfile)
        for row in summarized_strategies:
            writer.writerow(row)

def compute_relative_numbers(input_file, output_file):
    """Compute relative numbers for substrategies."""
    # Step 1: Add the global overview row (row "0")
    global_overview = {}
    with open('Java_chunks_javaonly.csv', 'r') as java_file:
        reader = csv.reader(java_file)
        next(reader)  # Skip header
        for row in reader:
            strategy = row[8].replace('CHUNK_', '').replace('CANONICAL_', '').replace('SEMICANONICAL_', '').replace('NONCANONICAL', 'NON').replace('SEMI', '')
            if strategy in global_overview:
                global_overview[strategy] += 1
            else:
                global_overview[strategy] = 1
    
    # Sort and format the global overview
    sorted_global = sorted(global_overview.items(), key=lambda x: (-x[1], x[0]))
    global_strategies = [f"{count}*{strategy}" for strategy, count in sorted_global]
    
    # Summarize the global overview row and compute relative numbers
    summarized_global = []
    total_global = sum(count for strategy, count in sorted_global)
    for strategy, count in sorted_global:
        substrategies = strategy.split('_')
        substrategy_counts = Counter(substrategies)
        sorted_substrategies = sorted(substrategy_counts.items(), key=lambda x: (-x[1], x[0]))
        relative_substrategies = []
        for sub, cnt in sorted_substrategies:
            relative_count = (cnt / len(substrategies)) * 100
            relative_count_rounded = round(relative_count, 2)
            relative_substrategies.append(f"{relative_count_rounded}*{sub}")
        relative_total = (count / total_global) * 100
        relative_total_rounded = round(relative_total, 2)
        summarized_strategy = f"{relative_total_rounded}*({'_'.join(relative_substrategies)})"
        summarized_global.append(summarized_strategy)
    
    # Step 2: Process the input file and prepend the summarized global overview
    with open(input_file, 'r') as infile, open(output_file, 'w', newline='') as outfile:
        reader = csv.reader(infile)
        writer = csv.writer(outfile)
        
        # Write the summarized global overview as the first row
        writer.writerow(['0', '|'.join(summarized_global)])
        
        # Process the rest of the file
        for row in reader:
            if len(row) < 2:
                writer.writerow(row)
                continue
            number_of_chunks = row[0]
            strategies = row[1].split('|')
            
            total_counts = []
            processed_strategies = []
            for strategy in strategies:
                if '*' not in strategy or '(' not in strategy:
                    processed_strategies.append(strategy)
                    continue
                
                total_count, substrategies_part = strategy.split('*', 1)
                total_count = int(total_count)
                total_counts.append(total_count)
                substrategies_part = substrategies_part.strip('()')
                substrategies = substrategies_part.split('_')
                
                substrategy_counts = []
                for substrategy in substrategies:
                    if '*' in substrategy:
                        count, sub = substrategy.split('*')
                        substrategy_counts.append((sub, int(count)))
                
                total_sub = sum(count for sub, count in substrategy_counts)
                relative_substrategies = []
                for sub, count in substrategy_counts:
                    relative_count = (count / total_sub) * 100
                    relative_count_rounded = round(relative_count, 2)
                    relative_substrategies.append(f"{relative_count_rounded}*{sub}")
                
                processed_strategies.append((total_count, relative_substrategies))
            
            if total_counts:
                total_sum = sum(total_counts)
                final_strategies = []
                for total_count, relative_substrategies in processed_strategies:
                    relative_total = (total_count / total_sum) * 100
                    relative_total_rounded = round(relative_total, 2)
                    relative_strategy = f"{relative_total_rounded}*({'_'.join(relative_substrategies)})"
                    final_strategies.append(relative_strategy)
                writer.writerow([number_of_chunks, '|'.join(final_strategies)])
            else:
                writer.writerow([number_of_chunks, '|'.join(processed_strategies)])

def main():
    # Step 1: Filter Java files
    filter_java_files('Java_chunks_original.csv', 'Java_chunks_javaonly.csv')
    print("Step 1: Filtered Java files.")
    
    # Step 2: Merge chunks by merge_id
    merge_chunks_by_merge_id('Java_chunks_javaonly.csv', 'merged_chunks.csv')
    print("Step 2: Merged chunks by merge_id.")
    
    # Step 3: Replace NONCANONICAL with NON
    replace_noncanonical('merged_chunks.csv', 'merged_chunks_replaced.csv')
    print("Step 3: Replaced NONCANONICAL with NON.")
    
    # Step 4: Remove CANONICAL_ and SEMICANONICAL_ prefixes
    remove_prefixes('merged_chunks_replaced.csv', 'merged_chunks_cleaned.csv')
    print("Step 4: Removed prefixes.")
    
    # Step 5: Group strategies by number_of_chunks
    group_strategies_by_chunk_count('merged_chunks_cleaned.csv', 'strategies_by_chunk_count.csv')
    print("Step 5: Grouped strategies by number_of_chunks.")
    
    # Step 6: Sort substrategies alphabetically
    sort_substrategies('strategies_by_chunk_count.csv', 'strategies_by_chunk_count_sorted.csv')
    print("Step 6: Sorted substrategies alphabetically.")
    
    # Step 7: Count and rank substrategies
    count_and_rank_strategies('strategies_by_chunk_count_sorted.csv', 'strategies_counted_ranked.csv')
    print("Step 7: Counted and ranked substrategies.")
    
    # Step 8: Summarize strategies
    summarize_strategies('strategies_counted_ranked.csv', 'strategies_summarized.csv')
    print("Step 8: Summarized strategies.")
    
    # Step 9: Compute relative numbers
    compute_relative_numbers('strategies_summarized.csv', 'relative_numbers_summary.csv')
    print("Step 9: Computed relative numbers.")
    
    # Step 10: Clean up interim files
    interim_files = [
        'Java_chunks_javaonly.csv',
        'merged_chunks.csv',
        'merged_chunks_replaced.csv',
        'merged_chunks_cleaned.csv',
        'strategies_by_chunk_count.csv',
        'strategies_by_chunk_count_sorted.csv',
        'strategies_counted_ranked.csv',
        'strategies_summarized.csv'
    ]
    for file in interim_files:
        if os.path.exists(file):
            os.remove(file)
            print(f"Cleaned up: {file}")
    
    print("\nFinal output: relative_numbers_summary.csv")

if __name__ == '__main__':
    main()