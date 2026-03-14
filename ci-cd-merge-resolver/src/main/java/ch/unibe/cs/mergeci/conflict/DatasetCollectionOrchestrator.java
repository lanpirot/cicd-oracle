package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.config.AppConfig;
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

    public DatasetCollectionOrchestrator(
            MergeFilter mergeFilter,
            MergeCheckoutProcessor mergeProcessor,
            DatasetRowBuilder rowBuilder) {
        this.mergeFilter = mergeFilter;
        this.mergeProcessor = mergeProcessor;
        this.rowBuilder = rowBuilder;
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

        // Filter to Java conflicts only
        int javaConflictCount = mergeFilter.countJavaConflicts(allMerges);

        if (javaConflictCount == 0) {
            return buildRejectionResult(
                    RepositoryStatus.REJECTED_NO_JAVA_CONFLICTS,
                    "No merges with Java file conflicts found",
                    repoName, repoUrl, totalCommitCount, totalMergeCount, allMerges.size(), 0, 0, 0, 0);
        }

        System.out.printf("  → Processing %d merges with Java conflicts (out of %d total merges)\n",
                javaConflictCount, totalMergeCount);

        // Clean temp directory
        FileUtils.deleteDirectory(tempPath.toFile());

        // Process merges in parallel
        ProcessingStatistics stats = processInParallel(
                allMerges,
                projectPath,
                tempPath,
                projectName,
                javaConflictCount);

        // Determine status and write results
        return buildFinalResult(
                stats,
                excelOutFile,
                repoName,
                repoUrl,
                totalCommitCount,
                totalMergeCount,
                allMerges.size(),
                javaConflictCount);
    }

    /**
     * Load merges from repository.
     * @throws RuntimeException if Git operations fail
     */
    private MergeLoadResult loadMerges(Path projectPath, int maxConflictMerges) {
        try (Git git = GitUtils.getGit(projectPath)) {
            int totalMergeCount = GitUtils.getTotalMergeCount(git);
            int totalCommitCount = GitUtils.getTotalCommitCount(git);
            List<MergeInfo> mergesWithConflicts = GitUtils.getConflictCommits(maxConflictMerges, git);
            return new MergeLoadResult(mergesWithConflicts, totalMergeCount, totalCommitCount);
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
        AtomicInteger maxModules = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(AppConfig.MAX_THREADS);

        for (MergeInfo merge : allMerges) {
            if (!mergeFilter.hasJavaConflicts(merge)) {
                continue;
            }

            pool.submit(() -> processMergeTask(
                    merge,
                    projectPath,
                    tempPath,
                    projectName,
                    rows,
                    processedCount,
                    noTestCount,
                    timeoutCount,
                    maxModules,
                    javaConflictCount));
        }

        Utility.shutdownAndAwaitTermination(pool);

        return new ProcessingStatistics(rows, noTestCount.get(), timeoutCount.get(), maxModules.get());
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
                System.out.println("✗ No tests");
            } else if (result.isTimedOut()) {
                timeoutCount.incrementAndGet();
                System.out.println("⏱ Timeout");
            } else {
                ExcelWriter.DatasetRow row = rowBuilder.buildRow(result);
                if (row != null) {
                    rows.add(row);
                }
                System.out.println("✓ Success");
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
            // Determine rejection reason
            if (stats.noTestCount == javaConflictCount) {
                return buildRejectionResult(
                        RepositoryStatus.REJECTED_NO_TESTS,
                        String.format("All %d merges with Java conflicts had no tests", javaConflictCount),
                        repoName, repoUrl, totalCommits, totalMerges, mergesWithConflicts, javaConflictCount,
                        stats.noTestCount, stats.timeoutCount, stats.maxModules);
            } else {
                return buildRejectionResult(
                        RepositoryStatus.REJECTED_OTHER,
                        String.format("No successful merges (timeouts: %d, no tests: %d)",
                                stats.timeoutCount, stats.noTestCount),
                        repoName, repoUrl, totalCommits, totalMerges, mergesWithConflicts, javaConflictCount,
                        stats.noTestCount, stats.timeoutCount, stats.maxModules);
            }
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

    /**
     * Result of loading merges from repository.
     */
    private static class MergeLoadResult {
        final List<MergeInfo> mergesWithConflicts;
        final int totalMergeCount;
        final int totalCommitCount;

        MergeLoadResult(List<MergeInfo> mergesWithConflicts, int totalMergeCount, int totalCommitCount) {
            this.mergesWithConflicts = mergesWithConflicts;
            this.totalMergeCount = totalMergeCount;
            this.totalCommitCount = totalCommitCount;
        }
    }

    /**
     * Statistics from parallel processing.
     */
    private static class ProcessingStatistics {
        final List<ExcelWriter.DatasetRow> rows;
        final int noTestCount;
        final int timeoutCount;
        final int maxModules;

        ProcessingStatistics(List<ExcelWriter.DatasetRow> rows, int noTestCount, int timeoutCount, int maxModules) {
            this.rows = rows;
            this.noTestCount = noTestCount;
            this.timeoutCount = timeoutCount;
            this.maxModules = maxModules;
        }
    }
}
