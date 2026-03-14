#!/usr/bin/env python3

import csv
import os
from collections import Counter

# Increase the field size limit for CSV reader
csv.field_size_limit(1000000)

def filter_java_files(input_data):
    """Filter rows to include only chunks from .java files."""
    header = input_data[0]
    filtered_data = [header]
    for row in input_data[1:]:
        if len(row) > 6 and row[6].endswith('.java'):
            filtered_data.append(row)
    return filtered_data

def merge_chunks_by_merge_id(input_data):
    """Group chunks by merge_id and concatenate their strategies."""
    counts = {}
    strategies = {}
    
    header = input_data[0]
    for row in input_data[1:]:
        if len(row) > 8:
            merge_id = row[3]
            strategy = row[8]
            if merge_id not in counts:
                counts[merge_id] = 0
                strategies[merge_id] = []
            counts[merge_id] += 1
            strategies[merge_id].append(strategy)
    
    output_data = [['merge_id', 'number_of_chunks', 'strategies']]
    for merge_id in counts:
        output_data.append([merge_id, counts[merge_id], '_'.join(strategies[merge_id])])
    return output_data

def replace_noncanonical(input_data):
    """Replace NONCANONICAL with NON."""
    output_data = []
    for row in input_data:
        if len(row) > 2:
            row[2] = row[2].replace('NONCANONICAL', 'NON')
        output_data.append(row)
    return output_data

def remove_prefixes(input_data):
    """Remove CANONICAL_, SEMICANONICAL_, CHUNK_, and SEMI prefixes."""
    output_data = []
    for row in input_data:
        if len(row) > 2:
            row[2] = row[2].replace('CANONICAL_', '').replace('SEMICANONICAL_', '').replace('CHUNK_', '').replace('SEMI', '')
        output_data.append(row)
    return output_data

def group_strategies_by_chunk_count(input_data):
    """Group strategies by number_of_chunks."""
    result = {}
    
    header = input_data[0]
    for row in input_data[1:]:
        if len(row) > 2:
            key = row[1]
            if key not in result:
                result[key] = []
            result[key].append(row[2])
    
    output_data = []
    for key in sorted(result.keys(), key=int):
        output_data.append([key, '|'.join(result[key])])
    return output_data

def sort_substrategies(input_data):
    """Sort substrategies alphabetically."""
    output_data = []
    for row in input_data:
        if len(row) < 2:
            output_data.append(row)
            continue
        number_of_chunks = row[0]
        strategies = row[1].split('|')
        sorted_strategies = []
        for strategy in strategies:
            parts = strategy.split('_')
            parts_sorted = sorted(parts)
            sorted_strategy = '_'.join(parts_sorted)
            sorted_strategies.append(sorted_strategy)
        output_data.append([number_of_chunks, '|'.join(sorted_strategies)])
    return output_data

def count_and_rank_strategies(input_data):
    """Count and rank substrategies."""
    output_data = []
    for row in input_data:
        if len(row) < 2:
            output_data.append(row)
            continue
        number_of_chunks = row[0]
        strategies = row[1].split('|')
        strategy_counts = Counter(strategies)
        sorted_strategies = sorted(strategy_counts.items(), key=lambda x: (-x[1], x[0]))
        ranked_strategies = [f"{count}*{strategy}" for strategy, count in sorted_strategies]
        output_data.append([number_of_chunks, '|'.join(ranked_strategies)])
    return output_data

def summarize_strategies(input_data):
    """Summarize each strategy by counting its substrategies."""
    output_data = []
    for row in input_data:
        if len(row) < 2:
            output_data.append(row)
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
        output_data.append([number_of_chunks, '|'.join(summarized)])
    
    return output_data

