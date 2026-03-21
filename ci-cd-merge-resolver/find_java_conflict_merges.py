#!/usr/bin/env python3

import csv
import os
from pathlib import Path

def find_java_conflict_merges():
    """Find all merges with at least one Java file conflict."""
    
    # Location of conflict datasets
    dataset_dir = Path("/home/lanpirot/data/bruteforcemerge/conflict_datasets/")
    
    if not dataset_dir.exists():
        print(f"Dataset directory not found: {dataset_dir}")
        return
    
    # Column indices from Utility.MERGECOLUMN
    MERGE_COMMIT_COL = 0
    NUM_JAVA_FILES_COL = 4
    
    merges_with_java_conflicts = []
    
    # Try to use pandas if available for Excel reading
    try:
        import pandas as pd
        
        # Process each dataset file
        for dataset_file in dataset_dir.glob("*.xlsx"):
            try:
                # Read Excel file
                df = pd.read_excel(dataset_file)
                
                # Filter rows with Java files > 0
                java_conflicts = df[df.iloc[:, NUM_JAVA_FILES_COL] > 0]
                
                for _, row in java_conflicts.iterrows():
                    merges_with_java_conflicts.append({
                        'dataset': dataset_file.name,
                        'merge_commit': row.iloc[MERGE_COMMIT_COL],
                        'num_java_files': row.iloc[NUM_JAVA_FILES_COL]
                    })
                    
            except Exception as e:
                print(f"Error reading {dataset_file}: {e}")
                
    except ImportError:
        print("Pandas not available. Trying alternative approach...")
        # Fallback: Check if there are any CSV versions or try other methods
        for dataset_file in dataset_dir.glob("*.csv"):
            try:
                with open(dataset_file, 'r', encoding='utf-8') as f:
                    reader = csv.reader(f)
                    headers = next(reader)  # Skip header
                    
                    for row in reader:
                        if len(row) <= NUM_JAVA_FILES_COL:
                            continue
                        
                        try:
                            num_java_files = int(row[NUM_JAVA_FILES_COL])
                            
                            if num_java_files > 0:
                                merge_commit = row[MERGE_COMMIT_COL]
                                merges_with_java_conflicts.append({
                                    'dataset': dataset_file.name,
                                    'merge_commit': merge_commit,
                                    'num_java_files': num_java_files
                                })
                        except (ValueError, IndexError):
                            continue
                            
            except Exception as e:
                print(f"Error reading {dataset_file}: {e}")
    
    # Write results to CSV
    output_file = dataset_dir / "merges_with_java_conflicts.csv"
    with open(output_file, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow(['Dataset', 'Merge Commit', 'Number of Java Files'])
        
        for merge in merges_with_java_conflicts:
            writer.writerow([merge['dataset'], merge['merge_commit'], merge['num_java_files']])
    
    print(f"Found {len(merges_with_java_conflicts)} merges with Java conflicts")
    print(f"Results written to: {output_file}")
    
    return merges_with_java_conflicts

if __name__ == "__main__":
    find_java_conflict_merges()