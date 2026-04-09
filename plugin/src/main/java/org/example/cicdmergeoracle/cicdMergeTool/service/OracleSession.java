package org.example.cicdmergeoracle.cicdMergeTool.service;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.runner.VariantBuildContext;
import lombok.Getter;
import lombok.Setter;
import org.example.cicdmergeoracle.cicdMergeTool.model.ChunkKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mutable session state shared across all UI panels for one merge resolution run.
 * Thread-safe: history is copy-on-write, currentBest is volatile, pinnedChunks is synchronized.
 */
@Getter
public class OracleSession {
    private volatile VariantResult currentBest;
    private final CopyOnWriteArrayList<VariantResult> history = new CopyOnWriteArrayList<>();
    private final Map<Integer, String> pinnedChunks = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<Integer, String> manualTexts = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<Integer, Integer> manualVersions = Collections.synchronizedMap(new LinkedHashMap<>());
    @Setter
    private List<ChunkKey> chunkIndex = List.of();
    @Setter
    private volatile VariantBuildContext buildContext;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final Object pauseLock = new Object();

    /**
     * Add a completed variant result. Updates currentBest if the new variant
     * has a strictly better score.
     *
     * @return true if currentBest was updated
     */
    public boolean addResult(VariantResult result) {
        history.add(result);
        if (result.score() == null) return false;

        synchronized (this) {
            if (currentBest == null || currentBest.score() == null
                    || result.score().isBetterThan(currentBest.score())) {
                currentBest = result;
                return true;
            }
        }
        return false;
    }

    /**
     * Build the chunk index from the build context's conflict file map.
     * Must be called after the context is set.
     */
    public void initChunkIndex() {
        if (buildContext == null) return;
        List<ChunkKey> index = new ArrayList<>();
        for (Map.Entry<String, ConflictFile> entry : buildContext.getConflictFileMap().entrySet()) {
            String filePath = entry.getKey();
            int withinFile = 0;
            for (IMergeBlock block : entry.getValue().getMergeBlocks()) {
                if (block instanceof ConflictBlock) {
                    index.add(new ChunkKey(filePath, withinFile++));
                }
            }
        }
        this.chunkIndex = List.copyOf(index);
    }

    /** Bump the version counter for a manual chunk (call when manual text is pinned/changed). */
    public void bumpManualVersion(int chunkIdx) {
        manualVersions.merge(chunkIdx, 1, Integer::sum);
    }

    /** Snapshot of current manual versions — safe to pass to worker threads. */
    public Map<Integer, Integer> getManualVersionSnapshot() {
        synchronized (manualVersions) {
            return Map.copyOf(manualVersions);
        }
    }

    public void pause() {
        paused.set(true);
    }

    /** True when execution should not proceed (paused or cancelled). */
    public boolean isStopped() {
        return paused.get() || cancelled.get();
    }

    public void resume() {
        synchronized (pauseLock) {
            paused.set(false);
            pauseLock.notifyAll();
        }
    }

    /**
     * Block the calling thread while paused.
     * Returns immediately if cancelled or not paused.
     */
    public void waitIfPaused() throws InterruptedException {
        synchronized (pauseLock) {
            while (paused.get() && !cancelled.get()) {
                pauseLock.wait();
            }
        }
    }

    public void cancel() {
        cancelled.set(true);
        // Wake any thread blocked in waitIfPaused
        synchronized (pauseLock) {
            paused.set(false);
            pauseLock.notifyAll();
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
