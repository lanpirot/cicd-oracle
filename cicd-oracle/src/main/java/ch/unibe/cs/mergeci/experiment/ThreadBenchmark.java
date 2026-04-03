package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.MLARGeneratorFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Standalone benchmark: runs a single merge in parallel mode with a given thread count.
 * Usage: java -DmaxThreads=N ThreadBenchmark
 *
 * Reads the human_baseline JSON from rq2/ to skip the baseline build,
 * then runs only the parallel mode. Output goes to a temp dir.
 */
public class ThreadBenchmark {

    private static final String MERGE_COMMIT = "da39aa805002d3b090d7f93410adce36f1e97d84";
    private static final String PARENT1 = "a3005aaf4e0c903c11d3ca3cc3681eea92064b45";
    private static final String PARENT2 = "01c788fb8d00fa3003434cb002b8951e4ea3bb6f";
    private static final String MERGE_ID = "205";
    private static final String PROJECT_NAME = "jboss-javassist/javassist";

    public static void main(String[] args) throws Exception {
        Path benchDir = Path.of(System.getProperty("benchDir",
                AppConfig.DATA_BASE_DIR.resolve("bench_threads").toString()));
        String tag = System.getProperty("benchTag", "run");

        Path humanBaselineDir = AppConfig.RQ2_VARIANT_EXPERIMENT_DIR.resolve("human_baseline");
        if (!humanBaselineDir.resolve(MERGE_COMMIT + ".json").toFile().exists()) {
            System.err.println("ERROR: human_baseline JSON not found — run RQ2 first");
            System.exit(1);
        }

        Path modeDir = benchDir.resolve("parallel_" + tag);
        if (modeDir.toFile().exists()) {
            org.apache.commons.io.FileUtils.deleteDirectory(modeDir.toFile());
        }
        modeDir.toFile().mkdirs();

        DatasetReader.MergeInfo info = new DatasetReader.MergeInfo();
        info.setMergeCommit(MERGE_COMMIT);
        info.setParent1(PARENT1);
        info.setParent2(PARENT2);
        info.setMergeId(MERGE_ID);

        List<DatasetReader.MergeInfo> merges = List.of(info);

        Path repoPath = AppConfig.REPO_DIR.resolve("jboss-javassist/javassist");

        long start = System.currentTimeMillis();
        ResolutionVariantRunner.makeAnalysisByMergeList(
                merges, PROJECT_NAME, repoPath, modeDir, humanBaselineDir,
                true, false, false,    // parallel=true, cache=false, skipVariants=false
                AppConfig.TMP_DIR, "parallel",
                MLARGeneratorFactory.INSTANCE, null, false);
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf("%n[BENCH] wall=%d ms  tag=%s%n", elapsed, tag);
    }
}
