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

This is a **research pipeline** that studies automated merge conflict resolution. The RQ2 and RQ3 pipelines read merge data from a pre-computed `merge_commits.csv` (extracted from an SQL dump), clone repositories on demand, and run variant experiments directly — there is no separate collection phase.

```
RQ2/RQ3:  RQ2PipelineRunner / RQ3PipelineRunner → ResolutionVariantRunner
Analysis: analyzeResults()                       → ResultsPresenter
```

A legacy collection pipeline (`RepoCollector` → `MergeConflictCollector`) still exists in the `repoCollection` and `conflict` packages but is not used by the current RQ pipelines. `BuildFailureClassifier` classifies failed builds into categories: **INFRA_FAILURE** (dead Maven repo, blocked mirror, missing system tool, unresolvable parent POM — permanently unfixable), **BROKEN_MERGE** (genuine `javac` errors in the merged source, e.g. committed conflict markers — a variant may fix it), and generic build failure. The RQ pipelines check for infra failures and zero-test builds after the human_baseline mode and skip projects that can never produce usable results.

### Variant Experiments (`experiment`, `runner`)

`ResolutionVariantRunner` iterates over 5 experiment modes defined in `Utility.Experiments` — a 2×2 matrix of (cache/no-cache) × (parallel/sequential), plus a baseline:

| Mode | Parallel | Cache | SkipVariants |
|------|----------|-------|--------------|
| `human_baseline` | — | — | true |
| `cache_parallel` | ✓ | ✓ | — |
| `cache_sequential` | — | ✓ | — |
| `parallel` | ✓ | — | — |
| `no_optimization` | — | — | — |

For every merge in a dataset, `MergeExperimentRunner` creates a `VariantBuildContext` (lazy state, no disk I/O yet), then `MavenExecutionFactory` creates a just-in-time runner. Variants are generated on-demand via `context.nextVariant()` in batches of `MAX_THREADS`, built to disk, run through Maven, then immediately deleted. The time budget is `TIMEOUT_MULTIPLIER × normalizedBaselineTime`; the deadline is checked before each variant starts (ever-decreasing per-variant timeout = `deadline − now()`).

`human_baseline` results are written to JSON first; subsequent modes read `humanBaselineSeconds` from that JSON to skip re-running the baseline build. In cache modes, the first variant warms the Maven cache; subsequent variants copy from it.

For **broken-baseline merges** (`baselineBroken=true`), the stored baseline is the module-normalized time from the merge's own build (e.g. 4 s with 1/2 modules → 8 s). When no modules succeeded, the fallback is `MAVEN_BUILD_TIMEOUT` (600 s).

### Analysis (`present`)

`ResultsPresenter` loads all JSON files and delegates to specialized analyzers: `StatisticsReporter`, `VariantResolutionAnalyzer`, `VariantRankingAnalyzer`, `ExecutionTimeAnalyzer`.

### Variant / Pattern Model (`model`, `model/patterns`)

A merge has N conflict chunks. Each variant assigns one pattern per chunk. Atomic patterns: `OURS`, `THEIRS`, `BASE`, `EMPTY`. Compound patterns combine atomics with colon notation (e.g., `OURS:BASE`). `StrategySelector` + `PatternHeuristics` (loaded from `src/main/resources/pattern-heuristics/learnt_historical_pattern_distribution.csv`) pick the order in which assignments are tried. For large N, the search space is exponential; the time budget is the natural stopping condition.

RQ1 large artefacts (`all_conflicts.csv`, fold CSVs, `.pt` checkpoints, prediction CSVs, results) live under `~/data/bruteforcemerge/rq1/` with subdirs `cv_folds/`, `checkpoints/`, `predictions/`, `results/`. Python scripts remain in `src/main/resources/pattern-heuristics/`. Paths are defined in `AppConfig.RQ1_*`.

`MergeFilter` loads `training_mergeIDs.csv` from the classpath and exposes `isTrainingMerge()`. `DatasetCollectionOrchestrator` skips training merges during dataset collection to prevent data leakage (the heuristics model was trained on those merges).

**Fold-correct inference in RQ2 and RQ3.** Both pipelines use `MLARGeneratorFactory` → `MLARGenerator` → `predict_mlar.py`. For each merge, `predict_mlar.py` consults `autoregressive_fold_assignment.json` (written by `train_autoregressive_model.py`) to determine which fold the merge belongs to, then loads `autoregressive_model_fold{k}.pt` — the checkpoint trained *without* fold k. The heuristic variant generator similarly reads `learnt_historical_pattern_distribution_train{k}.csv` for fold k. This guarantees RQ2/3 never use a model or heuristic distribution that was trained on the merge being evaluated. `predict_mlar.py` exits with a hard error if the fold assignment file is missing or does not contain the requested merge ID; it must be (re-)generated before starting an RQ2/3 run.

### Key Configuration (`config/AppConfig.java`)

Central source of truth for all paths, timeouts, Java installations, and feature flags. Machine-specific paths (e.g., `JAVA_HOMES`) must match the actual JDK installations on the host. `MAX_THREADS` is computed dynamically as `min(RAM_GB / 8, 16)`.

Overridable at runtime via system properties: `freshRun`, `coverageActivated`, `maxConflictMerges`.

### Maven Execution (`runner/maven`)

`MavenRunner` dispatches to four strategies: `SequentialStrategy`, `ParallelStrategy`, `CacheParallelStrategy`, `CacheSequentialStrategy`. All variants are run with `-fae -Dmaven.test.failure.ignore=true` so partial results are always collected. Coverage adds `jacoco:prepare-agent test jacoco:report` to the command. `MavenProcessExecutor` enforces a per-process timeout in seconds (not minutes).

JSON output per variant includes: `variantIndex`, `ownExecutionSeconds`, `timedOut`, `budgetExhausted`, plus `CompilationResult` and `TestTotal` summaries.

## Test Infrastructure

All test classes extend `BaseTest`, which auto-cleans test output directories before/after each test. Test repositories live under `src/test/resources/test-merge-projects/` and are version-controlled — never auto-cleaned.

Test directories (defined in `AppConfig`): `TEST_TMP_DIR`, `TEST_EXPERIMENTS_TEMP_DIR`, `TEST_COVERAGE_DIR`, `TEST_DATASET_DIR`.

## Data Directories (runtime, not in repo)

```
/home/lanpirot/data/bruteforcemerge/
  variant_experiments/   # Per-project JSON results, one subdir per mode
  test/                  # All test output
/home/lanpirot/tmp/
  bruteforce_repos/      # Cloned repositories
  bruteforce_tmp/        # Working scratch space
```

## Plot Color Scheme (ColorBrewer Paired)

Use these colors consistently across all RQ plots:

| Role       | Hex       | Description              |
|------------|-----------|--------------------------|
| Uniform    | `#a6cee3` | light blue               |
| Heuristic  | `#33a02c` | dark green               |
| ML-AR      | `#1f78b4` | dark blue                |
| (backup)   | `#b2df8a` | light green (other plots)|
