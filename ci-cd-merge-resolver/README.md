# CI/CD Merge Conflict Resolver

Research pipeline that studies automated merge conflict resolution by brute-forcing resolution patterns and evaluating them via Maven builds and test suites.

## Quick Start

```bash
mvn clean package
java -cp "target/*:target/lib/*" ch.unibe.cs.mergeci.CiCdMergeResolverApplication
```

## Requirements

- **Java 21** (JDK, set in `JAVA_HOME`)
- **Git** (in `PATH`)
- **Apache Maven 3.9+**

## Pipeline Overview

Three sequential phases, all coordinated by `CiCdMergeResolverApplication`:

```
Phase 0/1: collect()              → RepoCollector → MergeConflictCollector
Phase 2:   generateMergeVariants() → ResolutionVariantRunner
Phase 3:   analyzeResults()       → ResultsPresenter
```

### Phase 1 — Dataset Collection

`RepoCollector` reads an Excel file of GitHub repositories, clones each, and for Maven projects invokes `MergeConflictCollector`. The collector:

1. Finds merge commits that produce conflicts via JGit
2. **Resamples** if the first batch yields some but fewer than `MAX_CONFLICT_MERGES` Java-conflict merges — successive batches of unvisited commits are inspected until the quota is filled or no further improvement is found
3. Checks out each qualifying merge, runs a baseline Maven build, and writes a per-project dataset Excel file

Status is persisted in `.repo_status.json` so interrupted runs resume cleanly.

### Phase 2 — Variant Experiments

`ResolutionVariantRunner` iterates over four experiment modes:

| Mode | Parallel | Cache | SkipVariants |
|------|----------|-------|--------------|
| `human_baseline` | — | — | ✓ |
| `cache_parallel` | ✓ | ✓ | — |
| `parallel` | ✓ | — | — |
| `no_optimization` | — | — | — |

For each merge, `MavenExecutionFactory` runs variants **just-in-time**:

- Variant directories are written to disk on demand, in batches of `MAX_THREADS`
- **Rolling execution** (parallel modes): a free thread slot is refilled as soon as any variant finishes, eliminating straggler idle time
- **Cache warm-up** (`cache_parallel`): the first variant runs synchronously to populate the local Maven cache before parallel variants copy from it
- The time budget is `TIMEOUT_MULTIPLIER × normalizedBaselineTime`; each variant receives only the remaining time until the deadline
- Variant directories are deleted immediately after their results are collected

`human_baseline` results are written to JSON first; subsequent modes read `humanBaselineSeconds` from that JSON.

### Phase 3 — Analysis

`ResultsPresenter` loads all JSON result files and delegates to:
`StatisticsReporter`, `VariantResolutionAnalyzer`, `VariantRankingAnalyzer`, `ExecutionTimeAnalyzer`.

## Configuration

All paths, timeouts, and feature flags live in `AppConfig.java`.

### Java installations (machine-specific)

`JAVA_HOMES` maps Java major versions to JDK paths. The tool automatically selects the closest available JDK ≥ the version declared in a project's `pom.xml`:

```java
public static final Map<Integer, Path> JAVA_HOMES = Map.of(
    8,  Paths.get("/usr/lib/jvm/<your-jdk-8>"),
    11, Paths.get("/usr/lib/jvm/<your-jdk-11>"),
    17, Paths.get("/usr/lib/jvm/<your-jdk-17>"),
    21, Paths.get("/usr/lib/jvm/<your-jdk-21>")
);
```

Find available JDKs with:
```bash
ls /usr/lib/jvm/                          # Linux
ls /Library/Java/JavaVirtualMachines/     # macOS
```

Only include versions that are actually present on disk.

### Key tunables

| Property | Default | Effect |
|----------|---------|--------|
| `freshRun` | `false` | Delete all output and start from scratch |
| `coverageActivated` | `true` | Collect JaCoCo coverage data |
| `maxConflictMerges` | `10` | Max Java-conflict merges collected per project |
| `MAX_THREADS` | `min(RAM_GB/8, 16)` | Parallel build threads |
| `TIMEOUT_MULTIPLIER` | `10` | Variant budget = baseline × multiplier |

Override at runtime:
```bash
java -DfreshRun=true -DmaxConflictMerges=5 -cp "target/*:target/lib/*" \
     ch.unibe.cs.mergeci.CiCdMergeResolverApplication
```

## Execution Modes

### Fresh run
```bash
java -DfreshRun=true -cp "target/*:target/lib/*" ch.unibe.cs.mergeci.CiCdMergeResolverApplication
```
Deletes all output directories and processes everything from scratch.

### Resume (default)
```bash
java -cp "target/*:target/lib/*" ch.unibe.cs.mergeci.CiCdMergeResolverApplication
```
Skips already-processed repositories and merges; picks up where work stopped.

## Data Directories

```
/home/lanpirot/data/bruteforcemerge/
  conflict_datasets/     # Per-project Excel files (Phase 1 output)
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

## Resolution Patterns

A conflict chunk is resolved by assigning one atomic pattern:

| Pattern | Meaning |
|---------|---------|
| `OURS` | Take the "ours" side |
| `THEIRS` | Take the "theirs" side |
| `BASE` | Use the base version |
| `EMPTY` | Remove the conflicting block |

Compound patterns combine atomics (e.g., `OURSTHEIRS`). `StrategySelector` and `PatternHeuristics` (from `relative_numbers_summary.csv`) order the search. For large numbers of conflict chunks the space is exponential; the time budget is the natural stopping condition.

## Merge Selection Criteria

A merge is included in the dataset when:
1. The repository is a Maven project (`pom.xml` present)
2. The merge produces Java file conflicts
3. The baseline build compiles and at least one test passes
4. The project has not yet reached `MAX_CONFLICT_MERGES` qualifying merges

## Variant Success Criteria

A variant is successful when:
- At least one module compiled (`modulesPassed > 0`)
- At least one test passed (`numPassedTests > 0`)
- The build did not time out

## Pattern Heuristics (optional)

Regenerate the pattern frequency table from raw conflict data:

```bash
cd src/main/resources/pattern-heuristics
python3 complete_workflow.py
```

**Input**: `Java_chunks_original.csv`
**Output**: `relative_numbers_summary.csv` (used at runtime by `PatternHeuristics`)
