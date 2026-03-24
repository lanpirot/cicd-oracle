package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.IVariantGeneratorFactory;
import ch.unibe.cs.mergeci.runner.MLARGeneratorFactory;
import ch.unibe.cs.mergeci.util.Utility;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RQ3 pipeline: distributes {@link AppConfig#RQ3_SAMPLE_TOTAL} merges fairly across all
 * Maven projects, then runs {@code human_baseline} plus the best mode from RQ2
 * (configurable via system property {@code rq3BestMode}, default {@code cache_parallel}).
 */
public class RQ3PipelineRunner extends RQPipelineRunner {

    @Override
    protected List<DatasetReader.MergeInfo> sampleMerges() throws IOException {
        return new JavaChunksReader().sampleDistributed(
                AppConfig.MERGE_COMMITS_CSV, AppConfig.RQ3_SAMPLE_TOTAL);
    }

    @Override
    protected List<Utility.Experiments> modesToRun() {
        String bestMode = AppConfig.getRQ3BestMode();
        return Arrays.stream(Utility.Experiments.values())
                .filter(e -> e == Utility.Experiments.human_baseline
                          || e.getName().equals(bestMode))
                .collect(Collectors.toList());
    }

    @Override
    protected Path experimentDir() {
        return AppConfig.RQ3_VARIANT_EXPERIMENT_DIR;
    }

    @Override
    protected IVariantGeneratorFactory generatorFactory() {
        return MLARGeneratorFactory.INSTANCE;
    }
}