def compute_relative_numbers(input_data, java_chunks_data):
    """Compute relative numbers for substrategies."""
    # Step 1: Add the global overview row (row "0")
    global_overview = {}
    for row in java_chunks_data[1:]:  # Skip the header row
        if len(row) > 8:
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
    
    if not summarized_global:
        summarized_global.append("0*()")
    
    # Step 2: Process the input data and prepend the summarized global overview
    output_data = []
    output_data.append(['0', '|'.join(summarized_global)])
    
    for row in input_data:
        if len(row) < 2:
            output_data.append(row)
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
            output_data.append([number_of_chunks, '|'.join(final_strategies)])
        else:
            output_data.append([number_of_chunks, '|'.join(processed_strategies)])
    
    return output_data

def main():
    # Step 1: Read the original CSV file
    with open('Java_chunks_original.csv', 'r') as infile:
        reader = csv.reader(infile)
        original_data = list(reader)
    print("Step 1: Read original CSV file.")
    
    # Step 2: Filter Java files
    java_only_data = filter_java_files(original_data)
    print(f"Step 2: Filtered Java files. {len(java_only_data)} rows.")
    
    # Step 3: Merge chunks by merge_id
    merged_data = merge_chunks_by_merge_id(java_only_data)
    print(f"Step 3: Merged chunks by merge_id. {len(merged_data)} rows.")
    
    # Step 4: Replace NONCANONICAL with NON
    replaced_data = replace_noncanonical(merged_data)
    print(f"Step 4: Replaced NONCANONICAL with NON. {len(replaced_data)} rows.")
    
    # Step 5: Remove CANONICAL_ and SEMICANONICAL_ prefixes
    cleaned_data = remove_prefixes(replaced_data)
    print(f"Step 5: Removed prefixes. {len(cleaned_data)} rows.")
    
    # Step 6: Group strategies by number_of_chunks
    grouped_data = group_strategies_by_chunk_count(cleaned_data)
    print(f"Step 6: Grouped strategies by number_of_chunks. {len(grouped_data)} rows.")
    
    # Step 7: Sort substrategies alphabetically
    sorted_data = sort_substrategies(grouped_data)
    print(f"Step 7: Sorted substrategies alphabetically. {len(sorted_data)} rows.")
    
    # Step 8: Count and rank substrategies
    ranked_data = count_and_rank_strategies(sorted_data)
    print(f"Step 8: Counted and ranked substrategies. {len(ranked_data)} rows.")
    
    # Step 9: Summarize strategies
    summarized_data = summarize_strategies(ranked_data)
    print(f"Step 9: Summarized strategies. {len(summarized_data)} rows.")
    
    # Step 10: Add global overview and write the final output
    # Compute global overview from java_only_data (absolute counts only)
    global_overview = {}
    for row in java_only_data[1:]:  # Skip header
        if len(row) > 8:
            strategy = row[8].replace('CHUNK_', '').replace('CANONICAL_', '').replace('SEMICANONICAL_', '').replace('NONCANONICAL', 'NON').replace('SEMI', '')
            if strategy in global_overview:
                global_overview[strategy] += 1
            else:
                global_overview[strategy] = 1
    
    # Create global overview row with relative computations within each strategy
    sorted_global = sorted(global_overview.items(), key=lambda x: (-x[1], x[0]))
    global_strategies = []
    for strategy, count in sorted_global:
        # For global overview, strategies are simple (not compound)
        # So we just format as count*(100*strategy) to match other rows
        global_strategies.append(f"{count}*(100*{strategy})")
    
    # Process summarized_data to add relative computations within each strategy
    processed_summarized_data = []
    for row in summarized_data:
        if len(row) < 2:
            processed_summarized_data.append(row)
            continue
        
        number_of_chunks = row[0]
        strategies = row[1].split('|')
        
        processed_strategies = []
        for strategy in strategies:
            if '(' not in strategy or ')' not in strategy:
                # Simple strategy, keep as is
                processed_strategies.append(strategy)
                continue
            
            # Parse complex strategy like "3*(6*OURS_4*BASE)"
            # Extract the count and substrategies
            count_part, substrategies_part = strategy.split('*', 1)
            count = count_part
            substrategies_part = substrategies_part.strip('()')
            
            # Split substrategies like "6*OURS_4*BASE"
            substrategy_items = substrategies_part.split('_')
            
            # Calculate total
            total = 0
            substrategy_counts = []
            for item in substrategy_items:
                if '*' in item:
                    cnt, sub = item.split('*', 1)
                    total += int(cnt)
                    substrategy_counts.append((sub, int(cnt)))
                else:
                    # Handle case where there's no count (shouldn't happen)
                    total += 1
                    substrategy_counts.append((item, 1))
            
            # Calculate relative percentages
            if total > 0:
                relative_substrategies = []
                for sub, cnt in substrategy_counts:
                    percentage = (cnt / total) * 100
                    # Format to 9 significant digits
                    formatted = f"{percentage:.9g}"
                    relative_substrategies.append(f"{formatted}*{sub}")
                
                processed_strategy = f"{count}*({'_'.join(relative_substrategies)})"
                processed_strategies.append(processed_strategy)
            else:
                processed_strategies.append(strategy)
        
        processed_summarized_data.append([number_of_chunks, '|'.join(processed_strategies)])
    
    # Unify strategies with the same substrategy components
    unified_summarized_data = []
    for row in processed_summarized_data:
        if len(row) < 2:
            unified_summarized_data.append(row)
            continue
        
        number_of_chunks = row[0]
        strategies = row[1].split('|')
        
        # Debug: show strategies before grouping
        if number_of_chunks == '2048-4095':
            print(f"DEBUG: Processing row {number_of_chunks} with strategies: {strategies}")
        
        # Group strategies by their substrategy components
        strategy_groups = {}
        for strategy in strategies:
            # All strategies should have the format count*(percent*sub1_percent*sub2_...)
            # Even simple ones like 1*(100*OURS)
            if '(' not in strategy or ')' not in strategy:
                # True simple strategy without parentheses (shouldn't happen with current data)
                if 'simple' not in strategy_groups:
                    strategy_groups['simple'] = []
                strategy_groups['simple'].append(strategy)
                continue
            
            # Extract the substrategy components (without counts)
            # Strategy format: "count*(percent*sub1_percent*sub2_...)"
            parts = strategy.split('*(', 1)
            if len(parts) != 2:
                continue
            
            substrategies_part = parts[1].rstrip(')')
            # Extract just the substrategy names (OURS, THEIRS, etc.)
            sub_names = []
            for item in substrategies_part.split('_'):
                if '*' in item:
                    sub_name = item.split('*', 1)[1]
                    sub_names.append(sub_name)
            
            # Use tuple of substrategy names as key
            key = tuple(sorted(sub_names))
            if key not in strategy_groups:
                strategy_groups[key] = []
            strategy_groups[key].append(strategy)
            
            # Debug: show grouping
            if len(strategy_groups[key]) > 1:
                print(f"DEBUG: Group {key} now has {len(strategy_groups[key])} strategies")
            
            # Special debug for last row
            if number_of_chunks == '2048-4095':
                print(f"DEBUG: Row {number_of_chunks} - Strategy {strategy} -> Key {key}")
        
        # Unify strategies in each group
        unified_strategies = []
        
        # Process simple strategies first
        if 'simple' in strategy_groups:
            print(f"DEBUG: Found {len(strategy_groups['simple'])} simple strategies: {strategy_groups['simple']}")
            # Group simple strategies by their substrategy name for unification
            simple_strategy_groups = {}
            for strategy in strategy_groups['simple']:
                # Simple strategy format: count*(100*substrategy)
                if '*' in strategy and '(' in strategy and ')' in strategy:
                    count_part, rest = strategy.split('*(', 1)
                    substrategy = rest.rstrip(')').split('*', 1)[1]  # Extract just the substrategy name
                    
                    if substrategy not in simple_strategy_groups:
                        simple_strategy_groups[substrategy] = []
                    simple_strategy_groups[substrategy].append(strategy)
                    
                    # Debug: show simple strategy grouping
                    if len(simple_strategy_groups[substrategy]) > 1:
                        print(f"DEBUG: Simple group ({substrategy}) now has {len(simple_strategy_groups[substrategy])} strategies")
                else:
                    # Fallback for malformed simple strategies
                    unified_strategies.append(strategy)
            
            # Unify simple strategies with same substrategy
            for substrategy, strategy_list in simple_strategy_groups.items():
                if len(strategy_list) == 1:
                    # Only one strategy, no unification needed
                    unified_strategies.append(strategy_list[0])
                else:
                    # Unify multiple simple strategies
                    total_count = 0
                    for strategy in strategy_list:
                        count_part = strategy.split('*(', 1)[0]
                        total_count += int(count_part)
                    
                    # Create unified simple strategy
                    unified_strategy = f"{total_count}*(100*{substrategy})"
                    unified_strategies.append(unified_strategy)
        
        # Process compound strategies
        for key, strategy_list in strategy_groups.items():
            if key == 'simple':
                continue
            
            if len(strategy_list) == 1:
                # Only one strategy in this group, no unification needed
                unified_strategies.append(strategy_list[0])
            else:
                # Check if this is actually a simple strategy group (single substrategy)
                print(f"DEBUG: Processing group {key} with {len(strategy_list)} strategies: {strategy_list}")
                if len(key) == 1:
                    # This is a simple strategy group (single substrategy)
                    # Unify as simple strategies
                    total_count = 0
                    for strategy in strategy_list:
                        count_part = strategy.split('*(', 1)[0]
                        total_count += int(count_part)
                    
                    # Create unified simple strategy
                    substrategy = key[0]
                    unified_strategy = f"{total_count}*(100*{substrategy})"
                    unified_strategies.append(unified_strategy)
                    print(f"DEBUG: Unified simple strategies {key}: {strategy_list} -> {unified_strategy}")
                else:
                    # Unify multiple strategies with same substrategy components (compound)
                    total_count = 0
                    sub_totals = {}
                    
                    # Parse each strategy and accumulate counts
                    for strategy in strategy_list:
                        # Extract count and substrategies
                        count_part, substrategies_part = strategy.split('*(', 1)
                        count = int(count_part)
                        total_count += count
                        
                        # Parse each substrategy
                        for item in substrategies_part.rstrip(')').split('_'):
                            if '*' in item:
                                percent_str, sub_name = item.split('*', 1)
                                percent = float(percent_str)
                                actual_count = (percent / 100) * count
                                
                                if sub_name not in sub_totals:
                                    sub_totals[sub_name] = 0
                                sub_totals[sub_name] += actual_count
                    
                    # Create unified strategy
                    if total_count > 0:
                        # Calculate new percentages
                        relative_substrategies = []
                        for sub_name in sorted(sub_totals.keys()):
                            sub_total = sub_totals[sub_name]
                            percentage = (sub_total / total_count) * 100
                            formatted = f"{percentage:.9g}"
                            relative_substrategies.append(f"{formatted}*{sub_name}")
                        
                        unified_strategy = f"{total_count}*({'_'.join(relative_substrategies)})"
                        unified_strategies.append(unified_strategy)
                    

        
        unified_summarized_data.append([number_of_chunks, '|'.join(unified_strategies)])
    
    # Prepend global overview to unified summarized_data
    # Remove NON entries from the global row
    filtered_global_strategies = []
    for strategy in global_strategies:
        if 'NON' not in strategy:
            filtered_global_strategies.append(strategy)
    
    final_data = [['0', '|'.join(filtered_global_strategies)]] + unified_summarized_data
    
    # Concatenate rows into log-buckets based on number_of_chunks value
    # Keep first two rows as is (row 0 and row 1)
    bucketed_data = final_data[:2] if len(final_data) >= 2 else final_data
    
    # Group remaining rows by number_of_chunks ranges
    # Start with bucket_size = 2, then 4, 8, 16, etc.
    current_bucket_start = 2
    bucket_size = 2
    
    while current_bucket_start <= 3413:  # Go up to the maximum number_of_chunks
        # Determine the end of this bucket
        current_bucket_end = current_bucket_start + bucket_size - 1
        
        # Collect all strategies from rows where number_of_chunks is in this range
        all_strategies = []
        for row in final_data[2:]:  # Skip first two rows
            if len(row) > 1:
                try:
                    num_chunks = int(row[0])
                    if current_bucket_start <= num_chunks <= current_bucket_end:
                        strategies = row[1].split('|')
                        all_strategies.extend(strategies)
                except ValueError:
                    # Skip rows where first column is not a number
                    continue
        
        # Create the concatenated row if we found any strategies
        if all_strategies:
            # Format: "X-Y" where X is start and Y is end of number_of_chunks range
            number_of_chunks_range = f"{current_bucket_start}-{current_bucket_end}"
            
            # Apply unification to the concatenated strategies
            strategies = all_strategies
            
            # Group strategies by their substrategy components
            strategy_groups = {}
            for strategy in strategies:
                # All strategies should have the format count*(percent*sub1_percent*sub2_...)
                # Even simple ones like 1*(100*OURS)
                if '(' not in strategy or ')' not in strategy:
                    # True simple strategy without parentheses (shouldn't happen with current data)
                    if 'simple' not in strategy_groups:
                        strategy_groups['simple'] = []
                    strategy_groups['simple'].append(strategy)
                    continue
                
                # Extract the substrategy components (without counts)
                # Strategy format: "count*(percent*sub1_percent*sub2_...)"
                parts = strategy.split('*(', 1)
                if len(parts) != 2:
                    continue
                
                substrategies_part = parts[1].rstrip(')')
                # Extract just the substrategy names (OURS, THEIRS, etc.)
                sub_names = []
                for item in substrategies_part.split('_'):
                    if '*' in item:
                        sub_name = item.split('*', 1)[1]
                        sub_names.append(sub_name)
                    else:
                        sub_names.append(item)
                
                # Use tuple of substrategy names as key
                key = tuple(sorted(sub_names))
                if key not in strategy_groups:
                    strategy_groups[key] = []
                strategy_groups[key].append(strategy)
            
            # Unify strategies in each group
            unified_strategies = []
            
            # Process simple strategies first
            if 'simple' in strategy_groups:
                # Group simple strategies by their substrategy name for unification
                simple_strategy_groups = {}
                for strategy in strategy_groups['simple']:
                    # Simple strategy format: count*(100*substrategy)
                    if '*' in strategy and '(' in strategy and ')' in strategy:
                        count_part, rest = strategy.split('*(', 1)
                        substrategy = rest.rstrip(')').split('*', 1)[1]  # Extract just the substrategy name
                        
                        if substrategy not in simple_strategy_groups:
                            simple_strategy_groups[substrategy] = []
                        simple_strategy_groups[substrategy].append(strategy)
                    else:
                        # Fallback for malformed simple strategies
                        unified_strategies.append(strategy)
                
                # Unify simple strategies with same substrategy
                for substrategy, strategy_list in simple_strategy_groups.items():
                    if len(strategy_list) == 1:
                        # Only one strategy, no unification needed
                        unified_strategies.append(strategy_list[0])
                    else:
                        # Unify multiple simple strategies
                        total_count = 0
                        for strategy in strategy_list:
                            count_part = strategy.split('*(', 1)[0]
                            total_count += int(count_part)
                        
                        # Create unified simple strategy
                        unified_strategy = f"{total_count}*(100*{substrategy})"
                        unified_strategies.append(unified_strategy)
            
            # Process compound strategies
            for key, strategy_list in strategy_groups.items():
                if key == 'simple':
                    continue
                
                if len(strategy_list) == 1:
                    # Only one strategy in this group, no unification needed
                    unified_strategies.append(strategy_list[0])
                else:
                    # Check if this is actually a simple strategy group (single substrategy)
                    if len(key) == 1:
                        # This is a simple strategy group (single substrategy)
                        # Unify as simple strategies
                        total_count = 0
                        for strategy in strategy_list:
                            count_part = strategy.split('*(', 1)[0]
                            total_count += int(count_part)
                        
                        # Create unified simple strategy
                        substrategy = key[0]
                        unified_strategy = f"{total_count}*(100*{substrategy})"
                        unified_strategies.append(unified_strategy)
                    else:
                        # Unify multiple strategies with same substrategy components (compound)
                        total_count = 0
                        sub_totals = {}
                        
                        # Parse each strategy and accumulate counts
                        for strategy in strategy_list:
                            # Extract count and substrategies
                            count_part, substrategies_part = strategy.split('*(', 1)
                            count = int(count_part)
                            total_count += count
                            
                            # Parse each substrategy
                            for item in substrategies_part.rstrip(')').split('_'):
                                if '*' in item:
                                    percent_str, sub_name = item.split('*', 1)
                                    percent = float(percent_str)
                                    actual_count = (percent / 100) * count
                                    
                                    if sub_name not in sub_totals:
                                        sub_totals[sub_name] = 0
                                    sub_totals[sub_name] += actual_count
                        
                        # Create unified strategy
                        if total_count > 0:
                            # Calculate new percentages
                            relative_substrategies = []
                            for sub_name in sorted(sub_totals.keys()):
                                sub_total = sub_totals[sub_name]
                                percentage = (sub_total / total_count) * 100
                                formatted = f"{percentage:.9g}"
                                relative_substrategies.append(f"{formatted}*{sub_name}")
                            
                            unified_strategy = f"{total_count}*({'_'.join(relative_substrategies)})"
                            unified_strategies.append(unified_strategy)
            
            concatenated_row = [number_of_chunks_range, '|'.join(unified_strategies)]
            bucketed_data.append(concatenated_row)
        
        # Move to next bucket and double the size
        current_bucket_start = current_bucket_end + 1
        bucket_size *= 2
    
    # Relativize the factors in each row so they sum to 100
    relativized_data = []
    for row in bucketed_data:
        if len(row) < 2:
            relativized_data.append(row)
            continue
        
        number_of_chunks = row[0]
        strategies = row[1].split('|')
        
        # Extract the count factors from each strategy
        total_count = 0
        strategy_counts = []
        
        for strategy in strategies:
            if '*' in strategy and '(' in strategy:
                # Extract the count part (before the first '*' that's followed by '(')
                count_part = strategy.split('*(', 1)[0]
                try:
                    count = int(count_part)
                    strategy_counts.append((strategy, count))
                    total_count += count
                except ValueError:
                    # If we can't parse the count, keep the strategy as is
                    strategy_counts.append((strategy, 0))
            else:
                # Keep strategies without the expected format as is
                strategy_counts.append((strategy, 0))
        
        # Calculate relativized factors
        relativized_strategies = []
        if total_count > 0:
            for strategy, count in strategy_counts:
                if count > 0:
                    # Calculate the relativized factor (count / total_count * 100)
                    relativized_factor = (count / total_count) * 100
                    # Format to 9 significant digits
                    formatted_factor = f"{relativized_factor:.9g}"
                    
                    # Replace the original count with the relativized factor
                    # Strategy format: "count*(...)" -> "relativized_factor*(...)"
                    parts = strategy.split('*(', 1)
                    if len(parts) == 2:
                        new_strategy = f"{formatted_factor}*({parts[1]})"
                        relativized_strategies.append(new_strategy)
                    else:
                        relativized_strategies.append(strategy)
                else:
                    relativized_strategies.append(strategy)
        else:
            # If total_count is 0, keep strategies as is
            relativized_strategies = strategies
        
        relativized_data.append([number_of_chunks, '|'.join(relativized_strategies)])
    
    # Write the final output
    with open('relative_numbers_summary.csv', 'w', newline='') as outfile:
        writer = csv.writer(outfile)
        writer.writerows(relativized_data)
    print("\nFinal output: relative_numbers_summary.csv")

if __name__ == '__main__':
    main()