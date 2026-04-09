package org.example.cicdmergeoracle.cicdMergeTool.service;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.VariantProject;
import ch.unibe.cs.mergeci.present.VariantScore;
import ch.unibe.cs.mergeci.runner.IVariantGenerator;
import ch.unibe.cs.mergeci.runner.VariantBuildContext;
import ch.unibe.cs.mergeci.runner.VariantProjectBuilder;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.runner.maven.MavenProcessExecutor;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.ProjectBuilderUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Top-level coordinator for the plugin's variant resolution pipeline.
 * Generates variants in parallel, builds and tests each via Maven,
 * and notifies the UI as results arrive.
 *
 * <p>Unlike the pipeline's {@link VariantProjectBuilder#prepareVariants}, this class
 * builds the {@link VariantBuildContext} without touching the working tree — it uses
 * an in-core merge for conflict analysis and reads non-conflict objects from the
 * DirCache (index), which is safe during an active merge conflict.
 */
public class PluginOrchestrator {
    private static final int MAVEN_TIMEOUT_SECONDS = 600;

    private final Path repoPath;
    private final OracleSession session;
    private final Consumer<VariantResult> onVariantComplete;
    private final Consumer<Boolean> onRunFinished; // true = exhausted, false = cancelled
    private final Consumer<String> onError;
    private final HeuristicGeneratorFactory generatorFactory;
    private final boolean useMavenDaemon;
    private final int threadCount;

    private volatile ExecutorService executor;
    private volatile Future<?> coordinatorFuture;
    private volatile List<Future<?>> activeBatch = List.of();
    private volatile boolean exhausted;
    private final Deque<VariantProject> pendingRetry = new ConcurrentLinkedDeque<>();
    private Path tempDir;
    private VariantProjectBuilder builder;
    private Map<String, Double> globalWeightMap;

    public PluginOrchestrator(Path repoPath,
                              OracleSession session,
                              Consumer<VariantResult> onVariantComplete,
                              Consumer<Boolean> onRunFinished,
                              Consumer<String> onError,
                              boolean useMavenDaemon,
                              int threadCount) {
        this.repoPath = repoPath;
        this.session = session;
        this.onVariantComplete = onVariantComplete;
        this.onRunFinished = onRunFinished;
        this.onError = onError;
        this.generatorFactory = new HeuristicGeneratorFactory();
        this.useMavenDaemon = useMavenDaemon;
        this.threadCount = threadCount;
    }

    /**
     * Start the variant resolution pipeline on a background thread.
     */
    public void start(String oursCommit, String theirsCommit) {
        try {
            tempDir = Files.createTempDirectory("cicd-oracle-plugin");
        } catch (IOException e) {
            onError.accept("Failed to create temp directory: " + e.getMessage());
            return;
        }

        Path projectTempDir = tempDir.resolve("projects");
        builder = new VariantProjectBuilder(repoPath, tempDir, projectTempDir);
        globalWeightMap = generatorFactory.getGlobalWeightMap();

        coordinatorFuture = Executors.newSingleThreadExecutor().submit(() -> {
            try {
                VariantBuildContext context = prepareContext(oursCommit, theirsCommit);
                session.setBuildContext(context);
                session.initChunkIndex();
                runVariantLoop(context);
            } catch (Exception e) {
                e.printStackTrace();
                SwingUtilities.invokeLater(() -> onError.accept(e.getMessage()));
            }
        });
    }

