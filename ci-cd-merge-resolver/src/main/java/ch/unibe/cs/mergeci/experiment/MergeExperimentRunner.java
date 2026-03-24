package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.conflict.ConflictFileSaver;
import ch.unibe.cs.mergeci.runner.ExperimentTiming;
import ch.unibe.cs.mergeci.runner.IVariantEvaluator;
import ch.unibe.cs.mergeci.runner.IVariantGenerator;
import ch.unibe.cs.mergeci.runner.IVariantGeneratorFactory;
import ch.unibe.cs.mergeci.runner.MavenVariantEvaluator;
import ch.unibe.cs.mergeci.runner.VariantBuildContext;
import ch.unibe.cs.mergeci.runner.VariantProjectBuilder;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.JacocoReportFinder;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import ch.unibe.cs.mergeci.util.GitUtils;
import lombok.Getter;
import org.apache.commons.io.FileUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Processes individual merge commits for variant testing.
 * Encapsulates the logic for running merge analysis and collecting execution metrics.
 */
public class MergeExperimentRunner {
    private final Path repoPath;
    private final Path tmpDir;
    private final boolean isParallel;
    private final boolean isCache;
    private final boolean skipVariants;
    private final Map<String, Long> storedBaselines;
    private final IVariantGeneratorFactory generatorFactory;
    private final IVariantEvaluator evaluator;

    public MergeExperimentRunner(Path repoPath, boolean isParallel, boolean isCache) {
        this(repoPath, AppConfig.TMP_DIR, isParallel, isCache, false, Collections.emptyMap(), null, null);
    }

    public MergeExperimentRunner(Path repoPath, boolean isParallel, boolean isCache, boolean skipVariants) {
        this(repoPath, AppConfig.TMP_DIR, isParallel, isCache, skipVariants, Collections.emptyMap(), null, null);
    }

    public MergeExperimentRunner(Path repoPath, Path tmpDir, boolean isParallel, boolean isCache,
                                  boolean skipVariants, Map<String, Long> storedBaselines) {
        this(repoPath, tmpDir, isParallel, isCache, skipVariants, storedBaselines, null, null);
    }

    public MergeExperimentRunner(Path repoPath, Path tmpDir, boolean isParallel, boolean isCache,
                                  boolean skipVariants, Map<String, Long> storedBaselines,
                                  IVariantGeneratorFactory generatorFactory, IVariantEvaluator evaluator) {
        this.repoPath = repoPath;
        this.tmpDir = tmpDir;
        this.isParallel = isParallel;
        this.isCache = isCache;
        this.skipVariants = skipVariants;
        this.storedBaselines = storedBaselines;
        this.generatorFactory = generatorFactory;
        this.evaluator = evaluator;
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

        IVariantGenerator generator = (generatorFactory != null)
                ? generatorFactory.create(info.getMergeId(), repoPath, numConflictChunks)
                : null;

        MergeAnalysisResult result = runMergeAnalysis(info, generator);

        return ProcessedMerge.completed(info, numConflictChunks, result);
    }

    /**
     * Run the full merge analysis with just-in-time variant building.
     * Variants are built on-demand and deleted immediately after testing,
     * dramatically reducing disk usage.
     */
    private MergeAnalysisResult runMergeAnalysis(DatasetReader.MergeInfo info,
                                                   IVariantGenerator generator) throws Exception {
        // Clean up before starting
        Path tmpProjectDir = tmpDir.resolve("projects");
        FileUtils.deleteDirectory(tmpProjectDir.toFile());

        // Time the analysis
        Instant start = Instant.now();

        // Prepare variant metadata (no disk writes yet)
        VariantProjectBuilder variantProjectBuilder = new VariantProjectBuilder(repoPath, tmpDir, tmpProjectDir);
        VariantBuildContext context = (generator != null)
                ? variantProjectBuilder.prepareVariants(info.getParent1(), info.getParent2(), info.getMergeCommit(), generator)
                : variantProjectBuilder.prepareVariants(info.getParent1(), info.getParent2(), info.getMergeCommit(), info.getMergeId());

        // Run experiment using the evaluator (default: MavenVariantEvaluator)
        long storedBaseline = storedBaselines.getOrDefault(info.getMergeCommit(), 0L);
        IVariantEvaluator activeEvaluator = (evaluator != null)
                ? evaluator
                : new MavenVariantEvaluator(variantProjectBuilder.getLogDir());
        ExperimentTiming experimentTiming = activeEvaluator.runExperiment(
                context, variantProjectBuilder, isParallel, isCache, skipVariants, storedBaseline);

        Instant finish = Instant.now();
        long timeElapsed = Duration.between(start, finish).toSeconds();

        // Save human/tentative/variant file triplets for later inspection
        ConflictFileSaver.save(context, activeEvaluator.getTestResults(),
                activeEvaluator.getCompilationResults(), info, AppConfig.CONFLICT_FILES_DIR);

        return new MergeAnalysisResult(
                variantProjectBuilder,
                activeEvaluator.getCompilationResults(),
                activeEvaluator.getTestResults(),
                timeElapsed,
                experimentTiming,
                activeEvaluator.getCoverageResult(),
                activeEvaluator.getVariantFinishSeconds(),
                activeEvaluator.getVariantSinceMergeStartSeconds(),
                activeEvaluator.isBudgetExhausted(),
                activeEvaluator.getCacheWarmerKey(),
                activeEvaluator.getNumInFlightVariantsKilled()
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
        public record MergeAnalysisResult(VariantProjectBuilder analyzer, Map<String, CompilationResult> compilationResults,
                                          Map<String, TestTotal> testResults, long executionTimeSeconds,
                                          ExperimentTiming runExecutionTime, JacocoReportFinder.CoverageDTO coverageResult,
                                          Map<String, Double> variantFinishSeconds,
                                          Map<String, Double> variantSinceMergeStartSeconds,
                                          boolean budgetExhausted,
                                          String cacheWarmerKey,
                                          int numInFlightVariantsKilled) {

        public String getProjectName() {
                return analyzer.getProjectName();
            }
        }
}
