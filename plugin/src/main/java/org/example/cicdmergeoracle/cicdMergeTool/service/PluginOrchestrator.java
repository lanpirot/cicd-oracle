package org.example.cicdmergeoracle.cicdMergeTool.service;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.FixedTextBlock;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.VariantProject;
import org.example.cicdmergeoracle.cicdMergeTool.model.BlockGroup;
import ch.unibe.cs.mergeci.present.VariantScore;
import ch.unibe.cs.mergeci.runner.IVariantGenerator;
import ch.unibe.cs.mergeci.runner.VariantBuildContext;
import ch.unibe.cs.mergeci.runner.VariantDedup;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
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
    private static final Logger LOG = LoggerFactory.getLogger(PluginOrchestrator.class);
    private static final int MAVEN_TIMEOUT_SECONDS = 600;

    private final Path repoPath;
    private final OracleSession session;
    private final Consumer<VariantResult> onVariantComplete;
    private final Consumer<Boolean> onRunFinished; // true = exhausted, false = cancelled
    private final Consumer<String> onError;
    private final Consumer<Integer> onInFlightChanged;
    private final HeuristicGeneratorFactory generatorFactory;
    private final boolean useMavenDaemon;
    private final int threadCount;

    private volatile ExecutorService executor;
    private volatile Future<?> coordinatorFuture;
    private final Set<Future<?>> activeFutures = ConcurrentHashMap.newKeySet();
    private volatile boolean exhausted;
    private Path tempDir;
    private VariantProjectBuilder builder;
    private Map<String, Double> globalWeightMap;

    public PluginOrchestrator(Path repoPath,
                              OracleSession session,
                              Consumer<VariantResult> onVariantComplete,
                              Consumer<Boolean> onRunFinished,
                              Consumer<String> onError,
                              Consumer<Integer> onInFlightChanged,
                              boolean useMavenDaemon,
                              int threadCount) {
        this.repoPath = repoPath;
        this.session = session;
        this.onVariantComplete = onVariantComplete;
        this.onRunFinished = onRunFinished;
        this.onError = onError;
        this.onInFlightChanged = onInFlightChanged;
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
                LOG.error("Pipeline failed", e);
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
        for (Future<?> f : activeFutures) {
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

    /**
     * Continuously feeds variants to a fixed thread pool using a semaphore.
     * As soon as one variant finishes, a new one is submitted — threads stay
     * fully saturated instead of waiting for an entire batch to complete.
     *
     * <p>Interrupted variants (from a pause/cancel) are tracked via {@code submitted}
     * and retried on the next resume before asking the generator for new variants.
     */
    private void runVariantLoop(VariantBuildContext context) {
        executor = Executors.newFixedThreadPool(threadCount);
        Semaphore slots = new Semaphore(threadCount);
        AtomicInteger variantCounter = new AtomicInteger(1);
        AtomicInteger inFlight = new AtomicInteger();
        // Tracks in-flight variants (original, pre-pin) keyed by variant index.
        // Removed on successful completion; survivors after pause are retried.
        ConcurrentHashMap<Integer, VariantProject> submitted = new ConcurrentHashMap<>();
        Deque<VariantProject> pendingRetry = new ArrayDeque<>();
        Deque<Integer> freeIndices = new ArrayDeque<>();
        Set<List<String>> seenEffective = new HashSet<>();
        Path logDir = tempDir.resolve("log");
        logDir.toFile().mkdirs();

        boolean justResumed = false;
        try {
            while (!session.isCancelled()) {
                session.waitIfPaused();
                if (session.isCancelled()) break;

                // After a pause/resume cycle, wait for in-flight workers to drain
                // and re-queue any that didn't complete successfully.
                if (justResumed) {
                    slots.acquire(threadCount);
                    slots.release(threadCount);
                    // Reclaim variant indices and re-queue interrupted variants
                    for (Map.Entry<Integer, VariantProject> e : submitted.entrySet()) {
                        freeIndices.add(e.getKey());
                        pendingRetry.add(e.getValue());
                    }
                    submitted.clear();
                    justResumed = false;
                }

                // Drain retry queue first, then ask generator for a new variant
                VariantProject variant = pendingRetry.poll();
                if (variant == null) {
                    List<VariantProject> one = context.collectAllVariants(1);
                    if (one.isEmpty()) break; // generator exhausted
                    variant = one.get(0);
                }

                VariantProject buildVariant;
                try {
                    buildVariant = applyManualPins(variant);
                } catch (Throwable e) {
                    continue;
                }

                // Dedup: skip variants whose effective assignment was already tested
                List<String> effective = VariantDedup.computeEffectiveAssignment(
                        buildVariant, session.getManualTexts(), session.getManualVersionSnapshot());
                if (!seenEffective.add(effective)) {
                    continue; // duplicate — skip
                }

                // Block until a thread is free
                slots.acquire();
                if (session.isStopped()) {
                    slots.release();
                    pendingRetry.addFirst(variant);
                    justResumed = true;
                    continue; // loops back to waitIfPaused
                }

                Integer recycled = freeIndices.poll();
                int idx = recycled != null ? recycled : variantCounter.getAndIncrement();
                submitted.put(idx, variant);
                int count = inFlight.incrementAndGet();
                SwingUtilities.invokeLater(() -> onInFlightChanged.accept(count));

                Future<?>[] holder = new Future<?>[1];
                holder[0] = executor.submit(() -> {
                    try {
                        if (buildAndTest(context, buildVariant, idx, logDir)) {
                            submitted.remove(idx);
                        }
                    } finally {
                        activeFutures.remove(holder[0]);
                        int remaining = inFlight.decrementAndGet();
                        SwingUtilities.invokeLater(() -> onInFlightChanged.accept(remaining));
                        slots.release();
                    }
                });
                activeFutures.add(holder[0]);
            }

            // Wait for remaining in-flight variants to finish
            slots.acquire(threadCount);
            slots.release(threadCount);
        } catch (InterruptedException e) {
            // Hard cancel interrupted the wait — fall through to finally
        } finally {
            executor.shutdown();
            cleanupTempDir();
            exhausted = !session.isCancelled();
            SwingUtilities.invokeLater(() -> onRunFinished.accept(exhausted));
        }
    }

    /** Delete the temp directory and all its contents. Safe to call multiple times. */
    public void cleanupTempDir() {
        if (tempDir != null && tempDir.toFile().exists()) {
            org.example.cicdmergeoracle.cicdMergeTool.util.MyFileUtils.deleteDirectory(tempDir.toFile());
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

            // Extract failure details before variantPath is cleaned up
            List<String> failedModules = extractFailedModules(cr);
            List<String> testFailures = extractTestFailures(variantPath);

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
                    variantIndex, patterns, cr, tt, score, elapsed, logFile, manualVers,
                    failedModules, testFailures);

            SwingUtilities.invokeLater(() -> onVariantComplete.accept(result));
            return true;
        } catch (Exception e) {
            if (!session.isStopped()) LOG.warn("Build/test failed for variant {}", variantIndex, e);
            return false;
        } finally {
            if (variantPath != null) {
                org.example.cicdmergeoracle.cicdMergeTool.util.MyFileUtils.deleteDirectory(variantPath.toFile());
            }
        }
    }

    /** Extract names of modules that did not succeed (FAILURE, SKIPPED, TIMEOUT). */
    private static List<String> extractFailedModules(CompilationResult cr) {
        if (cr == null || cr.getModuleResults() == null) return List.of();
        List<String> failed = new ArrayList<>();
        for (CompilationResult.ModuleResult mr : cr.getModuleResults()) {
            if (mr.getStatus() != CompilationResult.Status.SUCCESS) {
                failed.add(mr.getModuleName() + " (" + mr.getStatus() + ")");
            }
        }
        return failed;
    }

    /** Parse surefire/failsafe XMLs for individual test failures. Must be called before variantPath cleanup. */
    private static List<String> extractTestFailures(Path variantPath) {
        List<String> failures = new ArrayList<>();
        try {
            java.util.stream.Stream<Path> files = java.nio.file.Files.walk(variantPath, 10);
            files.filter(p -> {
                String s = p.toString();
                return (s.contains("surefire-reports") || s.contains("failsafe-reports"))
                        && s.endsWith(".xml") && p.getFileName().toString().startsWith("TEST-");
            }).forEach(xmlPath -> {
                try {
                    javax.xml.parsers.DocumentBuilder db = javax.xml.parsers.DocumentBuilderFactory
                            .newInstance().newDocumentBuilder();
                    org.w3c.dom.Document doc = db.parse(xmlPath.toFile());
                    org.w3c.dom.NodeList testcases = doc.getElementsByTagName("testcase");
                    for (int i = 0; i < testcases.getLength(); i++) {
                        org.w3c.dom.Element tc = (org.w3c.dom.Element) testcases.item(i);
                        org.w3c.dom.NodeList failNodes = tc.getElementsByTagName("failure");
                        org.w3c.dom.NodeList errorNodes = tc.getElementsByTagName("error");
                        if (failNodes.getLength() > 0 || errorNodes.getLength() > 0) {
                            String className = tc.getAttribute("classname");
                            String method = tc.getAttribute("name");
                            String msg = "";
                            if (failNodes.getLength() > 0) {
                                msg = ((org.w3c.dom.Element) failNodes.item(0)).getAttribute("message");
                            } else if (errorNodes.getLength() > 0) {
                                msg = ((org.w3c.dom.Element) errorNodes.item(0)).getAttribute("message");
                            }
                            String entry = className + "#" + method;
                            if (!msg.isEmpty()) {
                                String shortMsg = msg.length() > 80 ? msg.substring(0, 80) + "..." : msg;
                                entry += " — " + shortMsg;
                            }
                            failures.add(entry);
                        }
                    }
                } catch (Exception e) {
                    // skip unparseable XML
                }
            });
            files.close();
        } catch (IOException e) {
            // variant path may already be gone
        }
        return failures;
    }

    /**
     * Overlay MANUAL-pinned chunks onto the variant by flattening entire
     * working-tree chunk groups into a single {@link FixedTextBlock}.
     * Safe because each variant from the generator has its own block copies.
     */
    private VariantProject applyManualPins(VariantProject variant) {
        Map<Integer, String> manualTexts = session.getManualTexts();
        if (manualTexts.isEmpty()) return variant;

        Map<Integer, BlockGroup> groupMap = session.getBlockGroupMap();
        int globalIdx = 0;

        for (ConflictFile cf : variant.getClasses()) {
            List<IMergeBlock> blocks = cf.getMergeBlocks();
            List<IMergeBlock> newBlocks = new ArrayList<>();
            int blockIdx = 0;

            // Find manual groups for THIS file's globalIdx range
            int fileStartGlobalIdx = globalIdx;
            int fileCBCount = (int) blocks.stream()
                    .filter(b -> b instanceof ConflictBlock).count();
            Set<BlockGroup> manualGroups = new HashSet<>();
            for (int gi = fileStartGlobalIdx; gi < fileStartGlobalIdx + fileCBCount; gi++) {
                if (manualTexts.containsKey(gi)) {
                    BlockGroup g = groupMap.get(gi);
                    if (g != null) manualGroups.add(g);
                }
            }

            while (blockIdx < blocks.size()) {
                // Check if this block is the START of a manual group in THIS file
                BlockGroup manualGroup = null;
                for (BlockGroup mg : manualGroups) {
                    if (mg.startBlockIndex() == blockIdx) {
                        manualGroup = mg;
                        break;
                    }
                }

                if (manualGroup != null) {
                    // Flatten: replace entire group with one FixedTextBlock
                    int primaryIdx = manualGroup.memberGlobalIndices().get(0);
                    String text = manualTexts.get(primaryIdx);
                    newBlocks.add(new FixedTextBlock(text, manualGroup.memberGlobalIndices().size()));
                    manualGroups.remove(manualGroup);
                    // Advance globalIdx past all ConflictBlocks in the group
                    for (int i = manualGroup.startBlockIndex(); i < manualGroup.endBlockIndex(); i++) {
                        if (blocks.get(i) instanceof ConflictBlock) globalIdx++;
                    }
                    blockIdx = manualGroup.endBlockIndex();
                    continue;
                }

                IMergeBlock block = blocks.get(blockIdx);

                if (block instanceof ConflictBlock) {
                    newBlocks.add(block);
                    globalIdx++;
                } else {
                    newBlocks.add(block);
                }
                blockIdx++;
            }

            cf.setMergeBlocks(newBlocks);
        }
        return variant;
    }

    public Path getTempDir() {
        return tempDir;
    }
}
