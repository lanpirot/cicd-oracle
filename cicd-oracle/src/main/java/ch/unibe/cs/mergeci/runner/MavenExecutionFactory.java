package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.MavenCacheManager;
import ch.unibe.cs.mergeci.runner.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.runner.maven.MavenProcessExecutor;
import ch.unibe.cs.mergeci.runner.maven.MavenRunner;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import ch.unibe.cs.mergeci.util.JavaVersionResolver;
import lombok.Getter;
import org.apache.commons.io.FileUtils;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates variant builds for the experiment pipeline.
 *
 * <p>Baseline measurement, budget computation, and overlay base creation remain here.
 * The variant build-test loop is delegated to {@link VariantExecutionEngine}, which is
 * shared with the IntelliJ plugin.
 */
public class MavenExecutionFactory {
    private final Path logDir;
    @Getter
    private Map<String, CompilationResult> compilationResults;
    @Getter
    private Map<String, TestTotal> testResults;
    @Getter
    private Map<String, Double> variantFinishSeconds;
    @Getter
    private Map<String, Double> variantSinceMergeStartSeconds;
    @Getter
    private boolean budgetExhausted;
    @Getter
    private Set<String> cacheDonorKeys;
    private DonorTracker donorTracker;
    @Getter
    private Set<String> cacheHitKeys;
    @Getter
    private int numInFlightVariantsKilled;
    @Getter
    private int maxThreads;
    @Getter
    private long peakBaselineRamBytes;
    @Getter
    private long baselineDirGrowthBytes;
    private String resolvedJavaHome;

    /** Temp directory for per-variant .m2 overlay upper/work/mount dirs. */
    private static final Path M2_OVERLAY_DIR = AppConfig.TMP_DIR.resolve("m2_overlays");
    /** Whether to use overlayFS isolation for the local Maven repo in parallel builds. */
    private static final boolean m2OverlayEnabled = OverlayMount.isAvailable();

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

    public IJustInTimeRunner createJustInTimeRunner(boolean isParallel, boolean isCache, boolean skipVariants, long storedBaselineSeconds) {
        return createJustInTimeRunner(isParallel, isCache, skipVariants, storedBaselineSeconds, 0L, 0L, true);
    }

