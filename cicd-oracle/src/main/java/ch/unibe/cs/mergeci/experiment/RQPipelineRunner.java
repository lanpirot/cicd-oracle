package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.present.CrossModeSanityChecker;
import ch.unibe.cs.mergeci.runner.IVariantEvaluator;
import ch.unibe.cs.mergeci.runner.IVariantGeneratorFactory;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.RepositoryManager;
import ch.unibe.cs.mergeci.util.Utility;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract base for RQ2 and RQ3 experiment pipelines.
 *
 * <p>Subclasses define which merges to sample, which experiment modes to run,
 * which generator/evaluator to use, and where to write results.
 * The shared per-project loop lives here.
 */
public abstract class RQPipelineRunner {

    protected final RepositoryManager repoManager;

    protected RQPipelineRunner() {
        this.repoManager = new RepositoryManager(AppConfig.REPO_DIR);
    }

    /** Return the list of merges to process (ordered; may span multiple projects). */
    protected abstract List<DatasetReader.MergeInfo> sampleMerges() throws IOException;

    /** Return the experiment modes to run (in order). */
    protected abstract List<Utility.Experiments> modesToRun();

    /** Return the root directory under which per-mode subdirs are created. */
    protected abstract Path experimentDir();

    /** Generator factory to use for variant generation; {@code null} = default heuristic. */
    protected IVariantGeneratorFactory generatorFactory() { return null; }

    /** Evaluator to use; {@code null} = default MavenVariantEvaluator. */
    protected IVariantEvaluator evaluator() { return null; }

    /**
     * Whether to stop variant generation as soon as a perfect variant is found.
     * RQ2 overrides this to {@code false} so all modes explore the same time budget
     * regardless of when (or whether) a perfect variant appears.
     */
    protected boolean stopOnPerfect() { return true; }

    /**
     * Maximum number of projects to process successfully before stopping.
     * Subclasses with oversampling (e.g. RQ2) override this to cap at the
     * target count so that extra candidates are only used as fallback.
     * Default: no limit.
     */
    protected int successLimit() { return Integer.MAX_VALUE; }

    public void run() throws Exception {
        List<DatasetReader.MergeInfo> allMerges = sampleMerges();
        System.out.printf("Pipeline: sampled %d merges across %d projects%n",
                allMerges.size(), countDistinctProjects(allMerges));

        Map<String, List<DatasetReader.MergeInfo>> byProject = groupByProject(allMerges);

        if (AppConfig.isFreshRun()) {
            cleanExperimentDirs();
        }

        int successes = 0;
        int limit = successLimit();
        for (Map.Entry<String, List<DatasetReader.MergeInfo>> entry : byProject.entrySet()) {
            if (successes >= limit) break;

            String projectName = entry.getKey();
            List<DatasetReader.MergeInfo> merges = entry.getValue();

            Path repoPath;
            try {
                repoPath = repoManager.getRepositoryPath(projectName, merges.get(0).getRemoteUrl());
            } catch (IOException e) {
                System.err.println("Skipping " + projectName + ": " + e.getMessage());
                continue;
            }

            for (DatasetReader.MergeInfo info : merges) {
                String[] parents = GitUtils.getParentCommits(repoPath, info.getMergeCommit());
                info.setParent1(parents[0]);
                info.setParent2(parents[1]);
            }

            try {
                runModes(projectName, merges, repoPath);
                successes++;
            } catch (Exception e) {
                System.err.println("Analysis failed for " + projectName + ": " + e.getMessage());
            } finally {
                RepositoryCache.clear();
                WindowCache.reconfigure(new WindowCacheConfig());
            }
        }

        if (limit < Integer.MAX_VALUE) {
            System.out.printf("Pipeline finished: %d/%d projects succeeded.%n", successes, limit);
        }

        CrossModeSanityChecker.check(experimentDir(), modesToRun());
    }

    private void runModes(String projectName, List<DatasetReader.MergeInfo> merges, Path repoPath) throws Exception {
        Path humanBaselineDir = experimentDir().resolve("human_baseline");
        humanBaselineDir.toFile().mkdirs();

        for (Utility.Experiments ex : modesToRun()) {
            Path modeDir = experimentDir().resolve(ex.getName());
            modeDir.toFile().mkdirs();

            ResolutionVariantRunner.makeAnalysisByMergeList(
                    merges, projectName, repoPath, modeDir, humanBaselineDir,
                    ex.isParallel(), ex.isCache(), ex.isSkipVariants(),
                    AppConfig.TMP_DIR, ex.getName(),
                    generatorFactory(), evaluator(), stopOnPerfect());
        }
    }

    private void cleanExperimentDirs() {
        for (Utility.Experiments ex : modesToRun()) {
            Path modeDir = experimentDir().resolve(ex.getName());
            if (modeDir.toFile().exists()) {
                System.out.println("FRESH_RUN enabled: Cleaning experiment directory: " + modeDir);
                try {
                    FileUtils.deleteDirectory(modeDir.toFile());
                } catch (IOException e) {
                    System.err.println("Warning: could not delete " + modeDir + ": " + e.getMessage());
                }
            }
        }
    }

    protected static Map<String, List<DatasetReader.MergeInfo>> groupByProject(List<DatasetReader.MergeInfo> merges) {
        Map<String, List<DatasetReader.MergeInfo>> map = new LinkedHashMap<>();
        for (DatasetReader.MergeInfo info : merges) {
            map.computeIfAbsent(info.getProjectName(), k -> new ArrayList<>()).add(info);
        }
        return map;
    }

    protected static long countDistinctProjects(List<DatasetReader.MergeInfo> merges) {
        return merges.stream().map(DatasetReader.MergeInfo::getProjectName).distinct().count();
    }
}
