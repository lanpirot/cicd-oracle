package ch.unibe.cs.mergeci.runner;

import java.nio.file.Path;

/** Factory that produces {@link HeuristicGenerator} instances (one per merge). */
public class HeuristicGeneratorFactory implements IVariantGeneratorFactory {

    public static final HeuristicGeneratorFactory INSTANCE = new HeuristicGeneratorFactory();

    private HeuristicGeneratorFactory() {}

    @Override
    public IVariantGenerator create(String mergeId, Path repoPath, int numChunks) {
        return new HeuristicGenerator(numChunks);
    }
}
