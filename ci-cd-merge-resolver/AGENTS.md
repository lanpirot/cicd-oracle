# CI/CD Merge Resolver - Architecture Documentation

## Project Purpose

This project implements a **research pipeline** to evaluate **brute-force merge conflict resolution strategies** in Java repositories. The goal is to systematically test pattern-based resolution strategies (e.g., OURS-OURS-OURS, OURS-OURS-THEIRS, OURS-THEIRS-OURS, etc.) to determine:

- **Success Rate**: How often does at least one automated strategy succeed?
- **Quality**: Can automated strategies match or exceed human baseline resolutions?
- **Cost**: What is the computational cost of trying multiple strategies?
- **Optimization**: How much do caching and parallelization improve performance?

Many resolution strategies will fail (this is expected), but we aim to find cases where automated resolution succeeds, comparing results against human-resolved merge commits.

---

## Pipeline Architecture

### Overall Flow

```
Excel Input → RepoCollector → MergeConflictCollector → ResolutionVariantRunner → ResultsPresenter
```

### Detailed Flow

1. **Excel Input**: List of Java repositories (names + URLs)
2. **RepoCollector**: Clones repositories and tracks metadata
3. **MergeConflictCollector**: Identifies merge commits with conflicts that are compilable and testable
4. **ResolutionVariantRunner**: Tests resolution strategies in three modes (no optimization, parallel, cache+parallel)
5. **ResultsPresenter**: Analyzes results and compares against human baseline

---

## Timeout Handling

To prevent hanging builds from blocking the pipeline, **different timeouts** are configured for different stages:

### Conflict Collection Timeout: **5 minutes**
- Applied when checking if human-resolved merges compile and pass tests
- **Rationale**: Quick verification - if baseline takes >5 minutes, it's likely stuck
- **Behavior**: Timed-out merges are **SKIPPED** (not added to dataset)
- **Why skip**: No point testing variants if baseline is problematic

### Variant Testing Timeout: **20 minutes**
- Applied when testing resolution variants
- **Rationale**: Variants may take longer due to multiple compilations
- **Behavior**: Timed-out variants are marked as **TIMEOUT** (treated like failures in success rate)
- **Why not skip**: Other variants for the same merge might succeed

### Timeout Detection
- Maven process is forcibly killed after timeout
- `CompilationResult` detects incomplete builds (missing "BUILD SUCCESS/FAILURE")
- Status enum: `SUCCESS`, `FAILURE`, `SKIPPED`, `TIMEOUT`

### ResultsPresenter Differentiation
- **FAILURE**: Code compiled but tests failed, or compilation errors
- **TIMEOUT**: Build killed due to time limit
- Metrics can differentiate: "X% failed due to timeout vs compilation errors"

---

## Components

### 1. RepoCollector

**Purpose**: Orchestrates repository cloning and initial analysis.

**Responsibilities**:
- Clone Git repositories from URLs (skip if already downloaded)
- Track repository metadata:
  - Is it a Maven project?
  - Total commits
  - Total merges
  - Merges with conflicts
  - Compilable and testable merges
- Invoke MergeConflictCollector for each repository
- Manage resume capability (skip already-processed repos)

**Input**: Excel file with repository names and URLs
**Output**: Invokes MergeConflictCollector for each repo

---

### 2. MergeConflictCollector

**Purpose**: Collects baseline data about merge commits with conflicts.

**Responsibilities**:
- Find merge commits with conflicts in a repository
- For each merge:
  - Check out the merge commit
  - Verify it compiles successfully (with **5-minute timeout**)
  - Verify tests pass
  - Count conflicting files (total and Java files)
  - Record compilation and test execution time
  - Detect multi-module Maven projects
- **Skip timed-out merges**: If baseline takes >5 minutes, merge is excluded
- Filter to only include **compilable and testable** merges (baseline quality check)
- Output merge metadata to Excel (.xlsx)

**Input**: Cloned Git repository
**Output**: Dataset Excel file with merge metadata

