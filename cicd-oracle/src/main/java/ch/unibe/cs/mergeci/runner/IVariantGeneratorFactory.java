package ch.unibe.cs.mergeci.runner;

import java.nio.file.Path;

/**
 * Factory that creates a per-merge {@link IVariantGenerator}.
 * The factory is injected into the pipeline and called once per merge commit
 * after the number of conflict chunks is known.
 */
public interface IVariantGeneratorFactory {

    /**
     * @param mergeId    merge ID (from maven_conflicts.csv) — may be null for test repos
     * @param repoPath   path to the cloned repository
     * @param numChunks  total number of conflict chunks in this merge
     */
    IVariantGenerator create(String mergeId, Path repoPath, int numChunks);
}
