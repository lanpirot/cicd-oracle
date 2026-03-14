package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.JacocoReportFinder;
import ch.unibe.cs.mergeci.runner.maven.MavenRunner;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import lombok.Getter;
import org.apache.commons.io.FileUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class MavenExecutionFactory {
    private final Path logDir;
    @Getter
    private Map<String, CompilationResult> compilationResults;
    @Getter
    private Map<String, TestTotal> testResults;
    @Getter
    private JacocoReportFinder.CoverageDTO coverageResult;
    @Getter
    private Map<String, Double> variantFinishSeconds;

    public MavenExecutionFactory(Path logDir) {
        this.logDir = logDir;
        this.compilationResults = new TreeMap<>();
        this.testResults = new TreeMap<>();
    }

    /**
     * Create a just-in-time runner.
     * The human baseline is run first unconstrained; its actual duration determines the variant
     * budget via TIMEOUT_MULTIPLIER. Variants share a fixed deadline computed when the first
     * variant starts, and each runner receives only the time remaining until that deadline.
     */
    public IJustInTimeRunner createJustInTimeRunner(boolean isParallel, boolean isCache) {
        compilationResults = new TreeMap<>();
        testResults = new TreeMap<>();
        coverageResult = null;
        variantFinishSeconds = new TreeMap<>();

        return new IJustInTimeRunner() {
            @Override
            public ExperimentTiming run(VariantBuildContext context, VariantProjectBuilder builder) throws Exception {
                ExperimentTiming experimentTiming = new ExperimentTiming();

                executeHumanBaseline(context, builder, experimentTiming);

                long rawBaselineSeconds = experimentTiming.getHumanBaselineExecutionTime().getSeconds();
                TestTotal baselineTests = testResults.get(context.getProjectName());
                long baselineSeconds = normalizeBaselineSeconds(rawBaselineSeconds, baselineTests);
                long totalBudgetSeconds = baselineSeconds * AppConfig.TIMEOUT_MULTIPLIER;
                Instant deadline = Instant.now().plusSeconds(totalBudgetSeconds);

                Instant variantsStart = Instant.now();
                if (isParallel) {
                    runVariantsParallel(context, builder, deadline);
                } else {
                    runVariantsSequential(context, builder, deadline);
                }
                experimentTiming.setVariantsExecutionTime(Duration.between(variantsStart, Instant.now()));

                return experimentTiming;
            }

            private void executeHumanBaseline(VariantBuildContext context, VariantProjectBuilder builder,
                                              ExperimentTiming experimentTiming) throws Exception {
                Path mainProjectPath = builder.buildMainProject(context);
                String projectName = context.getProjectName();

                MavenRunner mainRunner = new MavenRunner(logDir, false, AppConfig.MAVEN_BUILD_TIMEOUT);

                Instant start = Instant.now();
                mainRunner.run_no_optimization(mainProjectPath);
                if (AppConfig.isCoverageActivated()) {
                    try {
                        coverageResult = JacocoReportFinder.getCoverageResults(mainProjectPath, List.of());
                    } catch (Exception e) {
                        System.err.println("Coverage collection failed for " + projectName + ": " + e.getMessage());
                    }
                }
                experimentTiming.setHumanBaselineExecutionTime(Duration.between(start, Instant.now()));

                builder.collectCompilationResult(projectName).ifPresent(result ->
                    compilationResults.put(projectName, result)
                );
                testResults.put(projectName, builder.collectTestResult(projectName, mainProjectPath));
            }

            private void runVariantsParallel(VariantBuildContext context, VariantProjectBuilder builder,
                                             Instant deadline) throws Exception {
                int variantCount = context.getVariantCount();
                Path[] variantPaths = new Path[variantCount];

                for (int i = 0; i < variantCount; i++) {
                    variantPaths[i] = builder.buildVariant(context, i);
                }

                try {
                    int timeoutMinutes = remainingMinutes(deadline);
                    MavenRunner variantRunner = new MavenRunner(logDir, false, timeoutMinutes);

                    if (isCache) {
                        variantRunner.run_cache_parallel(variantPaths);
                    } else {
                        variantRunner.run_parallel(variantPaths);
                    }

                    String projectName = context.getProjectName();
                    for (int i = 0; i < variantCount; i++) {
                        String variantKey = projectName + "_" + i;
                        builder.collectCompilationResult(variantKey).ifPresent(result ->
                            compilationResults.put(variantKey, result)
                        );
                        testResults.put(variantKey, builder.collectTestResult(variantKey, variantPaths[i]));
                    }
                    for (int i = 0; i < variantCount; i++) {
                        String variantKey = projectName + "_" + i;
                        CompilationResult cr = compilationResults.get(variantKey);
                        double finish = (cr != null) ? (double) cr.getTotalTime() : 0.0;
                        variantFinishSeconds.put(variantKey, finish);
                    }
                } finally {
                    for (Path variantPath : variantPaths) {
                        if (variantPath != null && variantPath.toFile().exists()) {
                            FileUtils.deleteDirectory(variantPath.toFile());
                        }
                    }
                }
            }

            private void runVariantsSequential(VariantBuildContext context, VariantProjectBuilder builder,
                                               Instant deadline) throws Exception {
                int variantCount = context.getVariantCount();
                String projectName = context.getProjectName();
                double cumulativeSeconds = 0;

                for (int i = 0; i < variantCount; i++) {
                    if (Instant.now().isAfter(deadline)) {
                        System.out.println("⚠ Timeout budget exhausted, skipping remaining variants (" + (variantCount - i) + " left)");
                        break;
                    }

                    Path variantPath = builder.buildVariant(context, i);
                    String variantKey = projectName + "_" + i;

                    try {
                        MavenRunner variantRunner = new MavenRunner(logDir, false, remainingMinutes(deadline));
                        Instant variantStart = Instant.now();
                        variantRunner.run_no_optimization(variantPath);
                        cumulativeSeconds += Duration.between(variantStart, Instant.now()).toMillis() / 1000.0;

                        builder.collectCompilationResult(variantKey).ifPresent(result ->
                            compilationResults.put(variantKey, result)
                        );
                        testResults.put(variantKey, builder.collectTestResult(variantKey, variantPath));
                        variantFinishSeconds.put(variantKey, cumulativeSeconds);
                    } finally {
                        if (variantPath.toFile().exists()) {
                            FileUtils.deleteDirectory(variantPath.toFile());
                        }
                    }
                }
            }

            private int remainingMinutes(Instant deadline) {
                long remainingSeconds = Math.max(0, Duration.between(Instant.now(), deadline).getSeconds());
                return Math.max(AppConfig.MAVEN_BUILD_TIMEOUT, (int) Math.ceil(remainingSeconds / 60.0));
            }
        };
    }

    /**
     * Normalize baseline duration to account for mass test failures.
     * Falls back to raw duration when no test data is available.
     */
    private static long normalizeBaselineSeconds(long rawSeconds, TestTotal testTotal) {
        if (testTotal == null) return rawSeconds;
        int runTests = testTotal.getRunNum();
        int passedTests = runTests - testTotal.getFailuresNum() - testTotal.getErrorsNum();
        float testElapsed = testTotal.getElapsedTime();
        float compilationTime = Math.max(0, rawSeconds - testElapsed);
        float normalized = TestTotal.normalizeElapsedTime(compilationTime, testElapsed, runTests, passedTests);
        return Float.isNaN(normalized) ? rawSeconds : (long) normalized;
    }
}
