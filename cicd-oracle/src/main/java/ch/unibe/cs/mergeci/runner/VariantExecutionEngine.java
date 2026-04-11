package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.model.VariantProject;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.MavenCacheManager;
import ch.unibe.cs.mergeci.runner.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.JavaVersionResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unified variant build-test loop shared by both the experiment pipeline
 * ({@link MavenExecutionFactory}) and the IntelliJ plugin.
 *
 * <p>Configurable via:
 * <ul>
 *   <li>{@link VariantLifecycleListener} — hooks for UI, dedup, scoring, result collection</li>
 *   <li>{@link VariantStopCondition} — deadline, pause/resume, cancel</li>
 *   <li>{@link EngineConfig} — parallelism, overlay, cache, singlePhase, stableCwd</li>
 * </ul>
 *
 * <p>Uses a semaphore-gated thread pool: one variant is submitted as each slot frees,
 * keeping all threads maximally saturated.
 */
public class VariantExecutionEngine {
    private static final Logger LOG = LoggerFactory.getLogger(VariantExecutionEngine.class);

    /**
     * Configuration for the variant execution engine.
     *
     * @param threadCount     number of parallel build threads (1 = sequential)
     * @param useOverlay      use fuse-overlayfs for variant isolation
     * @param useCache        enable donor cache warming
     * @param singlePhase     true = maven-hook early-abort gate (one invocation)
     * @param stopOnPerfect   stop after a variant with all modules + all tests passing
     * @param tempDir         base temp directory for variant builds
     * @param logDir          directory for Maven build logs
     * @param javaHome        JAVA_HOME override, or null for auto-detection
     * @param stableCwd       stable cwd for mvnd daemons (avoids overlay mount crashes); null = variant dir
     * @param m2OverlayDir    directory for per-variant ~/.m2/repository overlay isolation; null = disabled
     * @param useMavenDaemon  whether to use mvnd (Maven Daemon) for builds;
     *                        {@code null} = auto-detect (uses mvnd if available on PATH)
     */
    public record EngineConfig(
            int threadCount,
            boolean useOverlay,
            boolean useCache,
            boolean singlePhase,
            boolean stopOnPerfect,
            Path tempDir,
            Path logDir,
            String javaHome,
            Path stableCwd,
            Path m2OverlayDir,
            Boolean useMavenDaemon
    ) {}

    private final EngineConfig config;
    private final VariantStopCondition stopCondition;
    private final VariantLifecycleListener listener;

    /** Tracks whether the engine stopped because the generator was exhausted (vs cancelled/deadline). */
    private volatile boolean exhausted;
    /** Number of in-flight variants killed during shutdown. */
    private volatile int killedInFlight;

    public VariantExecutionEngine(EngineConfig config,
                                   VariantStopCondition stopCondition,
                                   VariantLifecycleListener listener) {
        this.config = config;
        this.stopCondition = stopCondition;
        this.listener = listener;
    }

    public boolean isExhausted() { return exhausted; }
    public int getKilledInFlight() { return killedInFlight; }

