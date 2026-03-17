# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Agent / Model Selection

For simple tasks (file lookups, grep searches, quick reads), prefer using the `Grep`/`Glob` tools directly or spawn a `haiku` subagent rather than a full `sonnet`/`opus` agent. Reserve heavier models for complex reasoning and multi-step implementation tasks.

## Build & Test Commands

```bash
mvn compile                          # Compile only
mvn test                             # Run all tests
mvn test -Dtest=ClassName            # Run a single test class
mvn test -Dtest=ClassName#methodName # Run a single test method
mvn clean package                    # Full build + package
```

System property overrides for tests:
```bash
mvn test -DfreshRun=true             # Force fresh run (deletes all output dirs)
mvn test -DmaxConflictMerges=3       # Limit merges per project
mvn test -DcoverageActivated=false   # Disable JaCoCo
```

## Architecture Overview

This is a **research pipeline** that studies automated merge conflict resolution. It runs three sequential phases:

```
Phase 0/1: collect()              → RepoCollector → MergeConflictCollector
Phase 2:   generateMergeVariants() → ResolutionVariantRunner
Phase 3:   analyzeResults()       → ResultsPresenter
```

### Phase 0/1 — Repository Collection (`repoCollection`, `conflict`)

`RepoCollector` reads an Excel file of GitHub repositories, clones each, detects build tools, and for Maven projects invokes `MergeConflictCollector`. The conflict collector finds merge commits with Java conflicts, checks out each merge, runs a baseline Maven build (600s timeout), and writes a per-project dataset Excel file.

Status is persisted in `.repo_status.json` so interrupted runs resume cleanly. `NOT_PROCESSED_BUT_CLONED` means the repo was cloned but processing never completed — it skips the clone but re-runs analysis.

### Phase 2 — Variant Experiments (`experiment`, `runner`)

`ResolutionVariantRunner` iterates over all 4 experiment modes defined in `Utility.Experiments`:

| Mode | Parallel | Cache | SkipVariants |
|------|----------|-------|--------------|
| `human_baseline` | — | — | true |
| `cache_parallel` | ✓ | ✓ | — |
| `parallel` | ✓ | — | — |
| `no_optimization` | — | — | — |

For every merge in a dataset, `MergeExperimentRunner` creates a `VariantBuildContext` (lazy state, no disk I/O yet), then `MavenExecutionFactory` creates a just-in-time runner. Variants are generated on-demand via `context.nextVariant()` in batches of `MAX_THREADS`, built to disk, run through Maven, then immediately deleted. The time budget is `TIMEOUT_MULTIPLIER × normalizedBaselineTime`; the deadline is checked before each variant starts (ever-decreasing per-variant timeout = `deadline − now()`).

`human_baseline` results are written to JSON first; subsequent modes read `humanBaselineSeconds` from that JSON to skip re-running the baseline build.

### Phase 3 — Analysis (`present`)

`ResultsPresenter` loads all JSON files and delegates to specialized analyzers: `StatisticsReporter`, `VariantResolutionAnalyzer`, `VariantRankingAnalyzer`, `ExecutionTimeAnalyzer`.

### Variant / Pattern Model (`model`, `model/patterns`)

A merge has N conflict chunks. Each variant assigns one pattern per chunk. Atomic patterns: `OURS`, `THEIRS`, `BASE`, `EMPTY`. Compound patterns combine these (e.g., `OURSTHEIRS`). `StrategySelector` + `PatternHeuristics` (loaded from `src/main/resources/pattern-heuristics/relative_numbers_summary.csv`) pick the order in which assignments are tried. For large N, the search space is exponential; the time budget is the natural stopping condition.

### Key Configuration (`config/AppConfig.java`)

Central source of truth for all paths, timeouts, Java installations, and feature flags. Machine-specific paths (e.g., `JAVA_HOMES`) must match the actual JDK installations on the host. `MAX_THREADS` is computed dynamically as `min(RAM_GB / 8, 16)`.

Overridable at runtime via system properties: `freshRun`, `coverageActivated`, `maxConflictMerges`.

### Maven Execution (`runner/maven`)

`MavenRunner` dispatches to three strategies: `SequentialStrategy`, `ParallelStrategy`, `CacheParallelStrategy`. All variants are run with `-fae -Dmaven.test.failure.ignore=true` so partial results are always collected. Coverage adds `jacoco:prepare-agent test jacoco:report` to the command. `MavenProcessExecutor` enforces a per-process timeout in seconds (not minutes).

## Test Infrastructure

All test classes extend `BaseTest`, which auto-cleans test output directories before/after each test. Test repositories live under `src/test/resources/test-merge-projects/` and are version-controlled — never auto-cleaned.

Test directories (defined in `AppConfig`): `TEST_TMP_DIR`, `TEST_EXPERIMENTS_TEMP_DIR`, `TEST_COVERAGE_DIR`, `TEST_DATASET_DIR`.

## Data Directories (runtime, not in repo)

```
/home/lanpirot/data/bruteforcemerge/
  conflict_datasets/     # Per-project Excel datasets (Phase 1 output)
  variant_experiments/   # Per-project JSON results, one subdir per mode (Phase 2 output)
  test/                  # All test output
/home/lanpirot/tmp/
  bruteforce_repos/      # Cloned repositories
  bruteforce_tmp/        # Working scratch space
```
