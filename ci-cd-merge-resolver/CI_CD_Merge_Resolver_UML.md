# UML Class Diagram for CI/CD-Enhanced Conflict Resolution System

```mermaid
classDiagram
    %% Main Application
    class CiCdMergeResolverApplication {
        +main(String[] args)
        -collect()
        -generateMergeVariants()
        -analyzeResults()
    }

    %% Core Components
    class RepoCollector {
        -Path cloneDir
        -Path tempDir
        -Path datasetDir
        -RepositoryManager repoManager
        +processExcel(Path excelFile)
        -cloneRepo(String repoName, String url)
        -isMavenProject(Path repo)
        -receiveRepoName(String repoUrl)
    }

    class ExperimentRunner {
        -Path datasetsDir
        -Path repoDatasetsFile
        -Path tempDir
        -RepositoryManager repoManager
        +runTests(Path outputDir, boolean isParallel, boolean isCache)
        -getFilesFromDir(Path dir)
        -getRepoUrl(File dataset)
        +makeAnalysisByDataset(Path dataset, Path repoPath, Path Output, boolean isParallel, boolean isCache)
        -countNumberOfConflictChunks(Path repo, String parent1, String parent2)
    }

    class MergeAnalyzer {
        -Path repositoryPath
        -Path tempDir
        -Path projectTempDir
        -MavenRunner mavenRunner
        -String projectName
        -Path logDir
        -List~Map~String, List~String~~ conflictPatterns
        +buildProjects(String commit1, String commit2, String mergeCommit)
        +runTests(IRunner runner)
        +collectCompilationResults()
        +collectTestResults()
        +countProjects()
    }

    class MetricsAnalyzer {
        -Path resultsDir
        +makeFullAnalysis()
        -loadResults()
        -analyzeResolutionEffectiveness()
        -computeRankings()
        -analyzePerformance()
    }

    %% Utility Classes
    class RepositoryManager {
        -Path repoBaseDir
        -Map~String, RepositoryStatus~ repoStatusCache
        -Path statusFile
        +getRepositoryStatus(String repoName)
        +setRepositoryStatus(String repoName, RepositoryStatus status)
        +shouldDownloadRepository(String repoName)
        +getRepositoryPath(String repoName, String repoUrl)
        +markRepositorySuccess(String repoName)
        +markRepositoryRejected(String repoName, RepositoryStatus rejectionReason)
        -loadStatusCache()
        -saveStatusCache()
    }

    class GitUtils {
        +getGit(Path repoPath)
        +makeMerge(String commit1, String commit2, Git git)
        +getMergeResults(ResolveMerger merger)
        +countConflictChunks(String parent1, String parent2, Git git)
        +getNonConflictObjects(Git git, ObjectId branch1, ObjectId branch2)
        +getObjectsFromCommit(String commitHash, Git git)
        +cloneRepo(Path target, String url)
    }

    class ProjectBuilderUtils {
        +getProjectClass(MergeResult~? extends Sequence~ mergeResult, String filePath)
        +getAllPossibleConflictResolution(ProjectClass projectClass, List~IPattern~ patterns)
        +getProjects(Map~String, List~ProjectClass~~ mapClasses)
        +saveProjects(List~Project~ projects, Map~String, ObjectId~ nonConflictObjects)
    }

    %% Model Classes
    class Project {
        -String name
        -Map~String, String~ files
        +extractPatterns()
        +addFile(String path, String content)
    }

    class ProjectClass {
        -String filePath
        -List~MergeChunk~ conflictChunks
        -String baseContent
        +applyPattern(IPattern pattern, int chunkIndex)
        +getAllVariants(List~IPattern~ patterns)
    }

    %% Pattern Interface and Implementations
    interface IPattern {
        +apply(MergeResult~RawText~ mergeResult, Map~CheckoutCommand.Stage, MergeChunk~ chunks)
    }

    class OursPattern {
        +apply(MergeResult~RawText~ mergeResult, Map~CheckoutCommand.Stage, MergeChunk~ chunks)
    }

    class TheirsPattern {
        +apply(MergeResult~RawText~ mergeResult, Map~CheckoutCommand.Stage, MergeChunk~ chunks)
    }

    class BasePattern {
        +apply(MergeResult~RawText~ mergeResult, Map~CheckoutCommand.Stage, MergeChunk~ chunks)
    }

    class EmptyPattern {
        +apply(MergeResult~RawText~ mergeResult, Map~CheckoutCommand.Stage, MergeChunk~ chunks)
    }

    class CombinePattern {
        +apply(MergeResult~RawText~ mergeResult, Map~CheckoutCommand.Stage, MergeChunk~ chunks)
    }

    %% Data Classes
    class MergeOutputJSON {
        -String mergeCommit
        -String parent1
        -String parent2
        -int numConflictChunks
        -int numConflictFiles
        -int numJavaConflictFiles
        -boolean isMultiModule
        -TestTotal testResults
        -CompilationResult compilationResult
        -VariantsExecution variantsExecution
    }

    class AllMergesJSON {
        -String projectName
        -List~MergeOutputJSON~ merges
    }

    class CompilationResult {
        -boolean success
        -long durationMs
        -String errorMessage
    }

    class TestTotal {
        -int totalTests
        -int passedTests
        -int failedTests
        -int skippedTests
        -long durationMs
    }

    %% Enums
    enum RepositoryStatus {
        NOT_PROCESSED
        PROCESSING
        SUCCESS
        REJECTED_CLONE_FAILED
        REJECTED_NO_POM
        REJECTED_NO_CONFLICTS
        REJECTED_TOO_MANY_CONFLICTS
    }

    %% Relationships
    CiCdMergeResolverApplication --> RepoCollector : uses
    CiCdMergeResolverApplication --> ExperimentRunner : uses
    CiCdMergeResolverApplication --> MetricsAnalyzer : uses

    RepoCollector --> RepositoryManager : contains
    RepoCollector --> GitUtils : uses
    RepoCollector --> DatasetCollector : creates

    ExperimentRunner --> RepositoryManager : contains
    ExperimentRunner --> MergeAnalyzer : uses
    ExperimentRunner --> GitUtils : uses

    MergeAnalyzer --> ProjectBuilderUtils : uses
    MergeAnalyzer --> GitUtils : uses
    MergeAnalyzer --> MavenRunner : contains

    ProjectBuilderUtils --> Project : creates
    ProjectBuilderUtils --> ProjectClass : uses

    ProjectClass --> IPattern : uses
    IPattern <|-- OursPattern
    IPattern <|-- TheirsPattern
    IPattern <|-- BasePattern
    IPattern <|-- EmptyPattern
    IPattern <|-- CombinePattern

    MergeAnalyzer --> MergeOutputJSON : produces
    ExperimentRunner --> AllMergesJSON : produces
    MetricsAnalyzer --> MergeOutputJSON : consumes
    MetricsAnalyzer --> AllMergesJSON : consumes

    RepositoryManager --> RepositoryStatus : uses
```

