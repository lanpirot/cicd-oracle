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
        this(AppConfig.CONFLICT_DATASET_DIR, AppConfig.MAVEN_CONFLICTS_CSV, AppConfig.REPO_DIR);
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
                isParallel, isCache, skipVariants, tmpDir, modeName, generatorFactory, evaluator, true, null);
    }

    public static void makeAnalysisByMergeList(List<DatasetReader.MergeInfo> mergeInfos, String projectName,
                                                Path repoPath, Path modeDir, Path humanBaselineDir,
                                                boolean isParallel, boolean isCache, boolean skipVariants,
                                                Path tmpDir, String modeName,
                                                IVariantGeneratorFactory generatorFactory,
                                                IVariantEvaluator evaluator,
                                                boolean stopOnPerfect) throws Exception {
        makeAnalysisByMergeList(mergeInfos, projectName, repoPath, modeDir, humanBaselineDir,
                isParallel, isCache, skipVariants, tmpDir, modeName, generatorFactory, evaluator,
                stopOnPerfect, null);
    }

    public static void makeAnalysisByMergeList(List<DatasetReader.MergeInfo> mergeInfos, String projectName,
                                                Path repoPath, Path modeDir, Path humanBaselineDir,
                                                boolean isParallel, boolean isCache, boolean skipVariants,
                                                Path tmpDir, String modeName,
                                                IVariantGeneratorFactory generatorFactory,
                                                IVariantEvaluator evaluator,
                                                boolean stopOnPerfect,
                                                AttemptedMergeLog mergeLog) throws Exception {
        modeDir.toFile().mkdirs();

        StoredBaselines stored = skipVariants
                ? new StoredBaselines(Collections.emptyMap(), Collections.emptyMap())
                : loadStoredBaselines(humanBaselineDir);

        Map<String, String> skippedBaselines = skipVariants ? Collections.emptyMap()
                : loadSkippedBaselines(humanBaselineDir, mergeInfos);

        if (!skipVariants) {
            markBrokenBaselines(mergeInfos, humanBaselineDir);
        }

        MergeExperimentRunner processor = new MergeExperimentRunner(
                repoPath, tmpDir, isParallel, isCache, skipVariants, stored.seconds(),
                stored.peakRamBytes(), generatorFactory, evaluator, stopOnPerfect);
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
                skippedBaselines, failureLog, modeDir, humanBaselineDir, projectName, groundTruthPatterns,
                mergeLog);

        if (failureLog != null) failureLog.close();

        System.out.println(formatSummary(mergeInfos.size(), stats.resultCount(), stats.skippedCount(), stats.totalTime()));
    }

    /**
     * Detect broken baselines from human_baseline JSON files and set {@code baselineBroken=true}
     * on the corresponding {@link DatasetReader.MergeInfo} objects.  This is necessary because
     * the merge list may come from a CSV that lacks a {@code baselineBroken} column (e.g.
     * {@code maven_conflicts.csv}), so the flag would otherwise stay {@code false}.
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

    /** Stored baseline info loaded from human_baseline JSON files. */
    private record StoredBaselines(Map<String, Long> seconds, Map<String, Long> peakRamBytes) {}

    /**
     * Load per-merge humanBaselineSeconds and peakBaselineRamBytes from human_baseline JSON files.
     * Returns empty maps if the directory does not exist or cannot be read.
     */
    private static StoredBaselines loadStoredBaselines(Path humanBaselineDir) {
        if (humanBaselineDir == null || !humanBaselineDir.toFile().exists()) {
            return new StoredBaselines(Collections.emptyMap(), Collections.emptyMap());
        }
        File[] files = humanBaselineDir.toFile().listFiles((d, name) -> name.endsWith(AppConfig.JSON));
        if (files == null) return new StoredBaselines(Collections.emptyMap(), Collections.emptyMap());
        Map<String, Long> seconds = new HashMap<>();
        Map<String, Long> peakRam = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        for (File file : files) {
            try {
                MergeOutputJSON merge = mapper.readValue(file, MergeOutputJSON.class);
                if (merge.getMergeCommit() != null && merge.getBudgetBasisSeconds() > 0) {
                    seconds.put(merge.getMergeCommit(), merge.getBudgetBasisSeconds());
                    if (merge.getPeakBaselineRamBytes() > 0) {
                        peakRam.put(merge.getMergeCommit(), merge.getPeakBaselineRamBytes());
                    }
                }
            } catch (IOException e) {
                System.err.println("Warning: could not read stored baseline from " + file + ": " + e.getMessage());
            }
        }
        return new StoredBaselines(seconds, peakRam);
    }

    /**
     * Load the set of merge commits that should be skipped in variant modes.
     * Reads the {@code variantsSkipped} flag from human_baseline JSONs (set by
     * {@link #classifyBaseline}).  Merges with no human_baseline JSON at all
     * (skipped or errored during baseline mode) are also included.
     */
    private static Map<String, String> loadSkippedBaselines(Path humanBaselineDir,
                                                               List<DatasetReader.MergeInfo> mergeInfos) {
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
                if (merge.getMergeCommit() == null) continue;
                if (merge.isVariantsSkipped()) {
                    String reason = merge.getBaselineFailureType() != null
                            ? merge.getBaselineFailureType() : "VARIANTS_SKIPPED";
                    skipped.put(merge.getMergeCommit(), reason);
                }
            } catch (IOException e) {
                System.err.println("Warning: could not read baseline from " + file + ": " + e.getMessage());
            }
        }

        // Merges with no human_baseline JSON were skipped/errored during baseline mode —
        // no point running variants for them either.  Only applies when the baseline
        // directory has at least one JSON (i.e. the baseline mode actually ran);
        // an empty directory means this is a standalone run, not a restart.
        if (files.length > 0) {
            for (DatasetReader.MergeInfo info : mergeInfos) {
                String mc = info.getMergeCommit();
                if (!skipped.containsKey(mc)) {
                    Path baselineJson = humanBaselineDir.resolve(mc + AppConfig.JSON);
                    if (!baselineJson.toFile().exists()) {
                        skipped.put(mc, "NO_BASELINE");
                    }
                }
            }
        }

        return skipped;
    }

    /** Try to parse a JSON file as {@link MergeOutputJSON}; returns null if corrupt or truncated. */
    private static MergeOutputJSON readJsonOrNull(Path jsonFile) {
        try {
            return new ObjectMapper().readValue(jsonFile.toFile(), MergeOutputJSON.class);
        } catch (IOException e) {
            return null;
        }
    }

    private static final Map<String, String> SKIP_REASONS = Map.ofEntries(
            Map.entry("TIMEOUT", "baseline timed out"),
            Map.entry("INFRA_FAILURE", "infrastructure failure"),
            Map.entry("NO_TESTS", "baseline ran 0 tests"),
            Map.entry("NO_BASELINE", "no human_baseline result"),
            Map.entry("VARIANTS_SKIPPED", "variants skipped by baseline"),
            Map.entry("CHUNK_MISMATCH", "CSV/git chunk count mismatch"),
            Map.entry("MISSING_GIT_OBJECT", "missing git object"),
            Map.entry("VARIANT_SKIP", "skipped during variant processing")
    );

    private static MergeRunStats processMerges(List<DatasetReader.MergeInfo> mergeInfos,
                                               MergeExperimentRunner processor,
                                               VariantResultCollector collector,
                                               String modeName,
                                               boolean skipVariants,
                                               Map<String, String> skippedBaselines,
                                               BuildFailureLog failureLog,
                                               Path modeDir,
                                               Path humanBaselineDir,
                                               String projectName,
                                               Map<String, Map<String, List<String>>> groundTruthPatterns,
                                               AttemptedMergeLog mergeLog) throws Exception {
        int resultCount = 0;
        int skippedCount = 0;
        long totalTime = 0;

        for (int index = 1; index <= mergeInfos.size(); index++) {
            DatasetReader.MergeInfo info = mergeInfos.get(index - 1);
            System.out.printf("  [merge %d/%d] %s... ", index, mergeInfos.size(), info.getShortCommit());
            System.out.flush();

            // Per-merge resume check — validate that existing JSON is complete (processed=true);
            // incomplete or corrupt files are deleted and reprocessed.
            Path mergeOutputFile = modeDir.resolve(info.getMergeCommit() + AppConfig.JSON);
            if (!AppConfig.isFreshRun() && !AppConfig.isReanalyzeSuccess() && mergeOutputFile.toFile().exists()) {
                MergeOutputJSON existing = readJsonOrNull(mergeOutputFile);
                if (existing != null && existing.isProcessed()) {
                    System.out.println("SKIPPED (already processed)");
                    if (mergeLog != null) mergeLog.logSkipped(projectName, info.getMergeCommit(), modeName, "already processed");
                    skippedCount++;
                    continue;
                }
                System.out.print("incomplete JSON, reprocessing... ");
                mergeOutputFile.toFile().delete();
            }

            // Skip merges whose baseline is permanently unusable (timeout, infra failure, no tests)
            String skipType = skippedBaselines.get(info.getMergeCommit());
            if (skipType != null) {
                String reason = SKIP_REASONS.getOrDefault(skipType, skipType);
                System.out.println("SKIPPED (" + reason + ")");
                if (mergeLog != null) mergeLog.logSkipped(projectName, info.getMergeCommit(), modeName, reason);
                skippedCount++;
                continue;
            }

            MergeExperimentRunner.ProcessedMerge processed = processor.processMerge(info);

            if (processed.wasSkipped()) {
                System.out.println(processed.getSkipReason());
                if (mergeLog != null) mergeLog.logSkipped(projectName, info.getMergeCommit(), modeName, processed.getSkipReason());
                skippedCount++;
                if (!skipVariants) {
                    markBaselineSkipped(humanBaselineDir, info.getMergeCommit(),
                            classifySkipReason(processed.getSkipReason()));
                }
                continue;
            }

            Map<String, List<String>> mergeGroundTruth = groundTruthPatterns.get(info.getMergeId());
            MergeOutputJSON result = skipVariants
                    ? collector.collectBaselineResult(processed, mergeGroundTruth)
                    : collector.collectResults(processed);
            result.setMode(modeName);
            result.setProjectName(projectName);
            classifyBaseline(result, processed);

            // During human_baseline mode, don't persist JSONs for merges that no variant
            // mode will ever process.  Variant modes already skip merges without a
            // baseline JSON (NO_BASELINE), so omitting the file keeps all 5 mode
            // directories in sync.
            if (skipVariants && result.isVariantsSkipped()) {
                String reason = SKIP_REASONS.getOrDefault(result.getBaselineFailureType(), "unusable baseline");
                System.out.println("SKIPPED (" + reason + ")");
                logBaselineFailure(failureLog, result, info.getShortCommit());
                if (mergeLog != null) mergeLog.logSkipped(projectName, info.getMergeCommit(), modeName, reason);
                skippedCount++;
                continue;
            }

            result.setProcessed(true);
            new JsonResultWriter().writeResult(result, modeDir);
            logBaselineFailure(failureLog, result, info.getShortCommit());
            if (mergeLog != null) mergeLog.logProcessed(result);
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

        // Mark whether variant modes should skip this merge entirely.
        String ft = output.getBaselineFailureType();
        if ("TIMEOUT".equals(ft) || "INFRA_FAILURE".equals(ft)
                || ("NO_TESTS".equals(ft) && !output.isBaselineBroken())) {
            output.setVariantsSkipped(true);
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
     * Map a skip reason from {@link MergeExperimentRunner.ProcessedMerge#getSkipReason()}
     * to a {@link MergeFailureType} name for the baseline JSON.
     */
    private static String classifySkipReason(String skipReason) {
        if (skipReason == null) return "VARIANT_SKIP";
        if (skipReason.startsWith("chunk mismatch:")) return MergeFailureType.CHUNK_MISMATCH.name();
        if (skipReason.startsWith("missing git object:")) return "MISSING_GIT_OBJECT";
        return "VARIANT_SKIP";
    }

    /**
     * Update a human_baseline JSON to mark a merge as permanently skipped.
     * Called when a variant mode discovers a problem (e.g. chunk mismatch) that
     * makes all variant modes pointless.  Subsequent modes will read the updated
     * JSON and skip the merge via {@link #loadSkippedBaselines}.
     */
    private static void markBaselineSkipped(Path humanBaselineDir, String mergeCommit, String failureType) {
        Path jsonFile = humanBaselineDir.resolve(mergeCommit + AppConfig.JSON);
        if (!jsonFile.toFile().exists()) return;
        try {
            ObjectMapper mapper = new ObjectMapper();
            MergeOutputJSON baseline = mapper.readValue(jsonFile.toFile(), MergeOutputJSON.class);
            baseline.setVariantsSkipped(true);
            baseline.setBaselineFailureType(failureType);
            new JsonResultWriter().writeResult(baseline, humanBaselineDir);
        } catch (IOException e) {
            System.err.println("Warning: could not update baseline JSON for " + mergeCommit + ": " + e.getMessage());
        }
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
