# CI/CD Merge Conflict Resolver

Research tool for analyzing Git merge conflicts by generating resolution variants and evaluating them using CI/CD feedback.

## Quick Start

```bash
# Build and run
mvn clean package
java -cp "target/*:target/lib/*" ch.unibe.cs.mergeci.CiCdMergeResolverApplication
```

## Requirements

- **Java 21** (JDK, set in `JAVA_HOME`)
- **Git** (in `PATH`)
- **Apache Maven 3.9+**
- **Maven Daemon** (optional, for optimization)

## Overview

For each merge commit with conflicts, the tool:

1. Identifies conflicting files and chunks (using JGit)
2. Generates resolution variants using patterns (`OursPattern`, `TheirsPattern`, etc.)
3. Creates separate project copies for each variant
4. Executes Maven build and tests for all variants
5. Collects results (compilation status, test results, execution times)
6. Stores structured JSON output for analysis

## Configuration

Key settings in `AppConfig.java`:
- `BASE_DIR`: Base directory for operations
- `MAX_CONFLICT_MERGES`: Maximum merges per repository (default: 10 production, 5 test)
- `MAX_CONFLICT_CHUNKS`: Maximum conflict chunks to process (default: 6)
- `MAX_THREADS`: Parallel execution threads

## Complete Workflow

### Step 1: Collect Datasets

Clone repositories and generate conflict datasets:

```java
RepoCollector collector = new RepoCollector(
    "repos",   // clone directory
    "temp",    // working directory
    1,         // start row
    100        // end row
);
collector.processExcel(new File("projects_Java_desc-stars-1000.xlsx"));
```

**Output**: Excel files in `conflict_datasets/` with merge metadata

### Step 2: Run Experiments

Test resolution variants across all datasets:

```java
ExperimentRunner runner = new ExperimentRunner(
    new File("experiments/datasets"),
    new File("experiments/projects_Java_desc-stars-1000.xlsx"),
    new File("experiments/temp")
);
runner.runTests(new File("experiments/results"), false);
```

**Output**: JSON files in `experiments/results/`

### Step 3: Analyze Results

```java
MetricsAnalyzer analyzer = new MetricsAnalyzer(
    new File("analysis/results")
);
analyzer.makeFullAnalysis();
```

### Step 4: Pattern Heuristics (Optional)

Generate pattern heuristics from conflict chunks:

```bash
cd src/main/resources/pattern-heuristics
python3 complete_workflow.py
```

**Input**: `Java_chunks_original.csv` (conflict chunk data)
**Output**: `relative_numbers_summary.csv` (pattern frequency analysis)

## Execution Modes

Two modes control behavior:

### FRESH_RUN Mode
Clean slate - deletes everything and starts from scratch:
```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-DfreshRun=true"
```

### Resume Mode (Default)
Continue from where work stopped:
```bash
mvn spring-boot:run
```

## Repository Management

- **Successful repos**: Preserved between runs
- **Rejected repos**: Empty directory markers
- **Status tracking**: `.repo_status.json`
- **Smart downloads**: Only new/failed repositories

## Merge Selection Criteria

A merge is included in the dataset if:
1. Repository is a Maven project (has `pom.xml`)
2. Merge contains Java conflicts (`.java` files)
3. Project has at least one passing test
4. Within limit (`MAX_CONFLICT_MERGES` per repository)

## Timeout Handling

- **Conflict Collection**: 5-minute timeout (timed-out merges are skipped)
- **Variant Testing**: 20-minute timeout (timed-out variants marked as `TIMEOUT`)

## Directory Structure

```
/home/lanpirot/data/bruteforcemerge/
├── conflict_datasets/        # Dataset Excel files
│   ├── atmosphere.xlsx
│   └── jackson-databind.xlsx
├── experiments/              # Experiment JSON results
│   ├── no_optimization/
│   ├── parallel/
│   └── cache_parallel/
├── repos/                    # Cloned repositories
└── temp/                     # Temporary working directory
```

## Complete Workflow Example

```bash
# Fresh start
mvn clean compile && mvn spring-boot:run -Dspring-boot.run.jvmArguments="-DfreshRun=true"

# Generate pattern heuristics
cd src/main/resources/pattern-heuristics
python3 complete_workflow.py
cd ../../../..

# Commit results
git add .
git commit -m "feat: complete experiment results with pattern heuristics"
```

## Resolution Patterns

Implement `IPattern` interface to create custom patterns:
- `OursPattern`: Take "ours" side
- `TheirsPattern`: Take "theirs" side
- `BasePattern`: Use base version
- `EmptyPattern`: Remove conflicting code
- `CompoundPattern`: Combine multiple patterns

## Success Criteria

A variant is successful if:
- ✅ Compilation succeeds (all modules)
- ✅ All tests pass (0 failures, 0 errors)

## Research Questions

1. How often does brute-force resolution succeed?
2. Can simple patterns match human quality?
3. What is the computational cost?
4. Do optimizations (caching, parallelization) help?
5. Which patterns are most effective?

## Advanced: MergeAnalyzer

Core component for single-merge analysis:

```java
MergeAnalyzer analyzer = new MergeAnalyzer(
    new File("src/test/resources/test-merge-projects/zemberek-nlp"),
    "temp"
);
analyzer.buildProjects("mergeCommit", "parent1", "parent2");
analyzer.runTests(mavenRunner);
```

## Documentation

- **AGENTS.md**: Architecture and pipeline details
- **RECURSIVE_MERGE_STRATEGY.md**: Multiple merge base support
- **CRITICAL_UNTESTED_ANALYSIS.md**: Test coverage analysis