    public IJustInTimeRunner createJustInTimeRunner(boolean isParallel, boolean isCache, boolean skipVariants,
                                                     long storedBaselineSeconds, long storedPeakRamBytes,
                                                     long storedDirGrowthBytes, boolean stopOnPerfect) {
        compilationResults = new TreeMap<>();
        testResults = new TreeMap<>();
        variantFinishSeconds = new TreeMap<>();
        variantSinceMergeStartSeconds = new TreeMap<>();
        budgetExhausted = false;
        cacheDonorKeys = ConcurrentHashMap.newKeySet();
        donorTracker = new DonorTracker();
        cacheHitKeys = ConcurrentHashMap.newKeySet();
        numInFlightVariantsKilled = 0;
        resolvedJavaHome = null;

        return (context, builder) -> {
            ExperimentTiming experimentTiming = new ExperimentTiming();
            boolean useOverlay = OverlayMount.isAvailable();
            if (useOverlay) {
                OverlayMount.cleanupStaleMounts(AppConfig.OVERLAY_TMP_DIR);
            }

            // ── Baseline measurement ──────────────────────────────────────────
            long rawBaselineSeconds;
            long measuredPeakRam = 0;
            long measuredDirGrowth = 0;
            if (storedBaselineSeconds > 0) {
                experimentTiming.setHumanBaselineExecutionTime(Duration.ofSeconds(storedBaselineSeconds));
                rawBaselineSeconds = storedBaselineSeconds;
                measuredPeakRam = storedPeakRamBytes;
                measuredDirGrowth = storedDirGrowthBytes;
            } else {
                RamSampler ramSampler = new RamSampler();
                ramSampler.start();
                try {
                    measuredDirGrowth = executeHumanBaseline(context, builder, experimentTiming);
                } finally {
                    ramSampler.stop();
                }
                measuredPeakRam = ramSampler.peakUsageBytes();
                rawBaselineSeconds = experimentTiming.getHumanBaselineExecutionTime().getSeconds();
            }
            peakBaselineRamBytes = measuredPeakRam;
            baselineDirGrowthBytes = measuredDirGrowth;
            TestTotal baselineTests = testResults.get(context.getProjectName());
            CompilationResult baselineCompilation = compilationResults.get(context.getProjectName());
            long baselineSeconds = normalizeBaselineSeconds(rawBaselineSeconds, baselineTests, baselineCompilation);
            experimentTiming.setNormalizedBaselineSeconds(baselineSeconds);
            long totalBudgetSeconds = AppConfig.variantBudget(baselineSeconds);

            // ── Overlay base ──────────────────────────────────────────────────
            Path basePath = null;
            long baseSizeBytes = 0;
            if (useOverlay && !skipVariants) {
                basePath = builder.buildBase(context, AppConfig.OVERLAY_TMP_DIR);
                baseSizeBytes = org.apache.commons.io.FileUtils.sizeOfDirectory(basePath.toFile());
                System.out.printf("[overlay] base: %d MB, dir growth: %d MB%n",
                        baseSizeBytes / 1024 / 1024, measuredDirGrowth / 1024 / 1024);
            }

            // ── Thread count ──────────────────────────────────────────────────
            int threads;
            if (!isParallel) {
                threads = 1;
            } else if (useOverlay) {
                threads = AppConfig.computeMaxThreads(measuredPeakRam + measuredDirGrowth, baseSizeBytes);
            } else {
                threads = AppConfig.computeMaxThreads(measuredPeakRam);
            }
            maxThreads = threads;
            if (!skipVariants) {
                System.out.printf("[budget: %ds, threads: %d]%n", totalBudgetSeconds, threads);
            } else {
                System.out.println();
            }
            System.out.flush();

            // ── Variant loop via shared engine ────────────────────────────────
            Instant variantsStart = Instant.now();
            try {
                if (!skipVariants) {
                    Instant deadline = variantsStart.plusSeconds(totalBudgetSeconds);

                    VariantExecutionEngine.EngineConfig engineConfig = new VariantExecutionEngine.EngineConfig(
                            threads, useOverlay, isCache, false, stopOnPerfect,
                            AppConfig.TMP_DIR, logDir,
                            resolvedJavaHome,
                            useOverlay ? AppConfig.TMP_DIR : null,
                            m2OverlayEnabled ? M2_OVERLAY_DIR : null,
                            AppConfig.USE_MAVEN_DAEMON);

                    PipelineLifecycleListener pipelineListener = new PipelineLifecycleListener(
                            compilationResults, testResults, variantFinishSeconds,
                            variantSinceMergeStartSeconds, cacheDonorKeys, cacheHitKeys,
                            variantsStart);
                    DeadlineStopCondition deadlineStop = new DeadlineStopCondition(deadline);

                    MavenCacheManager cacheManager = new MavenCacheManager(logDir.resolveSibling("shared-cache"));
                    VariantExecutionEngine engine = new VariantExecutionEngine(
                            engineConfig, deadlineStop, pipelineListener);
                    engine.run(context, builder, donorTracker, cacheManager, basePath);

                    budgetExhausted = !engine.isExhausted();
                    numInFlightVariantsKilled = engine.getKilledInFlight();
                }
            } finally {
                if (basePath != null && basePath.toFile().exists()) {
                    try { FileUtils.deleteDirectory(basePath.toFile()); } catch (Exception ignored) {}
                }
            }
            experimentTiming.setVariantsExecutionTime(Duration.between(variantsStart, Instant.now()));

            return experimentTiming;
        };
    }

