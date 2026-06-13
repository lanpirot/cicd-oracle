package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.IVariantEvaluator;
import ch.unibe.cs.mergeci.runner.IVariantGeneratorFactory;
import ch.unibe.cs.mergeci.runner.MavenExecutionFactory;
import ch.unibe.cs.mergeci.runner.OverlayMount;
import ch.unibe.cs.mergeci.runner.maven.MavenCommandResolver;
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
import java.util.List;

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
        System.out.printf( "║  Target merges       : %s%n", limit == Integer.MAX_VALUE ? "unlimited" : limit);
        System.out.printf( "║  Experiment modes    : %s%n", modes);
        System.out.printf( "║  Stop on perfect     : %s%n", stopOnPerfect());
        System.out.printf( "║  Generator           : %s%n",
                generatorFactory() != null ? generatorFactory().getClass().getSimpleName() : "default (heuristic)");
        System.out.printf( "║  Baseline timeout    : %d s%n", AppConfig.MAVEN_BUILD_TIMEOUT);
        System.out.printf( "║  Variant budget      : %s%n", variantBudgetSplashLine());
        System.out.printf( "║  Max threads         : %d (cores − 2; re-computed per merge from measured peak RAM)%n",
                Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
        System.out.printf( "║  Maven daemon        : %s%n", AppConfig.USE_MAVEN_DAEMON);
        System.out.printf( "║  Maven heap          : %s%n", AppConfig.MAVEN_SUBPROCESS_HEAP);
        System.out.printf( "║  Output dir          : %s%n", experimentDir());
        System.out.printf( "║  Repo dir            : %s%n", AppConfig.REPO_DIR);
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    public void run() throws Exception {
        if (AppConfig.isAnalyzeOnly()) {
            System.out.println("analyzeOnly=true — skipping experiments, re-analyzing " + experimentDir());
            analyzeResults();
            return;
        }

        printSplash();

        List<DatasetReader.MergeInfo> allMerges = sampleMerges();
        long distinctProjects = allMerges.stream()
                .map(DatasetReader.MergeInfo::getProjectName).distinct().count();
        System.out.printf("Pipeline: round-robin queue of %d merges across %d projects%n",
                allMerges.size(), distinctProjects);

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
            runMerges(allMerges, mergeLog);
        }

        analyzeResults();
    }

    private void runMerges(List<DatasetReader.MergeInfo> merges,
                           AttemptedMergeLog mergeLog) throws Exception {
        int limit = processedLimit();
        int total = merges.size();
        int idx = 0;
        for (DatasetReader.MergeInfo info : merges) {
            idx++;
            int completed = countBaselineJsons();
            if (completed >= limit) break;

            String projectName = info.getProjectName();
            List<DatasetReader.MergeInfo> singleton = List.of(info);

            // Resume fast-path: this exact merge already has results in every mode, or is
            // recorded as a permanent infra-skip / project failure. Skip the clone /
            // parent-commit RevWalk / per-mode CSV reload churn.
            if (!AppConfig.isFreshRun() && !AppConfig.isReanalyzeSuccess()
                    && allMergesAlreadyAccountedFor(projectName, singleton)) {
                continue;
            }

            System.out.println();
            System.out.println("════════════════════════════════════════════════════════════");
            System.out.printf("  Merge %d/%d (queue): %s — %s%n",
                    idx, total, projectName, shortCommit(info.getMergeCommit()));
            System.out.printf("  Successful so far: %d/%d%n", completed,
                    limit == Integer.MAX_VALUE ? total : limit);
            System.out.println("════════════════════════════════════════════════════════════");

            Path repoPath;
            try {
                repoPath = repoManager.getRepositoryPath(projectName, info.getRemoteUrl());
            } catch (IOException e) {
                System.err.println("Skipping " + projectName + ": " + e.getMessage());
                mergeLog.logProjectFailure(projectName, "clone failed: " + e.getMessage());
                continue;
            }

            try {
                String[] parents = GitUtils.getParentCommits(repoPath, info.getMergeCommit());
                info.setParent1(parents[0]);
                info.setParent2(parents[1]);
                runModes(projectName, singleton, repoPath, mergeLog);
            } catch (Exception e) {
                System.err.println("Analysis failed for " + projectName + ": " + e.getMessage());
                // Record the failure so the resume fast-path skips this merge on the
                // next run instead of re-cloning and re-building it every restart
                // (e.g. a baseline that times out then throws during result parsing).
                // Logged as a human_baseline SKIP with a non-"already processed"
                // reason so loadAttemptedMergesIfNeeded() picks it up as a permanent
                // skip. Commas/newlines are stripped because the CSV resume reader
                // splits naively on ','.
                String reason = "analysis error: "
                        + String.valueOf(e.getMessage()).replace(',', ';').replace('\n', ' ');
                for (DatasetReader.MergeInfo m : singleton) {
                    mergeLog.logSkipped(projectName, m.getMergeCommit(), "human_baseline", reason);
                }
            } finally {
                RepositoryCache.clear();
                WindowCache.reconfigure(new WindowCacheConfig());
            }
        }
        int processed = countBaselineJsons();

        if (limit < Integer.MAX_VALUE) {
            System.out.printf("Pipeline finished: %d/%d successful merges.%n", processed, limit);
        }
    }

    private static String shortCommit(String full) {
        return full == null || full.length() < 8 ? String.valueOf(full) : full.substring(0, 8) + "...";
    }

    private static String variantBudgetSplashLine() {
        long capProbe = AppConfig.variantBudget(Long.MAX_VALUE / 20);
        return capProbe == Long.MAX_VALUE / 20 * 10
                ? "max(300s, baseline×10)"
                : String.format("max(300s, baseline×10), capped at %d s", capProbe);
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
        System.out.println("================================================================================");
        System.out.println("CI/CD Merge Resolver - Presentation Phase");
        System.out.println("================================================================================");

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

            try {
                ResolutionVariantRunner.makeAnalysisByMergeList(
                        merges, projectName, repoPath, modeDir, humanBaselineDir,
                        ex.isParallel(), ex.isCache(), ex.isSkipVariants(),
                        AppConfig.TMP_DIR, ex.getName(),
                        generatorFactory(), evaluator(), stopOnPerfect(), mergeLog);
            } finally {
                // Kill mvnd daemons after each (project, mode) pair, *including*
                // human_baseline. Within a mode we reuse one daemon per worker thread
                // (unique maven.repo.local per thread slot), but at the mode boundary
                // we wipe the slate clean so memory stays bounded across modes and
                // overlays can be unmounted without daemon FDs keeping them busy.
                //
                // Killing after human_baseline matters: the baseline build spawns an
                // mvnd daemon that would otherwise idle (3 h timeout) through the
                // entire no_optimization phase, holding ~500 MB resident for nothing.
                new MavenCommandResolver(AppConfig.USE_MAVEN_DAEMON).stopDaemons();

                // Wipe the shared maven-build-cache so it doesn't bleed into the next mode.
                FileUtils.deleteQuietly(AppConfig.SHARED_CACHE_DIR.toFile());

                // Sweep the overlay temp dirs: the donor variant's project overlay
                // and the last-variant-per-thread overlays are intentionally kept alive
                // during a mode (donor preserved by design; last-variant busy because
                // the daemon just finished it). With daemons dead, those mounts are now
                // cleanly unmountable. human_baseline doesn't create variant overlays,
                // so skip the sweep there.
                if (!ex.isSkipVariants()) {
                    OverlayMount.cleanupStaleMounts(AppConfig.OVERLAY_TMP_DIR.resolve("projects"));
                    OverlayMount.cleanupStaleMounts(MavenExecutionFactory.M2_OVERLAY_DIR);
                }
            }

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

    /** Lazy cache of merge commits with a terminal human_baseline SKIP in a prior pass
     *  (infra failure, broken baseline, analysis error — not the in-run "already
     *  processed" dedup marker). Re-running these is guaranteed waste. Populated once
     *  on the first project that needs the resume fast-path. */
    private java.util.Set<String> skippedMerges;
    /** Lazy cache of merge commits with a human_baseline PROCESSED row. A PROCESSED row
     *  alone does NOT prove the variant modes ran — the JVM may have died between the
     *  baseline write and the variant phase — so it only short-circuits the orphan case
     *  where {@link #removeOrphanedBaselines} deliberately deleted the baseline JSON
     *  (no variant mode produced a result); otherwise the per-mode JSON existence check
     *  decides. */
    private java.util.Set<String> processedMerges;
    /** Lazy cache of project names that PROJECT_FAILURE'd (typically clone timeout) — re-attempting
     *  is guaranteed to fail again until we stop using GitHub. */
    private java.util.Set<String> projectFailures;

    private void loadAttemptedMergesIfNeeded() {
        if (skippedMerges != null) return;
        skippedMerges = new java.util.HashSet<>();
        processedMerges = new java.util.HashSet<>();
        projectFailures = new java.util.HashSet<>();
        Path csv = experimentDir().resolve("attempted_merges.csv");
        if (!csv.toFile().exists()) return;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(csv.toFile()))) {
            String line; r.readLine(); // header
            while ((line = r.readLine()) != null) {
                String[] cols = line.split(",", -1);
                if (cols.length < 6) continue;
                String project = cols[1], mergeCommit = cols[2], mode = cols[3], verdict = cols[4], reason = cols[5];
                if ("PROJECT_FAILURE".equals(verdict) && !project.isEmpty()) {
                    projectFailures.add(project);
                } else if ("human_baseline".equals(mode) && !mergeCommit.isEmpty()) {
                    if ("SKIPPED".equals(verdict) && !reason.contains("already processed")) {
                        skippedMerges.add(mergeCommit);
                    } else if ("PROCESSED".equals(verdict)) {
                        processedMerges.add(mergeCommit);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read " + csv + ": " + e.getMessage());
        }
    }

    /** Returns true iff every merge was already run to a terminal state in a prior pass:
     *  a real baseline SKIP, a PROCESSED baseline whose JSON was deliberately
     *  orphan-removed, a JSON in every mode, or a whole-project clone-timeout /
     *  PROJECT_FAILURE. A PROCESSED baseline row with its JSON still present is NOT
     *  terminal by itself — a crash between the baseline and the variant modes leaves
     *  exactly that state, and the merge must be re-offered so the missing modes run
     *  (the baseline mode itself resumes from its JSON). */
    boolean allMergesAlreadyAccountedFor(String projectName,
                                         List<DatasetReader.MergeInfo> merges) {
        loadAttemptedMergesIfNeeded();
        if (projectFailures.contains(projectName)) return true;
        for (DatasetReader.MergeInfo info : merges) {
            String mergeCommit = info.getMergeCommit();
            if (skippedMerges.contains(mergeCommit)) continue;
            String filename = mergeCommit + AppConfig.JSON;
            if (processedMerges.contains(mergeCommit)
                    && !experimentDir().resolve("human_baseline").resolve(filename).toFile().exists()) {
                // Orphan-removed: baseline ran but every variant mode declined the
                // merge, so removeOrphanedBaselines deleted the JSON. Deliberate.
                continue;
            }
            for (Utility.Experiments ex : modesToRun()) {
                if (!experimentDir().resolve(ex.getName()).resolve(filename).toFile().exists()) {
                    return false;
                }
            }
        }
        return true;
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

}
