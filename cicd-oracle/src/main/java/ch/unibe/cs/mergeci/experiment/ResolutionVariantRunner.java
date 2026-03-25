package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.repoCollection.BuildFailureLog;
import ch.unibe.cs.mergeci.repoCollection.BuildFailureLog.MergeFailureType;
import ch.unibe.cs.mergeci.runner.IVariantEvaluator;
import ch.unibe.cs.mergeci.runner.IVariantGeneratorFactory;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import ch.unibe.cs.mergeci.util.RepositoryManager;
import ch.unibe.cs.mergeci.util.Utility;
import com.google.common.io.Files;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.unibe.cs.mergeci.runner.maven.CompilationResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ResolutionVariantRunner {
    private final Path datasetsDir;
    private final Path repoDatasetsFile;
    private final Path tempDir;
    private final RepositoryManager repoManager;

    public ResolutionVariantRunner() {
        this(AppConfig.CONFLICT_DATASET_DIR, AppConfig.MERGE_COMMITS_CSV, AppConfig.REPO_DIR);
    }

    public ResolutionVariantRunner(Path datasetsDir, Path repoDatasetsFile, Path tempDir) {
        this.datasetsDir = datasetsDir;
        this.repoDatasetsFile = repoDatasetsFile;
        this.tempDir = tempDir;
        this.repoManager = new RepositoryManager(tempDir);
    }

    public void runTests(Utility.Experiments ex) {
        try {
            runTests(AppConfig.VARIANT_EXPERIMENT_DIR.resolve(ex.getName()), ex.isParallel(), ex.isCache(), ex.isSkipVariants());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void runTests(Path outputDir, boolean isParallel, boolean isCache) throws Exception {
        runTests(outputDir, isParallel, isCache, false);
    }

    public void runTests(Path outputDir, boolean isParallel, boolean isCache, boolean skipVariants) throws Exception {
        prepareOutputDir(outputDir);

        Path humanBaselineDir = outputDir.getParent().resolve("human_baseline");
        if (!humanBaselineDir.toFile().exists()) humanBaselineDir.toFile().mkdirs();

        File[] xlsxDataset = datasetsDir.toFile().listFiles();
        if (xlsxDataset == null) return;

        printHeader(xlsxDataset, outputDir.getFileName().toString());

        for (File dataset : xlsxDataset) {
            processDataset(dataset, outputDir, humanBaselineDir, isParallel, isCache, skipVariants);
        }
    }

    private void printHeader(File[] datasets, String modeName) {
        int totalProjects = datasets.length;
        int totalMerges = 0;

        for (File dataset : datasets) {
            try {
                totalMerges += new DatasetReader().readMergeDataset(dataset.toPath()).size();
            } catch (Exception e) {
                // skip unreadable dataset
            }
        }

        System.out.println("================================================================================");
        System.out.println("CI/CD Merge Resolver - Variant Experiment Phase");
        System.out.printf("Mode: %s%n", modeName);
        System.out.printf("Total projects: %d | Total merges: %d | Fresh run: %s%n",
                totalProjects, totalMerges, AppConfig.isFreshRun());
        System.out.println("================================================================================\n");
    }

    private void prepareOutputDir(Path outputDir) throws IOException {
        if (AppConfig.isFreshRun() && outputDir.toFile().exists()) {
            System.out.println("FRESH_RUN enabled: Cleaning experiment directory: " + outputDir);
            FileUtils.deleteDirectory(outputDir.toFile());
        }
        if (!outputDir.toFile().exists()) {
            outputDir.toFile().mkdirs();
        }
    }

    private void processDataset(File dataset, Path modeDir, Path humanBaselineDir, boolean isParallel, boolean isCache, boolean skipVariants) throws Exception {
        String repoName = Files.getNameWithoutExtension(dataset.getName());
        Optional<String> repoUrlOpt = Utility.getRepoUrlFromCsv(repoDatasetsFile, repoName);

        if (repoUrlOpt.isEmpty()) {
            System.err.println("Repository URL not found in CSV for: " + repoName + ". Skipping...");
            return;
        }

        Path repoPath;
        try {
            repoPath = repoManager.getRepositoryPath(repoName, repoUrlOpt.get());
        } catch (IOException e) {
            System.err.println("Skipping repository " + repoName + ": " + e.getMessage());
            return;
        }

        try {
            makeAnalysisByDataset(dataset.toPath(), repoPath, modeDir, humanBaselineDir, isParallel, isCache, skipVariants, tempDir, modeDir.getFileName().toString());
            repoManager.markRepositorySuccess(repoName);
        } catch (Exception e) {
            System.err.println("Analysis failed for repository " + repoName + ": " + e.getMessage());
            // Don't mark as rejected - we might want to retry later
            throw e;
        } finally {
            RepositoryCache.clear();
            WindowCache.reconfigure(new WindowCacheConfig());
        }
    }

    /** Load dataset from a CSV file and delegate to {@link #makeAnalysisByMergeList}. */
    public static void makeAnalysisByDataset(Path dataset, Path repoPath, Path modeDir, Path humanBaselineDir,
                                              boolean isParallel, boolean isCache,
                                              boolean skipVariants, Path tmpDir, String modeName) throws Exception {
        List<DatasetReader.MergeInfo> mergeInfos = new DatasetReader().readMergeDataset(dataset);
        System.out.printf("\n→ Testing %d merges from %s\n", mergeInfos.size(), dataset.getFileName().toString());
        String projectName = Files.getNameWithoutExtension(dataset.getFileName().toString());
        makeAnalysisByMergeList(mergeInfos, projectName, repoPath, modeDir, humanBaselineDir,
                isParallel, isCache, skipVariants, tmpDir, modeName, null, null);
    }

    /** Convenience overload used by tests (no generator/evaluator — uses defaults). */
    public static void makeAnalysisByDataset(Path dataset, Path repoPath, Path modeDir, Path humanBaselineDir,
                                              boolean isParallel, boolean isCache) throws Exception {
        makeAnalysisByDataset(dataset, repoPath, modeDir, humanBaselineDir,
                isParallel, isCache, false, AppConfig.TMP_DIR, "");
    }

    /**
     * Run experiments for a list of merges.
     *
     * @param generatorFactory factory that produces one {@link ch.unibe.cs.mergeci.runner.IVariantGenerator}
     *                         per merge; {@code null} falls back to the pre-loaded ML-AR CSV predictions
     * @param evaluator        evaluator to use; {@code null} defaults to {@link ch.unibe.cs.mergeci.runner.MavenVariantEvaluator}
     */
    public static void makeAnalysisByMergeList(List<DatasetReader.MergeInfo> mergeInfos, String projectName,
                                                Path repoPath, Path modeDir, Path humanBaselineDir,
                                                boolean isParallel, boolean isCache, boolean skipVariants,
                                                Path tmpDir, String modeName,
                                                IVariantGeneratorFactory generatorFactory,
                                                IVariantEvaluator evaluator) throws Exception {
        modeDir.toFile().mkdirs();

        Map<String, Long> storedBaselines = skipVariants ? Collections.emptyMap()
                : loadStoredBaselines(humanBaselineDir);

        Set<String> timedOutBaselines = skipVariants ? Collections.emptySet()
                : loadTimedOutBaselines(humanBaselineDir);

        if (!skipVariants) {
            injectFallbackBaselinesForBrokenMerges(mergeInfos, storedBaselines);
        }

        MergeExperimentRunner processor = new MergeExperimentRunner(
                repoPath, tmpDir, isParallel, isCache, skipVariants, storedBaselines,
                generatorFactory, evaluator);
        VariantResultCollector collector = new VariantResultCollector();

        String header = modeName.isEmpty() ? "experiment" : modeName;
        System.out.printf("%n  ── %s  [%s] ──%n", header, projectName);

        BuildFailureLog failureLog = skipVariants
                ? BuildFailureLog.createOrNullAppend(AppConfig.DATA_BASE_DIR.resolve("build_failures.log"))
                : null;

        MergeRunStats stats = processMerges(mergeInfos, processor, collector, modeName, skipVariants,
                timedOutBaselines, failureLog, modeDir, projectName);

        if (failureLog != null) failureLog.close();

        System.out.println(formatSummary(mergeInfos.size(), stats.resultCount(), stats.skippedCount(), stats.totalTime()));
    }

    /**
     * For merges where the human baseline fails to compile ({@code baselineBroken=true}),
     * override their stored baseline with the average of the non-broken merges in the same
     * project, falling back to 300 s when no non-broken baselines are available.
     */
    private static void injectFallbackBaselinesForBrokenMerges(
            List<DatasetReader.MergeInfo> mergeInfos,
            Map<String, Long> storedBaselines) {

        long sum = 0, count = 0;
        for (DatasetReader.MergeInfo info : mergeInfos) {
            if (!info.isBaselineBroken()) {
                Long baseline = storedBaselines.get(info.getMergeCommit());
                if (baseline != null && baseline > 0) {
                    sum += baseline;
                    count++;
                }
            }
        }
        long fallback = count > 0 ? sum / count : 300L;

        for (DatasetReader.MergeInfo info : mergeInfos) {
            if (info.isBaselineBroken()) {
                storedBaselines.put(info.getMergeCommit(), fallback);
            }
        }
    }

    /**
     * Load per-merge humanBaselineSeconds from per-merge JSON files in the human_baseline directory.
     * Returns an empty map if the directory does not exist or cannot be read.
     */
    private static Map<String, Long> loadStoredBaselines(Path humanBaselineDir) {
        if (humanBaselineDir == null || !humanBaselineDir.toFile().exists()) {
            return Collections.emptyMap();
        }
        File[] files = humanBaselineDir.toFile().listFiles((d, name) -> name.endsWith(AppConfig.JSON));
        if (files == null) return Collections.emptyMap();
        Map<String, Long> map = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        for (File file : files) {
            try {
                MergeOutputJSON merge = mapper.readValue(file, MergeOutputJSON.class);
                if (merge.getMergeCommit() != null && merge.getBudgetBasisSeconds() > 0) {
                    map.put(merge.getMergeCommit(), merge.getBudgetBasisSeconds());
                }
            } catch (IOException e) {
                System.err.println("Warning: could not read stored baseline from " + file + ": " + e.getMessage());
            }
        }
        return map;
    }

    /**
     * Load the set of merge commits whose human baseline build timed out.
     */
    private static Set<String> loadTimedOutBaselines(Path humanBaselineDir) {
        if (humanBaselineDir == null || !humanBaselineDir.toFile().exists()) {
            return Collections.emptySet();
        }
        File[] files = humanBaselineDir.toFile().listFiles((d, name) -> name.endsWith(AppConfig.JSON));
        if (files == null) return Collections.emptySet();
        Set<String> timedOut = new HashSet<>();
        ObjectMapper mapper = new ObjectMapper();
        for (File file : files) {
            try {
                MergeOutputJSON merge = mapper.readValue(file, MergeOutputJSON.class);
                if (merge.getMergeCommit() == null) continue;
                CompilationResult baseline = merge.getCompilationResult();
                if (baseline != null && baseline.getBuildStatus() == CompilationResult.Status.TIMEOUT) {
                    timedOut.add(merge.getMergeCommit());
                }
            } catch (IOException e) {
                System.err.println("Warning: could not read timed-out baseline from " + file + ": " + e.getMessage());
            }
        }
        return timedOut;
    }

    private static MergeRunStats processMerges(List<DatasetReader.MergeInfo> mergeInfos,
                                               MergeExperimentRunner processor,
                                               VariantResultCollector collector,
                                               String modeName,
                                               boolean skipVariants,
                                               Set<String> timedOutBaselines,
                                               BuildFailureLog failureLog,
                                               Path modeDir,
                                               String projectName) throws Exception {
        int resultCount = 0;
        int skippedCount = 0;
        long totalTime = 0;

        for (int index = 1; index <= mergeInfos.size(); index++) {
            DatasetReader.MergeInfo info = mergeInfos.get(index - 1);
            System.out.printf("  [merge %d/%d] %s... ", index, mergeInfos.size(), info.getShortCommit());
            System.out.flush();

            // Per-merge resume check
            Path mergeOutputFile = modeDir.resolve(info.getMergeCommit() + AppConfig.JSON);
            if (!AppConfig.isFreshRun() && !AppConfig.isReanalyzeSuccess() && mergeOutputFile.toFile().exists()) {
                System.out.println("SKIPPED (already processed)");
                skippedCount++;
                continue;
            }

            if (timedOutBaselines.contains(info.getMergeCommit())) {
                System.out.println("SKIPPED (baseline timed out)");
                skippedCount++;
                continue;
            }

            MergeExperimentRunner.ProcessedMerge processed = processor.processMerge(info);

            if (processed.wasSkipped()) {
                System.out.println(processed.getSkipReason());
                skippedCount++;
                continue;
            }

            MergeOutputJSON result = skipVariants
                    ? collector.collectBaselineResult(processed)
                    : collector.collectResults(processed);
            result.setMode(modeName);
            result.setProjectName(projectName);
            new JsonResultWriter().writeResult(result, modeDir);
            logBaselineFailure(failureLog, processed);
            totalTime += processed.getAnalysisResult().executionTimeSeconds();
            resultCount++;
            System.out.println("  " + collector.getSuccessSummary(processed));
        }

        return new MergeRunStats(resultCount, skippedCount, totalTime);
    }

    private static void logBaselineFailure(BuildFailureLog failureLog,
                                            MergeExperimentRunner.ProcessedMerge processed) {
        if (failureLog == null) return;
        String projectName = processed.getAnalysisResult().getProjectName();
        String shortCommit = processed.getInfo().getShortCommit();
        CompilationResult baseline = processed.getAnalysisResult().compilationResults().get(projectName);
        if (baseline == null || baseline.getBuildStatus() == null) return;
        switch (baseline.getBuildStatus()) {
            case TIMEOUT  -> failureLog.logMergeFailure(projectName, shortCommit, MergeFailureType.TIMEOUT, "");
            case FAILURE  -> failureLog.logMergeFailure(projectName, shortCommit, MergeFailureType.COMPILE_FAILURE, "");
            case SUCCESS  -> {
                TestTotal tests = processed.getAnalysisResult().testResults().get(projectName);
                if (tests == null || tests.getRunNum() == 0)
                    failureLog.logMergeFailure(projectName, shortCommit, MergeFailureType.NO_TESTS, "");
            }
            default -> {}
        }
    }

    private record MergeRunStats(int resultCount, int skippedCount, long totalTime) {}

    private static String formatSummary(int total, int successful, int skipped, long totalTime) {
        StringBuilder summary = new StringBuilder();
        summary.append("\n  ════════════════════════════════════════\n");
        summary.append(String.format("  Summary: %d/%d merges", successful, total));

        if (skipped > 0) {
            summary.append(String.format(" (%d skipped)", skipped));
        }

        summary.append(String.format(" | Total: %s", formatTime(totalTime)));

        return summary.toString();
    }

    private static String formatTime(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            long mins = seconds / 60;
            long secs = seconds % 60;
            return String.format("%dm %ds", mins, secs);
        } else {
            long hours = seconds / 3600;
            long mins = (seconds % 3600) / 60;
            return String.format("%dh %dm", hours, mins);
        }
    }
}
