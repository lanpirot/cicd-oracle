# CI/CD-Enhanced Conflict Resolution

This repository contains a research prototype for **analyzing Git merge conflicts** by generating multiple conflict-resolution variants and evaluating them using **CI/CD build and test stages**.  
The goal is to study whether simple resolution patterns (e.g., *ours*, *theirs*) combined with CI/CD feedback can help identify suitable merge resolutions.

The project is developed in the context of an academic thesis and supports large-scale, fully automated experiments.

---

## Table of Contents
- [Overview](#overview)
- [Software Requirements](#software-requirements)
- [Repository Collection](#repository-collection)
- [MergeAnalyzer](#mergeanalyzer)
- [Experiment Setup & Evaluation Collection](#experiment-setup--evaluation-collection)
- [ExperimentRunner](#experimentrunner)
- [Metrics Analysis](#metrics-analysis)


---

## Overview

For a given **merge commit**, the tool:

1. Identifies conflicting files and conflict chunks using JGit
2. Generates all possible resolution variants based on simple resolution patterns. 
You can create your own patterns by implementing the `IPattern` interface in [directory](src/main/java/ch/unibe/cs/mergeci/model/patterns):
    - `OursPattern`
    - `TheirsPattern`
    - `BasePattern`
    - `EmptyPattern`
    - `CombinedPattern`
3. Creates separate project copies for each variant
4. Executes Maven build and test stages for:
    - the original merge
    - each generated variant
5. Collects compilation results, test results, execution times, and applied patterns
6. Stores results in structured JSON files for later analysis

---

## Software Requirements

### Required
- **Java JDK 21 (recommended)**
    - Set in `JAVA_HOME` environment variable
    - **Java 17** not guaranteed to work, but may work with minor modifications
- **Git** (must be available in `PATH`)
- **Apache Maven**
    - From 3.9.x.
    - Installed locally
    - **Maven Daemon (`mvnd`)**, for optimization purposes (optional)


### Supported Platforms
- Created for Windows (Not tested on other platforms)

### Recommended
- Multi-core CPU (variants can be executed in parallel)
- Sufficient disk space, nearly 100GB should  be enough (temporary project copies and logs)

---

### Usage

The workflow consists of **three phases**: dataset collection, experiment execution, and analysis.

---

### Repository Collection

`RepoCollector` is used to **clone multiple Git repositories from an Excel file** and generate datasets (`.xlsx`) of conflict merges for each valid **Maven Java project**.

#### Input format file with list of repositories

An Excel file (`.xlsx`) with at least two columns:

| Column | Description |
|------|-------------|
| A    | Repository name or identifier |
| B    | Git repository URL |

---

#### Dataset Collection

```java
RepoCollector collector =
        new RepoCollector(
                "repos",   // name of directory to clone projects
                "temp",    // temporary working directory
                1,         // start row
                100        // end row
        );
        
// File with list of java projects and their repo URL
collector.processExcel(new File("projects_Java_desc-stars-1000.xlsx")); 
```

### Criteria for Selecting Conflict Merges

During dataset collection, a merge commit is included **only if all of the following conditions are satisfied**:

1. **The repository is a Maven project**
    - The repository must contain a `pom.xml` file at its root.
    - Non-Maven projects are skipped.

2. **The merge contains Java conflicts**
    - At least one conflicting file must have the `.java` extension.
    - Merges with conflicts only in non-Java files are skipped.

3. **The number of collected conflict merges is limited**
    - For each repository, at most `maxConflictMerges` (default: 200) merge commits are analyzed.

4. **The project has at least one passed test**

Only merges that satisfy these criteria are written to the resulting dataset (`.xlsx`) file.

## MergeAnalyzer

[MergeAnalyzer](src/main/java/ch/unibe/cs/mergeci/experimentSetup/MetricsAnalyzer.java) is the core component responsible for **reconstructing merge conflicts, generating alternative resolutions, and evaluating them via compilation and test execution**.

It takes a conflicting merge commit and systematically compares:
- the **human (original) merge resolution**, and
- multiple **automated conflict resolution variants** (e.g., *ours*, *theirs*, *base*, etc.).

---

### Responsibilities

`MergeAnalyzer` performs the following steps:

1. **Reconstructs a conflicting merge**
    - Uses JGit to replay the merge between two parent commits.
    - Extracts all conflicting files and conflict chunks.

2. **Generates resolution variants**
    - For each conflict chunk, applies resolution patterns:
    - Produces all possible combinations of conflict resolutions.
    - Each combination is materialized as a separate project variant.

3. **Builds project variants**
    - Reconstructs full project snapshots by combining:
        - resolved conflict files, and
        - non-conflicting files taken directly from the repository.
    - Additionally reconstructs the original human-resolved merge commit.

4. **Executes compilation and tests**
    - Runs Maven builds and tests for:
        - the original merge, and
        - all generated variants.
    - Execution can be performed with or without build cache.

5. **Collects evaluation data**
    - Compilation status and build time (`CompilationResult`)
    - Test execution statistics (`TestTotal`)
    - Conflict resolution patterns applied per variant
---

### Usage Example

```java
        MergeAnalyzer mergeAnalyzer = new MergeAnalyzer(new File("src/test/resources/test-merge-projects/zemberek-nlp"), "temp");
        mergeAnalyzer.buildProjects("c10e035c4b36e0b4cd50e009fb94b67e8fc51a45", "356fa0178ca851a1ccee41c7a1846a1a19abbd6b", "4b39a3ee35ffcf61f66a783dde2af1d9fbd9c12a");
        mergeAnalyzer.runTests(new MavenExecutionFactory(mergeAnalyzer.getLogDir()).createMavenRunner());
```

## ExperimentRunner

[ExperimentRunner](src/main/java/ch/unibe/cs/mergeci/experimentSetup/evaluationCollection/ExperimentRunner.java) coordinates **end-to-end evaluation of merge commits across multiple repositories and datasets**.  
It connects datasets, Git repositories, merge reconstruction, test execution, and result aggregation into a single reproducible pipeline.

---

### Responsibilities

`ExperimentRunner` performs the following tasks:

1. **Dataset-driven experiment execution**
    - Reads merge datasets from `.xlsx` files.
    - Each dataset corresponds to one Git repository.
    - Each row represents a single merge commit with its parents and metadata.

2. **Repository management**
    - Clones repositories on demand.
    - Cleans up repositories and JGit caches between experiments to avoid interference.

3. **Merge-level analysis**
    - For each merge commit:
        - reconstructs the merge,
        - generates resolution variants via `MergeAnalyzer`,
        - runs compilation and tests,
        - measures execution time.

4. **Result aggregation**
    - Collects:
        - compilation results,
        - test results,
        - execution times,
        - applied conflict resolution patterns.
    - Distinguishes between:
        - the **human merge resolution**, and
        - **automated resolution variants**.

5. **Serialization**
    - Stores results as structured JSON files (`AllMergesJSON`, `MergeOutputJSON`)
    - Output is designed for downstream statistical analysis and ranking.

---

### Usage Example
```java
        ExperimentRunner experimentRunner = new ExperimentRunner(
                new File("experiments/datasets"), // directory with dataset that were collected by `RepoCollector`
                new File("experiments/projects_Java_desc-stars-1000.xlsx"), // Excel file with list of repositories
                new File("experiments/temp") // temporary working directory
        );

        experimentRunner.runTests(new File("experiments/results_cache_optimization"), false); // output directory, build cache optimization flag
```

## Metrics Analysis

The class `MetricsAnalyzer` provides the **post-processing and evaluation layer** of the MergeCI pipeline.  
It operates on the JSON artifacts produced by `ExperimentRunner` and computes aggregate metrics, rankings, and statistical summaries used in the empirical analysis.

Unlike previous components, `MetricsAnalyzer` does **not interact with Git or build systems**.  
It is a pure analysis component that derives insights from already collected experimental results.

---

## MetricsAnalyzer

[MetricsAnalyzer](src/main/java/ch/unibe/cs/mergeci/experimentSetup/MetricsAnalyzer.java) loads multiple experiment result files (`AllMergesJSON`) and performs a comprehensive analysis over all recorded merge scenarios.

---

### Responsibilities

`MetricsAnalyzer` is responsible for:

1. **Loading experiment results**
    - Reads all JSON result files from a given directory
    - Aggregates all merges across repositories into a single working set

2. **Merge classification**
    - Separates:
        - impact vs no-impact merges
        - single-module vs multi-module projects
    - Filters merges by:
        - coverage availability
        - number of conflict chunks

3. **Resolution effectiveness analysis**
    - Detects whether at least one automated resolution:
        - matches, or
        - outperforms
          the human merge resolution
    - Identifies merges where automated resolutions perform strictly better

4. **Pattern-based evaluation**
    - Analyzes uniform conflict resolution patterns
    - Distinguishes between:
        - `OursPattern`
        - `TheirsPattern`
        - mixed pattern resolutions

5. **Ranking**
    - Computes rankings of:
        - Human
        - Ours
        - Theirs
        - Mixed
    - Rankings are computed:
        - globally
        - by coverage range
        - by number of conflict chunks

6. **Performance analysis**
    - Compares execution time of variants against original merge
    - Computes execution-time ratios and distributions

---

### Usage Example

```java
        MetricsAnalyzer metricsAnalyzer = new MetricsAnalyzer(new File("analysis/results_wo_optimization"));

        metricsAnalyzer.makeFullAnalysis();
```
