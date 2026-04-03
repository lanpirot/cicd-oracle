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
    private Set<String> cacheDonorKeys;
    private String bestDonorKey;
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
        cacheDonorKeys = ConcurrentHashMap.newKeySet();
        bestDonorKey = null;
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
                long totalBudgetSeconds = AppConfig.variantBudget(baselineSeconds);

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
                MavenRunner mainRunner = new MavenRunner(logDir, AppConfig.USE_MAVEN_DAEMON, AppConfig.MAVEN_BUILD_TIMEOUT);

                // Pre-install SNAPSHOT inter-module dependencies so sibling artifacts are
                // available in the local cache.  Without this, a cold checkout of a
                // multi-module SNAPSHOT project fails dependency resolution immediately.
                if (isSnapshotMultiModule(mainProjectPath)) {
                    System.out.printf("  ⚙ SNAPSHOT multi-module — pre-installing to local cache...%n");
                    MavenCommandResolver cmdResolver = new MavenCommandResolver(AppConfig.USE_MAVEN_DAEMON);
                    String[] execArgs = cmdResolver.resolveExecutableArgs(mainProjectPath);
                    String[] cmd = AppConfig.concat(execArgs,
                            new String[]{"-B", "-fae", "-DskipTests=true", "-Dmaven.test.skip=true", "install"});
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
                MavenCommandResolver commandResolver = new MavenCommandResolver(AppConfig.USE_MAVEN_DAEMON);
                MavenCacheManager cacheManager = new MavenCacheManager();
                int globalVariantIndex = 1;
                boolean perfectFound = false;

                if (isParallel) {
                    // Donor registry: any completed variant that compiled successfully can become the donor.
                    // The donor evolves — a later variant with more successful modules replaces the current donor.
                    AtomicReference<Path> warmPathRef = new AtomicReference<>(null);
                    ExecutorService executor = Executors.newFixedThreadPool(maxThreads);
                    CompletionService<TaskResult> cs = new ExecutorCompletionService<>(executor);
                    List<Path> allocatedPaths = new ArrayList<>();
                    List<Path> retiredDonorPaths = new ArrayList<>();

                    try {
                        int inFlight = 0;
                        while (inFlight < maxThreads && !Instant.now().isAfter(deadline)) {
                            Optional<VariantProject> next = context.nextVariant();
                            if (next.isEmpty()) break;
                            int idx = globalVariantIndex++;
                            String key = projectName + "_" + idx;
                            Path vPath = builder.buildVariantBase(context, idx);
                            if (MavenExecutionFactory.this.resolvedJavaHome == null)
                                MavenExecutionFactory.this.resolvedJavaHome =
                                        JavaVersionResolver.resolveJavaHome(vPath).orElse(null);
                            allocatedPaths.add(vPath);
                            submitTask(cs, commandResolver, cacheManager, warmPathRef, vPath, next.get(), builder, key, deadline, MavenExecutionFactory.this.resolvedJavaHome);
                            inFlight++;
                        }

                        while (inFlight > 0) {
                            TaskResult done = drainOne(cs);
                            inFlight--;
                            collectResult(done.key(), done.path(), builder, variantsStart, done.wallClockSeconds());
                            if (stopOnPerfect && !perfectFound && isPerfect(compilationResults.get(done.key()), testResults.get(done.key()))) {
                                System.out.printf("✓ Perfect variant found (%s) — stopping early%n", done.key());
                                perfectFound = true;
                            }
                            // Evolving donor: promote if this variant compiled more modules than current donor.
                            CompilationResult doneCr = compilationResults.get(done.key());
                            CompilationResult currentDonorCr = (bestDonorKey != null) ? compilationResults.get(bestDonorKey) : null;
                            if (isCache && isBetterDonor(doneCr, currentDonorCr)) {
                                Path oldDonor = warmPathRef.get();
                                if (oldDonor != null) retiredDonorPaths.add(oldDonor);
                                warmPathRef.set(done.path());
                                MavenExecutionFactory.this.bestDonorKey = done.key();
                                MavenExecutionFactory.this.cacheDonorKeys.add(done.key());
                            } else if (!done.path().equals(warmPathRef.get()) && done.path().toFile().exists()) {
                                deleteWithRetry(done.path());
                            }
                            if (!perfectFound && !Instant.now().isAfter(deadline)) {
                                Optional<VariantProject> next = context.nextVariant();
                                if (next.isPresent()) {
                                    int idx = globalVariantIndex++;
                                    String key = projectName + "_" + idx;
                                    Path vPath = builder.buildVariantBase(context, idx);
                                    allocatedPaths.add(vPath);
                                    submitTask(cs, commandResolver, cacheManager, warmPathRef, vPath, next.get(), builder, key, deadline, MavenExecutionFactory.this.resolvedJavaHome);
                                    inFlight++;
                                }
                            }
                        }
                    } finally {
                        executor.shutdownNow();
                        MavenExecutionFactory.this.numInFlightVariantsKilled =
                                (globalVariantIndex - 1) - (compilationResults.size() - 1);
                        Path finalDonor = warmPathRef.get();
                        for (Path p : allocatedPaths) {
                            if (p != null && !p.equals(finalDonor) && p.toFile().exists()) {
                                try { FileUtils.deleteDirectory(p.toFile()); } catch (Exception ignored) {}
                            }
                        }
                        for (Path p : retiredDonorPaths) {
                            if (p != null && !p.equals(finalDonor) && p.toFile().exists()) {
                                try { FileUtils.deleteDirectory(p.toFile()); } catch (Exception ignored) {}
                            }
                        }
                        if (finalDonor != null && finalDonor.toFile().exists()) {
                            try { FileUtils.deleteDirectory(finalDonor.toFile()); } catch (Exception ignored) {}
                        }
                    }
                } else {
                    // Sequential: donor evolves in-place — each better variant replaces the previous donor.
                    Path donorPath = null;
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
                            Path variantPath = builder.buildVariantBase(context, idx);
                            if (MavenExecutionFactory.this.resolvedJavaHome == null)
                                MavenExecutionFactory.this.resolvedJavaHome =
                                        JavaVersionResolver.resolveJavaHome(variantPath).orElse(null);
                            String variantKey = projectName + "_" + idx;

                            try {
                                Instant variantStart = Instant.now();
                                if (isCache && donorPath != null) {
                                    // Consumer: copy from donor, run offline.
                                    System.out.printf("[DEBUG] sequential variant %d (cache from donor): timeout=%ds%n", idx, variantTimeout);
                                    cacheManager.injectCacheArtifacts(variantPath);
                                    cacheManager.copyTargetDirectories(donorPath.toFile(), variantPath.toFile());
                                    cacheManager.copyCacheDirectory(donorPath, variantPath);
                                    cacheHitKeys.add(variantKey);
                                    builder.applyConflictResolution(variantPath, variant);
                                    new MavenProcessExecutor(variantTimeout).executeCommand(
                                            variantPath, logDir.resolve(variantPath.getFileName() + "_compilation"),
                                            MavenExecutionFactory.this.resolvedJavaHome,
                                            AppConfig.buildCommandOffline(commandResolver.resolveExecutableArgs(variantPath),
                                                    commandResolver.resolveMavenGoal(variantPath)));
                                } else if (isCache) {
                                    // Potential donor: inject cache artifacts, run online.
                                    System.out.printf("[DEBUG] sequential variant %d (potential donor): timeout=%ds%n", idx, variantTimeout);
                                    cacheManager.injectCacheArtifacts(variantPath);
                                    builder.applyConflictResolution(variantPath, variant);
                                    new MavenProcessExecutor(variantTimeout).executeCommand(
                                            variantPath, logDir.resolve(variantPath.getFileName() + "_compilation"),
                                            MavenExecutionFactory.this.resolvedJavaHome,
                                            AppConfig.buildCommand(commandResolver.resolveExecutableArgs(variantPath),
                                                    commandResolver.resolveMavenGoal(variantPath)));
                                } else {
                                    System.out.printf("[DEBUG] sequential variant %d: timeout=%ds%n", idx, variantTimeout);
                                    builder.applyConflictResolution(variantPath, variant);
                                    new MavenRunner(logDir, AppConfig.USE_MAVEN_DAEMON, variantTimeout).run_no_optimization(
                                            MavenExecutionFactory.this.resolvedJavaHome, variantPath);
                                }
                                double wallClockSeconds = Duration.between(variantStart, Instant.now()).toMillis() / 1000.0;
                                collectResult(variantKey, variantPath, builder, variantsStart, wallClockSeconds);
                                // Evolving donor: promote if this variant compiled more modules.
                                if (isCache) {
                                    CompilationResult cr = compilationResults.get(variantKey);
                                    CompilationResult currentDonorCr = (bestDonorKey != null) ? compilationResults.get(bestDonorKey) : null;
                                    if (isBetterDonor(cr, currentDonorCr)) {
                                        if (donorPath != null && donorPath.toFile().exists()) {
                                            deleteWithRetry(donorPath);
                                        }
                                        donorPath = variantPath;
                                        MavenExecutionFactory.this.bestDonorKey = variantKey;
                                        MavenExecutionFactory.this.cacheDonorKeys.add(variantKey);
                                    }
                                }
                                if (stopOnPerfect && isPerfect(compilationResults.get(variantKey), testResults.get(variantKey))) {
                                    System.out.printf("✓ Perfect variant found (#%d) — stopping early%n", idx);
                                    break;
                                }
                            } finally {
                                if (!variantPath.equals(donorPath) && variantPath.toFile().exists()) {
                                    deleteWithRetry(variantPath);
                                }
                            }
                        }
                    } finally {
                        if (donorPath != null && donorPath.toFile().exists()) {
                            try { FileUtils.deleteDirectory(donorPath.toFile()); } catch (Exception ignored) {}
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
                MavenCommandResolver commandResolver = new MavenCommandResolver(AppConfig.USE_MAVEN_DAEMON);
                MavenCacheManager cacheManager = new MavenCacheManager();
                int globalVariantIndex = 1;
                boolean perfectFound = false;

                if (isParallel) {
                    AtomicReference<OverlayMount> warmOverlayRef = new AtomicReference<>(null);
                    ExecutorService executor = Executors.newFixedThreadPool(maxThreads);
                    CompletionService<OverlayTaskResult> cs = new ExecutorCompletionService<>(executor);
                    List<OverlayMount> allocatedOverlays = new ArrayList<>();
                    List<OverlayMount> retiredDonorOverlays = new ArrayList<>();

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
                            submitOverlayTask(cs, commandResolver, cacheManager, warmOverlayRef,
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
                            // Evolving donor: promote if this variant compiled more modules.
                            CompilationResult doneCr = compilationResults.get(done.key());
                            CompilationResult currentDonorCr = (bestDonorKey != null) ? compilationResults.get(bestDonorKey) : null;
                            if (isCache && isBetterDonor(doneCr, currentDonorCr)) {
                                OverlayMount oldDonor = warmOverlayRef.get();
                                if (oldDonor != null) retiredDonorOverlays.add(oldDonor);
                                warmOverlayRef.set(done.overlay());
                                MavenExecutionFactory.this.bestDonorKey = done.key();
                                MavenExecutionFactory.this.cacheDonorKeys.add(done.key());
                            } else if (done.overlay() != warmOverlayRef.get()) {
                                done.overlay().close();
                            }
                            if (!perfectFound && !Instant.now().isAfter(deadline)) {
                                Optional<VariantProject> next = context.nextVariant();
                                if (next.isPresent()) {
                                    int idx = globalVariantIndex++;
                                    String key = projectName + "_" + idx;
                                    OverlayMount overlay = builder.buildVariantOverlay(basePath, idx);
                                    allocatedOverlays.add(overlay);
                                    submitOverlayTask(cs, commandResolver, cacheManager, warmOverlayRef,
                                            overlay, next.get(), builder, key, deadline, MavenExecutionFactory.this.resolvedJavaHome);
                                    inFlight++;
                                }
                            }
                        }
                    } finally {
                        executor.shutdownNow();
                        MavenExecutionFactory.this.numInFlightVariantsKilled =
                                (globalVariantIndex - 1) - (compilationResults.size() - 1);
                        OverlayMount finalDonor = warmOverlayRef.get();
                        for (OverlayMount o : allocatedOverlays) {
                            if (o != null && o != finalDonor) {
                                try { o.close(); } catch (Exception ignored) {}
                            }
                        }
                        for (OverlayMount o : retiredDonorOverlays) {
                            if (o != null && o != finalDonor) {
                                try { o.close(); } catch (Exception ignored) {}
                            }
                        }
                        if (finalDonor != null) {
                            try { finalDonor.close(); } catch (Exception ignored) {}
                        }
                    }
                } else {
                    // Sequential overlay: donor evolves in-place.
                    OverlayMount donorOverlay = null;
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
                                Instant variantStart = Instant.now();
                                if (isCache && donorOverlay != null) {
                                    // Consumer: copy from donor, run offline.
                                    System.out.printf("[DEBUG] sequential variant %d (overlay, cache from donor): timeout=%ds%n", idx, variantTimeout);
                                    cacheManager.injectCacheArtifacts(variantPath);
                                    cacheManager.copyTargetDirectories(donorOverlay.mountPoint().toFile(), variantPath.toFile());
                                    cacheManager.copyCacheDirectory(donorOverlay.mountPoint(), variantPath);
                                    cacheHitKeys.add(variantKey);
                                    builder.applyConflictResolution(variantPath, variant);
                                    new MavenProcessExecutor(variantTimeout).executeCommand(
                                            variantPath, logDir.resolve(variantKey + "_compilation"),
                                            MavenExecutionFactory.this.resolvedJavaHome,
                                            AppConfig.buildCommandOffline(commandResolver.resolveExecutableArgs(variantPath),
                                                    commandResolver.resolveMavenGoal(variantPath)));
                                } else if (isCache) {
                                    // Potential donor: inject cache artifacts, run online.
                                    System.out.printf("[DEBUG] sequential variant %d (overlay, potential donor): timeout=%ds%n", idx, variantTimeout);
                                    cacheManager.injectCacheArtifacts(variantPath);
                                    builder.applyConflictResolution(variantPath, variant);
                                    new MavenProcessExecutor(variantTimeout).executeCommand(
                                            variantPath, logDir.resolve(variantKey + "_compilation"),
                                            MavenExecutionFactory.this.resolvedJavaHome,
                                            AppConfig.buildCommand(commandResolver.resolveExecutableArgs(variantPath),
                                                    commandResolver.resolveMavenGoal(variantPath)));
                                } else {
                                    System.out.printf("[DEBUG] sequential variant %d (overlay): timeout=%ds%n", idx, variantTimeout);
                                    builder.applyConflictResolution(variantPath, variant);
                                    new MavenRunner(logDir, AppConfig.USE_MAVEN_DAEMON, variantTimeout).run_no_optimization(
                                            MavenExecutionFactory.this.resolvedJavaHome, variantPath);
                                }
                                double wallClockSeconds = Duration.between(variantStart, Instant.now()).toMillis() / 1000.0;
                                // Collect results BEFORE closing overlay — surefire XML lives in the mountpoint
                                collectResult(variantKey, variantPath, builder, variantsStart, wallClockSeconds);
                                // Evolving donor: promote if this variant compiled more modules.
                                if (isCache) {
                                    CompilationResult cr = compilationResults.get(variantKey);
                                    CompilationResult currentDonorCr = (bestDonorKey != null) ? compilationResults.get(bestDonorKey) : null;
                                    if (isBetterDonor(cr, currentDonorCr)) {
                                        if (donorOverlay != null) donorOverlay.close();
                                        donorOverlay = overlay;
                                        MavenExecutionFactory.this.bestDonorKey = variantKey;
                                        MavenExecutionFactory.this.cacheDonorKeys.add(variantKey);
                                    }
                                }
                                if (stopOnPerfect && isPerfect(compilationResults.get(variantKey), testResults.get(variantKey))) {
                                    System.out.printf("✓ Perfect variant found (#%d) — stopping early%n", idx);
                                    break;
                                }
                            } finally {
                                if (overlay != donorOverlay) {
                                    overlay.close();
                                }
                            }
                        }
                    } finally {
                        if (donorOverlay != null) {
                            try { donorOverlay.close(); } catch (Exception ignored) {}
                        }
                    }
                }
            }

            private void submitOverlayTask(CompletionService<OverlayTaskResult> cs,
                                            MavenCommandResolver commandResolver,
                                            MavenCacheManager cacheManager,
                                            AtomicReference<OverlayMount> warmOverlayRef,
                                            OverlayMount overlay, VariantProject variant,
                                            VariantProjectBuilder builder,
                                            String key, Instant deadline, String javaHome) {
                cs.submit(() -> {
                    Path vPath = overlay.mountPoint();
                    Instant taskStart = Instant.now();
                    int timeout = remainingSeconds(deadline);
                    if (timeout > 0) {
                        OverlayMount donor = isCache ? warmOverlayRef.get() : null;
                        if (donor != null) {
                            System.out.printf("[DEBUG] variant %s starting (cache ready, overlay), timeout=%ds%n",
                                    key, timeout);
                            cacheManager.injectCacheArtifacts(vPath);
                            cacheManager.copyTargetDirectories(donor.mountPoint().toFile(), vPath.toFile());
                            cacheManager.copyCacheDirectory(donor.mountPoint(), vPath);
                            cacheHitKeys.add(key);
                            builder.applyConflictResolution(vPath, variant);
                            new MavenProcessExecutor(timeout).executeCommand(
                                    vPath, logDir.resolve(key + "_compilation"),
                                    javaHome,
                                    AppConfig.buildCommandOffline(commandResolver.resolveExecutableArgs(vPath),
                                            commandResolver.resolveMavenGoal(vPath)));
                        } else if (isCache) {
                            System.out.printf("[DEBUG] variant %s starting (potential donor, overlay), timeout=%ds%n",
                                    key, timeout);
                            cacheManager.injectCacheArtifacts(vPath);
                            builder.applyConflictResolution(vPath, variant);
                            new MavenProcessExecutor(timeout).executeCommand(
                                    vPath, logDir.resolve(key + "_compilation"),
                                    javaHome,
                                    AppConfig.buildCommand(commandResolver.resolveExecutableArgs(vPath),
                                            commandResolver.resolveMavenGoal(vPath)));
                        } else {
                            System.out.printf("[DEBUG] variant %s starting (overlay), timeout=%ds%n",
                                    key, timeout);
                            builder.applyConflictResolution(vPath, variant);
                            new MavenProcessExecutor(timeout).executeCommand(
                                    vPath, logDir.resolve(key + "_compilation"),
                                    javaHome,
                                    AppConfig.buildCommand(commandResolver.resolveExecutableArgs(vPath),
                                            commandResolver.resolveMavenGoal(vPath)));
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
                                    Path vPath, VariantProject variant, VariantProjectBuilder builder,
                                    String key, Instant deadline, String javaHome) {
                cs.submit(() -> {
                    Instant taskStart = Instant.now();
                    int timeout = remainingSeconds(deadline);
                    if (timeout > 0) {
                        Path donor = isCache ? warmPathRef.get() : null;
                        if (donor != null) {
                            // Consumer: copy from donor, run offline.
                            System.out.printf("[DEBUG] variant %s starting (cache ready), timeout=%ds%n",
                                    vPath.getFileName(), timeout);
                            cacheManager.injectCacheArtifacts(vPath);
                            cacheManager.copyTargetDirectories(donor.toFile(), vPath.toFile());
                            cacheManager.copyCacheDirectory(donor, vPath);
                            cacheHitKeys.add(key);
                            builder.applyConflictResolution(vPath, variant);
                            new MavenProcessExecutor(timeout).executeCommand(
                                    vPath, logDir.resolve(vPath.getFileName() + "_compilation"),
                                    javaHome,
                                    AppConfig.buildCommandOffline(commandResolver.resolveExecutableArgs(vPath),
                                            commandResolver.resolveMavenGoal(vPath)));
                        } else if (isCache) {
                            // Potential donor: inject cache artifacts, run online.
                            System.out.printf("[DEBUG] variant %s starting (potential donor), timeout=%ds%n",
                                    vPath.getFileName(), timeout);
                            cacheManager.injectCacheArtifacts(vPath);
                            builder.applyConflictResolution(vPath, variant);
                            new MavenProcessExecutor(timeout).executeCommand(
                                    vPath, logDir.resolve(vPath.getFileName() + "_compilation"),
                                    javaHome,
                                    AppConfig.buildCommand(commandResolver.resolveExecutableArgs(vPath),
                                            commandResolver.resolveMavenGoal(vPath)));
                        } else {
                            // No cache mode: build normally.
                            System.out.printf("[DEBUG] variant %s starting, timeout=%ds%n",
                                    vPath.getFileName(), timeout);
                            builder.applyConflictResolution(vPath, variant);
                            new MavenProcessExecutor(timeout).executeCommand(
                                    vPath, logDir.resolve(vPath.getFileName() + "_compilation"),
                                    javaHome,
                                    AppConfig.buildCommand(commandResolver.resolveExecutableArgs(vPath),
                                            commandResolver.resolveMavenGoal(vPath)));
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
     * Returns true when a variant produced at least one successful module,
     * meaning subsequent variants can benefit from copying its target directories.
     * When the variant compiled zero modules successfully, the cache contains nothing
     * useful and offline builds would be doomed to fail.
     */
    private static boolean isDonorUsable(CompilationResult cr) {
        if (cr == null) return false;
        return cr.getSuccessfulModules() > 0
                || cr.getBuildStatus() == CompilationResult.Status.SUCCESS;
    }

    /**
     * Returns true when candidate should replace the current donor (more modules compiled).
     * A consumer that was built from the previous donor can itself become the new donor,
     * creating an iterative improvement chain with progressively warmer caches.
     */
    private static boolean isBetterDonor(CompilationResult candidate, CompilationResult current) {
        if (!isDonorUsable(candidate)) return false;
        if (current == null) return true;
        return candidate.getSuccessfulModules() > current.getSuccessfulModules();
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
     * Normalize baseline duration to estimate a full successful build.
     * Decomposes total time into compilation (c = total − testElapsed) and test (t = testElapsed),
     * then scales each independently: c′ = c × totalModules/successfulModules,
     * t′ = t × runTests/passedTests. Returns c′ + t′.
     * Falls back to {@link AppConfig#MAVEN_BUILD_TIMEOUT} when nothing succeeded.
     */
    static long normalizeBaselineSeconds(long totalBuildSeconds, TestTotal testTotal,
                                                  CompilationResult compilationResult) {
        float testElapsed = (testTotal != null) ? testTotal.getElapsedTime() : 0;
        float compilationTime = Math.max(0, totalBuildSeconds - testElapsed);

        // Module normalization: scale compilation time by total/successful modules
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

        // Test normalization: scale test time by run/passed tests
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