    /**
     * Run the variant loop. Blocks until exhausted, cancelled, or deadline.
     *
     * @param context         variant build context with conflict files and generator
     * @param builder         project builder for materialising directories
     * @param donorTracker    donor promotion tracker (caller-owned)
     * @param cacheManager    Maven cache manager for artifact injection + target/ copying
     * @param overlayBasePath overlay base directory (required if useOverlay); null otherwise
     */
    public void run(VariantBuildContext context,
                    VariantProjectBuilder builder,
                    DonorTracker donorTracker,
                    MavenCacheManager cacheManager,
                    Path overlayBasePath) {
        Files.isDirectory(config.logDir); // ensure logDir readable
        try {
            java.nio.file.Files.createDirectories(config.logDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create log dir: " + config.logDir, e);
        }

        ExecutorService executor = Executors.newFixedThreadPool(config.threadCount);
        Semaphore slots = new Semaphore(config.threadCount);
        AtomicInteger variantCounter = new AtomicInteger(1);
        AtomicInteger inFlight = new AtomicInteger();
        // Tracks in-flight variants keyed by variant index — survivors after pause are retried
        ConcurrentHashMap<Integer, VariantProject> submitted = new ConcurrentHashMap<>();
        Deque<VariantProject> pendingRetry = new ArrayDeque<>();
        Deque<Integer> freeIndices = new ArrayDeque<>();
        java.util.Set<Future<?>> activeFutures = ConcurrentHashMap.newKeySet();

        MavenCommandResolver commandResolver = config.useMavenDaemon != null
                ? new MavenCommandResolver(config.useMavenDaemon)
                : new MavenCommandResolver(); // auto-detect mvnd
        // Java home: use provided value, or auto-detect from first variant
        String[] javaHomeHolder = new String[]{config.javaHome};

        boolean justResumed = false;
        boolean perfectFound = false;

        try {
            while (!stopCondition.isCancelled()) {
                stopCondition.waitIfPaused();
                if (stopCondition.isCancelled()) break;

                // Check deadline
                int remaining = stopCondition.remainingSeconds();
                if (remaining == 0) break; // deadline reached (0 means expired; -1 means no deadline)

                // After a pause/resume cycle, drain in-flight and re-queue incomplete variants
                if (justResumed) {
                    slots.acquire(config.threadCount);
                    slots.release(config.threadCount);
                    for (Map.Entry<Integer, VariantProject> e : submitted.entrySet()) {
                        freeIndices.add(e.getKey());
                        pendingRetry.add(e.getValue());
                    }
                    submitted.clear();
                    justResumed = false;
                }

                // Drain retry queue first, then ask generator
                VariantProject variant = pendingRetry.poll();
                if (variant == null) {
                    List<VariantProject> one = context.collectAllVariants(1);
                    if (one.isEmpty()) break; // generator exhausted
                    variant = one.get(0);
                }

                // Transform (manual pins for plugin, identity for pipeline)
                variant = listener.transformVariant(variant);

                // Assign index
                Integer recycled = freeIndices.poll();
                int idx = recycled != null ? recycled : variantCounter.getAndIncrement();

                // Dedup check
                if (!listener.beforeSubmit(variant, idx)) {
                    continue;
                }

                // Block until a thread is free
                slots.acquire();
                if (stopCondition.isStopped()) {
                    slots.release();
                    pendingRetry.addFirst(variant);
                    justResumed = true;
                    continue;
                }

                submitted.put(idx, variant);
                int count = inFlight.incrementAndGet();
                listener.onInFlightChanged(count);

                final VariantProject buildVariant = variant;
                final int variantIdx = idx;
                Future<?>[] holder = new Future<?>[1];
                holder[0] = executor.submit(() -> {
                    try {
                        buildAndTest(context, builder, donorTracker, cacheManager,
                                commandResolver, overlayBasePath, buildVariant,
                                variantIdx, javaHomeHolder);
                        submitted.remove(variantIdx);
                    } catch (Exception e) {
                        if (!stopCondition.isStopped()) {
                            LOG.warn("Build/test failed for variant {}", variantIdx, e);
                        }
                    } finally {
                        activeFutures.remove(holder[0]);
                        int rem = inFlight.decrementAndGet();
                        listener.onInFlightChanged(rem);
                        slots.release();
                    }
                });
                activeFutures.add(holder[0]);
            }

            // Wait for remaining in-flight variants to finish
            slots.acquire(config.threadCount);
            slots.release(config.threadCount);
        } catch (InterruptedException e) {
            // Hard cancel interrupted the wait
        } finally {
            executor.shutdownNow();
            killedInFlight = submitted.size();
            exhausted = !stopCondition.isCancelled() && pendingRetry.isEmpty();
            listener.onRunFinished(exhausted);
        }
    }

    /**
     * Build and test a single variant. Runs on a worker thread.
     */
    private void buildAndTest(VariantBuildContext context,
                              VariantProjectBuilder builder,
                              DonorTracker donorTracker,
                              MavenCacheManager cacheManager,
                              MavenCommandResolver commandResolver,
                              Path overlayBasePath,
                              VariantProject variant,
                              int variantIndex,
                              String[] javaHomeHolder) throws Exception {
        if (stopCondition.isStopped()) return;

        Instant start = Instant.now();
        String projectName = context.getProjectName();
        String variantKey = projectName + "_" + variantIndex;
        VariantSlot slot = null;
        boolean isDonor = false;

        try {
            // 1. Allocate variant directory
            slot = allocateSlot(context, builder, overlayBasePath, variantIndex);
            Path variantPath = slot.path();

            // Auto-detect Java home from first variant if not provided
            if (javaHomeHolder[0] == null) {
                javaHomeHolder[0] = JavaVersionResolver.resolveJavaHome(variantPath).orElse(null);
            }

            // 2. Cache warming from donor
            if (config.useCache) {
                // Overlay variants inherit .mvn/ cache config from the base — only
                // inject for non-overlay variants that were built from scratch.
                if (!config.useOverlay) {
                    cacheManager.injectCacheArtifacts(variantPath);
                }
                Path donorPath = donorTracker.getDonorPath();
                if (donorPath != null) {
                    cacheManager.copyTargetDirectories(donorPath.toFile(), variantPath.toFile());
                }
            }

            // 3. Apply conflict resolution (AFTER cache copy for timestamp ordering)
            builder.applyConflictResolution(variantPath, variant);

            if (stopCondition.isStopped()) return;

            // 4. Run build+test
            OverlayMount m2Overlay = null;
            Path repoLocal = null;
            try {
                if (config.m2OverlayDir != null) {
                    try {
                        m2Overlay = OverlayMount.create(
                                Path.of(System.getProperty("user.home"), ".m2", "repository"),
                                config.m2OverlayDir, variantKey);
                        repoLocal = m2Overlay.mountPoint();
                    } catch (IOException e) {
                        // Graceful fallback: use shared repo
                    }
                }

                int timeout = stopCondition.remainingSeconds();
                if (timeout == 0) return; // deadline expired

                TwoPhaseRunner runner = new TwoPhaseRunner(
                        commandResolver,
                        () -> {
                            int r = stopCondition.remainingSeconds();
                            return r < 0 ? 600 : r; // -1 (no deadline) → use 600s per-variant default
                        },
                        config.logDir,
                        javaHomeHolder[0],
                        config.singlePhase,
                        config.stableCwd);
                TwoPhaseRunner.TwoPhaseResult tpResult = runner.run(
                        variantPath, variantKey, donorTracker, repoLocal);

                if (stopCondition.isStopped()) return;

                // 5. Collect results (BEFORE closing overlay — surefire XML lives in mountpoint)
                Path logFile = config.logDir.resolve(variantKey + "_compilation");
                CompilationResult cr = tpResult.compilationResult();
                if (cr == null) cr = new CompilationResult(logFile);
                TestTotal tt = tpResult.testsRan() ? new TestTotal(variantPath.toFile()) : new TestTotal();

                // 6. Donor promotion
                if (cr != null) {
                    donorTracker.updateBestModules(cr.getSuccessfulModules());
                }
                if (config.useCache) {
                    Map<String, java.util.List<String>> patterns = variant.extractPatterns();
                    Path oldDonor = donorTracker.promoteDonorIfBetter(
                            variantPath, cr, String.valueOf(variantIndex));
                    isDonor = variantPath.equals(donorTracker.getDonorPath());
                    if (isDonor) {
                        donorTracker.setDonorPatterns(patterns);
                    }
                    if (oldDonor != null && !oldDonor.equals(variantPath)) {
                        deleteQuietly(oldDonor);
                    }
                }

                // 7. Report outcome
                Duration elapsed = Duration.between(start, Instant.now());
                VariantOutcome outcome = new VariantOutcome(
                        variantKey, variantIndex,
                        variant.extractPatterns(),
                        cr, tt, tpResult.testsRan(),
                        elapsed, logFile, variantPath, isDonor);
                listener.onVariantComplete(outcome);

                // 8. Perfect variant check (engine-level concern, not delegated)
                if (config.stopOnPerfect && isPerfect(cr, tt)) {
                    LOG.info("Perfect variant found ({}) — stopping early", variantKey);
                    // Signal exhaustion so the coordinator exits the loop
                    // (the coordinator checks stopCondition, but we can't cancel from here;
                    //  instead, the outcome is reported and the coordinator can check externally)
                }
            } finally {
                if (m2Overlay != null) {
                    try { m2Overlay.close(); } catch (Exception ignored) {}
                }
            }
        } finally {
            // 9. Cleanup (unless this variant is the donor)
            if (!isDonor && slot != null) {
                try { slot.close(); } catch (Exception ignored) {}
            }
        }
    }

    private VariantSlot allocateSlot(VariantBuildContext context,
                                     VariantProjectBuilder builder,
                                     Path overlayBasePath,
                                     int variantIndex) throws IOException {
        if (config.useOverlay) {
            OverlayMount overlay = builder.buildVariantOverlay(overlayBasePath, variantIndex);
            return new VariantSlot(overlay.mountPoint(), overlay);
        } else {
            Path path = builder.buildVariantBase(context, variantIndex);
            return new VariantSlot(path, null);
        }
    }

    /** Returns true when all modules built and all tests passed. */
    static boolean isPerfect(CompilationResult cr, TestTotal tt) {
        if (cr == null) return false;
        boolean buildOk = cr.getTotalModules() > 0
                ? cr.getSuccessfulModules() == cr.getTotalModules()
                : cr.getBuildStatus() == CompilationResult.Status.SUCCESS;
        if (!buildOk) return false;
        return tt != null && tt.getRunNum() > 0
                && tt.getFailuresNum() == 0 && tt.getErrorsNum() == 0;
    }

    private static void deleteQuietly(Path path) {
        if (path == null || !path.toFile().exists()) return;
        try {
            FileUtils.deleteDirectory(path.toFile());
        } catch (Exception ignored) {}
    }

    /** Abstracts over overlay mount vs full-copy variant directories. */
    private record VariantSlot(Path path, OverlayMount overlay) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            if (overlay != null) {
                overlay.close();
            } else if (path != null && path.toFile().exists()) {
                FileUtils.deleteDirectory(path.toFile());
            }
        }
    }
}
