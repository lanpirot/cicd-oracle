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
 *   <li>{@link EngineConfig} — parallelism, overlay, cache, threshold file, stableCwd</li>
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
     * @param thresholdFile   shared cross-JVM file for the maven-hook early-abort gate;
     *                        non-null enables the gate
     * @param stopOnPerfect   stop after a variant with all modules + all tests passing
     * @param tempDir         base temp directory for variant builds
     * @param logDir          directory for Maven build logs
     * @param javaHome        JAVA_HOME override, or null for auto-detection
     * @param stableCwd       stable cwd for mvnd daemons (avoids overlay mount crashes); null = variant dir
     * @param m2OverlayDir    directory for per-variant ~/.m2/repository overlay isolation; null = disabled
     * @param useMavenDaemon  whether to use mvnd (Maven Daemon) for builds;
     *                        {@code null} = auto-detect (uses mvnd if available on PATH)
     * @param affectedModules selective-reactor-pruning configuration. {@code null} = pruning OFF
     *                        (every variant builds the full reactor, current behavior). When non-null
     *                        and not {@link ConflictModuleAnalyzer.AffectedModules#isAll() all-affected},
     *                        the engine runs a single donor variant first ({@code mvn install}, full
     *                        reactor) so unaffected modules' jars land in {@code ~/.m2/}, then every
     *                        subsequent variant builds with {@code mvn -pl <affected>} and inherits
     *                        donor's per-module test counts for skipped modules.
     */
    public record EngineConfig(
            int threadCount,
            boolean useOverlay,
            boolean useCache,
            Path thresholdFile,
            boolean stopOnPerfect,
            Path tempDir,
            Path logDir,
            String javaHome,
            Path stableCwd,
            Path m2OverlayDir,
            Boolean useMavenDaemon,
            ConflictModuleAnalyzer.AffectedModules affectedModules
    ) {
        /** Backwards-compatible 11-arg constructor that disables pruning (for existing callers). */
        public EngineConfig(int threadCount, boolean useOverlay, boolean useCache,
                            Path thresholdFile, boolean stopOnPerfect, Path tempDir, Path logDir,
                            String javaHome, Path stableCwd, Path m2OverlayDir, Boolean useMavenDaemon) {
            this(threadCount, useOverlay, useCache, thresholdFile, stopOnPerfect, tempDir, logDir,
                    javaHome, stableCwd, m2OverlayDir, useMavenDaemon, null);
        }

        /** True iff selective reactor pruning is active for this run. */
        public boolean pruningEnabled() {
            return affectedModules != null && !affectedModules.isAll();
        }
    }

    private final EngineConfig config;
    private final VariantStopCondition stopCondition;
    private final VariantLifecycleListener listener;

    /** Tracks whether the engine stopped because the generator was exhausted (vs cancelled/deadline). */
    private volatile boolean exhausted;
    /** Number of in-flight variants killed during shutdown. */
    private volatile int killedInFlight;

    /**
     * Per-worker-thread ~/.m2/repository overlay. One overlay is created the first time
     * a worker thread asks for one, then reused for every subsequent variant that lands
     * on the same thread. This bounds the live mvnd-daemon count to the thread count
     * (daemons match on {@code -Dmaven.repo.local}, so a stable path per thread lets
     * them be reused) — previously each variant got a unique overlay path, so mvnd
     * spawned a fresh daemon per variant and RAM grew unboundedly within a mode.
     *
     * <p>Overlays are closed in the {@code run()} {@code finally} block, after the
     * executor has shut down and no worker can request a new one.
     */
    private final ConcurrentHashMap<Long, OverlayMount> threadM2Overlays = new ConcurrentHashMap<>();
    private final AtomicInteger nextM2Slot = new AtomicInteger(0);

    /**
     * Per-worker-thread variant overlay mount. Mounted at a STABLE path
     * {@code projects/<projectName>_t<slot>/} on first use of the thread and reused
     * for every subsequent variant that lands on the same thread. Each variant's
     * conflict-resolved files are written into the same upper layer (overwriting the
     * previous variant's), and (in non-cache modes) {@code target/} dirs are wiped
     * between variants to keep the no-cache baseline honest.
     *
     * <p>Why stable: mvnd daemons cache GAV → source-file-path mappings across builds.
     * Per-variant mountpoints poison that cache the moment a variant is torn down; a
     * stable per-thread mountpoint keeps the path the daemon's cache pinned to alive
     * for the whole variant phase. See the historical band-aid (now removed) below
     * for the failure mode this avoids.
     *
     * <p>Closed at the end of {@code run()} alongside the m2 overlays.
     */
    private final ConcurrentHashMap<Long, OverlayMount> threadVariantMounts = new ConcurrentHashMap<>();
    private final AtomicInteger nextVariantSlot = new AtomicInteger(0);

    /** Pruning spec for the currently-executing variant. Set by {@code run()} before the
     *  donor bootstrap (donor()) and again before the main loop (prune(csv)). Volatile so
     *  worker threads see the post-bootstrap update without needing a lock. */
    private volatile TwoPhaseRunner.PruningSpec currentPruningSpec;

    /**
     * Pause the engine indefinitely on the first variant whose build returns
     * {@link CompilationResult.Status#SCAN_FAILURE} (the parent.relativePath signature).
     * Set via {@code -DpauseOnFirstScanFailure=true}. Used to inspect the live mvnd
     * daemon's state after a known-bad outcome instead of re-running the whole
     * pipeline for each fix attempt.
     */
    private static final boolean PAUSE_ON_FIRST_SCAN_FAILURE =
            Boolean.getBoolean("pauseOnFirstScanFailure");
    private final java.util.concurrent.atomic.AtomicBoolean pausedOnFailure =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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
        boolean pruningActive = config.pruningEnabled();
        AtomicInteger variantStartIndex = new AtomicInteger(1);

        try {
            // ── Phase 0: donor bootstrap (only when selective reactor pruning is enabled) ──
            // Build a single full-reactor variant with `mvn install` so unaffected modules'
            // jars are installed into the per-thread ~/.m2/. Subsequent variants can then
            // use `mvn -pl <affected>` and resolve unaffected modules from there.
            //
            // Synchronous on the calling thread: parallelism stays cold for ~10–60s, accepted
            // as bootstrap cost. If the donor build fails to produce a usable donor (the
            // variant didn't compile, or promotion didn't take), we silently fall back to the
            // legacy non-pruned path so the run still produces results.
            if (pruningActive) {
                List<VariantProject> bootstrapBatch = context.collectAllVariants(1);
                if (!bootstrapBatch.isEmpty()) {
                    VariantProject donorVariant = listener.transformVariant(bootstrapBatch.get(0));
                    int donorIdx = variantStartIndex.getAndIncrement();
                    currentPruningSpec = TwoPhaseRunner.PruningSpec.donor();
                    if (listener.beforeSubmit(donorVariant, donorIdx)) {
                        try {
                            buildAndTest(context, builder, donorTracker, cacheManager,
                                    commandResolver, overlayBasePath, donorVariant,
                                    donorIdx, javaHomeHolder);
                        } catch (Exception e) {
                            LOG.warn("Pruning bootstrap donor build failed", e);
                        }
                    }
                    if (donorTracker.getDonorPath() == null
                            || donorTracker.getDonorPerModule().isEmpty()) {
                        LOG.warn("Pruning bootstrap did not yield a usable donor "
                                + "(donorPath={}, perModuleSize={}); falling back to non-pruned "
                                + "execution for this run.",
                                donorTracker.getDonorPath(),
                                donorTracker.getDonorPerModule().size());
                        pruningActive = false;
                        currentPruningSpec = null;
                    }
                }
            }
            if (pruningActive) {
                String csv = String.join(",", config.affectedModules.modules());
                currentPruningSpec = TwoPhaseRunner.PruningSpec.prune(csv);
                LOG.info("[reactor-pruning] active: subsequent variants build with -pl {}", csv);
            }
            variantCounter.set(variantStartIndex.get());

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

            // Close the per-thread m2 overlays AND per-thread variant mounts that we
            // kept alive across variants. Workers have been shut down, so nothing will
            // ask for one after this.
            //
            // Each close() spends ~6s retrying a regular unmount because mvnd daemons
            // (idleTimeout = 3h) keep FDs open on the overlays until they exit. Closing
            // in parallel turns N×6s of mode-teardown into ~6s wall — matters because
            // cache_parallel + parallel hit this at every merge boundary.
            java.util.stream.Stream.concat(
                    threadM2Overlays.values().stream(),
                    threadVariantMounts.values().stream()
            ).parallel().forEach(m -> {
                try { m.close(); } catch (Exception ignored) {}
            });
            threadM2Overlays.clear();
            threadVariantMounts.clear();

            // Final donor's snapshot dir is left alive on the previous code path
            // (we never demoted it, the run just ended). Sweep the whole donor staging
            // root so it doesn't carry over into later modes / merges.
            if (overlayBasePath != null) {
                deleteQuietly(donorStagingRoot(overlayBasePath));
            }
        }
    }

    /** Per-base staging dir for donor snapshots: sibling of basePath, name suffix _donors. */
    private static Path donorStagingRoot(Path overlayBasePath) {
        String baseName = overlayBasePath.getFileName().toString();
        return overlayBasePath.getParent().resolve(baseName + "_donors");
    }

    /**
     * Return (creating on first use) the per-worker-thread m2 overlay for the current
     * thread. Returns {@code null} if m2 overlays are disabled or creation failed —
     * caller then falls back to a shared maven.repo.local.
     */
    private OverlayMount acquireThreadM2Overlay(String projectName) {
        if (config.m2OverlayDir == null) return null;
        long tid = Thread.currentThread().threadId();
        return threadM2Overlays.computeIfAbsent(tid, k -> {
            int slot = nextM2Slot.getAndIncrement();
            try {
                return OverlayMount.create(
                        Path.of(System.getProperty("user.home"), ".m2", "repository"),
                        config.m2OverlayDir,
                        projectName + "_slot" + slot);
            } catch (IOException e) {
                return null; // graceful fallback; next variant on this thread retries
            }
        });
    }

    /**
     * Return (creating on first use) the per-worker-thread variant overlay mount for
     * the current thread. The mount is at a STABLE path so the mvnd daemon's
     * GAV → source-file-path cache stays valid across every variant on this thread.
     * Returns {@code null} (caller falls back to a per-variant mount) if creation
     * fails.
     */
    private OverlayMount acquireThreadVariantMount(String projectName, Path overlayBasePath) {
        long tid = Thread.currentThread().threadId();
        return threadVariantMounts.computeIfAbsent(tid, k -> {
            int slot = nextVariantSlot.getAndIncrement();
            try {
                return OverlayMount.create(
                        overlayBasePath, overlayBasePath.getParent(),
                        projectName + "_t" + slot);
            } catch (IOException e) {
                return null;
            }
        });
    }

    /**
     * Recursively delete every {@code target/} directory under the variant mount.
     * Called between variants on the same stable per-thread mount in non-cache modes,
     * so the no_optimization baseline does not accidentally inherit the prior
     * variant's compiled classes (which would be cross-variant incremental
     * compilation — a real speedup, but not what no-cache claims to measure).
     *
     * <p>Walks the OverlayFS upper layer directly (a normal directory on the host
     * filesystem) rather than the mountpoint, so we skip the per-readdir FUSE round
     * trip that walking through the mount would cost. Maven's {@code target/} output
     * always lands in upper because it's all newly-created files; lower never holds
     * a {@code target/} (the base extraction is just sources).
     *
     * <p>Cache modes deliberately keep {@code target/}: the build-cache extension
     * hashes per-module and rebuilds whatever changed; donor warming overlays its
     * own {@code target/} on top.
     */
    private static void wipeTargetDirsInUpper(OverlayMount mount) {
        Path upper = mount.upperDir();
        try (java.util.stream.Stream<Path> walk = Files.walk(upper)) {
            walk.filter(p -> Files.isDirectory(p) && p.getFileName() != null
                            && "target".equals(p.getFileName().toString()))
                    .sorted(java.util.Comparator.reverseOrder()) // deepest first
                    .forEach(VariantExecutionEngine::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort; the next variant either rebuilds cleanly or surfaces the
            // failure on its own
        }
    }

    /**
     * Snapshot a freshly-built variant's {@code target/} trees (and pom edits) into a
     * separate path so the per-thread variant mount can be reused for the next variant
     * without corrupting the donor. The donor staging path lives outside any thread's
     * mount and is treated as a plain directory by {@link MavenCacheManager#copyTargetDirectories}.
     *
     * @return path to the snapshot, or {@code null} if snapshotting failed
     */
    private static Path snapshotDonor(MavenCacheManager cacheManager,
                                      Path variantMount,
                                      Path overlayBasePath,
                                      String donorKey) {
        Path snapshotPath = donorStagingRoot(overlayBasePath)
                .resolve("donor_" + donorKey + "_" + System.nanoTime());
        try {
            Files.createDirectories(snapshotPath);
            cacheManager.copyTargetDirectories(variantMount.toFile(), snapshotPath.toFile());
            return snapshotPath;
        } catch (Exception e) {
            deleteQuietly(snapshotPath);
            return null;
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

            // 2. Maven extensions + cache warming from donor.
            //    Overlay variants inherit .mvn/ from the base — only inject for
            //    non-overlay variants that were built from scratch.
            //    All modes inject the maven-hook (for stable behavior by variant
            //    index across modes); only cache modes additionally enable the
            //    build-cache extension and warm target/ from the donor.
            if (!config.useOverlay) {
                if (config.useCache) {
                    cacheManager.injectCacheArtifacts(variantPath);
                } else {
                    cacheManager.injectMavenHookOnly(variantPath);
                }
            }
            TestTotal donorTtAtWarm = null;
            if (config.useCache) {
                donorTtAtWarm = warmFromDonorWithRetry(donorTracker, cacheManager, variantPath, variantIndex);
            }

            // 3. Apply conflict resolution (AFTER cache copy for timestamp ordering)
            builder.applyConflictResolution(variantPath, variant);

            if (stopCondition.isStopped()) return;

            // 4. Run build+test
            //
            // The m2 overlay is scoped to the worker THREAD, not the variant — see
            // `threadM2Overlays` on the engine. The same overlay (and therefore the
            // same `-Dmaven.repo.local`) is reused across every variant that lands on
            // this worker, which lets mvnd reuse a single daemon per thread instead
            // of spawning a fresh one per variant. Overlay lifetime ends in the run()
            // finally block, after all workers have stopped.
            OverlayMount m2Overlay = acquireThreadM2Overlay(projectName);
            Path repoLocal = m2Overlay != null ? m2Overlay.mountPoint() : null;
            try {
                if (stopCondition.remainingSeconds() == 0) return; // deadline expired

                TwoPhaseRunner runner = new TwoPhaseRunner(
                        commandResolver,
                        () -> {
                            int r = stopCondition.remainingSeconds();
                            return r < 0 ? 600 : r; // -1 (no deadline) → use 600s per-variant default
                        },
                        config.logDir,
                        javaHomeHolder[0],
                        config.stableCwd);
                TwoPhaseRunner.PruningSpec spec = currentPruningSpec;
                TwoPhaseRunner.TwoPhaseResult tpResult = runner.run(
                        variantPath, variantKey, config.thresholdFile, repoLocal, spec);

                if (stopCondition.isStopped()) return;

                // 5. Collect results (BEFORE closing overlay — surefire XML lives in mountpoint)
                Path logFile = config.logDir.resolve(variantKey + "_compilation");
                CompilationResult cr = tpResult.compilationResult();
                if (cr == null) cr = new CompilationResult(logFile);
                TestTotal tt = tpResult.testsRan() ? new TestTotal(variantPath.toFile()) : new TestTotal();

                // 5b. Pruned-variant test inheritance: only `affectedModules` were rebuilt,
                //     so `tt` only has counts for those. Merge donor's per-module counts for
                //     the modules we skipped so this variant's reported total reflects what
                //     a full-reactor build would have measured. Donor-bootstrap variant
                //     itself is not pruned (spec.affectedModulesCsv == null) — skip there.
                if (spec != null && spec.affectedModulesCsv() != null) {
                    Map<String, TestTotal.ModuleTotal> donorPerModule = donorTracker.getDonorPerModule();
                    if (!donorPerModule.isEmpty()) {
                        java.util.Set<String> built = new java.util.HashSet<>(
                                java.util.Arrays.asList(spec.affectedModulesCsv().split(",")));
                        java.util.List<String> skipped = donorPerModule.keySet().stream()
                                .filter(m -> !built.contains(m))
                                .toList();
                        tt = TestTotal.mergeWithDonor(tt, donorPerModule, skipped);
                    }
                }

                // hadWarmCacheReady reflects whether donor target/ trees were copied into
                // this variant before its build started — i.e. whether any donor parts were
                // available for the build-cache extension to consult. It does NOT mean any
                // compilation work was actually skipped; the build-cache extension may still
                // hash-miss and rebuild from scratch. It is set independently of the build's
                // outcome: a variant that copied donor artefacts and then failed (or was
                // gated) still "had a warm cache ready" for the build to consult.
                boolean hadWarmCacheReady = config.useCache && donorTtAtWarm != null;

                // Separately: when the build-cache extension cache-hit on every
                // test-running module, surefire is skipped and no fresh reports are
                // written → hasData=false. Inherit the donor's test outcomes in that
                // specific case (the cache extension has already proven module-level
                // source equivalence; donor's tests are this variant's tests).
                if (hadWarmCacheReady
                        && cr.getBuildStatus() == CompilationResult.Status.SUCCESS
                        && !tt.isHasData() && donorTtAtWarm.isHasData()) {
                    tt = TestTotal.copyOf(donorTtAtWarm);
                }

                // 6. Donor promotion. The maven-hook posts successful-module counts to
                // the shared threshold file as it observes them during the build, so we
                // do not need a redundant update here — the gate threshold has already
                // been raised by the time the variant returns.
                //
                // The donor's published path is a SNAPSHOT of the variant's target/ trees
                // taken into a separate staging dir (overlayBase/../donors/...), NOT the
                // live per-thread mount — otherwise the next variant on this thread would
                // overwrite the donor's classes the moment it starts.
                if (config.useCache && DonorTracker.isDonorUsable(cr)
                        && config.useOverlay && overlayBasePath != null) {
                    Path snapshotPath = snapshotDonor(cacheManager, variantPath,
                            overlayBasePath, String.valueOf(variantIndex));
                    if (snapshotPath != null) {
                        Map<String, java.util.List<String>> patterns = variant.extractPatterns();
                        Path oldDonor = donorTracker.promoteDonorIfBetter(
                                snapshotPath, cr, tt, String.valueOf(variantIndex));
                        isDonor = snapshotPath.equals(donorTracker.getDonorPath());
                        if (isDonor) {
                            donorTracker.setDonorPatterns(patterns);
                        } else {
                            // Lost the promotion race or our score did not beat current.
                            deleteQuietly(snapshotPath);
                        }
                        if (oldDonor != null && !oldDonor.equals(snapshotPath)) {
                            // The previous donor lives in donor staging too — plain rm -rf,
                            // no FUSE mount to unmount.
                            deleteQuietly(oldDonor);
                        }
                    }
                } else if (config.useCache && !config.useOverlay) {
                    // Non-overlay (no fuse) cache mode keeps the legacy per-variant
                    // donor-by-path model — the variant directory IS the donor and is
                    // skipped from cleanup below when promoted.
                    Map<String, java.util.List<String>> patterns = variant.extractPatterns();
                    Path oldDonor = donorTracker.promoteDonorIfBetter(
                            variantPath, cr, tt, String.valueOf(variantIndex));
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
                        elapsed, logFile, variantPath, isDonor, hadWarmCacheReady);
                listener.onVariantComplete(outcome);

                // 7b. Debug: pause indefinitely on the first SCAN_FAILURE (the
                //     parent.relativePath signature) when -DpauseOnFirstScanFailure=true.
                //     Lets a human attach with jcmd / mvnd --status and inspect the live
                //     daemon's cached GAV → path map without re-running the pipeline.
                if (PAUSE_ON_FIRST_SCAN_FAILURE
                        && cr.getBuildStatus() == CompilationResult.Status.SCAN_FAILURE
                        && pausedOnFailure.compareAndSet(false, true)) {
                    LOG.error("[debug-pause] variant {} failed with SCAN_FAILURE.\n"
                                    + "  variant mount : {}\n"
                                    + "  log file      : {}\n"
                                    + "  alive mvnd PIDs: run `pgrep -f mvnd.id=` from another shell\n"
                                    + "  engine paused — kill -INT this JVM to release.",
                            variantKey, variantPath, logFile);
                    while (!Thread.currentThread().isInterrupted()) {
                        try { Thread.sleep(60_000); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                // 8. Perfect variant check (engine-level concern, not delegated)
                if (config.stopOnPerfect && isPerfect(cr, tt)) {
                    LOG.info("Perfect variant found ({}) — stopping early", variantKey);
                    // Signal exhaustion so the coordinator exits the loop
                    // (the coordinator checks stopCondition, but we can't cancel from here;
                    //  instead, the outcome is reported and the coordinator can check externally)
                }
            } finally {
                // Note: m2Overlay is thread-scoped and closed at engine-run end; not here.
            }
        } finally {
            // 9. Cleanup. With the per-thread stable variant mount (overlay mode), the
            //    slot is engine-owned and stays alive across variants — slot.close() is
            //    a no-op (overlay==null in the slot). For non-overlay mode the slot owns
            //    a per-variant directory which is deleted unless this variant became the
            //    donor.
            //
            //    No daemon-kill here: the mvnd daemon (one per worker thread, matched on
            //    -Dmaven.repo.local) intentionally survives the entire variant phase. The
            //    GAV → source-file-path cache it builds up stays valid because the
            //    variant mount path is now stable per thread.
            if (!isDonor && slot != null) {
                try { slot.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Copy warmed target/ trees from the current donor into {@code variantPath}.
     * If the donor was demoted (and rm-rf'd) mid-copy, re-fetch the current donor
     * and retry up to 3 attempts in total. If retries run out — or the new donor
     * is the same as the one we just failed on — log a warning and proceed with no
     * warmed cache (the variant rebuilds those classes from scratch, no failure).
     *
     * @return the donor's TestTotal captured at the moment of the successful copy
     *         (consumed later if the cache extension skips surefire), or null when
     *         no warm happened or no donor existed.
     */
    private TestTotal warmFromDonorWithRetry(DonorTracker donorTracker,
                                             MavenCacheManager cacheManager,
                                             Path variantPath,
                                             int variantIndex) {
        DonorTracker.DonorSnapshot snap = donorTracker.getDonorSnapshot();
        if (snap.path() == null) return null; // no donor yet — normal early in a mode

        Path lastTried = null;
        int attempts = 0;
        while (snap.path() != null && !snap.path().equals(lastTried) && attempts < 3) {
            attempts++;
            try {
                cacheManager.copyTargetDirectories(snap.path().toFile(), variantPath.toFile());
                return snap.testTotal(); // success (full or best-effort partial)
            } catch (MavenCacheManager.DonorVanishedException dve) {
                lastTried = snap.path();
                snap = donorTracker.getDonorSnapshot();
                if (snap.path() != null && !snap.path().equals(lastTried) && attempts < 3) {
                    LOG.info("Variant {} cache warm: donor {} vanished, retrying with {}",
                            variantIndex, lastTried, snap.path());
                }
            }
        }
        LOG.warn("Variant {} cache warm gave up after {} attempt(s); "
                + "building without warmed cache (last donor {})",
                variantIndex, attempts, lastTried);
        return null;
    }

    private VariantSlot allocateSlot(VariantBuildContext context,
                                     VariantProjectBuilder builder,
                                     Path overlayBasePath,
                                     int variantIndex) throws IOException {
        if (config.useOverlay) {
            // Stable per-thread mount: same path across every variant on this worker, so
            // the mvnd daemon's cached GAV → source-file-path mapping stays valid for the
            // whole variant phase. The overlay is owned by the engine (closed in run()'s
            // finally), NOT by the per-variant slot.
            OverlayMount overlay = acquireThreadVariantMount(context.getProjectName(), overlayBasePath);
            if (overlay != null) {
                if (!config.useCache) {
                    // No-cache modes wipe target/ between variants on the same thread so we
                    // do not silently cross-pollinate compiled classes from variant N into
                    // variant N+1's "no-cache" measurement.
                    //
                    // Cache modes deliberately KEEP target/: the build-cache extension and
                    // maven-compiler-plugin's incremental compile mode both expect a
                    // self-consistent target/ tree. Wiping just target/<module>/ leaves the
                    // compiler plugin without target/maven-status/.../createdFiles.lst that
                    // it tries to read when it sees restored class files, producing
                    // "Error reading old mojo status: createdFiles.lst (No such file or
                    // directory)" and a build-blocking failure even on hertzbeat-class
                    // small projects.
                    //
                    // The known cost: projects whose code generation is daemon-mojo-cached
                    // (e.g. jsqlparser javacc-jjtree) may carry stale generated sources
                    // from V_n into V_n+1 because the daemon's mojo state thinks the
                    // generator already ran. That is a real regression vs the old per-
                    // variant-directory model and is documented for follow-up.
                    wipeTargetDirsInUpper(overlay);
                }
                return new VariantSlot(overlay.mountPoint(), null, true /* engineOwned */);
            }
            // Fallback: per-variant mount (legacy path) if per-thread mount creation failed.
            OverlayMount overlay2 = builder.buildVariantOverlay(overlayBasePath, variantIndex);
            return new VariantSlot(overlay2.mountPoint(), overlay2, false);
        } else {
            Path path = builder.buildVariantBase(context, variantIndex);
            return new VariantSlot(path, null, false);
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

    /**
     * Abstracts over overlay mount vs full-copy variant directories.
     *
     * <p>{@code engineOwned == true} marks slots whose lifetime is the engine's
     * (per-thread stable mount): close is a no-op, the engine reclaims the resource
     * in its own {@code finally}. Without this guard, close would treat the mount
     * point as a per-variant directory and rm-rf the entire mount on the first
     * variant teardown, deleting pom.xml from underneath the daemon.
     */
    private record VariantSlot(Path path, OverlayMount overlay, boolean engineOwned)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            if (engineOwned) return;
            if (overlay != null) {
                overlay.close();
            } else if (path != null && path.toFile().exists()) {
                FileUtils.deleteDirectory(path.toFile());
            }
        }
    }
}
