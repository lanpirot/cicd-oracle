package ch.unibe.cs.mergeci.experimentSetup.evaluationCollection;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.service.MavenExecutionFactory;
import ch.unibe.cs.mergeci.service.MergeAnalyzer;
import ch.unibe.cs.mergeci.service.RunExecutionTIme;
import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import ch.unibe.cs.mergeci.util.GitUtils;
import lombok.Getter;
import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Processes individual merge commits for variant testing.
 * Encapsulates the logic for running merge analysis and collecting execution metrics.
 */
public class MergeProcessor {
    private final Path repoPath;
    private final boolean isParallel;
    private final boolean isCache;

    public MergeProcessor(Path repoPath, boolean isParallel, boolean isCache) {
        this.repoPath = repoPath;
        this.isParallel = isParallel;
        this.isCache = isCache;
    }

    /**
     * Process a single merge and return the results.
     *
     * @param info Merge information from dataset
     * @return ProcessedMerge containing results and metrics
     * @throws Exception if merge processing fails
     */
    public ProcessedMerge processMerge(DatasetReader.MergeInfo info) throws Exception {
        // Check conflict chunk limit
        int numConflictChunks = GitUtils.getTotalConflictChunks(repoPath, info.getParent1(), info.getParent2());

        if (!shouldProcess(numConflictChunks)) {
            return ProcessedMerge.skipped(
                info,
                numConflictChunks,
                String.format("⏭ Skipped (%d chunks > %d limit)", numConflictChunks, AppConfig.MAX_CONFLICT_CHUNKS)
            );
        }

        // Run merge analysis
        MergeAnalysisResult result = runMergeAnalysis(info);

        return ProcessedMerge.completed(info, numConflictChunks, result);
    }

    /**
     * Check if merge should be processed based on conflict chunk count.
     */
    private boolean shouldProcess(int numConflictChunks) {
        return numConflictChunks <= AppConfig.MAX_CONFLICT_CHUNKS;
    }

    /**
     * Run the full merge analysis including building projects and running tests.
     */
    private MergeAnalysisResult runMergeAnalysis(DatasetReader.MergeInfo info) throws Exception {
        // Clean up before starting
        FileUtils.deleteDirectory(AppConfig.TMP_PROJECT_DIR.toFile());

        // Time the analysis
        Instant start = Instant.now();

        // Build merge variants
        MergeAnalyzer mergeAnalyzer = new MergeAnalyzer(repoPath, AppConfig.TMP_DIR, AppConfig.TMP_PROJECT_DIR);
        mergeAnalyzer.buildProjects(info.getParent1(), info.getParent2(), info.getMergeCommit());

        // Run tests on all variants
        RunExecutionTIme runExecutionTime = mergeAnalyzer.runTests(
                new MavenExecutionFactory(mergeAnalyzer.getLogDir()).createMavenRunner(isParallel, isCache)
        );

        Instant finish = Instant.now();
        long timeElapsed = Duration.between(start, finish).toSeconds();

        // Collect results
        Map<String, CompilationResult> compilationResults = mergeAnalyzer.collectCompilationResults();
        Map<String, TestTotal> testResults = mergeAnalyzer.collectTestResults();

        return new MergeAnalysisResult(
                mergeAnalyzer,
                compilationResults,
                testResults,
                timeElapsed,
                runExecutionTime
        );
    }

    /**
     * Result of processing a single merge.
     */
    @Getter
    public static class ProcessedMerge {
        private final DatasetReader.MergeInfo info;
        private final int numConflictChunks;
        private final boolean skipped;
        private final String skipReason;
        private final MergeAnalysisResult analysisResult;

        private ProcessedMerge(
                DatasetReader.MergeInfo info,
                int numConflictChunks,
                boolean skipped,
                String skipReason,
                MergeAnalysisResult analysisResult) {
            this.info = info;
            this.numConflictChunks = numConflictChunks;
            this.skipped = skipped;
            this.skipReason = skipReason;
            this.analysisResult = analysisResult;
        }

        public static ProcessedMerge skipped(DatasetReader.MergeInfo info, int numConflictChunks, String reason) {
            return new ProcessedMerge(info, numConflictChunks, true, reason, null);
        }

        public static ProcessedMerge completed(DatasetReader.MergeInfo info, int numConflictChunks, MergeAnalysisResult result) {
            return new ProcessedMerge(info, numConflictChunks, false, null, result);
        }

        public boolean wasSkipped() {
            return skipped;
        }
    }

    /**
     * Results from running merge analysis.
     */
    @Getter
    public static class MergeAnalysisResult {
        private final MergeAnalyzer analyzer;
        private final Map<String, CompilationResult> compilationResults;
        private final Map<String, TestTotal> testResults;
        private final long executionTimeSeconds;
        private final RunExecutionTIme runExecutionTime;

        public MergeAnalysisResult(
                MergeAnalyzer analyzer,
                Map<String, CompilationResult> compilationResults,
                Map<String, TestTotal> testResults,
                long executionTimeSeconds,
                RunExecutionTIme runExecutionTime) {
            this.analyzer = analyzer;
            this.compilationResults = compilationResults;
            this.testResults = testResults;
            this.executionTimeSeconds = executionTimeSeconds;
            this.runExecutionTime = runExecutionTime;
        }

        public String getProjectName() {
            return analyzer.getProjectName();
        }
    }
}
