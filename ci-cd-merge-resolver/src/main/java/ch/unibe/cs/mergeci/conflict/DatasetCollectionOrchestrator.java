package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.repoCollection.BuildFailureLog;
import ch.unibe.cs.mergeci.repoCollection.BuildFailureLog.MergeFailureType;
import ch.unibe.cs.mergeci.repoCollection.CollectionResult;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.RepositoryStatus;
import ch.unibe.cs.mergeci.util.Utility;
import ch.unibe.cs.mergeci.util.model.MergeInfo;
import org.eclipse.jgit.api.Git;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the dataset collection workflow.
 * Coordinates loading merges, filtering, parallel processing, and result aggregation.
 */
public class DatasetCollectionOrchestrator {
    private final MergeFilter mergeFilter;
    private final MergeCheckoutProcessor mergeProcessor;
    private final DatasetRowBuilder rowBuilder;
    private final BuildFailureLog failureLog;  // nullable

    public DatasetCollectionOrchestrator(
            MergeFilter mergeFilter,
            MergeCheckoutProcessor mergeProcessor,
            DatasetRowBuilder rowBuilder) {
        this(mergeFilter, mergeProcessor, rowBuilder, null);
    }

    public DatasetCollectionOrchestrator(
            MergeFilter mergeFilter,
            MergeCheckoutProcessor mergeProcessor,
            DatasetRowBuilder rowBuilder,
            BuildFailureLog failureLog) {
        this.mergeFilter = mergeFilter;
        this.mergeProcessor = mergeProcessor;
        this.rowBuilder = rowBuilder;
        this.failureLog = failureLog;
    }

    /**
     * Collect dataset from a repository.
     *
     * @param projectPath Path to the Git repository
     * @param tempPath Temporary directory for checkouts
     * @param projectName Project name
     * @param maxConflictMerges Maximum number of conflict merges to collect
     * @param excelOutFile Output Excel file path
     * @param repoName Repository name
     * @param repoUrl Repository URL
     * @return Collection result with status and statistics
     */
    public CollectionResult collectDataset(
            Path projectPath,
            Path tempPath,
            String projectName,
            int maxConflictMerges,
            Path excelOutFile,
            String repoName,
            String repoUrl) throws Exception {

        // Load merges with conflicts
        MergeLoadResult loadResult = loadMerges(projectPath, maxConflictMerges);
        List<MergeInfo> allMerges = loadResult.mergesWithConflicts;
        int totalMergeCount = loadResult.totalMergeCount;
        int totalCommitCount = loadResult.totalCommitCount;

        int totalConflictCount = allMerges.size();
        // Count Java conflicts for statistics (no longer used for filtering)
        int javaConflictCount = mergeFilter.countJavaConflicts(allMerges);

        if (totalConflictCount == 0) {
            return buildRejectionResult(
                    RepositoryStatus.REJECTED_NO_CONFLICTS,
                    "No merges with conflicts found",
                    repoName, repoUrl, totalCommitCount, totalMergeCount, 0, 0, 0, 0, 0);
        }

        System.out.printf("  → Processing %d merges with conflicts (%d with Java conflicts, out of %d total merges)\n",
                totalConflictCount, javaConflictCount, totalMergeCount);

        // Clean temp directory
        FileUtils.deleteDirectory(tempPath.toFile());

        // Process merges in parallel
        ProcessingStatistics stats = processInParallel(
                allMerges,
                projectPath,
                tempPath,
                projectName,
                totalConflictCount);

        // Determine status and write results
        return buildFinalResult(
                stats,
                excelOutFile,
                repoName,
                repoUrl,
                totalCommitCount,
                totalMergeCount,
                totalConflictCount,
                javaConflictCount);
    }

    /**
     * Load merges from repository up to {@code maxConflictMerges} any-conflict merges.
     *
     * @throws RuntimeException if Git operations fail
     */
    private MergeLoadResult loadMerges(Path projectPath, int maxConflictMerges) {
        try (Git git = GitUtils.getGit(projectPath)) {
            int totalMergeCount = GitUtils.getTotalMergeCount(git);
            int totalCommitCount = GitUtils.getTotalCommitCount(git);

            List<MergeInfo> allMerges = GitUtils.getConflictCommits(maxConflictMerges, 0, git);

            // Primary check: skip merges already used in pattern-learning (prevents data leakage)
            int beforeFilter = allMerges.size();
            allMerges = allMerges.stream()
                    .filter(m -> !mergeFilter.isTrainingMerge(m))
                    .collect(Collectors.toList());
            int skipped = beforeFilter - allMerges.size();
            if (skipped > 0) {
                System.out.printf("  → Skipped %d merge(s) already in training set (data leakage prevention)%n", skipped);
            }

            return new MergeLoadResult(allMerges, totalMergeCount, totalCommitCount);
        } catch (Exception e) {
            System.err.println("Failed to load merges from " + projectPath + ": " + e.getMessage());
            throw new RuntimeException("Failed to load merges from repository", e);
        }
    }

