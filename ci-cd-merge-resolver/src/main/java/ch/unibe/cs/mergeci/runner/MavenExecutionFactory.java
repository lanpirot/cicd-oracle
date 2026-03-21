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
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
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
    @Getter
    private Map<String, Double> variantSinceMergeStartSeconds;
    @Getter
    private boolean budgetExhausted;

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
        variantSinceMergeStartSeconds = new TreeMap<>();
        budgetExhausted = false;

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

                if (skipVariants) {
                    System.out.print("[∞] ");
                } else {
                    System.out.printf("[budget: %ds]%n", totalBudgetSeconds);
                }
                System.out.flush();

                Instant variantsStart = Instant.now();
                if (!skipVariants) {
                    Instant deadline = Instant.now().plusSeconds(totalBudgetSeconds);
                    runVariants(context, builder, deadline);
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
                testResults.put(projectName, builder.collectTestResult(mainProjectPath));
            }

            private void runVariants(VariantBuildContext context, VariantProjectBuilder builder,
                                     Instant deadline) throws Exception {
                java.nio.file.Files.createDirectories(logDir);
                String projectName = context.getProjectName();
                MavenCommandResolver commandResolver = new MavenCommandResolver(false);
                MavenCacheManager cacheManager = new MavenCacheManager();
                int globalVariantIndex = 1;
                Path cacheWarmPath = null;
                Instant variantsStart = Instant.now();

                // Cache mode: run the very first variant synchronously to warm the local Maven cache.
                if (isCache) {
                    Optional<VariantProject> first = context.nextVariant();
                    int timeout0 = remainingSeconds(deadline);
                    if (first.isPresent() && timeout0 > 0) {
                        int idx = globalVariantIndex++;
                        Path firstPath = builder.buildVariant(context, first.get(), idx);
                        cacheManager.injectCacheArtifacts(firstPath);
                        System.out.printf("[DEBUG] variant %s starting (cache-warmer), timeout=%ds%n",
                                firstPath.getFileName(), timeout0);
                        new MavenProcessExecutor(timeout0).executeCommand(
                                firstPath, logDir.resolve(firstPath.getFileName() + "_compilation"),
                                AppConfig.buildCommand(commandResolver.resolveMavenCommand(firstPath),
                                        commandResolver.resolveMavenGoal(firstPath), firstPath));
                        cacheWarmPath = firstPath;
                        String warmKey = projectName + "_" + idx;
                        collectResult(warmKey, firstPath, builder, variantsStart);
                    }
                }
                final Path warmPath = cacheWarmPath;

                if (isParallel) {
                    ExecutorService executor = Executors.newFixedThreadPool(AppConfig.MAX_THREADS);
                    CompletionService<Map.Entry<String, Path>> cs = new ExecutorCompletionService<>(executor);
                    // Track every path we build so the finally block can clean up after an exception.
                    List<Path> allocatedPaths = new ArrayList<>();
                    if (warmPath != null) allocatedPaths.add(warmPath);

                    try {
                        // Pre-fill the thread pool.
                        int inFlight = 0;
                        while (inFlight < AppConfig.MAX_THREADS && !Instant.now().isAfter(deadline)) {
                            Optional<VariantProject> next = context.nextVariant();
                            if (next.isEmpty()) break;
                            int idx = globalVariantIndex++;
                            String key = projectName + "_" + idx;
                            Path vPath = builder.buildVariant(context, next.get(), idx);
                            allocatedPaths.add(vPath);
                            submitTask(cs, commandResolver, cacheManager, warmPath, vPath, key, deadline);
                            inFlight++;
                        }

                        // Rolling: as each slot frees up, collect results and immediately fill the slot.
                        while (inFlight > 0) {
                            Map.Entry<String, Path> done = drainOne(cs);
                            inFlight--;
                            collectResult(done.getKey(), done.getValue(), builder, variantsStart);
                            if (!done.getValue().equals(warmPath) && done.getValue().toFile().exists()) {
                                deleteWithRetry(done.getValue());
                            }
                            if (!Instant.now().isAfter(deadline)) {
                                Optional<VariantProject> next = context.nextVariant();
                                if (next.isPresent()) {
                                    int idx = globalVariantIndex++;
                                    String key = projectName + "_" + idx;
                                    Path vPath = builder.buildVariant(context, next.get(), idx);
                                    allocatedPaths.add(vPath);
                                    submitTask(cs, commandResolver, cacheManager, warmPath, vPath, key, deadline);
                                    inFlight++;
                                }
                            }
                        }
                    } finally {
                        executor.shutdownNow();
                        for (Path p : allocatedPaths) {
                            if (p != null && !p.equals(warmPath) && p.toFile().exists()) {
                                try { FileUtils.deleteDirectory(p.toFile()); } catch (Exception ignored) {}
                            }
                        }
                        if (warmPath != null && warmPath.toFile().exists()) {
                            try { FileUtils.deleteDirectory(warmPath.toFile()); } catch (Exception ignored) {}
                        }
                    }
                } else {
                    double cumulativeSeconds = 0;
                    try {
                        while (true) {
                            int variantTimeout = remainingSeconds(deadline);
                            if (variantTimeout <= 0) {
                                System.out.println("⚠ Timeout budget exhausted");
                                MavenExecutionFactory.this.budgetExhausted = true;
                                break;
                            }
                            Optional<VariantProject> next = context.nextVariant();
                            if (next.isEmpty()) break;

                            VariantProject variant = next.get();
                            int idx = globalVariantIndex++;
                            Path variantPath = builder.buildVariant(context, variant, idx);
                            String variantKey = projectName + "_" + idx;

                            try {
                                System.out.printf("[DEBUG] sequential variant %d: timeout=%ds%n", idx, variantTimeout);
                                Instant variantStart = Instant.now();
                                if (isCache) {
                                    cacheManager.injectCacheArtifacts(variantPath);
                                    cacheManager.copyTargetDirectories(warmPath.toFile(), variantPath.toFile());
                                    cacheManager.copyCacheDirectory(warmPath, variantPath);
                                    new MavenProcessExecutor(variantTimeout).executeCommand(
                                            variantPath, logDir.resolve(variantPath.getFileName() + "_compilation"),
                                            AppConfig.buildCommandOffline(commandResolver.resolveMavenCommand(variantPath),
                                                    commandResolver.resolveMavenGoal(variantPath), variantPath));
                                } else {
                                    new MavenRunner(logDir, false, variantTimeout).run_no_optimization(variantPath);
                                }
                                Instant variantFinish = Instant.now();
                                cumulativeSeconds += Duration.between(variantStart, variantFinish).toMillis() / 1000.0;
                                builder.collectCompilationResult(variantKey).ifPresent(result ->
                                    compilationResults.put(variantKey, result)
                                );
                                testResults.put(variantKey, builder.collectTestResult(variantPath));
                                variantFinishSeconds.put(variantKey, cumulativeSeconds);
                                variantSinceMergeStartSeconds.put(variantKey,
                                        (double) Duration.between(variantsStart, variantFinish).getSeconds());
                            } finally {
                                if (!variantPath.equals(warmPath) && variantPath.toFile().exists()) {
                                    deleteWithRetry(variantPath);
                                }
                            }
                        }
                    } finally {
                        if (warmPath != null && warmPath.toFile().exists()) {
                            try { FileUtils.deleteDirectory(warmPath.toFile()); } catch (Exception ignored) {}
                        }
                    }
                }
            }

            private void submitTask(CompletionService<Map.Entry<String, Path>> cs,
                                    MavenCommandResolver commandResolver,
                                    MavenCacheManager cacheManager,
                                    Path warmPath, Path vPath, String key, Instant deadline) {
                cs.submit(() -> {
                    int timeout = remainingSeconds(deadline);
                    if (timeout > 0) {
                        System.out.printf("[DEBUG] variant %s starting, timeout=%ds%n", vPath.getFileName(), timeout);
                        if (isCache) {
                            cacheManager.injectCacheArtifacts(vPath);
                            cacheManager.copyTargetDirectories(warmPath.toFile(), vPath.toFile());
                            cacheManager.copyCacheDirectory(warmPath, vPath);
                            new MavenProcessExecutor(timeout).executeCommand(
                                    vPath, logDir.resolve(vPath.getFileName() + "_compilation"),
                                    AppConfig.buildCommandOffline(commandResolver.resolveMavenCommand(vPath),
                                            commandResolver.resolveMavenGoal(vPath), vPath));
                        } else {
                            new MavenProcessExecutor(timeout).executeCommand(
                                    vPath, logDir.resolve(vPath.getFileName() + "_compilation"),
                                    AppConfig.buildCommand(commandResolver.resolveMavenCommand(vPath),
                                            commandResolver.resolveMavenGoal(vPath), vPath));
                        }
                    }
                    return Map.entry(key, vPath);
                });
            }

            private Map.Entry<String, Path> drainOne(CompletionService<Map.Entry<String, Path>> cs)
                    throws InterruptedException, ExecutionException {
                return cs.take().get();
            }

            private void collectResult(String key, Path path, VariantProjectBuilder builder, Instant variantsStart) throws Exception {
                Instant finish = Instant.now();
                builder.collectCompilationResult(key).ifPresent(r -> compilationResults.put(key, r));
                testResults.put(key, builder.collectTestResult(path));
                CompilationResult cr = compilationResults.get(key);
                variantFinishSeconds.put(key, cr != null ? (double) cr.getTotalTime() : 0.0);
                variantSinceMergeStartSeconds.put(key, (double) Duration.between(variantsStart, finish).getSeconds());
            }

            private int remainingSeconds(Instant deadline) {
                return (int) Duration.between(Instant.now(), deadline).getSeconds();
            }
        };
    }

    /**
     * Delete a directory, retrying a few times to handle the race where a just-killed
     * child process (surefire fork, JaCoCo agent) hasn't yet released its file handles.
     */
    private static void deleteWithRetry(Path path) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                FileUtils.deleteDirectory(path.toFile());
                return;
            } catch (java.io.IOException e) {
                if (attempt == 4) throw e;
                Thread.sleep(500);
            }
        }
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
