package ch.unibe.cs.mergeci.runner;

import java.nio.file.Path;

/** Factory that produces {@link MLARGenerator} instances (one per merge). */
public class MLARGeneratorFactory implements IVariantGeneratorFactory {

    public static final MLARGeneratorFactory INSTANCE = new MLARGeneratorFactory();

    private MLARGeneratorFactory() {}

    @Override
    public IVariantGenerator create(String mergeId, Path repoPath, int numChunks) {
        return new MLARGenerator(mergeId, numChunks);
    }
}
