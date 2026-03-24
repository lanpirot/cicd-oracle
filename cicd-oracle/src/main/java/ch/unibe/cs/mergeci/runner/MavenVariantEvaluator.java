package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.JacocoReportFinder;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;

import java.nio.file.Path;
import java.util.Map;

/**
 * Maven-based implementation of {@link IVariantEvaluator}.
 * Delegates to {@link MavenExecutionFactory} for all build-and-test logic.
 */
public class MavenVariantEvaluator implements IVariantEvaluator {

    private final Path logDir;
    private MavenExecutionFactory factory;

    public MavenVariantEvaluator(Path logDir) {
        this.logDir = logDir;
    }

    @Override
    public ExperimentTiming runExperiment(VariantBuildContext context, VariantProjectBuilder builder,
                                          boolean isParallel, boolean isCache,
                                          boolean skipVariants, long storedBaselineSeconds) throws Exception {
        factory = new MavenExecutionFactory(logDir);
        IJustInTimeRunner runner = factory.createJustInTimeRunner(isParallel, isCache, skipVariants, storedBaselineSeconds);
        return builder.runTestsJustInTime(context, runner);
    }

    @Override public Map<String, CompilationResult> getCompilationResults()  { return factory.getCompilationResults(); }
    @Override public Map<String, TestTotal> getTestResults()                  { return factory.getTestResults(); }
    @Override public JacocoReportFinder.CoverageDTO getCoverageResult()       { return factory.getCoverageResult(); }
    @Override public Map<String, Double> getVariantFinishSeconds()            { return factory.getVariantFinishSeconds(); }
    @Override public Map<String, Double> getVariantSinceMergeStartSeconds()  { return factory.getVariantSinceMergeStartSeconds(); }
    @Override public boolean isBudgetExhausted()                              { return factory.isBudgetExhausted(); }
    @Override public String getCacheWarmerKey()                               { return factory.getCacheWarmerKey(); }
    @Override public int getNumInFlightVariantsKilled()                       { return factory.getNumInFlightVariantsKilled(); }
}
