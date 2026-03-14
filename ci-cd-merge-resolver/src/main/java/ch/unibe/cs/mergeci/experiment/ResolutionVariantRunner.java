package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.RepositoryManager;
import ch.unibe.cs.mergeci.util.Utility;
import com.google.common.io.Files;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ResolutionVariantRunner {
    private final Path datasetsDir;
    private final Path repoDatasetsFile;
    private final Path tempDir;
    private final RepositoryManager repoManager;

    public ResolutionVariantRunner() {
        this(AppConfig.CONFLICT_DATASET_DIR, AppConfig.INPUT_PROJECT_XLSX, AppConfig.REPO_DIR);
    }

    public ResolutionVariantRunner(Path datasetsDir, Path repoDatasetsFile, Path tempDir) {
        this.datasetsDir = datasetsDir;
        this.repoDatasetsFile = repoDatasetsFile;
        this.tempDir = tempDir;
        this.repoManager = new RepositoryManager(tempDir);
    }

    public void runTests(Utility.Experiments ex) {
        try {
            runTests(AppConfig.VARIANT_EXPERIMENT_DIR.resolve(ex.getName()), ex.isParallel(), ex.isCache());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void runTests(Path outputDir, boolean isParallel, boolean isCache) throws Exception {
        prepareOutputDir(outputDir);

        File[] xlsxDataset = datasetsDir.toFile().listFiles();
        if (xlsxDataset == null) return;

        printHeader(xlsxDataset, outputDir);

        for (File dataset : xlsxDataset) {
            processDataset(dataset, outputDir, isParallel, isCache);
        }
    }

    private void printHeader(File[] datasets, Path outputDir) {
        int totalProjects = datasets.length;
        int pendingProjects = 0;
        int totalMerges = 0;

        for (File dataset : datasets) {
            String repoName = Files.getNameWithoutExtension(dataset.getName());
            Path jsonOutputPath = outputDir.resolve(repoName + AppConfig.JSON);
            boolean pending = AppConfig.isFreshRun() || !jsonOutputPath.toFile().exists();
            if (pending) pendingProjects++;

            try {
                totalMerges += new DatasetReader().readMergeDataset(dataset.toPath()).size();
            } catch (Exception e) {
                // skip unreadable dataset
            }
        }

        System.out.println("================================================================================");
        System.out.println("CI/CD Merge Resolver - Variant Experiment Phase");
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

    private void processDataset(File dataset, Path outputDir, boolean isParallel, boolean isCache) throws Exception {
        String repoName = Files.getNameWithoutExtension(dataset.getName());
        Optional<String> repoUrlOpt = Utility.getRepoUrlFromExcel(repoDatasetsFile, repoName);

        if (repoUrlOpt.isEmpty()) {
            System.err.println("Repository URL not found in Excel for: " + repoName + ". Skipping...");
            return;
        }

        Path jsonOutputPath = outputDir.resolve(repoName + AppConfig.JSON);
        if (!AppConfig.isFreshRun() && jsonOutputPath.toFile().exists()) {
            System.out.printf("File %s already exists. Skipping...\n", jsonOutputPath.getFileName());
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
            makeAnalysisByDataset(dataset.toPath(), repoPath, jsonOutputPath, isParallel, isCache);
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

    public static void makeAnalysisByDataset(Path dataset, Path repoPath, Path output, boolean isParallel, boolean isCache) throws Exception {
        List<DatasetReader.MergeInfo> mergeInfos = new DatasetReader().readMergeDataset(dataset);
        System.out.printf("\n→ Testing %d merges from %s\n", mergeInfos.size(), dataset.getFileName().toString());

        MergeExperimentRunner processor = new MergeExperimentRunner(repoPath, isParallel, isCache);
        VariantResultCollector collector = new VariantResultCollector();

        MergeRunStats stats = processMerges(mergeInfos, processor, collector);

        System.out.println(formatSummary(mergeInfos.size(), stats.results().size(), stats.skippedCount(), stats.totalTime()));

        String projectName = com.google.common.io.Files.getNameWithoutExtension(dataset.getFileName().toString());
        new JsonResultWriter().writeResults(projectName, stats.results(), output);
    }

    private static MergeRunStats processMerges(List<DatasetReader.MergeInfo> mergeInfos,
                                               MergeExperimentRunner processor,
                                               VariantResultCollector collector) throws Exception {
        List<MergeOutputJSON> results = new ArrayList<>();
        int skippedCount = 0;
        long totalTime = 0;

        for (int index = 1; index <= mergeInfos.size(); index++) {
            DatasetReader.MergeInfo info = mergeInfos.get(index - 1);
            int progress = (index * 100) / mergeInfos.size();
            System.out.printf("  [%d/%d|%3d%%] %s... ", index, mergeInfos.size(), progress, info.getShortCommit());
            System.out.flush();

            MergeExperimentRunner.ProcessedMerge processed = processor.processMerge(info);

            if (processed.wasSkipped()) {
                System.out.println(processed.getSkipReason());
                skippedCount++;
                continue;
            }

            MergeOutputJSON result = collector.collectResults(processed);
            results.add(result);
            totalTime += processed.getAnalysisResult().getExecutionTimeSeconds();
            System.out.println(collector.getSuccessSummary(processed));
        }

        return new MergeRunStats(results, skippedCount, totalTime);
    }

    private record MergeRunStats(List<MergeOutputJSON> results, int skippedCount, long totalTime) {}

    /**
     * Format a summary line for the dataset processing results.
     */
    private static String formatSummary(int total, int successful, int skipped, long totalTime) {
        StringBuilder summary = new StringBuilder();
        summary.append("\n  ════════════════════════════════════════\n");
        summary.append(String.format("  Summary: %d tested", successful));

        if (skipped > 0) {
            summary.append(String.format(", %d skipped", skipped));
        }

        summary.append(String.format(" (%.1f%% success rate)", (successful * 100.0 / total)));
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
