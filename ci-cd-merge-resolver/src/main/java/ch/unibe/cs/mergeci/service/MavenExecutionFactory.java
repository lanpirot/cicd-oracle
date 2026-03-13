package ch.unibe.cs.mergeci.service;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenRunner;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
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

        return new IJustInTimeRunner() {
            @Override
            public RunExecutionTIme run(VariantBuildContext context, MergeAnalyzer analyzer) throws Exception {
                RunExecutionTIme runExecutionTime = new RunExecutionTIme();
                Instant overallStart = Instant.now();

                // Build and run main project (human resolution)
                Path mainProjectPath = analyzer.buildMainProject(context);
                String projectName = context.getProjectName();

                float remainingSeconds = totalBudgetSeconds;
                int timeoutMinutes = Math.max(AppConfig.MAVEN_BUILD_TIMEOUT, (int) Math.ceil(remainingSeconds / 60.0f));
                MavenRunner mainRunner = new MavenRunner(logDir, false, timeoutMinutes);

                Instant start = Instant.now();
                mainRunner.run_no_optimization(mainProjectPath);
                Instant end = Instant.now();
                runExecutionTime.setMainExecutionTime(Duration.between(start, end));

                // Collect main project results
                CompilationResult mainCompResult = analyzer.collectCompilationResult(projectName);
                if (mainCompResult != null) {
                    compilationResults.put(projectName, mainCompResult);
                }
                TestTotal mainTestResult = analyzer.collectTestResult(projectName, mainProjectPath);
                testResults.put(projectName, mainTestResult);

                // Calculate remaining time
                float elapsed = Duration.between(overallStart, Instant.now()).toSeconds();
                remainingSeconds = totalBudgetSeconds - elapsed;

                if (remainingSeconds <= 0) {
                    System.out.println("⚠ Timeout budget exhausted after main project");
                    return runExecutionTime;
                }

                // Run variants with just-in-time building and immediate cleanup
                start = Instant.now();
                if (isParallel) {
                    runVariantsParallel(context, analyzer, remainingSeconds, overallStart, totalBudgetSeconds);
                } else {
                    runVariantsSequential(context, analyzer, overallStart, totalBudgetSeconds);
                }
                end = Instant.now();
                runExecutionTime.setVariantsExecutionTime(Duration.between(start, end));

                return runExecutionTime;
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
                        CompilationResult compResult = analyzer.collectCompilationResult(variantKey);
                        if (compResult != null) {
                            compilationResults.put(variantKey, compResult);
                        }
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
                        CompilationResult compResult = analyzer.collectCompilationResult(variantKey);
                        if (compResult != null) {
                            compilationResults.put(variantKey, compResult);
                        }
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

    /**
     * Create a Maven runner with a total timeout budget.
     * Each variant run gets the remaining time from the budget.
     *
     * @param isParallel Whether to run variants in parallel
     * @param isCache Whether to use cache optimization
     * @param totalBudgetSeconds Total time budget in seconds for all variants
     * @return IRunner that manages timeout budget
     * @deprecated Use createJustInTimeRunner for better disk usage
     */
    @Deprecated
    public IRunner createMavenRunnerWithBudget(boolean isParallel, boolean isCache, float totalBudgetSeconds) {
        return new IRunner() {
            @Override
            public RunExecutionTIme run(Path mainProject, List<Path> variants, Boolean useMvnDaemon) {
                RunExecutionTIme runExecutionTime = new RunExecutionTIme();
                Instant overallStart = Instant.now();

                // Run main project with full budget
                float remainingSeconds = totalBudgetSeconds;
                int timeoutMinutes = Math.max(AppConfig.MAVEN_BUILD_TIMEOUT, (int) Math.ceil(remainingSeconds / 60.0f));
                MavenRunner mainRunner = new MavenRunner(logDir, false, timeoutMinutes);

                Instant start = Instant.now();
                mainRunner.run_no_optimization(mainProject);
                Instant end = Instant.now();
                runExecutionTime.setMainExecutionTime(Duration.between(start, end));

                // Calculate remaining time after main project
                float elapsed = Duration.between(overallStart, Instant.now()).toSeconds();
                remainingSeconds = totalBudgetSeconds - elapsed;

                if (remainingSeconds <= 0) {
                    // Budget exhausted, skip variants
                    System.out.println("⚠ Timeout budget exhausted after main project");
                    return runExecutionTime;
                }

                // Run variants with remaining budget
                start = Instant.now();
                if (isParallel && isCache) {
                    // All variants start in parallel, get same remaining time
                    timeoutMinutes = Math.max(AppConfig.MAVEN_BUILD_TIMEOUT, (int) Math.ceil(remainingSeconds / 60.0f));
                    MavenRunner variantRunner = new MavenRunner(logDir, false, timeoutMinutes);
                    variantRunner.run_cache_parallel(variants.toArray(new Path[0]));
                } else if (isParallel) {
                    // All variants start in parallel, get same remaining time
                    timeoutMinutes = Math.max(AppConfig.MAVEN_BUILD_TIMEOUT, (int) Math.ceil(remainingSeconds / 60.0f));
                    MavenRunner variantRunner = new MavenRunner(logDir, false, timeoutMinutes);
                    variantRunner.run_parallel(variants.toArray(new Path[0]));
                } else {
                    // Sequential: each variant gets remaining time
                    for (Path variant : variants) {
                        elapsed = Duration.between(overallStart, Instant.now()).toSeconds();
                        remainingSeconds = totalBudgetSeconds - elapsed;

                        if (remainingSeconds <= 0) {
                            // Budget exhausted, skip remaining variants
                            System.out.println("⚠ Timeout budget exhausted, skipping remaining variants");
                            break;
                        }

                        timeoutMinutes = Math.max(AppConfig.MAVEN_BUILD_TIMEOUT, (int) Math.ceil(remainingSeconds / 60.0f));
                        MavenRunner variantRunner = new MavenRunner(logDir, false, timeoutMinutes);
                        variantRunner.run_no_optimization(variant);
                    }
                }
                end = Instant.now();
                runExecutionTime.setVariantsExecutionTime(Duration.between(start, end));

                return runExecutionTime;
            }
        };
    }

    public IRunner createMavenRunner(boolean isParallel, boolean isCache, int timeoutMinutes) {
        return new IRunner() {
            @Override
            public RunExecutionTIme run(Path mainProject, List<Path> variants, Boolean useMvnDaemon) {
                MavenRunner mavenRunner = new MavenRunner(logDir, false, timeoutMinutes);
                RunExecutionTIme runExecutionTime = new RunExecutionTIme();

                Instant start = Instant.now();
                mavenRunner.run_no_optimization(mainProject);
                Instant end = Instant.now();
                runExecutionTime.setMainExecutionTime(Duration.between(start, end));

                start = Instant.now();
                if (isParallel && isCache)
                    mavenRunner.run_cache_parallel(variants.toArray(new Path[0]));
                else if (isParallel)
                    mavenRunner.run_parallel(variants.toArray(new Path[0]));
                else if (!isCache)
                    mavenRunner.run_no_optimization(variants.toArray(new Path[0]));
                else System.out.println("ERROR in createMavenRunner!");
                end = Instant.now();
                runExecutionTime.setVariantsExecutionTime(Duration.between(start, end));

                return runExecutionTime;
            }
        };
    }

    public IRunner createMavenRunner(boolean isParallel, boolean isCache) {
        return new IRunner() {
            @Override
            public RunExecutionTIme run(Path mainProject, List<Path> variants, Boolean useMvnDaemon) {
                MavenRunner mavenRunner = new MavenRunner(logDir);
                RunExecutionTIme runExecutionTime = new RunExecutionTIme();

                Instant start = Instant.now();
                mavenRunner.run_no_optimization(mainProject);
                Instant end = Instant.now();
                runExecutionTime.setMainExecutionTime(Duration.between(start, end));

                start = Instant.now();
                if (isParallel && isCache)
                    mavenRunner.run_cache_parallel(variants.toArray(new Path[0]));
                else if (isParallel)
                    mavenRunner.run_parallel(variants.toArray(new Path[0]));
                else if (!isCache)
                    mavenRunner.run_no_optimization(variants.toArray(new Path[0]));
                else System.out.println("ERROR in createMavenRunner!");
                end = Instant.now();
                runExecutionTime.setVariantsExecutionTime(Duration.between(start, end));

                return runExecutionTime;
            }
        };
    }
}