    /**
     * Parse conflict files from an in-core merge without modifying the working tree.
     * Can be called before a run starts (e.g. to populate the chunk table on load).
     */
    public static Map<String, ConflictFile> parseConflictFiles(Path repoPath,
                                                                String oursCommit,
                                                                String theirsCommit) throws Exception {
        Git git = Git.open(repoPath.toFile());
        ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(git.getRepository(), true);
        ObjectId oursId = git.getRepository().resolve(oursCommit);
        ObjectId theirsId = git.getRepository().resolve(theirsCommit);
        merger.merge(oursId, theirsId);

        Map<String, MergeResult<? extends Sequence>> mergeResultMap = merger.getMergeResults();
        Map<String, ConflictFile> conflictFileMap = new LinkedHashMap<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            conflictFileMap.put(entry.getKey(),
                    ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey()));
        }
        return conflictFileMap;
    }

    /**
     * Build a {@link VariantBuildContext} without modifying the working tree.
     * Uses an in-core merge for conflict analysis and reads non-conflict
     * objects from the DirCache (safe during an active merge conflict).
     */
    private VariantBuildContext prepareContext(String oursCommit, String theirsCommit) throws Exception {
        Map<String, ConflictFile> conflictFileMap = parseConflictFiles(repoPath, oursCommit, theirsCommit);

        int totalChunks = 0;
        for (ConflictFile cf : conflictFileMap.values()) {
            for (IMergeBlock block : cf.getMergeBlocks()) {
                if (block instanceof ConflictBlock) totalChunks++;
            }
        }

        // Read non-conflict objects from DirCache — safe during active merge
        Git git = Git.open(repoPath.toFile());
        Map<String, ObjectId> nonConflictObjects =
                org.example.cicdmergeoracle.cicdMergeTool.util.GitUtils
                        .getNonConflictObjectsFromCurrentMerge(git);

        // Create the generator with the now-known chunk count
        IVariantGenerator generator = generatorFactory.create(null, repoPath, totalChunks);

        return new VariantBuildContext(
                repoPath,
                tempDir.resolve("projects"),
                repoPath.getFileName().toString(),
                oursCommit,
                conflictFileMap,
                totalChunks,
                List.of(),       // no ML predictions
                generator,
                nonConflictObjects,
                Map.of()         // no merge commit objects (plugin doesn't build baseline)
        );
    }

    public void pause() {
        session.pause();
        // Interrupt in-flight workers so they exit early
        for (Future<?> f : activeBatch) {
            f.cancel(true);
        }
    }

    public void resume() {
        session.resume();
    }

    public boolean isExhausted() {
        return exhausted;
    }

    public void cancel() {
        session.cancel();
        if (coordinatorFuture != null) {
            coordinatorFuture.cancel(true);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void runVariantLoop(VariantBuildContext context) {
        executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger variantCounter = new AtomicInteger(1);
        Set<List<String>> seenEffective = new HashSet<>();
        Path logDir = tempDir.resolve("log");
        logDir.toFile().mkdirs();

        try {
            boolean resumedFromPause = false;
            while (!session.isCancelled()) {
                session.waitIfPaused();
                if (session.isCancelled()) break;
                if (resumedFromPause) {
                    // Manual pins may have changed while paused — reset dedup
                    seenEffective.clear();
                }
                resumedFromPause = false;

                // Drain retry queue first (killed variants from previous pause),
                // then ask the generator for remaining slots
                List<VariantProject> batch = new ArrayList<>();
                while (!pendingRetry.isEmpty() && batch.size() < threadCount) {
                    batch.add(pendingRetry.poll());
                }
                if (batch.size() < threadCount) {
                    batch.addAll(context.collectAllVariants(threadCount - batch.size()));
                }
                if (batch.isEmpty()) break;
                // Dedup: skip variants whose effective assignment (with MANUAL overrides)
                // has already been seen. Inject manual pins into non-skipped variants.
                Set<Integer> completed = ConcurrentHashMap.newKeySet();
                List<Future<?>> futures = new ArrayList<>();
                List<VariantProject> submittedBatch = new ArrayList<>();
                for (int i = 0; i < batch.size(); i++) {
                    VariantProject variant = batch.get(i);
                    try {
                        VariantProject buildVariant = applyManualPins(variant);
                        int idx = variantCounter.getAndIncrement();
                        int pos = submittedBatch.size();
                        submittedBatch.add(variant);
                        futures.add(executor.submit(() -> {
                            if (buildAndTest(context, buildVariant, idx, logDir)) {
                                completed.add(pos);
                            }
                        }));
                    } catch (Throwable e) {
                        // variant preparation failed — skip silently
                    }
                }
                activeBatch = List.copyOf(futures);

                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        if (session.isStopped()) break;
                        e.printStackTrace();
                    }
                }
                activeBatch = List.of();

                // Re-queue variants that were killed by pause for retry on resume
                if (session.isStopped()) {
                    resumedFromPause = true;
                    for (int i = 0; i < submittedBatch.size(); i++) {
                        if (!completed.contains(i)) {
                            pendingRetry.add(submittedBatch.get(i));
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            // Hard cancel interrupted the wait — fall through to finally
        } finally {
            executor.shutdown();
            exhausted = !session.isCancelled();
            SwingUtilities.invokeLater(() -> onRunFinished.accept(exhausted));
        }
    }

    /** @return true if the variant was built, tested, and reported successfully */
    private boolean buildAndTest(VariantBuildContext context, VariantProject variant,
                                 int variantIndex, Path logDir) {
        if (session.isStopped()) { return false; }

        Instant start = Instant.now();
        Path variantPath = null;
        try {
            variantPath = builder.buildVariant(context, variant, variantIndex);

            MavenCommandResolver resolver = new MavenCommandResolver(useMavenDaemon);
            MavenProcessExecutor processExecutor = new MavenProcessExecutor(MAVEN_TIMEOUT_SECONDS);
            String[] execArgs = resolver.resolveExecutableArgs(variantPath);
            String mavenGoal = resolver.resolveMavenGoal(variantPath);
            Path logFile = logDir.resolve(context.getProjectName() + "_" + variantIndex + "_compilation");
            processExecutor.executeCommand(variantPath, logFile, null,
                    AppConfig.buildCommand(execArgs, mavenGoal));

            if (session.isStopped()) { return false; }

            CompilationResult cr = new CompilationResult(logFile);
            TestTotal tt = new TestTotal(variantPath.toFile());
            Map<String, List<String>> patterns = variant.extractPatterns();
            double simplicity;
            try {
                simplicity = VariantScore.computeSimplicityScore(patterns, globalWeightMap);
            } catch (IllegalStateException e) {
                simplicity = 0.0;
            }
            VariantScore score = VariantScore.of(cr, tt, simplicity)
                    .map(s -> new VariantScore(s.successfulModules(), s.passedTests(),
                            s.simplicityScore(), variantIndex))
                    .orElse(null);

            Duration elapsed = Duration.between(start, Instant.now());
            Map<Integer, Integer> manualVers = session.getManualVersionSnapshot();
            VariantResult result = new VariantResult(
                    variantIndex, patterns, cr, tt, score, elapsed, logFile, manualVers);

            SwingUtilities.invokeLater(() -> onVariantComplete.accept(result));
            return true;
        } catch (Exception e) {
            if (!session.isStopped()) e.printStackTrace();
            return false;
        } finally {
            if (variantPath != null) {
                org.example.cicdmergeoracle.cicdMergeTool.util.MyFileUtils.deleteDirectory(variantPath.toFile());
            }
        }
    }

    /**
     * Clone a variant and replace MANUAL-pinned chunks with ManualPattern.
     * Returns the original variant unchanged if no manual pins are active.
     */
    /**
     * Overlay MANUAL-pinned chunks onto the variant in place.
     * Safe because each variant from the generator has its own ConflictBlocks.
     */
    private VariantProject applyManualPins(VariantProject variant) {
        Map<Integer, String> manualTexts = session.getManualTexts();
        if (manualTexts.isEmpty()) return variant;

        int globalIdx = 0;
        for (ConflictFile cf : variant.getClasses()) {
            for (IMergeBlock block : cf.getMergeBlocks()) {
                if (block instanceof ConflictBlock cb) {
                    String manualText = manualTexts.get(globalIdx);
                    if (manualText != null) {
                        cb.setPattern(new ManualPattern(manualText));
                    }
                    globalIdx++;
                }
            }
        }
        return variant;
    }

    /**
     * Compute the effective pattern assignment for dedup purposes.
     * MANUAL-pinned chunks are normalized to "MANUAL" so variants that differ
     * only in a pinned chunk are detected as duplicates.
     */
    private List<String> computeEffectiveAssignment(VariantProject variant) {
        Map<Integer, String> manualTexts = session.getManualTexts();
        List<String> effective = new ArrayList<>();
        int globalIdx = 0;
        for (ConflictFile cf : variant.getClasses()) {
            for (IMergeBlock block : cf.getMergeBlocks()) {
                if (block instanceof ConflictBlock cb) {
                    if (manualTexts.containsKey(globalIdx)) {
                        effective.add("MANUAL");
                    } else {
                        effective.add(cb.getPattern() != null ? cb.getPattern().name() : "?");
                    }
                    globalIdx++;
                }
            }
        }
        return effective;
    }

    public Path getTempDir() {
        return tempDir;
    }
}
