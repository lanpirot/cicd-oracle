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
import java.util.ArrayList;
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

        printHeader(xlsxDataset, outputDir, outputDir.getFileName().toString());

        for (File dataset : xlsxDataset) {
            processDataset(dataset, outputDir, humanBaselineDir, isParallel, isCache, skipVariants);
        }
    }

    private void printHeader(File[] datasets, Path outputDir, String modeName) {
        int totalProjects = datasets.length;
        int pendingProjects = 0;
        int totalMerges = 0;

        for (File dataset : datasets) {
            String repoName = Files.getNameWithoutExtension(dataset.getName());
            Path jsonOutputPath = outputDir.resolve(repoName + AppConfig.JSON);
            boolean pending = AppConfig.isFreshRun() || AppConfig.isReanalyzeSuccess() || !jsonOutputPath.toFile().exists();
            if (pending) pendingProjects++;

            try {
                totalMerges += new DatasetReader().readMergeDataset(dataset.toPath()).size();
            } catch (Exception e) {
                // skip unreadable dataset
            }
        }

        System.out.println("================================================================================");
        System.out.println("CI/CD Merge Resolver - Variant Experiment Phase");
        System.out.printf("Mode: %s%n", modeName);
        System.out.printf("Total projects: %d | Pending: %d | Total merges: %d | Fresh run: %s%n",
                totalProjects, pendingProjects, totalMerges, AppConfig.isFreshRun());
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

    private void processDataset(File dataset, Path outputDir, Path humanBaselineDir, boolean isParallel, boolean isCache, boolean skipVariants) throws Exception {
        String repoName = Files.getNameWithoutExtension(dataset.getName());
        Optional<String> repoUrlOpt = Utility.getRepoUrlFromCsv(repoDatasetsFile, repoName);

        if (repoUrlOpt.isEmpty()) {
            System.err.println("Repository URL not found in CSV for: " + repoName + ". Skipping...");
            return;
        }

        Path jsonOutputPath = outputDir.resolve(repoName + AppConfig.JSON);
        if (!AppConfig.isFreshRun() && !AppConfig.isReanalyzeSuccess() && jsonOutputPath.toFile().exists()) {
            System.out.printf("File %s already exists. Skipping...\n", jsonOutputPath.getFileName());
            return;
        }

        Path humanBaselineOutputPath = humanBaselineDir.resolve(repoName + AppConfig.JSON);

        Path repoPath;
        try {
            repoPath = repoManager.getRepositoryPath(repoName, repoUrlOpt.get());
        } catch (IOException e) {
            System.err.println("Skipping repository " + repoName + ": " + e.getMessage());
            return;
        }

        try {
            makeAnalysisByDataset(dataset.toPath(), repoPath, jsonOutputPath, humanBaselineOutputPath, isParallel, isCache, skipVariants, tempDir, outputDir.getFileName().toString());
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
    public static void makeAnalysisByDataset(Path dataset, Path repoPath, Path output, Path humanBaselineOutput,
                                              boolean isParallel, boolean isCache,
                                              boolean skipVariants, Path tmpDir, String modeName) throws Exception {
        List<DatasetReader.MergeInfo> mergeInfos = new DatasetReader().readMergeDataset(dataset);
        System.out.printf("\n→ Testing %d merges from %s\n", mergeInfos.size(), dataset.getFileName().toString());
        String projectName = Files.getNameWithoutExtension(dataset.getFileName().toString());
        makeAnalysisByMergeList(mergeInfos, projectName, repoPath, output, humanBaselineOutput,
                isParallel, isCache, skipVariants, tmpDir, modeName, null, null);
    }

    /** Convenience overload used by tests (no generator/evaluator — uses defaults). */
    public static void makeAnalysisByDataset(Path dataset, Path repoPath, Path output, Path humanBaselineOutput,
                                              boolean isParallel, boolean isCache) throws Exception {
        makeAnalysisByDataset(dataset, repoPath, output, humanBaselineOutput,
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
                                                Path repoPath, Path output, Path humanBaselineOutput,
                                                boolean isParallel, boolean isCache, boolean skipVariants,
                                                Path tmpDir, String modeName,
                                                IVariantGeneratorFactory generatorFactory,
                                                IVariantEvaluator evaluator) throws Exception {
        Map<String, Long> storedBaselines = skipVariants ? Collections.emptyMap()
                : loadStoredBaselines(humanBaselineOutput);
        boolean hasStoredBaselines = !storedBaselines.isEmpty();

        Set<String> timedOutBaselines = skipVariants ? Collections.emptySet()
                : loadTimedOutBaselines(humanBaselineOutput);

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

        MergeRunStats stats = processMerges(mergeInfos, processor, collector, modeName, timedOutBaselines, failureLog);

        if (failureLog != null) failureLog.close();

        System.out.println(formatSummary(mergeInfos.size(), stats.results().size(), stats.skippedCount(), stats.totalTime()));

        new JsonResultWriter().writeResults(projectName, stats.results(), output);
        if (!hasStoredBaselines) {
            new JsonResultWriter().writeResults(projectName, stats.baselineResults(), humanBaselineOutput);
        }
    }

    /**
     * For merges where the human baseline fails to compile ({@code baselineBroken=true}),
     * override their stored baseline with the average of the non-broken merges in the same
     * project, falling back to 300 s when no non-broken baselines are available.
     * This gives broken-baseline merges a fair variant budget instead of inheriting a tiny
     * value from a failed compilation attempt.
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
     * Load per-merge humanBaselineSeconds from an existing human_baseline JSON file.
     * Returns an empty map if the file does not exist or cannot be read.
     */
    private static Map<String, Long> loadStoredBaselines(Path humanBaselineOutput) {
        if (humanBaselineOutput == null || !humanBaselineOutput.toFile().exists()) {
            return Collections.emptyMap();
        }
        try {
            AllMergesJSON allMerges = new ObjectMapper().readValue(humanBaselineOutput.toFile(), AllMergesJSON.class);
            if (allMerges.getMerges() == null) return Collections.emptyMap();
            Map<String, Long> map = new HashMap<>();
            for (MergeOutputJSON merge : allMerges.getMerges()) {
                if (merge.getMergeCommit() != null && merge.getBudgetBasisSeconds() > 0) {
                    map.put(merge.getMergeCommit(), merge.getBudgetBasisSeconds());
                }
            }
            return map;
        } catch (IOException e) {
            System.err.println("Warning: could not read stored baselines from " + humanBaselineOutput + ": " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Load the set of merge commits whose human baseline build timed out.
     * These merges should be skipped in all other experiment modes to avoid
     * running variants that will also exceed the time budget.
     */
    private static Set<String> loadTimedOutBaselines(Path humanBaselineOutput) {
        if (humanBaselineOutput == null || !humanBaselineOutput.toFile().exists()) {
            return Collections.emptySet();
        }
        try {
            AllMergesJSON allMerges = new ObjectMapper().readValue(humanBaselineOutput.toFile(), AllMergesJSON.class);
            if (allMerges.getMerges() == null) return Collections.emptySet();
            Set<String> timedOut = new HashSet<>();
            for (MergeOutputJSON merge : allMerges.getMerges()) {
                if (merge.getMergeCommit() == null) continue;
                CompilationResult baseline = merge.getCompilationResult();
                if (baseline != null && baseline.getBuildStatus() == CompilationResult.Status.TIMEOUT) {
                    timedOut.add(merge.getMergeCommit());
                }
            }
            return timedOut;
        } catch (IOException e) {
            System.err.println("Warning: could not read timed-out baselines from " + humanBaselineOutput + ": " + e.getMessage());
            return Collections.emptySet();
        }
    }

    private static MergeRunStats processMerges(List<DatasetReader.MergeInfo> mergeInfos,
                                               MergeExperimentRunner processor,
                                               VariantResultCollector collector,
                                               String modeName,
                                               Set<String> timedOutBaselines,
                                               BuildFailureLog failureLog) throws Exception {
        List<MergeOutputJSON> results = new ArrayList<>();
        List<MergeOutputJSON> baselineResults = new ArrayList<>();
        int skippedCount = 0;
        long totalTime = 0;

        for (int index = 1; index <= mergeInfos.size(); index++) {
            DatasetReader.MergeInfo info = mergeInfos.get(index - 1);
            System.out.printf("  [merge %d/%d] %s... ", index, mergeInfos.size(), info.getShortCommit());
            System.out.flush();

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

            MergeOutputJSON result = collector.collectResults(processed);
            result.setMode(modeName);
            results.add(result);
            MergeOutputJSON baselineResult = collector.collectBaselineResult(processed);
            baselineResults.add(baselineResult);
            logBaselineFailure(failureLog, processed);
            totalTime += processed.getAnalysisResult().executionTimeSeconds();
            System.out.println("  " + collector.getSuccessSummary(processed));
        }

        return new MergeRunStats(results, baselineResults, skippedCount, totalTime);
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

    private record MergeRunStats(List<MergeOutputJSON> results, List<MergeOutputJSON> baselineResults, int skippedCount, long totalTime) {}

    /**
     * Format a summary line for the dataset processing results.
     */
    private static String formatSummary(int total, int successful, int skipped, long totalTime) {
        StringBuilder summary = new StringBuilder();
        summary.append("\n  ════════════════════════════════════════\n");
        summary.append(String.format("  Summary: %d/%d merges", successful, total));

        if (skipped > 0) {
            summary.append(String.format(" (%d skipped: baseline timed out)", skipped));
        }

        summary.append(String.format(" | Total: %s", formatTime(totalTime)));

        return summary.toString();
    }

    /**
     * Format time in a human-readable way (seconds, minutes, or hours).
     */
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
