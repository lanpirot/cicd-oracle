package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;

import java.util.Map;

/**
 * Interface for running variant build-and-test experiments.
 * Implementations control how variants are compiled, tested, and timed.
 *
 * <p>The canonical implementation is {@link MavenVariantEvaluator}, which delegates to
 * {@link MavenExecutionFactory}. Alternative evaluators (e.g., a no-op stub for tests)
 * can be injected via {@link ch.unibe.cs.mergeci.experiment.MergeExperimentRunner}.
 *
 * <p>Lifecycle: call {@link #runExperiment}, then read results via the getter methods.
 */
public interface IVariantEvaluator {

    /**
     * Run the full experiment for one merge: baseline build + variant loop.
     *
     * @param context              variant build context (conflict files, generator, project metadata)
     * @param builder              project builder for materialising directories
     * @param isParallel           run variants in parallel
     * @param isCache              warm Maven cache before variants
     * @param skipVariants         run baseline only (human_baseline mode)
     * @param storedBaselineSeconds pre-recorded baseline time; 0 = run baseline fresh
     * @param storedPeakRamBytes   pre-recorded peak RAM of baseline build (bytes); 0 = unknown
     * @return timing statistics
     */
    ExperimentTiming runExperiment(VariantBuildContext context, VariantProjectBuilder builder,
                                   boolean isParallel, boolean isCache,
                                   boolean skipVariants, long storedBaselineSeconds,
                                   long storedPeakRamBytes) throws Exception;

    Map<String, CompilationResult> getCompilationResults();
    Map<String, TestTotal> getTestResults();
    Map<String, Double> getVariantFinishSeconds();
    Map<String, Double> getVariantSinceMergeStartSeconds();
    boolean isBudgetExhausted();
    String getCacheWarmerKey();
    int getNumInFlightVariantsKilled();
    int getMaxThreads();
    long getPeakBaselineRamBytes();
}
