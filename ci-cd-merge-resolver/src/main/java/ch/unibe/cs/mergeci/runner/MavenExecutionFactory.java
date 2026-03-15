package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.model.VariantProject;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.JacocoReportFinder;
import ch.unibe.cs.mergeci.runner.maven.MavenCacheManager;
import ch.unibe.cs.mergeci.runner.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.runner.maven.MavenProcessExecutor;
import ch.unibe.cs.mergeci.runner.maven.MavenRunner;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import ch.unibe.cs.mergeci.util.Utility;
import lombok.Getter;
import org.apache.commons.io.FileUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        return createJustInTimeRunner(isParallel, isCache, false);
    }

    public IJustInTimeRunner createJustInTimeRunner(boolean isParallel, boolean isCache, boolean skipVariants) {
        return createJustInTimeRunner(isParallel, isCache, skipVariants, 0L);
    }

    /**
     * Create a just-in-time runner.
     * If storedBaselineSeconds > 0, the human baseline build is skipped and the stored value
     * is used directly as the timeout budget base. Otherwise, the baseline is run first and
     * its actual duration sets the variant budget via TIMEOUT_MULTIPLIER.
     */
    public IJustInTimeRunner createJustInTimeRunner(boolean isParallel, boolean isCache, boolean skipVariants, long storedBaselineSeconds) {
        compilationResults = new TreeMap<>();
        testResults = new TreeMap<>();
        coverageResult = null;
        variantFinishSeconds = new TreeMap<>();

        return new IJustInTimeRunner() {
            @Override
            public ExperimentTiming run(VariantBuildContext context, VariantProjectBuilder builder) throws Exception {
                ExperimentTiming experimentTiming = new ExperimentTiming();

                long rawBaselineSeconds;
                if (storedBaselineSeconds > 0) {
                    experimentTiming.setHumanBaselineExecutionTime(Duration.ofSeconds(storedBaselineSeconds));
                    rawBaselineSeconds = storedBaselineSeconds;
                } else {
                    executeHumanBaseline(context, builder, experimentTiming);
                    rawBaselineSeconds = experimentTiming.getHumanBaselineExecutionTime().getSeconds();
                }
                TestTotal baselineTests = testResults.get(context.getProjectName());
                long baselineSeconds = normalizeBaselineSeconds(rawBaselineSeconds, baselineTests);
                long totalBudgetSeconds = baselineSeconds * AppConfig.TIMEOUT_MULTIPLIER;
                Instant deadline = Instant.now().plusSeconds(totalBudgetSeconds);

                if (skipVariants) {
                    System.out.print("[∞] ");
                } else {
                    System.out.printf("[budget: %ds] ", totalBudgetSeconds);
                }
                System.out.flush();

                Instant variantsStart = Instant.now();
                if (!skipVariants) {
                    if (isParallel) {
                        runVariantsParallel(context, builder, deadline);
                    } else {
                        runVariantsSequential(context, builder, deadline);
                    }
                }
                experimentTiming.setVariantsExecutionTime(Duration.between(variantsStart, Instant.now()));

                return experimentTiming;
            }

            private void executeHumanBaseline(VariantBuildContext context, VariantProjectBuilder builder,
                                              ExperimentTiming experimentTiming) throws Exception {
                Path mainProjectPath = builder.buildMainProject(context);
                String projectName = context.getProjectName();

                // No timeout for the baseline: its real duration sets the budget for all variant modes.
                MavenRunner mainRunner = new MavenRunner(logDir, false, 0);

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
                String projectName = context.getProjectName();
                MavenCommandResolver commandResolver = new MavenCommandResolver(false);
                MavenCacheManager cacheManager = new MavenCacheManager();
                int globalVariantIndex = 0;
                // For cache mode: keep the first-ever variant path alive so subsequent batches can copy from it.
                Path cacheWarmPath = null;

                try {
                    while (!Instant.now().isAfter(deadline)) {
                        // Generate up to MAX_THREADS variants just-in-time for this batch.
                        List<VariantProject> batch = new ArrayList<>();
                        while (batch.size() < AppConfig.MAX_THREADS) {
                            Optional<VariantProject> next = context.nextVariant();
                            if (next.isEmpty()) break;
                            batch.add(next.get());
                        }
                        if (batch.isEmpty()) break; // strategy space exhausted

                        int batchStart = globalVariantIndex;
                        int batchSize = batch.size();
                        Path[] batchPaths = new Path[batchSize];
                        for (int i = 0; i < batchSize; i++) {
                            batchPaths[i] = builder.buildVariant(context, batch.get(i), batchStart + i);
                        }
                        globalVariantIndex += batchSize;

                        System.out.printf("[DEBUG] parallel batch: %d variants, deadline in %ds%n", batchSize, remainingSeconds(deadline));

                        ExecutorService batchExecutor = Executors.newFixedThreadPool(AppConfig.MAX_THREADS);
                        try {
                            if (isCache) {
                                int startIdx = 0;
                                if (cacheWarmPath == null) {
                                    // Run first variant sequentially to warm the Maven cache.
                                    Path first = batchPaths[0];
                                    if (!Instant.now().isAfter(deadline)) {
                                        cacheManager.injectCacheArtifacts(first);
                                        int timeout0 = remainingSeconds(deadline);
                                        System.out.printf("[DEBUG] variant %s starting (cache-warmer), timeout=%ds%n", first.getFileName(), timeout0);
                                        new MavenProcessExecutor(timeout0).executeCommand(
                                                first, logDir.resolve(first.getFileName() + "_compilation"),
                                                AppConfig.buildCommand(commandResolver.resolveMavenCommand(first)));
                                        cacheWarmPath = first;
                                    }
                                    startIdx = 1;
                                }
                                final Path warmPath = cacheWarmPath;
                                for (int i = startIdx; i < batchSize; i++) {
                                    final Path variantPath = batchPaths[i];
                                    batchExecutor.submit(() -> {
                                        if (Instant.now().isAfter(deadline)) return;
                                        int timeout = remainingSeconds(deadline);
                                        System.out.printf("[DEBUG] variant %s starting, timeout=%ds%n", variantPath.getFileName(), timeout);
                                        cacheManager.injectCacheArtifacts(variantPath);
                                        cacheManager.copyTargetDirectories(warmPath.toFile(), variantPath.toFile());
                                        cacheManager.copyCacheDirectory(warmPath, variantPath);
                                        new MavenProcessExecutor(timeout).executeCommand(
                                                variantPath, logDir.resolve(variantPath.getFileName() + "_compilation"),
                                                AppConfig.buildCommandOffline(commandResolver.resolveMavenCommand(variantPath)));
                                    });
                                }
                            } else {
                                for (Path variantPath : batchPaths) {
                                    batchExecutor.submit(() -> {
                                        if (Instant.now().isAfter(deadline)) return;
                                        int timeout = remainingSeconds(deadline);
                                        System.out.printf("[DEBUG] variant %s starting, timeout=%ds%n", variantPath.getFileName(), timeout);
                                        new MavenProcessExecutor(timeout).executeCommand(
                                                variantPath, logDir.resolve(variantPath.getFileName() + "_compilation"),
                                                AppConfig.buildCommand(commandResolver.resolveMavenCommand(variantPath)));
                                    });
                                }
                            }
                            Utility.shutdownAndAwaitTermination(batchExecutor);

                            // Collect results for this batch.
                            for (int i = 0; i < batchSize; i++) {
                                String variantKey = projectName + "_" + (batchStart + i);
                                final Path vPath = batchPaths[i];
                                builder.collectCompilationResult(variantKey).ifPresent(result ->
                                    compilationResults.put(variantKey, result)
                                );
                                testResults.put(variantKey, builder.collectTestResult(variantKey, vPath));
                                CompilationResult cr = compilationResults.get(variantKey);
                                double finish = (cr != null) ? (double) cr.getTotalTime() : 0.0;
                                variantFinishSeconds.put(variantKey, finish);
                            }
                        } finally {
                            // Clean up batch paths, keeping cacheWarmPath alive for subsequent batches.
                            for (Path p : batchPaths) {
                                if (p != null && !p.equals(cacheWarmPath) && p.toFile().exists()) {
                                    FileUtils.deleteDirectory(p.toFile());
                                }
                            }
                        }
                    }
                } finally {
                    if (cacheWarmPath != null && cacheWarmPath.toFile().exists()) {
                        FileUtils.deleteDirectory(cacheWarmPath.toFile());
                    }
                }
            }

            private void runVariantsSequential(VariantBuildContext context, VariantProjectBuilder builder,
                                               Instant deadline) throws Exception {
                String projectName = context.getProjectName();
                double cumulativeSeconds = 0;
                int variantIndex = 0;

                while (true) {
                    if (Instant.now().isAfter(deadline)) {
                        System.out.println("⚠ Timeout budget exhausted");
                        break;
                    }

                    Optional<VariantProject> next = context.nextVariant();
                    if (next.isEmpty()) break; // strategy space exhausted

                    VariantProject variant = next.get();
                    if (Instant.now().isAfter(deadline)) {
                        System.out.println("⚠ Timeout budget exhausted");
                        break;
                    }
                    Path variantPath = builder.buildVariant(context, variant, variantIndex);
                    String variantKey = projectName + "_" + variantIndex;
                    variantIndex++;

                    try {
                        int variantTimeout = remainingSeconds(deadline);
                        System.out.printf("[DEBUG] sequential variant %d: timeout=%ds%n", variantIndex - 1, variantTimeout);
                        MavenRunner variantRunner = new MavenRunner(logDir, false, variantTimeout);
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

            private int remainingSeconds(Instant deadline) {
                return (int) Math.max(1, Duration.between(Instant.now(), deadline).getSeconds());
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