    /**
     * Run the human baseline on HDD and return the dir growth in bytes
     * (how much Maven wrote during the build: target/, surefire-reports, etc.).
     */
    private long executeHumanBaseline(VariantBuildContext context, VariantProjectBuilder builder,
                                      ExperimentTiming experimentTiming) throws Exception {
        Path mainProjectPath = builder.buildMainProject(context);
        String projectName = context.getProjectName();
        resolvedJavaHome = JavaVersionResolver.resolveJavaHome(mainProjectPath).orElse(null);

        MavenRunner mainRunner = new MavenRunner(logDir, AppConfig.USE_MAVEN_DAEMON, AppConfig.MAVEN_BUILD_TIMEOUT);

        if (isSnapshotMultiModule(mainProjectPath)) {
            System.out.printf("  ⚙ SNAPSHOT multi-module — pre-installing to local cache...%n");
            MavenCommandResolver cmdResolver = new MavenCommandResolver(AppConfig.USE_MAVEN_DAEMON);
            String[] execArgs = cmdResolver.resolveExecutableArgs(mainProjectPath);
            String[] cmd = AppConfig.concat(execArgs,
                    new String[]{"-B", "-fae", "-DskipTests=true", "-Dmaven.test.skip=true", "install"});
            Path preInstallLog = logDir.resolve(mainProjectPath.getFileName() + "_preinstall");
            java.nio.file.Files.createDirectories(logDir);
            new MavenProcessExecutor(AppConfig.MAVEN_BUILD_TIMEOUT)
                    .executeCommand(mainProjectPath, preInstallLog, resolvedJavaHome, cmd);
        }

        long dirSizeBefore = FileUtils.sizeOfDirectory(mainProjectPath.toFile());

        Instant start = Instant.now();
        mainRunner.run_no_optimization(resolvedJavaHome, mainProjectPath);
        experimentTiming.setHumanBaselineExecutionTime(Duration.between(start, Instant.now()));

        long dirSizeAfter = FileUtils.sizeOfDirectory(mainProjectPath.toFile());

        builder.collectCompilationResult(projectName).ifPresent(result ->
            compilationResults.put(projectName, result)
        );
        testResults.put(projectName, builder.collectTestResult(mainProjectPath));

        return Math.max(0, dirSizeAfter - dirSizeBefore);
    }

    // ── Pipeline adapter: collects results into batch maps ────────────────

    /**
     * Populates the factory's result maps from engine outcomes.
     * Discards timed-out/interrupted variants (non-clean results).
     */
    private static class PipelineLifecycleListener implements VariantLifecycleListener {
        private final Map<String, CompilationResult> compilationResults;
        private final Map<String, TestTotal> testResults;
        private final Map<String, Double> variantFinishSeconds;
        private final Map<String, Double> variantSinceMergeStartSeconds;
        private final Set<String> cacheDonorKeys;
        private final Set<String> cacheHitKeys;
        private final Instant variantsStart;

        PipelineLifecycleListener(Map<String, CompilationResult> compilationResults,
                                   Map<String, TestTotal> testResults,
                                   Map<String, Double> variantFinishSeconds,
                                   Map<String, Double> variantSinceMergeStartSeconds,
                                   Set<String> cacheDonorKeys,
                                   Set<String> cacheHitKeys,
                                   Instant variantsStart) {
            this.compilationResults = compilationResults;
            this.testResults = testResults;
            this.variantFinishSeconds = variantFinishSeconds;
            this.variantSinceMergeStartSeconds = variantSinceMergeStartSeconds;
            this.cacheDonorKeys = cacheDonorKeys;
            this.cacheHitKeys = cacheHitKeys;
            this.variantsStart = variantsStart;
        }

        @Override
        public void onVariantComplete(VariantOutcome outcome) {
            String key = outcome.variantKey();
            CompilationResult cr = outcome.compilationResult();
            TestTotal tt = outcome.testTotal();
            double wallClockSeconds = outcome.elapsed().toMillis() / 1000.0;
            double sinceMergeStart = Duration.between(variantsStart, Instant.now()).toMillis() / 1000.0;

            // Only keep clean (non-timed-out) results
            if (cr != null && cr.getBuildStatus() != null
                    && cr.getBuildStatus() != CompilationResult.Status.TIMEOUT) {
                compilationResults.put(key, cr);
                testResults.put(key, tt);
                variantFinishSeconds.put(key, wallClockSeconds);
                variantSinceMergeStartSeconds.put(key, sinceMergeStart);

                if (outcome.isDonor()) {
                    cacheDonorKeys.add(key);
                }
            }
        }
    }

