package org.example.cicdmergeoracle.cicdMergeTool.service;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.runner.DonorTracker;
import ch.unibe.cs.mergeci.runner.IVariantGenerator;
import ch.unibe.cs.mergeci.runner.OverlayMount;
import ch.unibe.cs.mergeci.runner.VariantBuildContext;
import ch.unibe.cs.mergeci.runner.VariantExecutionEngine;
import ch.unibe.cs.mergeci.runner.VariantProjectBuilder;
import ch.unibe.cs.mergeci.runner.maven.MavenCacheManager;
import ch.unibe.cs.mergeci.runner.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.util.FileUtils;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Top-level coordinator for the plugin's variant resolution pipeline.
 * Delegates the variant build-test loop to the shared {@link VariantExecutionEngine}
 * and handles plugin-specific concerns: DirCache-based context preparation,
 * cleanup (mvnd daemons, overlay mounts), and UI lifecycle callbacks.
 */
public class PluginOrchestrator {
    private static final Logger LOG = LoggerFactory.getLogger(PluginOrchestrator.class);

    private final Path repoPath;
    private final OracleSession session;
    private final Consumer<VariantResult> onVariantComplete;
    private final Consumer<Boolean> onRunFinished;
    private final Consumer<String> onError;
    private final Consumer<Integer> onInFlightChanged;
    private final HeuristicGeneratorFactory generatorFactory;
    private final int threadCount;

    private volatile Future<?> coordinatorFuture;
    private volatile boolean exhausted;
    private Path tempDir;
    private VariantProjectBuilder builder;
    private boolean useOverlay;
    private Path overlayBasePath;
    private Path overlayTmpDir;
    private MavenCacheManager cacheManager;

    public PluginOrchestrator(Path repoPath,
                              OracleSession session,
                              Consumer<VariantResult> onVariantComplete,
                              Consumer<Boolean> onRunFinished,
                              Consumer<String> onError,
                              Consumer<Integer> onInFlightChanged,
                              int threadCount) {
        this.repoPath = repoPath;
        this.session = session;
        this.onVariantComplete = onVariantComplete;
        this.onRunFinished = onRunFinished;
        this.onError = onError;
        this.onInFlightChanged = onInFlightChanged;
        this.generatorFactory = new HeuristicGeneratorFactory();
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

        useOverlay = OverlayMount.isAvailable();
        overlayTmpDir = tempDir;
        cacheManager = new MavenCacheManager(tempDir.resolve("shared-cache"));

        coordinatorFuture = Executors.newSingleThreadExecutor().submit(() -> {
            try {
                VariantBuildContext context = prepareContext(oursCommit, theirsCommit);
                session.setBuildContext(context);
                session.initChunkIndex();

                // Build shared overlay base once
                if (useOverlay) {
                    overlayBasePath = builder.buildBase(context, overlayTmpDir);
                    cacheManager.injectCacheArtifacts(overlayBasePath);
                }

                Path logDir = tempDir.resolve("log");

                VariantExecutionEngine.EngineConfig config = new VariantExecutionEngine.EngineConfig(
                        threadCount, useOverlay, true, true, false,
                        tempDir, logDir, null, tempDir, null, null);

                PluginLifecycleListener listener = new PluginLifecycleListener(
                        session, generatorFactory.getGlobalWeightMap(),
                        onVariantComplete, onInFlightChanged, onRunFinished);

                VariantExecutionEngine engine = new VariantExecutionEngine(
                        config, new SessionStopCondition(session), listener);
                engine.run(context, builder, session.getDonorTracker(), cacheManager, overlayBasePath);

                exhausted = engine.isExhausted();
            } catch (Exception e) {
                LOG.error("Pipeline failed", e);
                SwingUtilities.invokeLater(() -> onError.accept(e.getMessage()));
            } finally {
                cleanupTempDir();
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

        Git git = Git.open(repoPath.toFile());
        Map<String, ObjectId> nonConflictObjects =
                org.example.cicdmergeoracle.cicdMergeTool.util.GitUtils
                        .getNonConflictObjectsFromCurrentMerge(git);

        IVariantGenerator generator = generatorFactory.create(null, repoPath, totalChunks);

        return new VariantBuildContext(
                repoPath,
                tempDir.resolve("projects"),
                repoPath.getFileName().toString(),
                oursCommit,
                conflictFileMap,
                totalChunks,
                List.of(),
                generator,
                nonConflictObjects,
                Map.of()
        );
    }

    public void pause() {
        session.pause();
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
    }

    /** Delete the temp directory, overlay base, donor, and stop mvnd daemons. Safe to call multiple times. */
    public void cleanupTempDir() {
        stopMvndDaemons();

        if (overlayTmpDir != null) {
            OverlayMount.cleanupStaleMounts(overlayTmpDir);
        }
        DonorTracker tracker = session.getDonorTracker();
        Path donorPath = tracker.getDonorPath();
        if (donorPath != null && donorPath.toFile().exists()) {
            FileUtils.deleteDirectory(donorPath.toFile());
        }
        if (overlayBasePath != null && overlayBasePath.toFile().exists()) {
            FileUtils.deleteDirectory(overlayBasePath.toFile());
            overlayBasePath = null;
        }
        if (overlayTmpDir != null && !overlayTmpDir.equals(tempDir) && overlayTmpDir.toFile().exists()) {
            FileUtils.deleteDirectory(overlayTmpDir.toFile());
        }
        if (tempDir != null && tempDir.toFile().exists()) {
            FileUtils.deleteDirectory(tempDir.toFile());
        }
    }

    private void stopMvndDaemons() {
        try {
            new MavenCommandResolver().stopDaemons();
        } catch (Exception e) {
            LOG.warn("Could not stop mvnd daemons: {}", e.getMessage());
        }
    }

    public Path getTempDir() {
        return tempDir;
    }
}
