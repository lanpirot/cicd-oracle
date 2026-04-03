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
mvn test -DmaxConflictMerges=3       # Limit merges per project (default: 5)
```

## Architecture Overview

This is a **research pipeline** that studies automated merge conflict resolution. The RQ2 and RQ3 pipelines read merge data from a pre-computed `maven_conflicts.csv` (extracted from an SQL dump), clone repositories on demand, and run variant experiments directly — there is no separate collection phase.

```
RQ2/RQ3:  RQ2PipelineRunner / RQ3PipelineRunner → ResolutionVariantRunner
Analysis: RQPipelineRunner.analyzeResults()      → ResultsPresenter.presentFullResults()
```

`BuildFailureClassifier` classifies failed builds into categories: **INFRA_FAILURE** (dead Maven repo, blocked mirror, missing system tool, unresolvable parent POM — permanently unfixable), **BROKEN_MERGE** (genuine `javac` errors in the merged source, e.g. committed conflict markers — a variant may fix it), and generic build failure. The RQ pipelines check for infra failures and zero-test builds after the human_baseline mode and skip projects that can never produce usable results.

### Variant Experiments (`experiment`, `runner`)

`ResolutionVariantRunner` iterates over 5 experiment modes defined in `Utility.Experiments` — a 2×2 matrix of (cache/no-cache) × (parallel/sequential), plus a baseline. Enum constants map to display names via the `name` field:

| Enum Constant | Display Name (`name`) | Parallel | Cache | SkipVariants |
|---------------|----------------------|----------|-------|--------------|
| `human_baseline` | `human_baseline` | — | — | true |
| `no_cache_no_parallel` | `no_optimization` | — | — | — |
| `cache_no_parallel` | `cache_sequential` | — | ✓ | — |
| `no_cache_parallel` | `parallel` | ✓ | — | — |
| `cache_parallel` | `cache_parallel` | ✓ | ✓ | — |

For every merge in a dataset, `MergeExperimentRunner` creates a `VariantBuildContext` (lazy state, no disk I/O yet), then `MavenExecutionFactory` creates a just-in-time runner. Variants are generated on-demand via `context.nextVariant()` in batches of `MAX_THREADS`, built to disk, run through Maven, then immediately deleted. The time budget is `TIMEOUT_MULTIPLIER × normalizedBaselineTime`; the deadline is checked before each variant starts (ever-decreasing per-variant timeout = `deadline − now()`).

`human_baseline` results are written to JSON first; subsequent modes read `budgetBasisSeconds` from that JSON to skip re-running the baseline build. In cache modes, the first variant warms the Maven cache; subsequent variants copy from it.

For **broken-baseline merges** (`baselineBroken=true`), the stored baseline is the module-normalized time from the merge's own build (e.g. 4 s with 1/2 modules → 8 s). When no modules succeeded, the fallback is `MAVEN_BUILD_TIMEOUT` (600 s).

### Analysis (`present`)

`ResultsPresenter.presentFullResults()` loads all JSON files and delegates to `StatisticsReporter.presentFullResults()`, which uses `VariantResolutionAnalyzer`, `VariantRankingAnalyzer`, and `ExecutionTimeAnalyzer`.

### Variant / Pattern Model (`model`, `model/patterns`)

A merge has N conflict chunks. Each variant assigns one pattern per chunk. Atomic patterns (`IPattern` implementations): `OursPattern`, `TheirsPattern`, `BasePattern`, `EmptyPattern`. Compound patterns (`CompoundPattern`) combine atomics with colon notation (e.g., `OURS:BASE`). `StrategySelector` + `PatternHeuristics` (loaded via `PatternHeuristics.loadFromFile()` from per-fold CSVs under `RQ1_CV_FOLDS_DIR`, e.g. `learnt_historical_pattern_distribution_train{k}.csv`) pick the order in which assignments are tried. For large N, the search space is exponential; the time budget is the natural stopping condition.

RQ1 large artefacts (`all_conflicts.csv`, fold CSVs, `.pt` checkpoints, prediction CSVs, results) live under `~/data/bruteforcemerge/rq1/` with subdirs `cv_folds/`, `checkpoints/`, `predictions/`, `results/`. Python scripts remain in `src/main/resources/pattern-heuristics/`. Paths are defined in `AppConfig.RQ1_*`.

**Fold-correct inference in RQ2 and RQ3.** Both pipelines use `MLARGeneratorFactory` → `MLARGenerator` → `predict_mlar.py`. For each merge, `predict_mlar.py` consults `autoregressive_fold_assignment.json` (written by `train_autoregressive_model.py`) to determine which fold the merge belongs to, then loads `autoregressive_model_fold{k}.pt` — the checkpoint trained *without* fold k. The heuristic variant generator similarly reads `learnt_historical_pattern_distribution_train{k}.csv` for fold k. This guarantees RQ2/3 never use a model or heuristic distribution that was trained on the merge being evaluated. `predict_mlar.py` exits with a hard error if the fold assignment file is missing or does not contain the requested merge ID; it must be (re-)generated before starting an RQ2/3 run.

### Key Configuration (`config/AppConfig.java`)

Central source of truth for all paths, timeouts, Java installations, and feature flags. Machine-specific paths (e.g., `JAVA_HOMES`) must match the actual JDK installations on the host. `MAX_THREADS` is computed dynamically by `computeMaxThreads()` as `max(1, min((MemAvailable − 5 GB) / peakBuildRamBytes, cores − 2))`, falling back to 4 on error.

Overridable at runtime via system properties: `freshRun`, `maxConflictMerges`, `reanalyzeSuccess`, `rq3BestMode`.

### Maven Execution (`runner/maven`)

`MavenRunner` provides `run_no_optimization()` (delegates to `SequentialStrategy`). The core experiment loop in `MavenExecutionFactory.createJustInTimeRunner(isParallel, isCache)` handles all four variant modes directly using the two boolean flags — it does not use `MavenRunner` for variants. The human baseline runs via `MavenRunner.run_no_optimization()`. All builds use `-B -fae -Dmaven.test.failure.ignore=true` plus static-analysis skips (PMD, Checkstyle, SpotBugs, JaCoCo, etc.) so partial results are always collected. `MavenProcessExecutor` enforces a per-process timeout in seconds (not minutes).

JSON output per variant includes: `variantIndex`, `ownExecutionSeconds`, `timedOut`, `budgetExhausted`, plus `CompilationResult` and `TestTotal` summaries.

## Test Infrastructure

All test classes extend `BaseTest`, which auto-cleans test output directories before/after each test. Test repositories live under `src/test/resources/test-merge-projects/` and are version-controlled — never auto-cleaned.

Test directories (defined in `AppConfig`): `TEST_TMP_DIR`, `TEST_EXPERIMENTS_TEMP_DIR`, `TEST_COVERAGE_DIR`, `TEST_DATASET_DIR`.

## Data Directories (runtime, not in repo)

```
/home/lanpirot/data/bruteforcemerge/
  common/                # Shared inputs: all_conflicts.csv, maven_conflicts.csv, maven_check_cache.json
  variant_experiments/   # Generic pipeline output (per-project JSON, one subdir per mode)
  rq2/                   # RQ2 output (RQ2_VARIANT_EXPERIMENT_DIR)
  rq3/                   # RQ3 output (RQ3_VARIANT_EXPERIMENT_DIR)
  rq1/                   # RQ1 artefacts: cv_folds/, checkpoints/, predictions/, results/
  test/                  # All test output
/home/lanpirot/tmp/
  bruteforce_repos/      # Cloned repositories
  bruteforce_tmp/        # Variant working directories
```

## Plot Color Scheme (ColorBrewer Paired)

Use these colors consistently across all RQ plots:

### RQ1 (variant generators)

| Role       | Hex       | Description              |
|------------|-----------|--------------------------|
| Uniform    | `#a6cee3` | light blue               |
| Heuristic  | `#33a02c` | dark green               |
| ML-AR      | `#1f78b4` | dark blue                |

### RQ2/RQ3 (execution modes)

Hue encodes sequential (red) vs. parallel (green); shade encodes cache (lighter = with cache).

| Mode               | Short | Hex       | Description    |
|--------------------|-------|-----------|----------------|
| Human Baseline     | HB    | `#a6cee3` | light blue     |
| Sequential         | S     | `#e31a1c` | red            |
| Sequential + Cache | S+    | `#fb9a99` | light red      |
| Parallel           | P     | `#33a02c` | dark green     |
| Parallel + Cache   | P+    | `#b2df8a` | light green    |
