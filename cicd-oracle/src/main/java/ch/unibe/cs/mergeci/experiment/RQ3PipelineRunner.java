package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.conflict.MergeStatistics;
import ch.unibe.cs.mergeci.present.ResultsPresenter;
import ch.unibe.cs.mergeci.present.StatisticsReporter;
import ch.unibe.cs.mergeci.runner.IVariantGeneratorFactory;
import ch.unibe.cs.mergeci.runner.MLARGeneratorFactory;
import ch.unibe.cs.mergeci.util.Utility;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RQ3 pipeline: walks the round-robin queue from {@link JavaChunksReader#sampleRoundRobin}
 * and stops once {@link AppConfig#getRQ3SampleTarget()} successful merges are recorded.
 * Runs {@code human_baseline} plus the best mode from RQ2 (configurable via system
 * property {@code rq3BestMode}, default {@code cache_parallel}).
 */
public class RQ3PipelineRunner extends RQPipelineRunner {

    @Override
    protected List<DatasetReader.MergeInfo> sampleMerges() throws IOException {
        return new JavaChunksReader().sampleRoundRobin(AppConfig.MAVEN_CONFLICTS_CSV);
    }

    @Override
    protected int processedLimit() {
        return AppConfig.getRQ3SampleTarget();
    }

    /**
     * Progress = JSONs in the variant mode dir (the gate metric for "successful merge").
     * Counting human_baseline alone would over-count: HB can succeed while the variant
     * phase still skips the merge (chunk-count mismatch, etc.).
     */
    @Override
    int countBaselineJsons() {
        File[] files = experimentDir().resolve(AppConfig.getRQ3BestMode()).toFile().listFiles();
        if (files == null) return 0;
        return (int) Arrays.stream(files)
                .filter(f -> f.getName().endsWith(AppConfig.JSON))
                .count();
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

    @Override
    protected void analyzeResults() {
        super.analyzeResults();

        String bestMode = AppConfig.getRQ3BestMode();
        Path bestModeDir = experimentDir().resolve(bestMode);
        if (!bestModeDir.toFile().exists()) return;

        ResultsPresenter presenter = new ResultsPresenter(bestModeDir);
        MergeStatistics stats = presenter.getStatistics();
        StatisticsReporter reporter = new StatisticsReporter(stats, bestMode);
        reporter.exportRQ3LatexVariables(stats.getAllMerges());
    }

    public static void main(String[] args) throws Exception {
        new RQ3PipelineRunner().run();
    }
}
