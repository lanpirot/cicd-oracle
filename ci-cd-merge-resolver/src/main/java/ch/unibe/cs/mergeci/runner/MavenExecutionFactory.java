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
                java.nio.file.Files.createDirectories(logDir);
                String projectName = context.getProjectName();
                MavenCommandResolver commandResolver = new MavenCommandResolver(false);
                MavenCacheManager cacheManager = new MavenCacheManager();
                int globalVariantIndex = 0;
                Path cacheWarmPath = null;

                ExecutorService executor = Executors.newFixedThreadPool(AppConfig.MAX_THREADS);
                CompletionService<Map.Entry<String, Path>> cs = new ExecutorCompletionService<>(executor);
                // Track every path we build so the finally block can clean up after an exception.
                List<Path> allocatedPaths = new ArrayList<>();

                try {
                    // Cache mode: run the very first variant synchronously to warm the local Maven cache
                    // before any parallel tasks start copying from it.
                    if (isCache) {
                        Optional<VariantProject> first = context.nextVariant();
                        if (first.isPresent() && !Instant.now().isAfter(deadline)) {
                            int idx = globalVariantIndex++;
                            Path firstPath = builder.buildVariant(context, first.get(), idx);
                            allocatedPaths.add(firstPath);
                            cacheManager.injectCacheArtifacts(firstPath);
                            int timeout0 = remainingSeconds(deadline);
                            System.out.printf("[DEBUG] variant %s starting (cache-warmer), timeout=%ds%n",
                                    firstPath.getFileName(), timeout0);
                            new MavenProcessExecutor(timeout0).executeCommand(
                                    firstPath, logDir.resolve(firstPath.getFileName() + "_compilation"),
                                    AppConfig.buildCommand(commandResolver.resolveMavenCommand(firstPath),
                                            commandResolver.resolveMavenGoal(firstPath)));
                            cacheWarmPath = firstPath;
                            String warmKey = projectName + "_" + idx;
                            collectResult(warmKey, firstPath, builder);
                        }
                    }
                    final Path warmPath = cacheWarmPath;

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
                        collectResult(done.getKey(), done.getValue(), builder);
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
                        if (p != null && !p.equals(cacheWarmPath) && p.toFile().exists()) {
                            try { FileUtils.deleteDirectory(p.toFile()); } catch (Exception ignored) {}
                        }
                    }
                    if (cacheWarmPath != null && cacheWarmPath.toFile().exists()) {
                        try { FileUtils.deleteDirectory(cacheWarmPath.toFile()); } catch (Exception ignored) {}
                    }
                }
            }

            private void submitTask(CompletionService<Map.Entry<String, Path>> cs,
                                    MavenCommandResolver commandResolver,
                                    MavenCacheManager cacheManager,
                                    Path warmPath, Path vPath, String key, Instant deadline) {
                cs.submit(() -> {
                    if (!Instant.now().isAfter(deadline)) {
                        int timeout = remainingSeconds(deadline);
                        System.out.printf("[DEBUG] variant %s starting, timeout=%ds%n", vPath.getFileName(), timeout);
                        if (isCache) {
                            cacheManager.injectCacheArtifacts(vPath);
                            cacheManager.copyTargetDirectories(warmPath.toFile(), vPath.toFile());
                            cacheManager.copyCacheDirectory(warmPath, vPath);
                            new MavenProcessExecutor(timeout).executeCommand(
                                    vPath, logDir.resolve(vPath.getFileName() + "_compilation"),
                                    AppConfig.buildCommandOffline(commandResolver.resolveMavenCommand(vPath),
                                            commandResolver.resolveMavenGoal(vPath)));
                        } else {
                            new MavenProcessExecutor(timeout).executeCommand(
                                    vPath, logDir.resolve(vPath.getFileName() + "_compilation"),
                                    AppConfig.buildCommand(commandResolver.resolveMavenCommand(vPath),
                                            commandResolver.resolveMavenGoal(vPath)));
                        }
                    }
                    return Map.entry(key, vPath);
                });
            }

            private Map.Entry<String, Path> drainOne(CompletionService<Map.Entry<String, Path>> cs)
                    throws InterruptedException, ExecutionException {
                return cs.take().get();
            }

            private void collectResult(String key, Path path, VariantProjectBuilder builder) throws Exception {
                builder.collectCompilationResult(key).ifPresent(r -> compilationResults.put(key, r));
                testResults.put(key, builder.collectTestResult(key, path));
                CompilationResult cr = compilationResults.get(key);
                variantFinishSeconds.put(key, cr != null ? (double) cr.getTotalTime() : 0.0);
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
                            deleteWithRetry(variantPath);
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
