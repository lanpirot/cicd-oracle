# Architecture Guide

## Recent Improvements

### ✅ Multiple Merge Base Support (RECURSIVE Strategy)
- **Changed**: `GitUtils.makeMerge()` now uses `RECURSIVE` merge strategy (was `RESOLVE`)
- **Impact**: Handles criss-cross merges with multiple merge bases (previously threw `NoMergeBaseException`)
- **Verification**: Tests explicitly verify multiple merge bases are detected using `RevWalk`
- **Evidence**: `MultipleMergeBasesTest` shows "Found 2 merge base(s)" in output
- **Background**: Git's default strategy, handles complex merge histories recursively

### ✅ Comprehensive Test Suite
- **Status**: 189 tests passing (100% success rate)
- **Coverage**: Added 60+ tests across critical components
- **Focus areas**: Execution time analysis, XML parsing, Maven cache, Jacoco reports, coverage strategies
- **Quality**: All edge cases covered (timeouts, invalid data, multi-module projects)

### ✅ `-fae` (Fail-at-End) Aware Test Evaluation
- **Changed**: `MergeCheckoutProcessor` now uses `modulesPassed > 0` (not `compilationSuccess`) to decide if test results are valid
- **Impact**: With Maven `-fae`, tests can run even when some modules fail; results are only discarded on complete compilation failure (`modulesPassed == 0`)
- **Success criteria tightened**: `isSuccessful()` now requires `modulesPassed > 0 && numPassedTests > 0`
- **Dataset expanded**: `numberOfModules` and `modulesPassed` are now recorded per merge

## Purpose

Research pipeline to evaluate **brute-force merge conflict resolution** in Java projects. Tests all pattern combinations (OURS, THEIRS) to find successful automated resolutions.

**Key Questions**:
- Success rate: How often does automated resolution succeed?
- Quality: Can it match/exceed human resolutions?
- Cost: Computational overhead?
- Optimization: Impact of caching and parallelization?

---

## Pipeline Flow

```
Excel Input → RepoCollector → MergeConflictCollector → ResolutionVariantRunner → ResultsPresenter
```

**Step-by-step**:
1. **Excel Input**: Java repository list (names + URLs)
2. **RepoCollector**: Clone repos, track metadata
3. **MergeConflictCollector**: Identify compilable merges with conflicts
4. **ResolutionVariantRunner**: Test all resolution patterns
5. **ResultsPresenter**: Analyze and compare to human baseline

---

## Components

### 1. RepoCollector

**Purpose**: Clone and validate repositories.

**Responsibilities**:
- Clone Git repositories (skip if exists)
- Track metadata: Maven project?, total merges, conflicts
- Invoke `MergeConflictCollector` for each repo
- Resume capability (skip processed repos)

**Input**: Excel (repo names + URLs)
**Output**: Triggers `MergeConflictCollector`

---

### 2. MergeConflictCollector

**Purpose**: Collect baseline merge data.

**Responsibilities**:
- Find merge commits with conflicts
- Verify baseline compiles and tests pass (**5-minute timeout**)
- Count conflicting files (total + Java files)
- Record compilation/test execution time
- Detect multi-module projects
- **Skip timed-out merges** (baseline quality check)

**Input**: Cloned repository
**Output**: Dataset Excel (`.xlsx`)

**Dataset Format**:
```
| Merge Commit | Parent1 | Parent2 | # Tests | Conflict Files | Java Files | Compiled | Tests Pass | Time | Multi-Module | # Modules | Modules Passed |
```

---

### 3. ResolutionVariantRunner

**Purpose**: Test resolution strategies.

**Responsibilities**:
- Read dataset Excel files
- Generate resolution variants (pattern combinations)
- Compile each variant (**20-minute timeout**)
- Run tests on successful compilations
- Mark timed-out variants as `TIMEOUT`
- Compare to human baseline
- Output results to JSON

**Three Execution Modes**:
1. **no_optimization**: Sequential, no cache
2. **parallel**: Parallel execution
3. **cache_parallel**: Parallel + Maven cache

**Input**: Dataset Excel
**Output**: JSON results

---

### 4. ResultsPresenter

**Purpose**: Analyze experiment results.

**Responsibilities**:
- Load experiment JSON files
- Calculate metrics:
  - Success rates
  - Timeout vs failure breakdown
  - Comparison to baseline
  - Execution time analysis
  - Pattern effectiveness
  - Multi-module impact

**Input**: Experiment JSON
**Output**: Console metrics/reports

---

## Timeout Strategy

### Conflict Collection: 5 minutes
- **Applied**: During baseline verification
- **Rationale**: Quick check - long builds likely problematic
- **Action**: **SKIP** merge (exclude from dataset)

### Variant Testing: 20 minutes
- **Applied**: During variant compilation
- **Rationale**: Multiple compilations take longer
- **Action**: Mark as **TIMEOUT** (count as failure)

**Status Enum**: `SUCCESS`, `FAILURE`, `SKIPPED`, `TIMEOUT`

---

## Resolution Patterns

**Current Patterns**:
- `OursPattern`: Take "ours" side
- `TheirsPattern`: Take "theirs" side
- `BasePattern`: Use base version
- `EmptyPattern`: Remove conflict
- `CompoundPattern`: Combine multiple patterns dynamically

### Brute-Force Strategy

For **N conflict chunks**, generate **2^N combinations**:
- 1 chunk: 2 variants (O, T)
- 2 chunks: 4 variants (OO, OT, TO, TT)
- 3 chunks: 8 variants (OOO, OOT, OTO, OTT, TOO, TOT, TTO, TTT)

---

## Execution Modes

### FRESH_RUN Mode

Clean slate - deletes everything:
```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-DfreshRun=true"
```