    /** Deadline-based stop condition for the pipeline's time-budgeted variant loop. */
    private static class DeadlineStopCondition implements VariantStopCondition {
        private final Instant deadline;

        DeadlineStopCondition(Instant deadline) {
            this.deadline = deadline;
        }

        @Override
        public boolean isCancelled() {
            return Instant.now().isAfter(deadline);
        }

        @Override
        public int remainingSeconds() {
            return (int) Duration.between(Instant.now(), deadline).getSeconds();
        }
    }

    // ── Static utilities ──────────────────────────────────────────────────

    /**
     * Returns true when a variant is "perfect": all modules built successfully
     * and all tests ran and passed.
     */
    static boolean isPerfect(CompilationResult cr, TestTotal tt) {
        if (cr == null) return false;
        boolean buildOk = cr.getTotalModules() > 0
                ? cr.getSuccessfulModules() == cr.getTotalModules()
                : cr.getBuildStatus() == CompilationResult.Status.SUCCESS;
        if (!buildOk) return false;
        return tt != null && tt.getRunNum() > 0
                && tt.getFailuresNum() == 0 && tt.getErrorsNum() == 0;
    }

    /**
     * Samples {@code MemAvailable} from {@code /proc/meminfo} every 200 ms while a build
     * runs and reports peak consumption as {@code initialAvailable − minimumAvailable}.
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

        long peakUsageBytes() {
            return Math.max(0, initialFreeBytes - minFreeBytes);
        }

        private static long freeSystemRam() {
            try (var br = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("MemAvailable:")) {
                        return Long.parseLong(line.split("\\s+")[1]) * 1024;
                    }
                }
            } catch (Exception ignored) {}
            try {
                return ((com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean()).getFreeMemorySize();
            } catch (Exception e) {
                return Runtime.getRuntime().freeMemory();
            }
        }
    }

    /**
     * Normalize baseline duration to estimate a full successful build.
     * Decomposes total time into compilation (c = total - testElapsed) and test (t = testElapsed),
     * then scales each independently: c' = c * totalModules/successfulModules,
     * t' = t * runTests/passedTests. Returns c' + t'.
     * Falls back to {@link AppConfig#MAVEN_BUILD_TIMEOUT} when nothing succeeded.
     */
    static long normalizeBaselineSeconds(long totalBuildSeconds, TestTotal testTotal,
                                                  CompilationResult compilationResult) {
        float testElapsed = (testTotal != null) ? testTotal.getElapsedTime() : 0;
        float compilationTime = Math.max(0, totalBuildSeconds - testElapsed);

        if (compilationResult != null) {
            int totalModules = compilationResult.getTotalModules();
            int successfulModules = compilationResult.getSuccessfulModules();
            if (totalModules > 1 && successfulModules == 0) {
                return AppConfig.MAVEN_BUILD_TIMEOUT;
            }
            if (totalModules > 1 && successfulModules < totalModules) {
                compilationTime = compilationTime * totalModules / successfulModules;
            }
        }

        float normalizedTestTime = testElapsed;
        if (testTotal != null) {
            int runTests = testTotal.getRunNum();
            int passedTests = runTests - testTotal.getFailuresNum() - testTotal.getErrorsNum();
            if (runTests > 0 && passedTests == 0) {
                return AppConfig.MAVEN_BUILD_TIMEOUT;
            }
            if (passedTests > 0 && passedTests < runTests) {
                normalizedTestTime = testElapsed * runTests / passedTests;
            }
        }

        return (long) (compilationTime + normalizedTestTime);
    }

    static boolean isSnapshotMultiModule(Path projectPath) {
        Path pomFile = projectPath.resolve("pom.xml");
        if (!pomFile.toFile().exists()) return false;
        try {
            String pom = java.nio.file.Files.readString(pomFile);
            return pom.contains("-SNAPSHOT") && pom.contains("<modules>");
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
