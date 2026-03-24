package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.IVariantEvaluator;
import ch.unibe.cs.mergeci.runner.IVariantGeneratorFactory;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.RepositoryManager;
import ch.unibe.cs.mergeci.util.Utility;
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

    public void run() throws Exception {
        List<DatasetReader.MergeInfo> allMerges = sampleMerges();
        System.out.printf("Pipeline: sampled %d merges across %d projects%n",
                allMerges.size(), countDistinctProjects(allMerges));

        Map<String, List<DatasetReader.MergeInfo>> byProject = groupByProject(allMerges);

        for (Map.Entry<String, List<DatasetReader.MergeInfo>> entry : byProject.entrySet()) {
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
            } catch (Exception e) {
                System.err.println("Analysis failed for " + projectName + ": " + e.getMessage());
            } finally {
                RepositoryCache.clear();
                WindowCache.reconfigure(new WindowCacheConfig());
            }
        }
    }

    private void runModes(String projectName, List<DatasetReader.MergeInfo> merges, Path repoPath) throws Exception {
        Path humanBaselineDir = experimentDir().resolve("human_baseline");
        humanBaselineDir.toFile().mkdirs();
        Path humanBaselineOutput = humanBaselineDir.resolve(projectName + AppConfig.JSON);

        for (Utility.Experiments ex : modesToRun()) {
            Path modeDir = experimentDir().resolve(ex.getName());
            modeDir.toFile().mkdirs();
            Path output = modeDir.resolve(projectName + AppConfig.JSON);

            if (!AppConfig.isFreshRun() && output.toFile().exists()) {
                System.out.printf("File %s already exists. Skipping...%n", output.getFileName());
                continue;
            }

            ResolutionVariantRunner.makeAnalysisByMergeList(
                    merges, projectName, repoPath, output, humanBaselineOutput,
                    ex.isParallel(), ex.isCache(), ex.isSkipVariants(),
                    AppConfig.TMP_DIR, ex.getName(),
                    generatorFactory(), evaluator());
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