**Deletes**:
- All cloned repositories
- All dataset files
- All experiment results

### Resume Mode (Default)

Continue from last state:
```bash
mvn spring-boot:run
```

**Skips**:
- Already-cloned repos
- Already-collected datasets
- Already-executed experiments

---

## Data Flow

```
┌─────────────────┐
│ projects.xlsx   │ (Repo list)
└────────┬────────┘
         ▼
┌─────────────────┐
│ RepoCollector   │ (Clone + metadata)
└────────┬────────┘
         ▼
┌─────────────────┐
│MergeConflict    │ (Find compilable merges)
│Collector        │
└────────┬────────┘
         ▼
┌─────────────────┐
│ dataset.xlsx    │ (Baseline data)
└────────┬────────┘
         ▼
┌─────────────────┐
│ResolutionVariant│ (Test strategies)
│Runner           │
└────────┬────────┘
         ▼
┌─────────────────┐
│ results.json    │ (Variant results)
└────────┬────────┘
         ▼
┌─────────────────┐
│ResultsPresenter │ (Analyze metrics)
└─────────────────┘
```

---

## Directory Structure

```
/home/lanpirot/data/bruteforcemerge/
├── conflict_datasets/        # Excel datasets
├── experiments/
│   ├── no_optimization/      # Mode 1 JSON results
│   ├── parallel/             # Mode 2 JSON results
│   └── cache_parallel/       # Mode 3 JSON results
├── repos/                    # Cloned repositories
└── temp/                     # Working directory
```

---

## Configuration

**AppConfig.java** settings:
- `MAX_CONFLICT_MERGES`: Merges per repo (10 production, 5 test)
- `MAX_CONFLICT_CHUNKS`: Max chunks per merge (6)
- `MAVEN_BUILD_TIMEOUT_CONFLICT_COLLECTION_MINUTES`: Baseline timeout (5)
- `MAVEN_BUILD_TIMEOUT_VARIANT_TESTING_MINUTES`: Variant timeout (20)
- `INPUT_PROJECT_XLSX`: Repository list path

---

## Success Criteria

Variant is **successful** if:
1. ✅ At least one module compiled successfully (`modulesPassed > 0`)
2. ✅ At least one test passed (`numPassedTests > 0`)
3. ✅ Build did not time out

Variant is **better than baseline** if:
- Variant succeeds AND baseline succeeds
- Variant ≤ test failures than baseline
- Reasonable execution time

---

## Running the Pipeline

### Complete Workflow

```bash
# 1. Fresh start
mvn clean compile && mvn spring-boot:run -Dspring-boot.run.jvmArguments="-DfreshRun=true"

# 2. Generate pattern heuristics (optional)
cd src/main/resources/pattern-heuristics
python3 complete_workflow.py  # Processes Java_chunks_original.csv
cd ../../../..

# 3. Commit results
git add .
git commit -m "feat: experiment results with pattern analysis"
```

### Resume Previous Run

```bash
mvn clean compile && mvn spring-boot:run
```

### Re-run Experiments Only

```bash
# Delete only experiment results
rm -rf /home/lanpirot/data/bruteforcemerge/experiments
rm -rf /home/lanpirot/data/bruteforcemerge/temp

# Resume (preserves repos + datasets)
mvn clean compile && mvn spring-boot:run
```

---

## JSON Output Format

```json
{
  "projectName": "atmosphere",
  "merges": [{
    "mergeCommit": "0ca55a81...",
    "parent1": "18630d78...",
    "parent2": "fd2b7573...",
    "numConflictChunks": 1,
    "isMultiModule": true,
    "totalExecutionTime": 55,
    "compilationResult": {
      "buildStatus": "SUCCESS",
      "totalTime": 44.305
    },
    "testResults": {
      "runNum": 262,
      "failuresNum": 0
    },
    "variantsExecution": {
      "executionTimeSeconds": 9,
      "variants": [{
        "variantName": "atmosphere_0",
        "compilationResult": {...},
        "testResults": {...},
        "conflictPatterns": {
          "Calculator.java": ["OursPattern"]
        }
      }]
    }
  }]
}
```

---

## Key Features

### Repository Management
- **Success tracking**: `.repo_status.json`
- **Smart cloning**: Skip existing repos
- **Visual markers**: Empty dirs for rejected repos

### Merge Selection
1. Maven project (has `pom.xml`)
2. Contains Java conflicts
3. Has ≥1 passing test
4. Within merge limit

### Optimization Strategies
1. **Parallel execution**: Multiple variants simultaneously
2. **Build caching**: Copy `target/` directories
3. **Resume capability**: Skip completed work

---

## Pattern Heuristics Workflow

Analyze conflict resolution patterns from collected data:

```bash
cd src/main/resources/pattern-heuristics
python3 complete_workflow.py
```

**Pipeline**:
1. Filter Java files only
2. Merge chunks by merge ID
3. Clean pattern names (NONCANONICAL → NON)
4. Remove prefixes (CANONICAL_, CHUNK_, etc.)
5. Group by chunk count
6. Sort substrategies
7. Count and rank patterns
8. Summarize strategies
9. Compute relative frequencies

**Input**: `Java_chunks_original.csv`
**Output**: `relative_numbers_summary.csv`

---

## Research Questions

1. **Success Rate**: How often does ≥1 automated strategy succeed?
2. **Quality**: Can automated match/exceed human quality?
3. **Cost**: Computational overhead of brute-force?
4. **Optimization**: Impact of caching + parallelization?
5. **Patterns**: Which patterns are most effective?
6. **Complexity**: Does multi-module affect success?

---

## Future Enhancements

- More patterns (Empty, Base, Combine)
- ML-based pattern selection
- Gradle project support
- Coverage-based comparison
- Better timeout handling