## Key Components and Their Responsibilities

### 1. Main Application Flow
- **CiCdMergeResolverApplication**: Orchestrates the complete pipeline with three main phases:
  1. Repository collection and analysis
  2. Merge conflict resolution variant generation
  3. Results aggregation and reporting

### 2. Repository Collection Layer
- **RepoCollector**: Clones Git repositories from Excel files, validates Maven projects, and collects merge conflict datasets
- **RepositoryManager**: Manages repository lifecycle with intelligent caching and status tracking
- **DatasetCollector**: Extracts merge conflict information from Git repositories

### 3. Experiment Execution Layer
- **ExperimentRunner**: Coordinates end-to-end evaluation of merge commits across repositories
- **MergeAnalyzer**: Core component that reconstructs merge conflicts, generates resolution variants, and evaluates them via Maven builds
- **MavenRunner**: Executes Maven compilation and tests for project variants

### 4. Analysis Layer
- **MetricsAnalyzer**: Performs post-processing analysis on experiment results, computing rankings and statistical summaries

### 5. Pattern System
- **IPattern Interface**: Defines the contract for conflict resolution patterns
- **Concrete Patterns**: OursPattern, TheirsPattern, BasePattern, EmptyPattern, CombinePattern

### 6. Data Models
- **Project/ProjectClass**: Represent project structures and conflict resolution variants
- **MergeOutputJSON/AllMergesJSON**: Data structures for storing experiment results
- **CompilationResult/TestTotal**: Capture build and test execution outcomes

## Key Relationships

1. **Composition**: RepoCollector and ExperimentRunner contain RepositoryManager instances
2. **Dependency**: Components depend on GitUtils for Git operations and ProjectBuilderUtils for project manipulation
3. **Inheritance**: Multiple pattern classes implement the IPattern interface
4. **Data Flow**: MergeAnalyzer produces MergeOutputJSON, which is consumed by MetricsAnalyzer

This UML represents a sophisticated CI/CD-enhanced conflict resolution system that systematically evaluates different merge resolution strategies using real build and test feedback.