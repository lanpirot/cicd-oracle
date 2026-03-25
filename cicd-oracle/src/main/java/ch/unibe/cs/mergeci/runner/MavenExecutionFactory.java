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

import java.lang.management.ManagementFactory;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    @Getter
    private String cacheWarmerKey;
    @Getter
    private int numInFlightVariantsKilled;

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
        cacheWarmerKey = null;
        numInFlightVariantsKilled = 0;

        return new IJustInTimeRunner() {
            @Override
            public ExperimentTiming run(VariantBuildContext context, VariantProjectBuilder builder) throws Exception {
                ExperimentTiming experimentTiming = new ExperimentTiming();

                long rawBaselineSeconds;
                long peakBaselineRamBytes = 0;
                if (storedBaselineSeconds > 0) {
                    experimentTiming.setHumanBaselineExecutionTime(Duration.ofSeconds(storedBaselineSeconds));
                    rawBaselineSeconds = storedBaselineSeconds;
                } else {
                    RamSampler ramSampler = new RamSampler();
                    ramSampler.start();
                    try {
                        executeHumanBaseline(context, builder, experimentTiming);
                    } finally {
                        ramSampler.stop();
                    }
                    peakBaselineRamBytes = ramSampler.peakUsageBytes();
                    rawBaselineSeconds = experimentTiming.getHumanBaselineExecutionTime().getSeconds();
                }
                TestTotal baselineTests = testResults.get(context.getProjectName());
                long baselineSeconds = normalizeBaselineSeconds(rawBaselineSeconds, baselineTests);
                long totalBudgetSeconds = baselineSeconds * AppConfig.TIMEOUT_MULTIPLIER;

                int maxThreads = AppConfig.computeMaxThreads(peakBaselineRamBytes);
                if (!skipVariants) {
                    System.out.printf("[budget: %ds, threads: %d]%n", totalBudgetSeconds, maxThreads);
                } else {
                    System.out.println();
                }
                System.out.flush();

                Instant variantsStart = Instant.now();
                if (!skipVariants) {
                    Instant deadline = variantsStart.plusSeconds(totalBudgetSeconds);
                    runVariants(context, builder, deadline, variantsStart, maxThreads);
                }
                experimentTiming.setVariantsExecutionTime(Duration.between(variantsStart, Instant.now()));

                return experimentTiming;
            }

            private void executeHumanBaseline(VariantBuildContext context, VariantProjectBuilder builder,
                                              ExperimentTiming experimentTiming) throws Exception {
                Path mainProjectPath = builder.buildMainProject(context);
                String projectName = context.getProjectName();

                // Cap the baseline at MAVEN_BUILD_TIMEOUT so hung builds don't block the pipeline.
                // The actual wall-clock duration (capped at this limit) sets the variant budget.
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
                testResults.put(projectName, builder.collectTestResult(mainProjectPath));
            }

            private void runVariants(VariantBuildContext context, VariantProjectBuilder builder,
                                     Instant deadline, Instant variantsStart, int maxThreads) throws Exception {
                java.nio.file.Files.createDirectories(logDir);
                String projectName = context.getProjectName();
                MavenCommandResolver commandResolver = new MavenCommandResolver(false);
                MavenCacheManager cacheManager = new MavenCacheManager();
                int globalVariantIndex = 1;
                Path cacheWarmPath = null;

                // Sequential cache mode: run the very first variant synchronously to warm the local Maven cache.
                if (isCache && !isParallel) {
                    Optional<VariantProject> first = context.nextVariant();
                    int timeout0 = remainingSeconds(deadline);
                    if (first.isPresent() && timeout0 > 0) {
                        int idx = globalVariantIndex++;
                        Path firstPath = builder.buildVariant(context, first.get(), idx);
                        cacheManager.injectCacheArtifacts(firstPath);
                        System.out.printf("[DEBUG] variant %s starting (cache-warmer), timeout=%ds%n",
                                firstPath.getFileName(), timeout0);
                        Instant warmStart = Instant.now();
                        new MavenProcessExecutor(timeout0).executeCommand(
                                firstPath, logDir.resolve(firstPath.getFileName() + "_compilation"),
                                AppConfig.buildCommand(commandResolver.resolveMavenCommand(firstPath),
                                        commandResolver.resolveMavenGoal(firstPath), firstPath));
                        double warmWallClock = Duration.between(warmStart, Instant.now()).toMillis() / 1000.0;
                        cacheWarmPath = firstPath;
                        String warmKey = projectName + "_" + idx;
                        MavenExecutionFactory.this.cacheWarmerKey = warmKey;
                        collectResult(warmKey, firstPath, builder, variantsStart, warmWallClock);
                    }
                }
                final Path warmPath = cacheWarmPath;

                if (isParallel) {
                    // In cache mode, the first submitted variant acts as the cache warmer;
                    // subsequent variants copy from it if it has finished, or fall back to a normal build.
                    AtomicReference<Path> warmPathRef = new AtomicReference<>(null);
                    ExecutorService executor = Executors.newFixedThreadPool(maxThreads);
                    CompletionService<TaskResult> cs = new ExecutorCompletionService<>(executor);
                    // Track every path we build so the finally block can clean up after an exception.
                    List<Path> allocatedPaths = new ArrayList<>();

                    try {
                        // Pre-fill the thread pool.
                        int inFlight = 0;
                        while (inFlight < maxThreads && !Instant.now().isAfter(deadline)) {
                            Optional<VariantProject> next = context.nextVariant();
                            if (next.isEmpty()) break;
                            int idx = globalVariantIndex++;
                            String key = projectName + "_" + idx;
                            Path vPath = builder.buildVariant(context, next.get(), idx);
                            allocatedPaths.add(vPath);
                            boolean isWarmer = isCache && (inFlight == 0);
                            submitTask(cs, commandResolver, cacheManager, warmPathRef, isWarmer, vPath, key, deadline);
                            inFlight++;
                        }

                        // Rolling: as each slot frees up, collect results and immediately fill the slot.
                        while (inFlight > 0) {
                            TaskResult done = drainOne(cs);
                            inFlight--;
                            collectResult(done.key(), done.path(), builder, variantsStart, done.wallClockSeconds());
                            Path currentWarm = warmPathRef.get();
                            if (!done.path().equals(currentWarm) && done.path().toFile().exists()) {
                                deleteWithRetry(done.path());
                            }
                            if (!Instant.now().isAfter(deadline)) {
                                Optional<VariantProject> next = context.nextVariant();
                                if (next.isPresent()) {
                                    int idx = globalVariantIndex++;
                                    String key = projectName + "_" + idx;
                                    Path vPath = builder.buildVariant(context, next.get(), idx);
                                    allocatedPaths.add(vPath);
                                    submitTask(cs, commandResolver, cacheManager, warmPathRef, false, vPath, key, deadline);
                                    inFlight++;
                                }
                            }
                        }
                    } finally {
                        executor.shutdownNow();
                        // variants submitted but not yet collected when budget expired
                        MavenExecutionFactory.this.numInFlightVariantsKilled =
                                (globalVariantIndex - 1) - (compilationResults.size() - 1);
                        Path finalWarm = warmPathRef.get();
                        for (Path p : allocatedPaths) {
                            if (p != null && !p.equals(finalWarm) && p.toFile().exists()) {
                                try { FileUtils.deleteDirectory(p.toFile()); } catch (Exception ignored) {}
                            }
                        }
                        if (finalWarm != null && finalWarm.toFile().exists()) {
                            try { FileUtils.deleteDirectory(finalWarm.toFile()); } catch (Exception ignored) {}
                        }
                    }
                } else {
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
                                double wallClockSeconds = Duration.between(variantStart, variantFinish).toMillis() / 1000.0;
                                builder.collectCompilationResult(variantKey).ifPresent(result ->
                                    compilationResults.put(variantKey, result)
                                );
                                testResults.put(variantKey, builder.collectTestResult(variantPath));
                                variantFinishSeconds.put(variantKey, wallClockSeconds);
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

            private void submitTask(CompletionService<TaskResult> cs,
                                    MavenCommandResolver commandResolver,
                                    MavenCacheManager cacheManager,
                                    AtomicReference<Path> warmPathRef,
                                    boolean isWarmer,
                                    Path vPath, String key, Instant deadline) {
                cs.submit(() -> {
                    Instant taskStart = Instant.now();
                    int timeout = remainingSeconds(deadline);
                    if (timeout > 0) {
                        if (isWarmer) {
                            System.out.printf("[DEBUG] variant %s starting (cache-warmer), timeout=%ds%n",
                                    vPath.getFileName(), timeout);
                            cacheManager.injectCacheArtifacts(vPath);
                            new MavenProcessExecutor(timeout).executeCommand(
                                    vPath, logDir.resolve(vPath.getFileName() + "_compilation"),
                                    AppConfig.buildCommand(commandResolver.resolveMavenCommand(vPath),
                                            commandResolver.resolveMavenGoal(vPath), vPath));
                            warmPathRef.set(vPath);
                            MavenExecutionFactory.this.cacheWarmerKey = key;
                        } else {
                            Path warm = isCache ? warmPathRef.get() : null;
                            System.out.printf("[DEBUG] variant %s starting%s, timeout=%ds%n",
                                    vPath.getFileName(), warm != null ? " (cache ready)" : "", timeout);
                            if (warm != null) {
                                cacheManager.injectCacheArtifacts(vPath);
                                cacheManager.copyTargetDirectories(warm.toFile(), vPath.toFile());
                                cacheManager.copyCacheDirectory(warm, vPath);
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
                    }
                    double wallClockSeconds = Duration.between(taskStart, Instant.now()).toMillis() / 1000.0;
                    return new TaskResult(key, vPath, wallClockSeconds);
                });
            }

            private TaskResult drainOne(CompletionService<TaskResult> cs)
                    throws InterruptedException, ExecutionException {
                return cs.take().get();
            }

            private void collectResult(String key, Path path, VariantProjectBuilder builder,
                                       Instant variantsStart, double wallClockSeconds) throws Exception {
                Instant finish = Instant.now();
                builder.collectCompilationResult(key).ifPresent(r -> compilationResults.put(key, r));
                testResults.put(key, builder.collectTestResult(path));
                variantFinishSeconds.put(key, wallClockSeconds);
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

    /** Carries per-variant wall-clock timing out of parallel task lambdas. */
    private record TaskResult(String key, Path path, double wallClockSeconds) {}

    /**
     * Samples system free RAM every 200 ms while a build runs and reports the
     * peak consumption as {@code initialFreeRam − minimumFreeRam}.
     */
    private static class RamSampler {
        private final long initialFreeBytes;
        private volatile long minFreeBytes;
        private final ScheduledExecutorService scheduler;

        RamSampler() {
            long free = freeSystemRam();
            this.initialFreeBytes = free;
            this.minFreeBytes = free;
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ram-sampler");
                t.setDaemon(true);
                return t;
            });
        }

        void start() {
            scheduler.scheduleAtFixedRate(() -> {
                long free = freeSystemRam();
                if (free < minFreeBytes) minFreeBytes = free;
            }, 0, 200, TimeUnit.MILLISECONDS);
        }

        void stop() {
            scheduler.shutdownNow();
        }

        /** Peak RAM consumed during sampling: initialFree − lowestFree seen. */
        long peakUsageBytes() {
            return Math.max(0, initialFreeBytes - minFreeBytes);
        }

        private static long freeSystemRam() {
            try {
                return ((com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean()).getFreeMemorySize();
            } catch (Exception e) {
                return Runtime.getRuntime().freeMemory();
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
