package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.present.CrossModeSanityChecker;
import ch.unibe.cs.mergeci.runner.IVariantEvaluator;
import ch.unibe.cs.mergeci.runner.IVariantGeneratorFactory;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.RepositoryManager;
import ch.unibe.cs.mergeci.util.Utility;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

import java.io.File;
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

    /** Modes to include in the cross-mode sanity check. Defaults to {@link #modesToRun()}. */
    protected List<Utility.Experiments> sanityCheckModes() { return modesToRun(); }

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
     * Maximum number of projects to process before stopping.
     * Subclasses with oversampling (e.g. RQ2) override this to cap at the
     * target count so that extra candidates are only used as fallback.
     * Default: no limit.
     */
    protected int processedLimit() { return Integer.MAX_VALUE; }

    private void printSplash() {
        String modes = modesToRun().stream()
                .map(Utility.Experiments::getName)
                .collect(java.util.stream.Collectors.joining(", "));
        int limit = processedLimit();

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║              " + getClass().getSimpleName() + " — Pipeline Settings");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf( "║  Fresh run           : %s%n", AppConfig.isFreshRun());
        System.out.printf( "║  Project limit       : %s%n", limit == Integer.MAX_VALUE ? "unlimited" : limit);
        System.out.printf( "║  Experiment modes    : %s%n", modes);
        System.out.printf( "║  Stop on perfect     : %s%n", stopOnPerfect());
        System.out.printf( "║  Generator           : %s%n",
                generatorFactory() != null ? generatorFactory().getClass().getSimpleName() : "default (heuristic)");
        System.out.printf( "║  Baseline timeout    : %d s%n", AppConfig.MAVEN_BUILD_TIMEOUT);
        System.out.printf( "║  Variant budget      : max(300s, baseline×10)%n");
        System.out.printf( "║  Max threads         : %d (cores − 2; re-computed per merge from measured peak RAM)%n",
                Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
        System.out.printf( "║  Maven daemon        : %s%n", AppConfig.USE_MAVEN_DAEMON);
        System.out.printf( "║  Maven heap          : %s%n", AppConfig.MAVEN_SUBPROCESS_HEAP);
        System.out.printf( "║  Output dir          : %s%n", experimentDir());
        System.out.printf( "║  Repo dir            : %s%n", AppConfig.REPO_DIR);
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    public void run() throws Exception {
        printSplash();

        List<DatasetReader.MergeInfo> allMerges = sampleMerges();
        System.out.printf("Pipeline: sampled %d merges across %d projects%n",
                allMerges.size(), countDistinctProjects(allMerges));

        Map<String, List<DatasetReader.MergeInfo>> byProject = groupByProject(allMerges);

        if (AppConfig.isFreshRun()) {
            System.out.println("\n⚠  FRESH_RUN is enabled — this will wipe all prior data in " + experimentDir());
            System.out.print("Do you want to continue? [y/N] ");
            String answer = new java.util.Scanner(System.in).nextLine().trim();
            if (!answer.equalsIgnoreCase("y")) {
                System.out.println("Aborted.");
                return;
            }
            cleanExperimentDirs();
        }

        try (AttemptedMergeLog mergeLog = new AttemptedMergeLog(
                experimentDir().resolve("attempted_merges.csv"))) {
            runProjects(byProject, mergeLog);
        }

        CrossModeSanityChecker.check(experimentDir(), sanityCheckModes());
        analyzeResults();
    }

    private void runProjects(Map<String, List<DatasetReader.MergeInfo>> byProject,
                             AttemptedMergeLog mergeLog) throws Exception {
        int limit = processedLimit();
        int totalProjects = byProject.size();
        int projectIndex = 0;
        for (Map.Entry<String, List<DatasetReader.MergeInfo>> entry : byProject.entrySet()) {
            int completed = countBaselineJsons();
            if (completed >= limit) break;

            projectIndex++;
            String projectName = entry.getKey();
            List<DatasetReader.MergeInfo> merges = entry.getValue();

            System.out.println();
            System.out.println("════════════════════════════════════════════════════════════");
            System.out.printf("  Project %d/%d: %s  (%d merges)%n", projectIndex, totalProjects, projectName, merges.size());
            System.out.printf("  Completed baselines: %d/%d%n", completed, limit == Integer.MAX_VALUE ? totalProjects : limit);
            System.out.println("════════════════════════════════════════════════════════════");

            Path repoPath;
            try {
                repoPath = repoManager.getRepositoryPath(projectName, merges.get(0).getRemoteUrl());
            } catch (IOException e) {
                System.err.println("Skipping " + projectName + ": " + e.getMessage());
                mergeLog.logProjectFailure(projectName, "clone failed: " + e.getMessage());
                continue;
            }

            try {
                for (DatasetReader.MergeInfo info : merges) {
                    String[] parents = GitUtils.getParentCommits(repoPath, info.getMergeCommit());
                    info.setParent1(parents[0]);
                    info.setParent2(parents[1]);
                }
                runModes(projectName, merges, repoPath, mergeLog);
            } catch (Exception e) {
                System.err.println("Analysis failed for " + projectName + ": " + e.getMessage());
            } finally {
                RepositoryCache.clear();
                WindowCache.reconfigure(new WindowCacheConfig());
            }
        }
        int processed = countBaselineJsons();

        if (limit < Integer.MAX_VALUE) {
            System.out.printf("Pipeline finished: %d/%d projects processed.%n", processed, limit);
        }
    }

    /**
     * Final pipeline phase: console summary via {@link ResultsPresenter} for each
     * mode, then paper-ready PDF via the Python presentation script.
     * Subclasses may override to add RQ-specific analysis.
     */
    protected void analyzeResults() {
        System.out.println("\n══════════════════════════════════════════════════════════");
        System.out.println("  Analysis Phase");
        System.out.println("══════════════════════════════════════════════════════════");

        for (Utility.Experiments ex : modesToRun()) {
            Path modeDir = experimentDir().resolve(ex.getName());
            if (modeDir.toFile().exists()) {
                new ch.unibe.cs.mergeci.present.ResultsPresenter(modeDir).presentFullResults();
            }
        }

        generatePdfPlots();
    }

    /**
     * Invoke the Python presentation script to produce paper-ready PDFs.
     * Results are written to {@code experimentDir()/results/} — individual
     * PDFs per chart plus a combined {@code all_plots.pdf} and {@code summary.txt}.
     * Python is the single source of truth for all visual output; Java keeps
     * StatisticsReporter only for quick console/development checks.
     */
    private void generatePdfPlots() {
        Path resultsDir = experimentDir().resolve("results");
        try {
            System.out.printf("%nGenerating paper-ready results in: %s%n", resultsDir);
            Process p = new ProcessBuilder(
                    AppConfig.PYTHON_EXECUTABLE,
                    AppConfig.PLOT_SCRIPT.toString(),
                    experimentDir().toString(),
                    resultsDir.resolve("all_plots.pdf").toString()
            ).inheritIO().start();
            int exit = p.waitFor();
            if (exit != 0) {
                System.err.printf("Warning: plot script exited with code %d%n", exit);
            }
        } catch (Exception e) {
            System.err.println("Warning: could not generate PDF plots: " + e.getMessage());
        }
    }

    private void runModes(String projectName, List<DatasetReader.MergeInfo> merges,
                          Path repoPath, AttemptedMergeLog mergeLog) throws Exception {
        Path humanBaselineDir = experimentDir().resolve("human_baseline");
        humanBaselineDir.toFile().mkdirs();

        for (Utility.Experiments ex : modesToRun()) {
            Path modeDir = experimentDir().resolve(ex.getName());
            modeDir.toFile().mkdirs();

            ResolutionVariantRunner.makeAnalysisByMergeList(
                    merges, projectName, repoPath, modeDir, humanBaselineDir,
                    ex.isParallel(), ex.isCache(), ex.isSkipVariants(),
                    AppConfig.TMP_DIR, ex.getName(),
                    generatorFactory(), evaluator(), stopOnPerfect(), mergeLog);

            // After the human_baseline build, check for problems that make this
            // project permanently unusable.  Abort early so it does not waste a
            // processed slot and variant modes are not attempted.
            if (ex.isSkipVariants()) {
                checkBaselineViability(projectName, merges, repoPath);
            }
        }

        removeOrphanedBaselines(merges, humanBaselineDir);
    }

    /**
     * After all modes complete for a project, delete any human_baseline JSON
     * that has no corresponding JSON in any variant mode directory.
     * This handles cases like chunk-count mismatches where the baseline succeeds
     * but every variant mode skips the merge.
     */
    void removeOrphanedBaselines(List<DatasetReader.MergeInfo> merges, Path humanBaselineDir) {
        List<Utility.Experiments> variantModes = modesToRun().stream()
                .filter(ex -> !ex.isSkipVariants())
                .toList();

        for (DatasetReader.MergeInfo info : merges) {
            String filename = info.getMergeCommit() + AppConfig.JSON;
            File baselineFile = humanBaselineDir.resolve(filename).toFile();
            if (!baselineFile.exists()) continue;

            boolean hasVariant = variantModes.stream()
                    .anyMatch(ex -> experimentDir().resolve(ex.getName()).resolve(filename).toFile().exists());

            if (!hasVariant) {
                System.out.printf("Removing orphaned human_baseline: %s (no variant mode produced a result)%n",
                        info.getMergeCommit());
                baselineFile.delete();
            }
        }
    }

    /** Count JSON files in the human_baseline directory. */
    int countBaselineJsons() {
        File[] files = experimentDir().resolve("human_baseline").toFile().listFiles();
        if (files == null) return 0;
        return (int) java.util.Arrays.stream(files)
                .filter(f -> f.getName().endsWith(AppConfig.JSON))
                .count();
    }

    /**
     * After the human_baseline mode, inspect the written JSON(s) for problems that make
     * this project permanently unusable.  Uses the {@code baselineFailureType} field set
     * by {@link ResolutionVariantRunner#classifyBaseline} — the single source of truth
     * for baseline classification.
     *
     * <p>Aborts the project (throws) when <em>every</em> merge in the project is
     * classified as permanently unusable (INFRA_FAILURE or NO_TESTS).  If at least one
     * merge is viable, the project proceeds and the per-merge skip logic in
     * {@link ResolutionVariantRunner} handles individual merges.
     */
    private void checkBaselineViability(String projectName, List<DatasetReader.MergeInfo> merges,
                                         Path repoPath) throws IOException {
        Path humanBaselineDir = experimentDir().resolve("human_baseline");
        ObjectMapper mapper = new ObjectMapper();

        int checked = 0;
        int permanentlyBroken = 0;
        String lastReason = null;

        for (DatasetReader.MergeInfo info : merges) {
            File jsonFile = humanBaselineDir.resolve(info.getMergeCommit() + AppConfig.JSON).toFile();
            if (!jsonFile.exists()) continue;
            try {
                MergeOutputJSON result = mapper.readValue(jsonFile, MergeOutputJSON.class);
                checked++;
                if (result.isVariantsSkipped()) {
                    permanentlyBroken++;
                    lastReason = result.getBaselineFailureType();
                }
            } catch (IOException e) {
                System.err.println("Warning: could not read baseline JSON " + jsonFile + ": " + e.getMessage());
            }
        }

        if (checked == 0) {
            throw new IOException(projectName + ": no baseline JSON written for any of "
                    + merges.size() + " merge(s) — skipping remaining modes");
        }
        if (permanentlyBroken == checked) {
            throw new IOException(projectName + ": all " + checked + " merge(s) permanently unusable ("
                    + lastReason + ") — skipping remaining modes");
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
        // Also clean the append-only merge log so it doesn't carry stale rows from prior runs
        Path mergeLog = experimentDir().resolve("attempted_merges.csv");
        if (mergeLog.toFile().exists()) {
            mergeLog.toFile().delete();
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
