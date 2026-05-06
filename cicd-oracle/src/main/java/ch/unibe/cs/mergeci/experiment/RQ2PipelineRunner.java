package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.IVariantGeneratorFactory;
import ch.unibe.cs.mergeci.runner.MLARGeneratorFactory;
import ch.unibe.cs.mergeci.util.Utility;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * RQ2 pipeline: walks the round-robin queue from {@link JavaChunksReader#sampleRoundRobin}
 * and stops once {@link AppConfig#getRQ2SampleTarget()} successful merges are recorded.
 * Runs all 5 experiment modes per merge.
 *
 * <p>The pipeline expects pre-existing results from a VM run (human_baseline plus
 * the four variant modes) in the experiment directory.  The per-merge resume logic
 * fast-skips all completed modes; only {@code cache_parallel} (P+) actually
 * executes.  Afterwards, a sanity check compares P+ against the existing
 * {@code no_optimization} (S) results.
 */
public class RQ2PipelineRunner extends RQPipelineRunner {

    @Override
    protected List<DatasetReader.MergeInfo> sampleMerges() throws IOException {
        return new JavaChunksReader().sampleRoundRobin(AppConfig.MAVEN_CONFLICTS_CSV);
    }

    @Override
    protected int processedLimit() {
        return AppConfig.getRQ2SampleTarget();
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

    @Override
    protected boolean stopOnPerfect() { return false; }

    /**
     * Progress = cache_parallel JSONs.  Pre-existing human_baseline data from
     * the VM run must not count as "completed" or the oversampling loop would
     * exit before P+ runs.
     */
    @Override
    int countBaselineJsons() {
        File[] files = experimentDir().resolve(Utility.Experiments.cache_parallel.getName())
                .toFile().listFiles();
        if (files == null) return 0;
        return (int) java.util.Arrays.stream(files)
                .filter(f -> f.getName().endsWith(AppConfig.JSON))
                .count();
    }

    public static void main(String[] args) throws Exception {
        new RQ2PipelineRunner().run();
    }
}
