# CI/CD Merge Conflict Resolver

Research pipeline that studies automated merge conflict resolution by brute-forcing resolution patterns and evaluating them via Maven builds and test suites.

## Research Questions

- **RQ1:** *Are pattern distributions learnable to bias search space exploration?*
- **RQ2:** *Which variant generation mode is the fastest?*
- **RQ3:** *Can we find variants as good or better than the human baseline?*

## Quick Start

```bash
mvn clean package
java -cp "target/*:target/lib/*" ch.unibe.cs.mergeci.CiCdMergeResolverApplication
```

Resume from previous run (default) or start fresh:
```bash
# Resume — skips already-processed repos and merges
java -cp "target/*:target/lib/*" ch.unibe.cs.mergeci.CiCdMergeResolverApplication

# Fresh run — deletes all output and reprocesses everything
java -DfreshRun=true -cp "target/*:target/lib/*" ch.unibe.cs.mergeci.CiCdMergeResolverApplication
```

## Requirements

- **Java 21** (JDK, set in `JAVA_HOME`)
- **Git** (in `PATH`)
- **Apache Maven 3.9+**

## Pipeline

Three sequential phases coordinated by `CiCdMergeResolverApplication`:

```
Phase 1: collect()              → RepoCollector → MergeConflictCollector
Phase 2: generateMergeVariants() → ResolutionVariantRunner
Phase 3: analyzeResults()       → ResultsPresenter
```

### Phase 1 — Dataset Collection

`RepoCollector` reads a CSV file of GitHub repos, clones Maven projects, and invokes `MergeConflictCollector`, which:

1. Finds merge commits with Java file conflicts
2. Resamples if the first batch yields fewer than `MAX_CONFLICT_MERGES` qualifying merges
3. Runs a baseline Maven build per merge and writes a per-project dataset CSV file
4. Skips merges listed in `training_mergeIDs.csv` (training data, filtered to prevent leakage)

Status is persisted in `.repo_status.json` so interrupted runs resume cleanly.

### Phase 2 — Variant Experiments

`ResolutionVariantRunner` iterates over four experiment modes — a 2×2 matrix of (cache/no-cache) × (parallel/sequential):

| Mode | Parallel | Cache |
|------|----------|-------|
| `human_baseline` | — | — |
| `cache_parallel` | ✓ | ✓ |
| `cache_sequential` | — | ✓ |
| `parallel` | ✓ | — |
| `no_optimization` | — | — |

`human_baseline` only records the human-authored merge result (no variants). All other modes use it as the time-budget base: `budget = TIMEOUT_MULTIPLIER × humanBaselineSeconds`.

Variant execution is **just-in-time**: variants are written to disk in batches of `MAX_THREADS`, run, and immediately deleted. The per-variant timeout shrinks as the deadline approaches (`deadline − now()`). In `cache_parallel`, the first variant runs synchronously to warm the Maven cache before parallel variants copy from it.

### Phase 3 — Analysis

`ResultsPresenter` loads all JSON result files and delegates to `StatisticsReporter`, `VariantResolutionAnalyzer`, `VariantRankingAnalyzer`, and `ExecutionTimeAnalyzer`.

Visualizations can be generated via:
```bash
python3 scripts/plot_results.py               # real data
python3 scripts/plot_results.py --mockup      # instant preview with synthetic data
```

For manual inspection of noteworthy or edge-case merges, Phase 2 saves human/tentative/variant file triplets to `conflict_files/` (±100-line windows around each conflict for large files); run `python3 scripts/view_noteworthy.py` to generate a self-contained `viewer.html` with a three-panel diff view and color-coded conflict regions.

## Resolution Patterns

Each conflict chunk is resolved by one pattern:

| Pattern | Meaning |
|---------|---------|
| `OURS` | Take the "ours" side |
| `THEIRS` | Take the "theirs" side |
| `BASE` | Use the base version |
| `EMPTY` | Remove the block |

Compound patterns combine atomics (e.g., `OURS:BASE`). `StrategySelector` + `PatternHeuristics` order the search by historical frequency. For large numbers of conflict chunks the space is exponential; the time budget is the natural stopping condition.

## Configuration

All paths, timeouts, and feature flags live in `AppConfig.java`.

### Java installations (machine-specific)

`JAVA_HOMES` maps Java major versions to JDK paths. The tool selects the closest available JDK ≥ the version in `pom.xml`:

```java
public static final Map<Integer, Path> JAVA_HOMES = Map.of(
    8,  Paths.get("/usr/lib/jvm/<your-jdk-8>"),
    11, Paths.get("/usr/lib/jvm/<your-jdk-11>"),
    17, Paths.get("/usr/lib/jvm/<your-jdk-17>"),
    21, Paths.get("/usr/lib/jvm/<your-jdk-21>")
);
```