**Dataset Format (.xlsx)**:
```
| Merge Commit | Parent1 | Parent2 | Number of Tests | Conflicting Files | Java Files | Compilation Success | Test Success | Elapsed Time | Multi Module |
|--------------|---------|---------|-----------------|-------------------|------------|---------------------|--------------|--------------|--------------|
| 0ca55a81... | 18630d78... | fd2b7573... | 262 | 1 | 1 | TRUE | TRUE | 37.88 | TRUE |
```

---

### 3. ResolutionVariantRunner

**Purpose**: Tests automated resolution strategies against collected merge conflicts.

**Responsibilities**:
- Read dataset Excel files from MergeConflictCollector
- For each merge commit:
  - Generate resolution variants using patterns (OursPattern, TheirsPattern, etc.)
  - Apply patterns to conflict chunks
  - Compile each variant (with **20-minute timeout**)
  - Run tests on successful compilations
  - Record results (compilation status, test results, execution time)
  - **Mark timed-out variants as TIMEOUT** (treated as failure in metrics)
- Execute in three experiment modes:
  - **no_optimization**: Sequential execution, no caching
  - **parallel**: Parallel execution across variants
  - **cache_parallel**: Parallel + Maven build cache optimization
- Compare variant results against human baseline
- Output results to JSON

**Input**: Dataset Excel files (.xlsx)
**Output**: Experiment result JSON files

**JSON Output Format**:
```json
{
  "projectName": "atmosphere",
  "merges": [{
    "mergeCommit": "0ca55a81...",
    "parent1": "18630d78...",
    "parent2": "fd2b7573...",
    "numConflictFiles": 1,
    "numJavaConflictFiles": 1,
    "numConflictChunks": 1,
    "isMultiModule": true,
    "totalExecutionTime": 55,
    "compilationResult": {
      "moduleResults": [...],
      "buildStatus": "SUCCESS",
      "totalTime": 44.305
    },
    "testResults": {
      "runNum": 262,
      "failuresNum": 0,
      "errorsNum": 0,
      "elapsedTime": 37.08
    },
    "variantsExecution": {
      "executionTimeSeconds": 9,
      "variants": [{
        "variantName": "atmosphere_0",
        "compilationResult": {...},
        "testResults": {...},
        "conflictPatterns": {
          "file.java": ["OursPattern"]
        }
      }]
    }
  }]
}
```

---

### 4. ResultsPresenter

**Purpose**: Analyzes and presents experiment results.

**Responsibilities**:
- Load experiment JSON results
- Calculate metrics:
  - **Success rates**: % of merges with at least one successful variant
  - **Failure analysis**: Differentiate compilation errors, test failures, and timeouts
  - **Timeout statistics**: % of variants that timed out vs failed normally
  - **Comparison to baseline**: Variants equal/better than human resolution
  - **Execution time analysis**: Cost of brute-force approach
  - **Pattern effectiveness**: Which patterns succeed most often
  - **Multi-module impact**: Success rates for single vs. multi-module projects
- Present results to console/reports

**Input**: Experiment JSON files
**Output**: Console output with metrics and analysis

**Key Method**: `presentFullResults()`

---

## Key Concepts

### Resolution Patterns

**Current Patterns**:
- **OursPattern**: Take the "ours" side of all conflicts
- **TheirsPattern**: Take the "theirs" side of all conflicts

**Future Patterns** (planned):
- EmptyPattern: Remove conflicting code
- BasePattern: Use base version
- CombinePattern: Combine both sides

### Brute-Force Strategy

For a merge with **N conflict chunks**, there are **2^N possible combinations** (each chunk can be resolved with OURS or THEIRS). For example:
- 1 chunk: 2 variants (O, T)
- 2 chunks: 4 variants (OO, OT, TO, TT)
- 3 chunks: 8 variants (OOO, OOT, OTO, OTT, TOO, TOT, TTO, TTT)

The pipeline generates and tests **all combinations** to find successful resolutions.

### Experiment Modes

Three modes optimize the brute-force evaluation:

1. **no_optimization** (`no_cache_no_parallel`):
   - Sequential execution
   - No Maven build caching
   - Baseline for comparison

2. **parallel** (`no_cache_parallel`):
   - Parallel execution of variants
   - No Maven build caching
   - Tests parallelization benefit

3. **cache_parallel** (`cache_parallel`):
   - Parallel execution of variants
   - Maven build cache enabled (copy `target/` directories)
   - Tests combined optimization benefit

### FRESH_RUN Mode

**Purpose**: Complete clean slate for experiments.

**When enabled** (`-DfreshRun=true`):
- Deletes all cloned repositories
- Deletes all dataset files
- Deletes all experiment results
- Starts pipeline from scratch

**Default behavior** (FRESH_RUN=false):
- Resume from where work stopped
- Skip already-processed repositories
- Skip already-collected datasets
- Skip already-executed experiments

---

## Data Flow Summary

```
┌─────────────────────┐
│ projects.xlsx       │  List of Java repos
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  RepoCollector      │  Clone repos, track metadata
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│MergeConflictCollector│ Find compilable merges with conflicts
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ dataset_name.xlsx   │  Merge metadata (baseline)
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ResolutionVariant    │  Test all resolution strategies
│Runner               │  (3 experiment modes)
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ project_name.json   │  Variant results + comparison
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ ResultsPresenter    │  Analyze and present metrics
└─────────────────────┘
```

---

## Directory Structure

```
/home/lanpirot/data/bruteforcemerge/
├── datasets/              # Dataset Excel files (MergeConflictCollector output)
│   ├── atmosphere.xlsx
│   ├── jackson-databind.xlsx
│   └── ...
├── experiments/           # Experiment JSON results
│   ├── no_optimization/   # Mode 1 results
│   ├── parallel/          # Mode 2 results
│   └── cache_parallel/    # Mode 3 results
│       ├── atmosphere.json
│       ├── jackson-databind.json
│       └── ...

/home/lanpirot/tmp/
├── bruteforce_repos/      # Cloned repositories
│   ├── atmosphere/
│   ├── jackson-databind/
│   └── ...
└── bruteforce_tmp/        # Temporary working directory
    ├── projects/          # Variant project copies during execution
    └── log/              # Maven compilation logs
```

---

## Running the Pipeline

### Full Pipeline (FRESH_RUN)
```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-DfreshRun=true"
```

### Resume Mode (Default)
```bash
mvn spring-boot:run
```

### Configuration

Edit `AppConfig.java` to configure:
- `MAX_CONFLICT_MERGES`: Max merges per project (10 production, 5 test)
- `MAX_CONFLICT_CHUNKS`: Max chunks per merge (6 default)
- `MAVEN_BUILD_TIMEOUT_CONFLICT_COLLECTION_MINUTES`: Timeout for baseline checking (5 minutes default)
- `MAVEN_BUILD_TIMEOUT_VARIANT_TESTING_MINUTES`: Timeout for variant testing (20 minutes default)
- `INPUT_PROJECT_XLSX`: Path to repository list

---

## Success Criteria

A resolution variant is considered **successful** if:
1. ✅ Compilation succeeds (all modules)
2. ✅ All tests pass (0 failures, 0 errors)

A merge is considered **better than baseline** if:
- Variant succeeds AND human baseline succeeds
- Variant has ≤ test failures than baseline
- Variant execution time is reasonable

---

## Research Questions

This pipeline helps answer:
1. **How often does brute-force resolution succeed?**
2. **Can simple patterns (OURS/THEIRS) match human quality?**
3. **What is the cost of trying all combinations?**
4. **Do optimizations (caching, parallelization) make it practical?**
5. **Which patterns are most effective?**
6. **Does project complexity (multi-module) affect success rates?**

---

## Future Enhancements

- Add more resolution patterns (Empty, Base, Combine)
- Implement smarter pattern selection (ML-based)
- Support for Gradle projects
- Coverage-based comparison (not just compilation + tests)
- Timeout handling for hanging builds