    /**
     * Process merges in parallel using thread pool.
     */
    private ProcessingStatistics processInParallel(
            List<MergeInfo> allMerges,
            Path projectPath,
            Path tempPath,
            String projectName,
            int javaConflictCount) {

        List<ExcelWriter.DatasetRow> rows = Collections.synchronizedList(new java.util.ArrayList<>());
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger noTestCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);
        AtomicInteger buildFailureCount = new AtomicInteger(0);
        AtomicInteger allTestsFailedCount = new AtomicInteger(0);
        AtomicInteger maxModules = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(AppConfig.MAX_THREADS);

        for (MergeInfo merge : allMerges) {
            pool.submit(() -> processMergeTask(
                    merge,
                    projectPath,
                    tempPath,
                    projectName,
                    rows,
                    processedCount,
                    noTestCount,
                    timeoutCount,
                    buildFailureCount,
                    allTestsFailedCount,
                    maxModules,
                    javaConflictCount));
        }

        Utility.shutdownAndAwaitTermination(pool);

        return new ProcessingStatistics(rows, noTestCount.get(), timeoutCount.get(),
                buildFailureCount.get(), allTestsFailedCount.get(), maxModules.get());
    }

    /**
     * Process a single merge as a task.
     */
    private void processMergeTask(
            MergeInfo merge,
            Path projectPath,
            Path tempPath,
            String projectName,
            List<ExcelWriter.DatasetRow> rows,
            AtomicInteger processedCount,
            AtomicInteger noTestCount,
            AtomicInteger timeoutCount,
            AtomicInteger buildFailureCount,
            AtomicInteger allTestsFailedCount,
            AtomicInteger maxModules,
            int totalJavaCount) {

        int taskID = processedCount.incrementAndGet();
        String commitShort = merge.getResultedMergeCommit().getName().substring(0, 8);

        try {
            System.out.printf("    [%d/%d] %s... ", taskID, totalJavaCount, commitShort);
            System.out.flush();

            MergeCheckoutProcessor.MergeProcessResult result = mergeProcessor.processMerge(
                    merge, projectPath, tempPath, projectName);

            if (result.getNumberOfModules() > 0) {
                maxModules.updateAndGet(current -> Math.max(current, result.getNumberOfModules()));
            }

            if (!result.isHadTests()) {
                noTestCount.incrementAndGet();
                logMergeFailure(projectName, commitShort, MergeFailureType.NO_TESTS);
                System.out.println("✗ No tests");
            } else if (result.isTimedOut()) {
                timeoutCount.incrementAndGet();
                logMergeFailure(projectName, commitShort, MergeFailureType.TIMEOUT);
                System.out.println("⏱ Timeout");
            } else {
                ExcelWriter.DatasetRow row = rowBuilder.buildRow(result);
                if (row != null) {
                    rows.add(row);
                    System.out.println("✓ Success");
                } else if (result.getModulesPassed() == 0) {
                    buildFailureCount.incrementAndGet();
                    logCompileFailure(projectName, commitShort, result);
                    System.out.println("✗ Build failed");
                } else {
                    allTestsFailedCount.incrementAndGet();
                    logCompileFailure(projectName, commitShort, result);
                    System.out.println("✗ All tests failed");
                }
            }
        } catch (Exception e) {
            System.out.println("✗ Error: " + e.getMessage());
        }
    }

    /**
     * Build final result with status determination.
     */
    private CollectionResult buildFinalResult(
            ProcessingStatistics stats,
            Path excelOutFile,
            String repoName,
            String repoUrl,
            int totalCommits,
            int totalMerges,
            int mergesWithConflicts,
            int javaConflictCount) throws Exception {

        if (stats.rows.isEmpty()) {
            int exceptionCount = mergesWithConflicts
                    - stats.noTestCount - stats.timeoutCount
                    - stats.buildFailureCount - stats.allTestsFailedCount;

            boolean hasNoTest       = stats.noTestCount > 0;
            boolean hasTimeout      = stats.timeoutCount > 0;
            boolean hasBuildFail    = stats.buildFailureCount > 0;
            boolean hasTestsFail    = stats.allTestsFailedCount > 0;
            boolean hasException    = exceptionCount > 0;

            int activeTypes = (hasNoTest ? 1 : 0) + (hasTimeout ? 1 : 0)
                    + (hasBuildFail ? 1 : 0) + (hasTestsFail ? 1 : 0) + (hasException ? 1 : 0);

            RepositoryStatus rejectionStatus;
            String message;
            if (activeTypes > 1) {
                rejectionStatus = RepositoryStatus.REJECTED_MULTI;
                message = String.format("No successful merges (no tests: %d, timeouts: %d, build failures: %d, all tests failed: %d, exceptions: %d)",
                        stats.noTestCount, stats.timeoutCount, stats.buildFailureCount, stats.allTestsFailedCount, exceptionCount);
            } else if (hasNoTest) {
                rejectionStatus = RepositoryStatus.REJECTED_NO_TESTS;
                message = String.format("All %d merges had no tests", mergesWithConflicts);
            } else if (hasTimeout) {
                rejectionStatus = RepositoryStatus.REJECTED_TIMEOUT;
                message = String.format("All %d merges timed out", mergesWithConflicts);
            } else if (hasBuildFail) {
                rejectionStatus = RepositoryStatus.REJECTED_BUILD_FAILED;
                message = String.format("All %d merges failed to compile", mergesWithConflicts);
            } else if (hasTestsFail) {
                rejectionStatus = RepositoryStatus.REJECTED_ALL_TESTS_FAILED;
                message = String.format("All %d merges compiled but every test failed", mergesWithConflicts);
            } else {
                rejectionStatus = RepositoryStatus.REJECTED_OTHER;
                message = String.format("All %d merges failed with unexpected exceptions", mergesWithConflicts);
            }

            return buildRejectionResult(
                    rejectionStatus, message,
                    repoName, repoUrl, totalCommits, totalMerges, mergesWithConflicts, javaConflictCount,
                    stats.noTestCount, stats.timeoutCount, stats.maxModules);
        }

        // Success - write Excel file
        ExcelWriter.writeExcel(excelOutFile, stats.rows);

        return CollectionResult.builder()
                .status(RepositoryStatus.SUCCESS)
                .repoName(repoName)
                .repoUrl(repoUrl)
                .isMaven(true)
                .totalCommits(totalCommits)
                .totalMerges(totalMerges)
                .mergesWithConflicts(mergesWithConflicts)
                .mergesWithJavaConflicts(javaConflictCount)
                .successfulMerges(stats.rows.size())
                .mergesWithNoTests(stats.noTestCount)
                .mergesTimedOut(stats.timeoutCount)
                .maxModules(stats.maxModules)
                .message(String.format("Dataset created with %d compilable and testable merges", stats.rows.size()))
                .build();
    }

    private CollectionResult buildRejectionResult(
            RepositoryStatus status,
            String message,
            String repoName,
            String repoUrl,
            int totalCommits,
            int totalMerges,
            int mergesWithConflicts,
            int javaConflictCount,
            int noTestCount,
            int timeoutCount,
            int maxModules) {

        return CollectionResult.builder()
                .status(status)
                .repoName(repoName)
                .repoUrl(repoUrl)
                .isMaven(true)
                .totalCommits(totalCommits)
                .totalMerges(totalMerges)
                .mergesWithConflicts(mergesWithConflicts)
                .mergesWithJavaConflicts(javaConflictCount)
                .successfulMerges(0)
                .mergesWithNoTests(noTestCount)
                .mergesTimedOut(timeoutCount)
                .maxModules(maxModules)
                .message(message)
                .build();
    }

    private void logMergeFailure(String repoName, String shortCommit,
                                  MergeFailureType type) {
        if (failureLog != null) failureLog.logMergeFailure(repoName, shortCommit, type, "");
    }

    private void logCompileFailure(String repoName, String shortCommit,
                                    MergeCheckoutProcessor.MergeProcessResult result) {
        if (failureLog == null) return;
        if (result.isJavaVersionError()) {
            String detail = String.format("pom.xml requires Java %d, ran with Java %s",
                    result.getRequiredJavaVersion(),
                    result.getUsedJavaVersion() > 0 ? result.getUsedJavaVersion() : "system default");
            failureLog.logMergeFailure(repoName, shortCommit, MergeFailureType.JAVA_VERSION, detail);
        } else {
            String detail = result.getNumberOfModules() > 1
                    ? result.getModulesPassed() + "/" + result.getNumberOfModules() + " modules compiled"
                    : "";
            failureLog.logMergeFailure(repoName, shortCommit, MergeFailureType.COMPILE_FAILURE, detail);
        }
    }

    /**
         * Result of loading merges from repository.
         */
        private record MergeLoadResult(List<MergeInfo> mergesWithConflicts, int totalMergeCount, int totalCommitCount) {
    }

    /**
         * Statistics from parallel processing.
         */
        private record ProcessingStatistics(List<ExcelWriter.DatasetRow> rows, int noTestCount, int timeoutCount,
                                            int buildFailureCount, int allTestsFailedCount, int maxModules) {
    }
}
