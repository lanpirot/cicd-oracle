package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.IVariantGeneratorFactory;
import ch.unibe.cs.mergeci.runner.MLARGeneratorFactory;
import ch.unibe.cs.mergeci.util.Utility;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * RQ2 pipeline: samples up to {@link AppConfig#RQ2_SAMPLE_REPOS} Maven projects,
 * taking {@link AppConfig#RQ2_MERGES_PER_REPO} merge(s) per project, then runs
 * all 5 experiment modes.
 */
public class RQ2PipelineRunner extends RQPipelineRunner {

    @Override
    protected List<DatasetReader.MergeInfo> sampleMerges() throws IOException {
        // Sample all fold-assigned Maven projects; run() stops once successLimit() succeed.
        return new JavaChunksReader().sample(
                AppConfig.MERGE_COMMITS_CSV,
                Integer.MAX_VALUE,
                AppConfig.RQ2_MERGES_PER_REPO);
    }

    @Override
    protected int successLimit() {
        return AppConfig.RQ2_SAMPLE_REPOS;
    }

    @Override
    protected List<Utility.Experiments> modesToRun() {
        return Arrays.asList(Utility.Experiments.values());
    }

    @Override
    protected Path experimentDir() {
        return AppConfig.RQ2_VARIANT_EXPERIMENT_DIR;
    }

    @Override
    protected IVariantGeneratorFactory generatorFactory() {
        return MLARGeneratorFactory.INSTANCE;
    }

    public static void main(String[] args) throws Exception {
        new RQ2PipelineRunner().run();
    }
}
