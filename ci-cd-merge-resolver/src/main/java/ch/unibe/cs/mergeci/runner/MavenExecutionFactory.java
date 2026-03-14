package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.JacocoReportFinder;
import ch.unibe.cs.mergeci.runner.maven.MavenRunner;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class MavenExecutionFactory {
    private final Path logDir;
    private Map<String, CompilationResult> compilationResults;
    private Map<String, TestTotal> testResults;
    private JacocoReportFinder.CoverageDTO coverageResult;

    public MavenExecutionFactory(Path logDir) {
        this.logDir = logDir;
        this.compilationResults = new TreeMap<>();
        this.testResults = new TreeMap<>();
    }

    /**
     * Get collected compilation results (populated during just-in-time execution).
     */
    public Map<String, CompilationResult> getCompilationResults() {
        return compilationResults;
    }

    /**
     * Get collected test results (populated during just-in-time execution).
     */
    public Map<String, TestTotal> getTestResults() {
        return testResults;
    }

    /**
     * Get coverage result for the main project, or null if coverage was disabled or failed.
     */
    public JacocoReportFinder.CoverageDTO getCoverageResult() {
        return coverageResult;
    }

    /**
     * Create a just-in-time runner with timeout budget.
     * Builds variant directories on-demand, runs Maven, collects results, and deletes immediately.
     * This dramatically reduces disk usage by only keeping 1-5 variant directories at a time.
     *
     * @param isParallel Whether to run variants in parallel
     * @param isCache Whether to use cache optimization
     * @param totalBudgetSeconds Total time budget in seconds for all variants
     * @return IJustInTimeRunner that manages just-in-time building and cleanup
     */
    public IJustInTimeRunner createJustInTimeRunner(boolean isParallel, boolean isCache, float totalBudgetSeconds) {
        compilationResults = new TreeMap<>();
        testResults = new TreeMap<>();
        coverageResult = null;

        return new IJustInTimeRunner() {
            @Override
            public RunExecutionTIme run(VariantBuildContext context, MergeAnalyzer analyzer) throws Exception {
                RunExecutionTIme runExecutionTime = new RunExecutionTIme();
                Instant overallStart = Instant.now();

                float remainingSeconds = executeMainProject(context, analyzer, totalBudgetSeconds, overallStart, runExecutionTime);

                if (remainingSeconds <= 0) {
                    System.out.println("⚠ Timeout budget exhausted after main project");
                    return runExecutionTime;
                }

                Instant start = Instant.now();
                if (isParallel) {
                    runVariantsParallel(context, analyzer, remainingSeconds, overallStart, totalBudgetSeconds);
                } else {
                    runVariantsSequential(context, analyzer, overallStart, totalBudgetSeconds);
                }
                runExecutionTime.setVariantsExecutionTime(Duration.between(start, Instant.now()));

                return runExecutionTime;
            }

            private float executeMainProject(VariantBuildContext context, MergeAnalyzer analyzer,
                                             float totalBudgetSeconds, Instant overallStart,
                                             RunExecutionTIme runExecutionTime) throws Exception {
                Path mainProjectPath = analyzer.buildMainProject(context);
                String projectName = context.getProjectName();

                int timeoutMinutes = Math.max(AppConfig.MAVEN_BUILD_TIMEOUT, (int) Math.ceil(totalBudgetSeconds / 60.0f));
                MavenRunner mainRunner = new MavenRunner(logDir, false, timeoutMinutes);

                Instant start = Instant.now();
                mainRunner.run_no_optimization(mainProjectPath);
                if (AppConfig.isCoverageActivated()) {
                    try {
                        coverageResult = JacocoReportFinder.getCoverageResults(mainProjectPath, List.of());
                    } catch (Exception e) {
                        System.err.println("Coverage collection failed for " + projectName + ": " + e.getMessage());
                    }
                }
                runExecutionTime.setMainExecutionTime(Duration.between(start, Instant.now()));

                analyzer.collectCompilationResult(projectName).ifPresent(result ->
                    compilationResults.put(projectName, result)
                );
                testResults.put(projectName, analyzer.collectTestResult(projectName, mainProjectPath));

                return totalBudgetSeconds - Duration.between(overallStart, Instant.now()).toSeconds();
            }

            private void runVariantsParallel(VariantBuildContext context, MergeAnalyzer analyzer,
                                            float remainingSeconds, Instant overallStart, float totalBudgetSeconds) throws Exception {
                // Build all variant directories for parallel execution
                int variantCount = context.getVariantCount();
                Path[] variantPaths = new Path[variantCount];

                for (int i = 0; i < variantCount; i++) {
                    variantPaths[i] = analyzer.buildVariant(context, i);
                }

                try {
                    // Run Maven on all variants in parallel
                    int timeoutMinutes = Math.max(AppConfig.MAVEN_BUILD_TIMEOUT, (int) Math.ceil(remainingSeconds / 60.0f));
                    MavenRunner variantRunner = new MavenRunner(logDir, false, timeoutMinutes);

                    if (isCache) {
                        variantRunner.run_cache_parallel(variantPaths);
                    } else {
                        variantRunner.run_parallel(variantPaths);
                    }

                    // Collect results from all variants
                    String projectName = context.getProjectName();
                    for (int i = 0; i < variantCount; i++) {
                        String variantKey = projectName + "_" + i;
                        analyzer.collectCompilationResult(variantKey).ifPresent(result ->
                            compilationResults.put(variantKey, result)
                        );
                        TestTotal testResult = analyzer.collectTestResult(variantKey, variantPaths[i]);
                        testResults.put(variantKey, testResult);
                    }
                } finally {
                    // Delete all variant directories
                    for (int i = 0; i < variantCount; i++) {
                        if (variantPaths[i] != null && variantPaths[i].toFile().exists()) {
                            FileUtils.deleteDirectory(variantPaths[i].toFile());
                        }
                    }
                }
            }

            private void runVariantsSequential(VariantBuildContext context, MergeAnalyzer analyzer,
                                              Instant overallStart, float totalBudgetSeconds) throws Exception {
                int variantCount = context.getVariantCount();
                String projectName = context.getProjectName();

                for (int i = 0; i < variantCount; i++) {
                    // Check remaining budget
                    float elapsed = Duration.between(overallStart, Instant.now()).toSeconds();
                    float remainingSeconds = totalBudgetSeconds - elapsed;

                    if (remainingSeconds <= 0) {
                        System.out.println("⚠ Timeout budget exhausted, skipping remaining variants (" + (variantCount - i) + " left)");
                        break;
                    }

                    // Build variant directory
                    Path variantPath = analyzer.buildVariant(context, i);
                    String variantKey = projectName + "_" + i;

                    try {
                        // Run Maven with remaining time
                        int timeoutMinutes = Math.max(AppConfig.MAVEN_BUILD_TIMEOUT, (int) Math.ceil(remainingSeconds / 60.0f));
                        MavenRunner variantRunner = new MavenRunner(logDir, false, timeoutMinutes);
                        variantRunner.run_no_optimization(variantPath);

                        // Collect results immediately
                        analyzer.collectCompilationResult(variantKey).ifPresent(result ->
                            compilationResults.put(variantKey, result)
                        );
                        TestTotal testResult = analyzer.collectTestResult(variantKey, variantPath);
                        testResults.put(variantKey, testResult);
                    } finally {
                        // Delete variant directory immediately
                        if (variantPath.toFile().exists()) {
                            FileUtils.deleteDirectory(variantPath.toFile());
                        }
                    }
                }
            }
        };
    }

}
