package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.conflict.BuildFailureClassifier;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
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
import java.util.TreeMap;

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
     * @param stopOnPerfect    when {@code false}, variant generation continues even after a perfect variant
     *                         is found (required for fair RQ2 comparisons)
     */
    public static void makeAnalysisByMergeList(List<DatasetReader.MergeInfo> mergeInfos, String projectName,
                                                Path repoPath, Path modeDir, Path humanBaselineDir,
                                                boolean isParallel, boolean isCache, boolean skipVariants,
                                                Path tmpDir, String modeName,
                                                IVariantGeneratorFactory generatorFactory,
                                                IVariantEvaluator evaluator) throws Exception {
        makeAnalysisByMergeList(mergeInfos, projectName, repoPath, modeDir, humanBaselineDir,
                isParallel, isCache, skipVariants, tmpDir, modeName, generatorFactory, evaluator, true);
    }

    public static void makeAnalysisByMergeList(List<DatasetReader.MergeInfo> mergeInfos, String projectName,
                                                Path repoPath, Path modeDir, Path humanBaselineDir,
                                                boolean isParallel, boolean isCache, boolean skipVariants,
                                                Path tmpDir, String modeName,
                                                IVariantGeneratorFactory generatorFactory,
                                                IVariantEvaluator evaluator,
                                                boolean stopOnPerfect) throws Exception {
        modeDir.toFile().mkdirs();

        Map<String, Long> storedBaselines = skipVariants ? Collections.emptyMap()
                : loadStoredBaselines(humanBaselineDir);

        Map<String, String> skippedBaselines = skipVariants ? Collections.emptyMap()
                : loadSkippedBaselines(humanBaselineDir);

        if (!skipVariants) {
            markBrokenBaselines(mergeInfos, humanBaselineDir);
            injectFallbackBaselinesForBrokenMerges(mergeInfos, storedBaselines);
        }

        MergeExperimentRunner processor = new MergeExperimentRunner(
                repoPath, tmpDir, isParallel, isCache, skipVariants, storedBaselines,
                generatorFactory, evaluator, stopOnPerfect);
        VariantResultCollector collector = new VariantResultCollector();

        Map<String, Map<String, List<String>>> groundTruthPatterns = skipVariants
                ? loadGroundTruthPatterns(AppConfig.ALL_CONFLICTS_CSV)
                : Collections.emptyMap();

        String header = modeName.isEmpty() ? "experiment" : modeName;
        System.out.printf("%n  ── %s  [%s] ──%n", header, projectName);

        BuildFailureLog failureLog = skipVariants
                ? BuildFailureLog.createOrNullAppend(AppConfig.DATA_BASE_DIR.resolve("build_failures.log"))
                : null;

        MergeRunStats stats = processMerges(mergeInfos, processor, collector, modeName, skipVariants,
                skippedBaselines, failureLog, modeDir, projectName, groundTruthPatterns);

        if (failureLog != null) failureLog.close();

        System.out.println(formatSummary(mergeInfos.size(), stats.resultCount(), stats.skippedCount(), stats.totalTime()));
    }

    /**
     * Detect broken baselines from human_baseline JSON files and set {@code baselineBroken=true}
     * on the corresponding {@link DatasetReader.MergeInfo} objects.  This is necessary because
     * the merge list may come from a CSV that lacks a {@code baselineBroken} column (e.g.
     * {@code merge_commits.csv}), so the flag would otherwise stay {@code false}.
     */
    private static void markBrokenBaselines(List<DatasetReader.MergeInfo> mergeInfos, Path humanBaselineDir) {
        if (humanBaselineDir == null || !humanBaselineDir.toFile().exists()) return;
        File[] files = humanBaselineDir.toFile().listFiles((d, name) -> name.endsWith(AppConfig.JSON));
        if (files == null) return;

        Set<String> brokenCommits = new HashSet<>();
        ObjectMapper mapper = new ObjectMapper();
        for (File file : files) {
            try {
                MergeOutputJSON merge = mapper.readValue(file, MergeOutputJSON.class);
                if (merge.getMergeCommit() == null) continue;
                CompilationResult baseline = merge.getCompilationResult();
                if (baseline != null && baseline.getBuildStatus() == CompilationResult.Status.FAILURE) {
                    brokenCommits.add(merge.getMergeCommit());
                }
            } catch (IOException e) {
                System.err.println("Warning: could not read baseline status from " + file + ": " + e.getMessage());
            }
        }

        for (DatasetReader.MergeInfo info : mergeInfos) {
            if (brokenCommits.contains(info.getMergeCommit())) {
                info.setBaselineBroken(true);
            }
        }
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
        long fallback = count > 0 ? sum / count : AppConfig.MAVEN_BUILD_TIMEOUT;

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
     * Load the set of merge commits that should be skipped in variant modes, together with
     * their failure type for consistent CLI logging.  A merge is skipped when:
     * <ul>
     *   <li>{@code TIMEOUT} — baseline build did not finish in time</li>
     *   <li>{@code INFRA_FAILURE} — permanently broken infrastructure (dead repo, missing tool);
     *       not set when build-file conflict markers are present (reclassified to BROKEN_MERGE)</li>
     *   <li>{@code NO_TESTS} — baseline compiled but 0 tests ran, so variant quality cannot
     *       be measured.  Only skipped when the baseline is not broken — a broken baseline that
     *       ran 0 tests may still produce tests once a variant fixes the compilation.</li>
     * </ul>
     */
    private static Map<String, String> loadSkippedBaselines(Path humanBaselineDir) {
        if (humanBaselineDir == null || !humanBaselineDir.toFile().exists()) {
            return Collections.emptyMap();
        }
        File[] files = humanBaselineDir.toFile().listFiles((d, name) -> name.endsWith(AppConfig.JSON));
        if (files == null) return Collections.emptyMap();

        Map<String, String> skipped = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        for (File file : files) {
            try {
                MergeOutputJSON merge = mapper.readValue(file, MergeOutputJSON.class);
                if (merge.getMergeCommit() == null || merge.getBaselineFailureType() == null) continue;
                String type = merge.getBaselineFailureType();
                switch (type) {
                    case "TIMEOUT", "INFRA_FAILURE" -> skipped.put(merge.getMergeCommit(), type);
                    case "NO_TESTS" -> {
                        // Only skip when baseline compiled — broken baselines may unlock tests via variants
                        if (!merge.isBaselineBroken()) {
                            skipped.put(merge.getMergeCommit(), type);
                        }
                    }
                    default -> { /* BROKEN_MERGE, COMPILE_FAILURE — variants may fix these */ }
                }
            } catch (IOException e) {
                System.err.println("Warning: could not read baseline from " + file + ": " + e.getMessage());
            }
        }
        return skipped;
    }

    private static final Map<String, String> SKIP_REASONS = Map.of(
            "TIMEOUT", "baseline timed out",
            "INFRA_FAILURE", "infrastructure failure",
            "NO_TESTS", "baseline ran 0 tests"
    );

    private static MergeRunStats processMerges(List<DatasetReader.MergeInfo> mergeInfos,
                                               MergeExperimentRunner processor,
                                               VariantResultCollector collector,
                                               String modeName,
                                               boolean skipVariants,
                                               Map<String, String> skippedBaselines,
                                               BuildFailureLog failureLog,
                                               Path modeDir,
                                               String projectName,
                                               Map<String, Map<String, List<String>>> groundTruthPatterns) throws Exception {
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

            // Skip merges whose baseline is permanently unusable (timeout, infra failure, no tests)
            String skipType = skippedBaselines.get(info.getMergeCommit());
            if (skipType != null) {
                System.out.println("SKIPPED (" + SKIP_REASONS.getOrDefault(skipType, skipType) + ")");
                skippedCount++;
                continue;
            }

            MergeExperimentRunner.ProcessedMerge processed = processor.processMerge(info);

            if (processed.wasSkipped()) {
                System.out.println(processed.getSkipReason());
                skippedCount++;
                continue;
            }

            Map<String, List<String>> mergeGroundTruth = groundTruthPatterns.get(info.getMergeId());
            MergeOutputJSON result = skipVariants
                    ? collector.collectBaselineResult(processed, mergeGroundTruth)
                    : collector.collectResults(processed);
            result.setMode(modeName);
            result.setProjectName(projectName);
            classifyBaseline(result, processed);
            new JsonResultWriter().writeResult(result, modeDir);
            logBaselineFailure(failureLog, result, info.getShortCommit());
            totalTime += processed.getAnalysisResult().executionTimeSeconds();
            resultCount++;
            System.out.println("  " + collector.getSuccessSummary(processed));
        }

        return new MergeRunStats(resultCount, skippedCount, totalTime);
    }

    /**
     * Classify the baseline build outcome and set {@code baselineBroken},
     * {@code baselineFailureType}, and {@code buildFileConflictMarkers} on the JSON output.
     *
     * <p>When an infra failure coincides with conflict markers in build descriptor files
     * (pom.xml, package.json, *.gradle), the failure is reclassified as {@code BROKEN_MERGE}
     * because a variant that resolves the build file conflict differently may produce a
     * clean build.
     */
    private static void classifyBaseline(MergeOutputJSON output,
                                          MergeExperimentRunner.ProcessedMerge processed) {
        String projectName = processed.getAnalysisResult().getProjectName();
        Path repoPath = processed.getAnalysisResult().analyzer().getRepositoryPath();
        CompilationResult baseline = processed.getAnalysisResult().compilationResults().get(projectName);
        if (baseline == null || baseline.getBuildStatus() == null) return;

        boolean hasBuildFileMarkers = BuildFailureClassifier.hasBuildFileConflictMarkers(repoPath);
        output.setBuildFileConflictMarkers(hasBuildFileMarkers);

        switch (baseline.getBuildStatus()) {
            case TIMEOUT -> {
                output.setBaselineBroken(true);
                output.setBaselineFailureType(MergeFailureType.TIMEOUT.name());
            }
            case FAILURE -> {
                output.setBaselineBroken(true);
                Path compilationLog = AppConfig.TMP_DIR.resolve("log")
                        .resolve(projectName + "_compilation");
                if (BuildFailureClassifier.isInfraFailure(compilationLog)) {
                    // Infra failure caused by conflicted build files is fixable by a variant
                    output.setBaselineFailureType(hasBuildFileMarkers
                            ? MergeFailureType.BROKEN_MERGE.name()
                            : MergeFailureType.INFRA_FAILURE.name());
                } else if (BuildFailureClassifier.isGenuineCompilationError(compilationLog)) {
                    output.setBaselineFailureType(MergeFailureType.BROKEN_MERGE.name());
                } else {
                    output.setBaselineFailureType(MergeFailureType.COMPILE_FAILURE.name());
                }
            }
            case SUCCESS -> {
                TestTotal tests = processed.getAnalysisResult().testResults().get(projectName);
                if (tests == null || tests.getRunNum() == 0) {
                    output.setBaselineFailureType(MergeFailureType.NO_TESTS.name());
                }
            }
        }
    }

    /**
     * Write the baseline failure classification to the build_failures.log file.
     * Reads from the already-classified {@code MergeOutputJSON} to stay consistent
     * with JSON output and skip decisions.
     */
    private static void logBaselineFailure(BuildFailureLog failureLog,
                                            MergeOutputJSON result,
                                            String shortCommit) {
        if (failureLog == null || result.getBaselineFailureType() == null) return;
        MergeFailureType type = MergeFailureType.valueOf(result.getBaselineFailureType());
        failureLog.logMergeFailure(result.getProjectName(), shortCommit, type, "");
    }

    /**
     * Load ground-truth conflict patterns from all_conflicts.csv.
     * Returns mergeId → (filename → patterns ordered by chunkIndex).
     * Returns an empty map if the file is absent or unreadable.
     */
    static Map<String, Map<String, List<String>>> loadGroundTruthPatterns(Path csvPath) {
        if (csvPath == null || !csvPath.toFile().exists()) {
            System.err.println("Warning: all_conflicts.csv not found at " + csvPath + " — baseline conflictPatterns will be null");
            return Collections.emptyMap();
        }
        // mergeId → filename → (chunkIndex → normalizedPattern)
        Map<String, Map<String, Map<Integer, String>>> raw = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(csvPath.toFile()))) {
            String headerLine = reader.readLine();
            if (headerLine == null) return Collections.emptyMap();
            String[] headers = headerLine.split(",", -1);
            int idxMergeId = indexOf(headers, "merge_id");
            int idxFilename = indexOf(headers, "filename");
            int idxChunkIndex = indexOf(headers, "chunkIndex");
            int idxLabel = indexOf(headers, "y_conflictResolutionResult");
            if (idxMergeId < 0 || idxFilename < 0 || idxChunkIndex < 0 || idxLabel < 0) {
                System.err.println("Warning: all_conflicts.csv missing required columns — baseline conflictPatterns will be null");
                return Collections.emptyMap();
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] fields = DatasetReader.parseCsvLine(line);
                if (fields.length <= idxLabel) continue;
                String mergeId = fields[idxMergeId].trim();
                String filename = fields[idxFilename].trim();
                String rawLabel = fields[idxLabel].trim();
                int chunkIdx;
                try { chunkIdx = Integer.parseInt(fields[idxChunkIndex].trim()); }
                catch (NumberFormatException e) { continue; }
                String pattern = normalizeGroundTruthLabel(rawLabel);
                raw.computeIfAbsent(mergeId, k -> new HashMap<>())
                   .computeIfAbsent(filename, k -> new TreeMap<>())
                   .put(chunkIdx, pattern);
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read all_conflicts.csv: " + e.getMessage());
            return Collections.emptyMap();
        }
        // Flatten: for each merge/file, ordered list of patterns (TreeMap already sorted by chunkIndex)
        Map<String, Map<String, List<String>>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, Map<Integer, String>>> mergeEntry : raw.entrySet()) {
            Map<String, List<String>> fileMap = new HashMap<>();
            for (Map.Entry<String, Map<Integer, String>> fileEntry : mergeEntry.getValue().entrySet()) {
                fileMap.put(fileEntry.getKey(), new ArrayList<>(fileEntry.getValue().values()));
            }
            result.put(mergeEntry.getKey(), fileMap);
        }
        return result;
    }

    private static String normalizeGroundTruthLabel(String raw) {
        return raw.replace("CHUNK_", "")
                  .replace("CANONICAL_", "")
                  .replace("SEMICANONICAL_", "")
                  .replace("NONCANONICAL", "NON")
                  .replace("SEMI", "");
    }

    private static int indexOf(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].trim().equalsIgnoreCase(name)) return i;
        }
        return -1;
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
