# CI/CD Merge Conflict Resolver

Research pipeline that studies automated merge conflict resolution by brute-forcing resolution patterns and evaluating them via Maven builds and test suites.

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
cd src/main/resources/pattern-heuristics
python3 learn_historical_pattern_distribution.py
```

**Input**: `Java_chunks_original.csv` (or any conflict chunks CSV, all file types)
**Output**: `learnt_historical_pattern_distribution.csv` — loaded at runtime by `PatternHeuristics`

The script groups by conflict chunk count, ranks patterns by frequency, unifies similar strategies, and relativizes frequencies per row. Youngest 10% of merges are excluded from training to prevent data leakage (those IDs are saved to `training_mergeIDs.csv` for training and `evaluation_mergeIDs.csv` for evaluation).