### Key tunables

| Property | Default | Effect |
|----------|---------|--------|
| `freshRun` | `false` | Delete all output and start from scratch |
| `coverageActivated` | `true` | Collect JaCoCo coverage data |
| `maxConflictMerges` | `10` | Max qualifying merges per project |
| `MAX_THREADS` | `min(RAM_GB/8, 16)` | Parallel build threads |
| `TIMEOUT_MULTIPLIER` | `10` | Variant budget = baseline × multiplier |

## Data Directories

```
/home/lanpirot/data/bruteforcemerge/
  conflict_datasets/     # Per-project CSV files (Phase 1 output)
  variant_experiments/   # Per-project JSON results, one subdir per mode (Phase 2 output)
  test/                  # All test output
/home/lanpirot/tmp/
  bruteforce_repos/      # Cloned repositories
  bruteforce_tmp/        # Variant working directories
```

## Build & Test

```bash
mvn compile                          # Compile only
mvn test                             # Run all tests
mvn test -Dtest=ClassName            # Run a single test class
mvn test -Dtest=ClassName#methodName # Run a single test method
mvn clean package                    # Full build + package
```

Test property overrides:
```bash
mvn test -DfreshRun=true
mvn test -DmaxConflictMerges=3
mvn test -DcoverageActivated=false
```

## Pattern Heuristics

Learn historical pattern distributions from conflict data to guide variant ordering:

```bash
python3 src/main/resources/pattern-heuristics/learn_historical_pattern_distribution.py
```

**Input**: `all_conflicts.csv` (conflict chunks CSV, extracted from the SQL dump)
**Output**: `learnt_historical_pattern_distribution.csv` in `src/main/resources/pattern-heuristics/` — loaded at runtime by `PatternHeuristics`

The script groups by conflict chunk count, ranks patterns by frequency, unifies similar strategies, and relativizes frequencies per row. Youngest 10% of merges are excluded from training to prevent data leakage (those IDs are saved to `training_mergeIDs.csv` for training and `evaluation_mergeIDs.csv` for evaluation).

## RQ1 Pipeline

All large generated artefacts for RQ1 live under `~/data/bruteforcemerge/rq1/` (configured in `AppConfig.RQ1_DIR`):

| Subdirectory | Contents |
|---|---|
| `rq1/` | `all_conflicts.csv`, `merge_commits.csv`, `maven_check_cache.json` |
| `rq1/cv_folds/` | `evaluation_fold{k}.csv`, `learnt_*_train{k}.csv` — generated by `learn_cv_folds.py` |
| `rq1/checkpoints/` | `autoregressive_model_fold{k}.pt`, `autoregressive_fold_assignment.json` |
| `rq1/predictions/` | `autoregressive_predictions_fold{k}.csv` (~200 MB each) |
| `rq1/results/` | `cv_results.csv`, `cv_trajectory.csv`, LaTeX tables, PDF plots |

### Running the RQ1 pipeline

**Prerequisites**

- A GitHub personal access token (needed to probe projects not yet in `maven_check_cache.json`).
  Set it once:
  ```bash
  echo 'export GITHUB_TOKEN=ghp_yourToken' >> ~/.bashrc && source ~/.bashrc
  ```
- The Python **virtual environment** in `.venv/` at the repo root — contains PyTorch with CUDA.
  The pipeline script picks it up automatically.
  **Do not use the system `python3`** for training — it has a CPU-only PyTorch build (10–50× slower).

**One-liner (full pipeline from scratch):**
```bash
./src/main/resources/pattern-heuristics/run_rq1.sh
```

Resume from a specific step (e.g. after changing the model, skip re-extraction):
```bash
./src/main/resources/pattern-heuristics/run_rq1.sh --from-step 4
```

| Step | What it does | Time |
|------|-------------|------|
| 1 | Extract `all_conflicts.csv` + `merge_commits.csv` from SQL dump | ≈2 min |
| 2 | Learn global pattern distribution (`learnt_historical_pattern_distribution.csv`) | ≈30 s |
| 3 | Generate chronological 10-fold CV splits (fold 0 = oldest 10% of merges) | ≈2 min |
| 4 | Train autoregressive model + generate per-fold predictions | hours (GPU) |
| 5 | Evaluate all strategies → `cv_results.csv`, LaTeX tables, PDF plots | ≈30 min |

After step 5 the script prints a **temporal sanity check**: per-fold ML-AR hit rate.
Fold 9 (newest merges) should not perform much worse than folds 0–8; a large drop would
indicate the model is overfitting to temporal patterns rather than structural ones.
