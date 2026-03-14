package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.IJustInTimeRunner;
import ch.unibe.cs.mergeci.runner.MavenExecutionFactory;
import ch.unibe.cs.mergeci.runner.VariantProjectBuilder;
import ch.unibe.cs.mergeci.runner.ExperimentTiming;
import ch.unibe.cs.mergeci.runner.VariantBuildContext;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.JacocoReportFinder;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
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
public class MergeExperimentRunner {
    private final Path repoPath;
    private final boolean isParallel;
    private final boolean isCache;

    public MergeExperimentRunner(Path repoPath, boolean isParallel, boolean isCache) {
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
        int numConflictChunks = GitUtils.getTotalConflictChunks(repoPath, info.getParent1(), info.getParent2());

        // Run merge analysis
        MergeAnalysisResult result = runMergeAnalysis(info);

        return ProcessedMerge.completed(info, numConflictChunks, result);
    }

    /**
     * Run the full merge analysis with just-in-time variant building.
     * Variants are built on-demand and deleted immediately after testing,
     * dramatically reducing disk usage.
     */
    private MergeAnalysisResult runMergeAnalysis(DatasetReader.MergeInfo info) throws Exception {
        // Clean up before starting
        FileUtils.deleteDirectory(AppConfig.TMP_PROJECT_DIR.toFile());

        // Time the analysis
        Instant start = Instant.now();

        // Prepare variant metadata (no disk writes yet)
        VariantProjectBuilder variantProjectBuilder = new VariantProjectBuilder(repoPath, AppConfig.TMP_DIR, AppConfig.TMP_PROJECT_DIR);
        VariantBuildContext context = variantProjectBuilder.prepareVariants(info.getParent1(), info.getParent2(), info.getMergeCommit());

        // Create factory and run tests with just-in-time building
        MavenExecutionFactory factory = new MavenExecutionFactory(variantProjectBuilder.getLogDir());
        IJustInTimeRunner runner = factory.createJustInTimeRunner(isParallel, isCache);

        // Run tests (builds variants on-demand, deletes immediately after)
        ExperimentTiming experimentTiming = variantProjectBuilder.runTestsJustInTime(context, runner);

        Instant finish = Instant.now();
        long timeElapsed = Duration.between(start, finish).toSeconds();

        // Collect results (already collected during just-in-time execution)
        Map<String, CompilationResult> compilationResults = factory.getCompilationResults();
        Map<String, TestTotal> testResults = factory.getTestResults();
        JacocoReportFinder.CoverageDTO coverageResult = factory.getCoverageResult();
        Map<String, Double> variantFinishSeconds = factory.getVariantFinishSeconds();

        return new MergeAnalysisResult(
                variantProjectBuilder,
                compilationResults,
                testResults,
                timeElapsed,
                experimentTiming,
                coverageResult,
                variantFinishSeconds
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
        private final VariantProjectBuilder analyzer;
        private final Map<String, CompilationResult> compilationResults;
        private final Map<String, TestTotal> testResults;
        private final long executionTimeSeconds;
        private final ExperimentTiming runExecutionTime;
        private final JacocoReportFinder.CoverageDTO coverageResult;
        private final Map<String, Double> variantFinishSeconds;

        public MergeAnalysisResult(
                VariantProjectBuilder analyzer,
                Map<String, CompilationResult> compilationResults,
                Map<String, TestTotal> testResults,
                long executionTimeSeconds,
                ExperimentTiming runExecutionTime,
                JacocoReportFinder.CoverageDTO coverageResult,
                Map<String, Double> variantFinishSeconds) {
            this.analyzer = analyzer;
            this.compilationResults = compilationResults;
            this.testResults = testResults;
            this.executionTimeSeconds = executionTimeSeconds;
            this.runExecutionTime = runExecutionTime;
            this.coverageResult = coverageResult;
            this.variantFinishSeconds = variantFinishSeconds;
        }

        public String getProjectName() {
            return analyzer.getProjectName();
        }
    }
}
