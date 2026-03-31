package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.model.VariantProject;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    private String cacheWarmerKey;
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
        cacheWarmerKey = null;
        cacheHitKeys = ConcurrentHashMap.newKeySet();
        numInFlightVariantsKilled = 0;
        resolvedJavaHome = null;

        return new IJustInTimeRunner() {
            @Override
            public ExperimentTiming run(VariantBuildContext context, VariantProjectBuilder builder) throws Exception {
                ExperimentTiming experimentTiming = new ExperimentTiming();
                boolean useOverlay = OverlayMount.isAvailable();
                if (useOverlay) {
                    OverlayMount.cleanupStaleMounts(AppConfig.OVERLAY_TMP_DIR);
                }

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
                MavenExecutionFactory.this.peakBaselineRamBytes = measuredPeakRam;
                MavenExecutionFactory.this.baselineDirGrowthBytes = measuredDirGrowth;
                TestTotal baselineTests = testResults.get(context.getProjectName());
                CompilationResult baselineCompilation = compilationResults.get(context.getProjectName());
                long baselineSeconds = normalizeBaselineSeconds(rawBaselineSeconds, baselineTests, baselineCompilation);
                experimentTiming.setNormalizedBaselineSeconds(baselineSeconds);
                long totalBudgetSeconds = baselineSeconds * AppConfig.TIMEOUT_MULTIPLIER;

                // Build overlay base (once per merge) on OVERLAY_TMP_DIR (potentially tmpfs)
                Path basePath = null;
                long baseSizeBytes = 0;
                if (useOverlay && !skipVariants) {
                    basePath = builder.buildBase(context, AppConfig.OVERLAY_TMP_DIR);
                    baseSizeBytes = org.apache.commons.io.FileUtils.sizeOfDirectory(basePath.toFile());
                    System.out.printf("[overlay] base: %d MB, dir growth: %d MB%n",
                            baseSizeBytes / 1024 / 1024, measuredDirGrowth / 1024 / 1024);
                }

                int maxThreads;
                if (!isParallel) {
                    maxThreads = 1;
                } else if (useOverlay) {
                    maxThreads = AppConfig.computeMaxThreads(measuredPeakRam + measuredDirGrowth, baseSizeBytes);
                } else {
                    maxThreads = AppConfig.computeMaxThreads(measuredPeakRam);
                }
                MavenExecutionFactory.this.maxThreads = maxThreads;
                if (!skipVariants) {
                    System.out.printf("[budget: %ds, threads: %d]%n", totalBudgetSeconds, maxThreads);
                } else {
                    System.out.println();
                }
                System.out.flush();

                Instant variantsStart = Instant.now();
                try {
                    if (!skipVariants) {
                        Instant deadline = variantsStart.plusSeconds(totalBudgetSeconds);
                        if (useOverlay) {
                            runVariantsOverlay(context, builder, deadline, variantsStart, maxThreads, basePath);
                        } else {
                            runVariants(context, builder, deadline, variantsStart, maxThreads);
                        }
                    }
                } finally {
                    // Clean up overlay base
                    if (basePath != null && basePath.toFile().exists()) {
                        try { FileUtils.deleteDirectory(basePath.toFile()); } catch (Exception ignored) {}
                    }
                }
                experimentTiming.setVariantsExecutionTime(Duration.between(variantsStart, Instant.now()));

                return experimentTiming;
            }

            /**
             * Run the human baseline on HDD and return the dir growth in bytes
             * (how much Maven wrote during the build: target/, surefire-reports, etc.).
             */
            private long executeHumanBaseline(VariantBuildContext context, VariantProjectBuilder builder,
                                              ExperimentTiming experimentTiming) throws Exception {
                Path mainProjectPath = builder.buildMainProject(context);
                String projectName = context.getProjectName();
                MavenExecutionFactory.this.resolvedJavaHome =
                        JavaVersionResolver.resolveJavaHome(mainProjectPath).orElse(null);

                // Cap the baseline at MAVEN_BUILD_TIMEOUT so hung builds don't block the pipeline.
                // The actual wall-clock duration (capped at this limit) sets the variant budget.
                MavenRunner mainRunner = new MavenRunner(logDir, false, AppConfig.MAVEN_BUILD_TIMEOUT);

                // Pre-install SNAPSHOT inter-module dependencies so sibling artifacts are
                // available in the local cache.  Without this, a cold checkout of a
                // multi-module SNAPSHOT project fails dependency resolution immediately.
                if (isSnapshotMultiModule(mainProjectPath)) {
                    System.out.printf("  ⚙ SNAPSHOT multi-module — pre-installing to local cache...%n");
                    MavenCommandResolver cmdResolver = new MavenCommandResolver(false);
                    String mvnCmd = cmdResolver.resolveMavenCommand(mainProjectPath);
                    String[] cmd = {mvnCmd, "-B", "-fae", "-DskipTests=true", "-Dmaven.test.skip=true", "install"};
                    Path preInstallLog = logDir.resolve(mainProjectPath.getFileName() + "_preinstall");
                    java.nio.file.Files.createDirectories(logDir);
                    new MavenProcessExecutor(AppConfig.MAVEN_BUILD_TIMEOUT)
                            .executeCommand(mainProjectPath, preInstallLog,
                                    MavenExecutionFactory.this.resolvedJavaHome, cmd);
                }

                // Measure dir size before Maven to compute dir growth (= per-variant tmpfs overhead)
                long dirSizeBefore = FileUtils.sizeOfDirectory(mainProjectPath.toFile());

                Instant start = Instant.now();
                mainRunner.run_no_optimization(MavenExecutionFactory.this.resolvedJavaHome, mainProjectPath);
                experimentTiming.setHumanBaselineExecutionTime(Duration.between(start, Instant.now()));

                long dirSizeAfter = FileUtils.sizeOfDirectory(mainProjectPath.toFile());

                builder.collectCompilationResult(projectName).ifPresent(result ->
                    compilationResults.put(projectName, result)
                );
                testResults.put(projectName, builder.collectTestResult(mainProjectPath));

                return Math.max(0, dirSizeAfter - dirSizeBefore);
            }

            private void runVariants(VariantBuildContext context, VariantProjectBuilder builder,
                                     Instant deadline, Instant variantsStart, int maxThreads) throws Exception {
                java.nio.file.Files.createDirectories(logDir);
                String projectName = context.getProjectName();
                MavenCommandResolver commandResolver = new MavenCommandResolver(false);
                MavenCacheManager cacheManager = new MavenCacheManager();
                int globalVariantIndex = 1;
                Path cacheWarmPath = null;
                Path warmerDirToCleanUp = null;
                boolean perfectFound = false;

                // Sequential cache mode: run the very first variant synchronously to warm the local Maven cache.
                if (isCache && !isParallel) {
                    Optional<VariantProject> first = context.nextVariant();
                    int timeout0 = remainingSeconds(deadline);
                    if (first.isPresent() && timeout0 > 0) {
                        int idx = globalVariantIndex++;
                        // T-1: non-conflict files; injectCacheArtifacts writes XML config (timestamp irrelevant);
                        // T1: conflict files written last — no cache to copy yet so ordering only matters for
                        // subsequent variants that copy from this warmer.
                        Path firstPath = builder.buildVariantBase(context, idx);
                        if (MavenExecutionFactory.this.resolvedJavaHome == null)
                            MavenExecutionFactory.this.resolvedJavaHome =
                                    JavaVersionResolver.resolveJavaHome(firstPath).orElse(null);
                        cacheManager.injectCacheArtifacts(firstPath);
                        builder.applyConflictResolution(firstPath, first.get());
                        System.out.printf("[DEBUG] variant %s starting (cache-warmer), timeout=%ds%n",
                                firstPath.getFileName(), timeout0);
                        Instant warmStart = Instant.now();
                        new MavenProcessExecutor(timeout0).executeCommand(
                                firstPath, logDir.resolve(firstPath.getFileName() + "_compilation"),
                                MavenExecutionFactory.this.resolvedJavaHome,
                                AppConfig.buildCommand(commandResolver.resolveMavenCommand(firstPath),
                                        commandResolver.resolveMavenGoal(firstPath), firstPath));
                        double warmWallClock = Duration.between(warmStart, Instant.now()).toMillis() / 1000.0;
                        String warmKey = projectName + "_" + idx;
                        MavenExecutionFactory.this.cacheWarmerKey = warmKey;
                        collectResult(warmKey, firstPath, builder, variantsStart, warmWallClock);
                        warmerDirToCleanUp = firstPath;
                        if (isWarmerUsable(compilationResults.get(warmKey))) {
                            cacheWarmPath = firstPath;
                        } else {
                            System.out.println("⚠ Cache warmer produced no successful modules — falling back to non-cache builds");
                        }
                        if (stopOnPerfect && isPerfect(compilationResults.get(warmKey), testResults.get(warmKey))) {
                            System.out.printf("✓ Perfect variant found (cache-warmer #%d) — stopping early%n", idx);
                            perfectFound = true;
                        }
                    }
                }
                final Path warmPath = cacheWarmPath;
                final Path seqWarmerCleanup = warmerDirToCleanUp;

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
                            // T-1: non-conflict files only; applyConflictResolution called inside the task.
                            Path vPath = builder.buildVariantBase(context, idx);
                            if (MavenExecutionFactory.this.resolvedJavaHome == null)
                                MavenExecutionFactory.this.resolvedJavaHome =
                                        JavaVersionResolver.resolveJavaHome(vPath).orElse(null);
                            allocatedPaths.add(vPath);
                            boolean isWarmer = isCache && (inFlight == 0);
                            submitTask(cs, commandResolver, cacheManager, warmPathRef, isWarmer, vPath, next.get(), builder, key, deadline, MavenExecutionFactory.this.resolvedJavaHome);
                            inFlight++;
                        }

                        // Rolling: as each slot frees up, collect results and immediately fill the slot.
                        while (inFlight > 0) {
                            TaskResult done = drainOne(cs);
                            inFlight--;
                            collectResult(done.key(), done.path(), builder, variantsStart, done.wallClockSeconds());
                            if (stopOnPerfect && !perfectFound && isPerfect(compilationResults.get(done.key()), testResults.get(done.key()))) {
                                System.out.printf("✓ Perfect variant found (%s) — stopping early%n", done.key());
                                perfectFound = true;
                            }
                            Path currentWarm = warmPathRef.get();
                            if (!done.path().equals(currentWarm) && done.path().toFile().exists()) {
                                deleteWithRetry(done.path());
                            }
                            if (!perfectFound && !Instant.now().isAfter(deadline)) {
                                Optional<VariantProject> next = context.nextVariant();
                                if (next.isPresent()) {
                                    int idx = globalVariantIndex++;
                                    String key = projectName + "_" + idx;
                                    Path vPath = builder.buildVariantBase(context, idx);
                                    allocatedPaths.add(vPath);
                                    submitTask(cs, commandResolver, cacheManager, warmPathRef, false, vPath, next.get(), builder, key, deadline, MavenExecutionFactory.this.resolvedJavaHome);
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
                            if (perfectFound) break;
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
                            // Build non-conflict files first (T-1); conflict files written after any cache copy.
                            Path variantPath = builder.buildVariantBase(context, idx);
                            if (MavenExecutionFactory.this.resolvedJavaHome == null)
                                MavenExecutionFactory.this.resolvedJavaHome =
                                        JavaVersionResolver.resolveJavaHome(variantPath).orElse(null);
                            String variantKey = projectName + "_" + idx;

                            try {
                                System.out.printf("[DEBUG] sequential variant %d: timeout=%ds%n", idx, variantTimeout);
                                Instant variantStart = Instant.now();
                                if (isCache && warmPath != null) {
                                    // T0: copy compiled artifacts from warmer (fresh timestamps, T0 > T-1).
                                    cacheManager.injectCacheArtifacts(variantPath);
                                    cacheManager.copyTargetDirectories(warmPath.toFile(), variantPath.toFile());
                                    cacheManager.copyCacheDirectory(warmPath, variantPath);
                                    cacheHitKeys.add(variantKey);
                                    // T1: conflict files written last (T1 > T0) so Maven recompiles only changed modules.
                                    builder.applyConflictResolution(variantPath, variant);
                                    new MavenProcessExecutor(variantTimeout).executeCommand(
                                            variantPath, logDir.resolve(variantPath.getFileName() + "_compilation"),
                                            MavenExecutionFactory.this.resolvedJavaHome,
                                            AppConfig.buildCommandOffline(commandResolver.resolveMavenCommand(variantPath),
                                                    commandResolver.resolveMavenGoal(variantPath), variantPath));
                                } else {
                                    builder.applyConflictResolution(variantPath, variant);
                                    new MavenRunner(logDir, false, variantTimeout).run_no_optimization(
                                            MavenExecutionFactory.this.resolvedJavaHome, variantPath);
                                }
                                Instant variantFinish = Instant.now();
                                double wallClockSeconds = Duration.between(variantStart, variantFinish).toMillis() / 1000.0;
                                builder.collectCompilationResult(variantKey).ifPresent(result ->
                                    compilationResults.put(variantKey, result)
                                );
                                testResults.put(variantKey, builder.collectTestResult(variantPath));
                                variantFinishSeconds.put(variantKey, wallClockSeconds);
                                variantSinceMergeStartSeconds.put(variantKey,
                                        Duration.between(variantsStart, variantFinish).toMillis() / 1000.0);
                                if (stopOnPerfect && isPerfect(compilationResults.get(variantKey), testResults.get(variantKey))) {
                                    System.out.printf("✓ Perfect variant found (#%d) — stopping early%n", idx);
                                    break;
                                }
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
                        if (seqWarmerCleanup != null && seqWarmerCleanup.toFile().exists()) {
                            try { FileUtils.deleteDirectory(seqWarmerCleanup.toFile()); } catch (Exception ignored) {}
                        }
                    }
                }
            }

            // ── Overlay-aware variant execution ──────────────────────────────────

            private void runVariantsOverlay(VariantBuildContext context, VariantProjectBuilder builder,
                                            Instant deadline, Instant variantsStart, int maxThreads,
                                            Path basePath) throws Exception {
                java.nio.file.Files.createDirectories(logDir);
                String projectName = context.getProjectName();
                MavenCommandResolver commandResolver = new MavenCommandResolver(false);
                MavenCacheManager cacheManager = new MavenCacheManager();
                int globalVariantIndex = 1;
                OverlayMount warmerOverlay = null;
                boolean warmerUsable = false;
                boolean perfectFound = false;

                // Sequential cache mode: first variant warms the cache
                if (isCache && !isParallel) {
                    Optional<VariantProject> first = context.nextVariant();
                    int timeout0 = remainingSeconds(deadline);
                    if (first.isPresent() && timeout0 > 0) {
                        int idx = globalVariantIndex++;
                        String key = projectName + "_" + idx;
                        OverlayMount overlay = builder.buildVariantOverlay(basePath, idx);
                        Path vPath = overlay.mountPoint();
                        try {
                            if (MavenExecutionFactory.this.resolvedJavaHome == null)
                                MavenExecutionFactory.this.resolvedJavaHome =
                                        JavaVersionResolver.resolveJavaHome(vPath).orElse(null);
                            cacheManager.injectCacheArtifacts(vPath);
                            builder.applyConflictResolution(vPath, first.get());
                            System.out.printf("[DEBUG] variant %s starting (cache-warmer, overlay), timeout=%ds%n",
                                    key, timeout0);
                            Instant warmStart = Instant.now();
                            new MavenProcessExecutor(timeout0).executeCommand(
                                    vPath, logDir.resolve(key + "_compilation"),
                                    MavenExecutionFactory.this.resolvedJavaHome,
                                    AppConfig.buildCommand(commandResolver.resolveMavenCommand(vPath),
                                            commandResolver.resolveMavenGoal(vPath), vPath));
                            double warmWallClock = Duration.between(warmStart, Instant.now()).toMillis() / 1000.0;
                            MavenExecutionFactory.this.cacheWarmerKey = key;
                            collectResult(key, vPath, builder, variantsStart, warmWallClock);
                            if (isWarmerUsable(compilationResults.get(key))) {
                                warmerOverlay = overlay;
                                warmerUsable = true;
                            } else {
                                System.out.println("⚠ Cache warmer produced no successful modules — falling back to non-cache builds");
                                overlay.close();
                            }
                        } catch (Exception e) {
                            overlay.close();
                            throw e;
                        }
                        if (stopOnPerfect && isPerfect(compilationResults.get(key), testResults.get(key))) {
                            System.out.printf("✓ Perfect variant found (cache-warmer #%d) — stopping early%n", idx);
                            perfectFound = true;
                        }
                    }
                }
                final OverlayMount seqWarmerOverlay = warmerOverlay;

                if (isParallel) {
                    AtomicReference<OverlayMount> warmOverlayRef = new AtomicReference<>(null);
                    ExecutorService executor = Executors.newFixedThreadPool(maxThreads);
                    CompletionService<OverlayTaskResult> cs = new ExecutorCompletionService<>(executor);
                    List<OverlayMount> allocatedOverlays = new ArrayList<>();

                    try {
                        int inFlight = 0;
                        while (inFlight < maxThreads && !Instant.now().isAfter(deadline)) {
                            Optional<VariantProject> next = context.nextVariant();
                            if (next.isEmpty()) break;
                            int idx = globalVariantIndex++;
                            String key = projectName + "_" + idx;
                            OverlayMount overlay = builder.buildVariantOverlay(basePath, idx);
                            Path vPath = overlay.mountPoint();
                            if (MavenExecutionFactory.this.resolvedJavaHome == null)
                                MavenExecutionFactory.this.resolvedJavaHome =
                                        JavaVersionResolver.resolveJavaHome(vPath).orElse(null);
                            allocatedOverlays.add(overlay);
                            boolean isWarmer = isCache && (inFlight == 0);
                            submitOverlayTask(cs, commandResolver, cacheManager, warmOverlayRef, isWarmer,
                                    overlay, next.get(), builder, key, deadline, MavenExecutionFactory.this.resolvedJavaHome);
                            inFlight++;
                        }

                        while (inFlight > 0) {
                            OverlayTaskResult done = cs.take().get();
                            inFlight--;
                            collectResult(done.key(), done.overlay().mountPoint(), builder, variantsStart, done.wallClockSeconds());
                            if (stopOnPerfect && !perfectFound && isPerfect(compilationResults.get(done.key()), testResults.get(done.key()))) {
                                System.out.printf("✓ Perfect variant found (%s) — stopping early%n", done.key());
                                perfectFound = true;
                            }
                            OverlayMount currentWarm = warmOverlayRef.get();
                            if (done.overlay() != currentWarm) {
                                done.overlay().close();
                            }
                            if (!perfectFound && !Instant.now().isAfter(deadline)) {
                                Optional<VariantProject> next = context.nextVariant();
                                if (next.isPresent()) {
                                    int idx = globalVariantIndex++;
                                    String key = projectName + "_" + idx;
                                    OverlayMount overlay = builder.buildVariantOverlay(basePath, idx);
                                    allocatedOverlays.add(overlay);
                                    submitOverlayTask(cs, commandResolver, cacheManager, warmOverlayRef, false,
                                            overlay, next.get(), builder, key, deadline, MavenExecutionFactory.this.resolvedJavaHome);
                                    inFlight++;
                                }
                            }
                        }
                    } finally {
                        executor.shutdownNow();
                        MavenExecutionFactory.this.numInFlightVariantsKilled =
                                (globalVariantIndex - 1) - (compilationResults.size() - 1);
                        OverlayMount finalWarm = warmOverlayRef.get();
                        for (OverlayMount o : allocatedOverlays) {
                            if (o != null && o != finalWarm) {
                                try { o.close(); } catch (Exception ignored) {}
                            }
                        }
                        if (finalWarm != null) {
                            try { finalWarm.close(); } catch (Exception ignored) {}
                        }
                    }
                } else {
                    try {
                        while (true) {
                            if (perfectFound) break;
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
                            String variantKey = projectName + "_" + idx;
                            OverlayMount overlay = builder.buildVariantOverlay(basePath, idx);
                            Path variantPath = overlay.mountPoint();

                            try {
                                if (MavenExecutionFactory.this.resolvedJavaHome == null)
                                    MavenExecutionFactory.this.resolvedJavaHome =
                                            JavaVersionResolver.resolveJavaHome(variantPath).orElse(null);
                                System.out.printf("[DEBUG] sequential variant %d (overlay): timeout=%ds%n", idx, variantTimeout);
                                Instant variantStart = Instant.now();
                                if (isCache && warmerUsable) {
                                    cacheManager.injectCacheArtifacts(variantPath);
                                    cacheManager.copyTargetDirectories(seqWarmerOverlay.mountPoint().toFile(), variantPath.toFile());
                                    cacheManager.copyCacheDirectory(seqWarmerOverlay.mountPoint(), variantPath);
                                    cacheHitKeys.add(variantKey);
                                    builder.applyConflictResolution(variantPath, variant);
                                    new MavenProcessExecutor(variantTimeout).executeCommand(
                                            variantPath, logDir.resolve(variantKey + "_compilation"),
                                            MavenExecutionFactory.this.resolvedJavaHome,
                                            AppConfig.buildCommandOffline(commandResolver.resolveMavenCommand(variantPath),
                                                    commandResolver.resolveMavenGoal(variantPath), variantPath));
                                } else {
                                    builder.applyConflictResolution(variantPath, variant);
                                    new MavenRunner(logDir, false, variantTimeout).run_no_optimization(
                                            MavenExecutionFactory.this.resolvedJavaHome, variantPath);
                                }
                                Instant variantFinish = Instant.now();
                                double wallClockSeconds = Duration.between(variantStart, variantFinish).toMillis() / 1000.0;
                                // Collect results BEFORE closing overlay — surefire XML lives in the mountpoint
                                builder.collectCompilationResult(variantKey).ifPresent(result ->
                                    compilationResults.put(variantKey, result)
                                );
                                testResults.put(variantKey, builder.collectTestResult(variantPath));
                                variantFinishSeconds.put(variantKey, wallClockSeconds);
                                variantSinceMergeStartSeconds.put(variantKey,
                                        Duration.between(variantsStart, variantFinish).toMillis() / 1000.0);
                                if (stopOnPerfect && isPerfect(compilationResults.get(variantKey), testResults.get(variantKey))) {
                                    System.out.printf("✓ Perfect variant found (#%d) — stopping early%n", idx);
                                    break;
                                }
                            } finally {
                                overlay.close();
                            }
                        }
                    } finally {
                        if (seqWarmerOverlay != null) {
                            try { seqWarmerOverlay.close(); } catch (Exception ignored) {}
                        }
                    }
                }
            }

            private void submitOverlayTask(CompletionService<OverlayTaskResult> cs,
                                            MavenCommandResolver commandResolver,
                                            MavenCacheManager cacheManager,
                                            AtomicReference<OverlayMount> warmOverlayRef,
                                            boolean isWarmer,
                                            OverlayMount overlay, VariantProject variant,
                                            VariantProjectBuilder builder,
                                            String key, Instant deadline, String javaHome) {
                cs.submit(() -> {
                    Path vPath = overlay.mountPoint();
                    Instant taskStart = Instant.now();
                    int timeout = remainingSeconds(deadline);
                    if (timeout > 0) {
                        if (isWarmer) {
                            System.out.printf("[DEBUG] variant %s starting (cache-warmer, overlay), timeout=%ds%n",
                                    key, timeout);
                            cacheManager.injectCacheArtifacts(vPath);
                            builder.applyConflictResolution(vPath, variant);
                            new MavenProcessExecutor(timeout).executeCommand(
                                    vPath, logDir.resolve(key + "_compilation"),
                                    javaHome,
                                    AppConfig.buildCommand(commandResolver.resolveMavenCommand(vPath),
                                            commandResolver.resolveMavenGoal(vPath), vPath));
                            MavenExecutionFactory.this.cacheWarmerKey = key;
                            Path logPath = logDir.resolve(key + "_compilation");
                            CompilationResult warmCr = logPath.toFile().exists() ? new CompilationResult(logPath) : null;
                            if (isWarmerUsable(warmCr)) {
                                warmOverlayRef.set(overlay);
                            } else {
                                System.out.println("⚠ Cache warmer produced no successful modules — falling back to non-cache builds");
                            }
                        } else {
                            OverlayMount warm = isCache ? warmOverlayRef.get() : null;
                            System.out.printf("[DEBUG] variant %s starting%s (overlay), timeout=%ds%n",
                                    key, warm != null ? " (cache ready)" : "", timeout);
                            if (warm != null) {
                                cacheManager.injectCacheArtifacts(vPath);
                                cacheManager.copyTargetDirectories(warm.mountPoint().toFile(), vPath.toFile());
                                cacheManager.copyCacheDirectory(warm.mountPoint(), vPath);
                                cacheHitKeys.add(key);
                                builder.applyConflictResolution(vPath, variant);
                                new MavenProcessExecutor(timeout).executeCommand(
                                        vPath, logDir.resolve(key + "_compilation"),
                                        javaHome,
                                        AppConfig.buildCommandOffline(commandResolver.resolveMavenCommand(vPath),
                                                commandResolver.resolveMavenGoal(vPath), vPath));
                            } else {
                                builder.applyConflictResolution(vPath, variant);
                                new MavenProcessExecutor(timeout).executeCommand(
                                        vPath, logDir.resolve(key + "_compilation"),
                                        javaHome,
                                        AppConfig.buildCommand(commandResolver.resolveMavenCommand(vPath),
                                                commandResolver.resolveMavenGoal(vPath), vPath));
                            }
                        }
                    }
                    double wallClockSeconds = Duration.between(taskStart, Instant.now()).toMillis() / 1000.0;
                    return new OverlayTaskResult(key, overlay, wallClockSeconds);
                });
            }

            private void submitTask(CompletionService<TaskResult> cs,
                                    MavenCommandResolver commandResolver,
                                    MavenCacheManager cacheManager,
                                    AtomicReference<Path> warmPathRef,
                                    boolean isWarmer,
                                    Path vPath, VariantProject variant, VariantProjectBuilder builder,
                                    String key, Instant deadline, String javaHome) {
                cs.submit(() -> {
                    Instant taskStart = Instant.now();
                    int timeout = remainingSeconds(deadline);
                    if (timeout > 0) {
                        if (isWarmer) {
                            // No previous cache to copy from: write conflict files immediately (T1 after T-1).
                            System.out.printf("[DEBUG] variant %s starting (cache-warmer), timeout=%ds%n",
                                    vPath.getFileName(), timeout);
                            cacheManager.injectCacheArtifacts(vPath);
                            builder.applyConflictResolution(vPath, variant);
                            new MavenProcessExecutor(timeout).executeCommand(
                                    vPath, logDir.resolve(vPath.getFileName() + "_compilation"),
                                    javaHome,
                                    AppConfig.buildCommand(commandResolver.resolveMavenCommand(vPath),
                                            commandResolver.resolveMavenGoal(vPath), vPath));
                            MavenExecutionFactory.this.cacheWarmerKey = key;
                            // Only advertise the cache if the warmer produced usable artifacts;
                            // otherwise other threads will fall back to normal online builds.
                            Path logPath = logDir.resolve(vPath.getFileName() + "_compilation");
                            CompilationResult warmCr = logPath.toFile().exists() ? new CompilationResult(logPath) : null;
                            if (isWarmerUsable(warmCr)) {
                                warmPathRef.set(vPath);
                            } else {
                                System.out.println("⚠ Cache warmer produced no successful modules — falling back to non-cache builds");
                            }
                        } else {
                            Path warm = isCache ? warmPathRef.get() : null;
                            System.out.printf("[DEBUG] variant %s starting%s, timeout=%ds%n",
                                    vPath.getFileName(), warm != null ? " (cache ready)" : "", timeout);
                            if (warm != null) {
                                // T0: copy compiled artifacts (fresh timestamps, T0 > T-1).
                                cacheManager.injectCacheArtifacts(vPath);
                                cacheManager.copyTargetDirectories(warm.toFile(), vPath.toFile());
                                cacheManager.copyCacheDirectory(warm, vPath);
                                cacheHitKeys.add(key);
                                // T1: conflict files written last so Maven recompiles only changed modules.
                                builder.applyConflictResolution(vPath, variant);
                                new MavenProcessExecutor(timeout).executeCommand(
                                        vPath, logDir.resolve(vPath.getFileName() + "_compilation"),
                                        javaHome,
                                        AppConfig.buildCommandOffline(commandResolver.resolveMavenCommand(vPath),
                                                commandResolver.resolveMavenGoal(vPath), vPath));
                            } else {
                                // Cache not yet ready: write conflict files and build normally.
                                builder.applyConflictResolution(vPath, variant);
                                new MavenProcessExecutor(timeout).executeCommand(
                                        vPath, logDir.resolve(vPath.getFileName() + "_compilation"),
                                        javaHome,
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
                variantSinceMergeStartSeconds.put(key, Duration.between(variantsStart, finish).toMillis() / 1000.0);
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

    /** Overlay variant of TaskResult — carries the mount so it can be closed after result collection. */
    private record OverlayTaskResult(String key, OverlayMount overlay, double wallClockSeconds) {}

    /**
     * Returns true when a variant is "perfect": all modules built successfully
     * and all tests ran and passed.
     */
    private static boolean isPerfect(CompilationResult cr, TestTotal tt) {
        if (cr == null) return false;
        boolean buildOk = cr.getTotalModules() > 0
                ? cr.getSuccessfulModules() == cr.getTotalModules()
                : cr.getBuildStatus() == CompilationResult.Status.SUCCESS;
        if (!buildOk) return false;
        return tt != null && tt.getRunNum() > 0
                && tt.getFailuresNum() == 0 && tt.getErrorsNum() == 0;
    }

    /**
     * Returns true when a cache warmer produced at least one successful module,
     * meaning subsequent variants can benefit from copying its target directories.
     * When the warmer compiled zero modules successfully, the cache contains nothing
     * useful and offline builds would be doomed to fail.
     */
    private static boolean isWarmerUsable(CompilationResult cr) {
        if (cr == null) return false;
        return cr.getSuccessfulModules() > 0
                || cr.getBuildStatus() == CompilationResult.Status.SUCCESS;
    }

    /**
     * Samples {@code MemAvailable} from {@code /proc/meminfo} every 200 ms while a build
     * runs and reports peak consumption as {@code initialAvailable − minimumAvailable}.
     * Uses {@code MemAvailable} instead of JMX {@code getFreeMemorySize()} because the
     * latter reports {@code MemFree}, which conflates real memory consumption with page
     * cache churn — during I/O-heavy Maven builds, {@code MemFree} can drop by gigabytes
     * even when the build itself only allocates hundreds of megabytes.
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
            try (var br = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("MemAvailable:")) {
                        return Long.parseLong(line.split("\\s+")[1]) * 1024; // kB → bytes
                    }
                }
            } catch (Exception ignored) {}
            // Fallback for non-Linux systems
            try {
                return ((com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean()).getFreeMemorySize();
            } catch (Exception e) {
                return Runtime.getRuntime().freeMemory();
            }
        }
    }

    /**
     * Normalize baseline duration to account for partial builds:
     * <ol>
     *   <li>Module normalization: if only some modules compiled, scale the raw time
     *       by totalModules/successfulModules to estimate a full-module build.
     *       Falls back to {@link AppConfig#MAVEN_BUILD_TIMEOUT} when no modules succeeded.</li>
     *   <li>Test normalization: if tests failed early, scale the test portion
     *       by runTests/passedTests to estimate a fully-green run.</li>
     * </ol>
     */
    static long normalizeBaselineSeconds(long rawSeconds, TestTotal testTotal,
                                                  CompilationResult compilationResult) {
        float seconds = rawSeconds;

        // 1. Module normalization: scale up if only a fraction of modules succeeded
        if (compilationResult != null) {
            int total = compilationResult.getTotalModules();
            int successful = compilationResult.getSuccessfulModules();
            if (total > 1 && successful == 0) {
                return AppConfig.MAVEN_BUILD_TIMEOUT;
            }
            if (total > 1 && successful < total) {
                seconds = seconds * total / successful;
            }
        }

        // 2. Test normalization: scale up if tests failed/errored
        if (testTotal != null) {
            int runTests = testTotal.getRunNum();
            int passedTests = runTests - testTotal.getFailuresNum() - testTotal.getErrorsNum();
            float testElapsed = testTotal.getElapsedTime();
            float compilationTime = Math.max(0, seconds - testElapsed);
            float normalized = TestTotal.normalizeElapsedTime(compilationTime, testElapsed, runTests, passedTests);
            if (!Float.isNaN(normalized)) {
                seconds = normalized;
            }
        }

        return (long) seconds;
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
