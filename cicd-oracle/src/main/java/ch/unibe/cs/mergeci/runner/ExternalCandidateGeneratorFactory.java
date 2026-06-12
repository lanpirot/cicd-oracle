package ch.unibe.cs.mergeci.runner;

import java.nio.file.Path;

/**
 * Factory bound to one candidate merge directory. The external-candidates runner
 * constructs a fresh factory per merge (processing is single-merge sequential),
 * so {@link #create} ignores its arguments.
 */
public class ExternalCandidateGeneratorFactory implements IVariantGeneratorFactory {

    private final Path mergeDir;
    private final ExternalCandidateMeta meta;
    private ExternalCandidateGenerator lastCreated;

    public ExternalCandidateGeneratorFactory(Path mergeDir, ExternalCandidateMeta meta) {
        this.mergeDir = mergeDir;
        this.meta = meta;
    }

    @Override
    public IVariantGenerator create(String mergeId, Path repoPath, int numChunks) {
        lastCreated = new ExternalCandidateGenerator(mergeDir, meta);
        return lastCreated;
    }

    /** The generator created for the merge that just ran — used to map variantIndex → candidate k. */
    public ExternalCandidateGenerator getLastCreated() {
        return lastCreated;
    }
}
