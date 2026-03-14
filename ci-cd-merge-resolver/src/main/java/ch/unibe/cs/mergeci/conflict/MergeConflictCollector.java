package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.repoCollection.BuildFailureLog;
import ch.unibe.cs.mergeci.repoCollection.CollectionResult;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Collects merge conflict datasets from repositories.
 * Entry point that delegates to specialized components for filtering, processing, and orchestration.
 */
public class MergeConflictCollector {
    private final Path projectPath;
    private final Path tempPath;
    private final String projectName;
    private final int maxConflictMerges;
    private final DatasetCollectionOrchestrator orchestrator;

    public MergeConflictCollector(Path projectPath, Path tempPath, int maxConflictMerges) throws IOException {
        this(projectPath, tempPath, maxConflictMerges, null);
    }

    public MergeConflictCollector(Path projectPath, Path tempPath, int maxConflictMerges,
                                   BuildFailureLog failureLog) throws IOException {
        this.projectPath = projectPath;
        this.tempPath = tempPath;
        this.projectName = projectPath.toFile().getName();
        this.maxConflictMerges = maxConflictMerges;

        // Initialize components
        MergeFilter mergeFilter = new MergeFilter();
        ch.unibe.cs.mergeci.runner.maven.MavenRunner mavenRunner = new ch.unibe.cs.mergeci.runner.maven.MavenRunner(tempPath, AppConfig.MAVEN_BUILD_TIMEOUT);
        MergeCheckoutProcessor mergeProcessor = new MergeCheckoutProcessor(mavenRunner);
        DatasetRowBuilder rowBuilder = new DatasetRowBuilder();

        // Create orchestrator with all dependencies
        this.orchestrator = new DatasetCollectionOrchestrator(
                mergeFilter,
                mergeProcessor,
                rowBuilder,
                failureLog);
    }

    /**
     * Collect dataset of merge conflicts and save to Excel file.
     *
     * @param excelOutFile Path to output Excel file
     * @param repoName Repository name
     * @param repoUrl Repository URL
     * @return Collection result with status and statistics
     * @throws Exception if collection fails
     */
    public CollectionResult collectDataset(Path excelOutFile, String repoName, String repoUrl) throws Exception {
        return orchestrator.collectDataset(
                projectPath,
                tempPath,
                projectName,
                maxConflictMerges,
                excelOutFile,
                repoName,
                repoUrl);
    }
}
