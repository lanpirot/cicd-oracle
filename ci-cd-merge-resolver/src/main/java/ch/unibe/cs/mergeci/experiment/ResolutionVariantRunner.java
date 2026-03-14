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
        // Handle FRESH_RUN mode - clean experiment output directory
        if (AppConfig.isFreshRun() && outputDir.toFile().exists()) {
            System.out.println("FRESH_RUN enabled: Cleaning experiment directory: " + outputDir);
            FileUtils.deleteDirectory(outputDir.toFile());
        }

        if (!outputDir.toFile().exists()) {
            outputDir.toFile().mkdirs();
        }

        File[] xlsxDataset = datasetsDir.toFile().listFiles();

        if (xlsxDataset == null) {return;}

        for (File dataset : xlsxDataset) {
            String repoName = Files.getNameWithoutExtension(dataset.getName());
            Optional<String> repoUrlOpt = Utility.getRepoUrlFromExcel(repoDatasetsFile, repoName);

            if (repoUrlOpt.isEmpty()) {
                System.err.println("Repository URL not found in Excel for: " + repoName + ". Skipping...");
                continue;
            }

            String repoUrl = repoUrlOpt.get();
            String nameOfOutputFIle = repoName + AppConfig.JSON;

            // Skip if already processed (unless FRESH_RUN, which already cleaned the directory)
            Path jsonOutputPath = outputDir.resolve(nameOfOutputFIle);
            if (!AppConfig.isFreshRun() && jsonOutputPath.toFile().exists()) {
                System.out.printf("File %s already exists. Skipping...\n", nameOfOutputFIle);
                continue;
            }

            Path repoPath;
            try {
                repoPath = repoManager.getRepositoryPath(repoName, repoUrl);
            } catch (IOException e) {
                System.err.println("Skipping repository " + repoName + ": " + e.getMessage());
                continue;
            }

            try {
                makeAnalysisByDataset(dataset.toPath(), repoPath, outputDir.resolve(nameOfOutputFIle), isParallel, isCache);
                // Mark as successful only if analysis completes without major issues
                repoManager.markRepositorySuccess(repoName);
            } catch (Exception e) {
                System.err.println("Analysis failed for repository " + repoName + ": " + e.getMessage());
                // Don't mark as rejected - we might want to retry later
                throw e;
            } finally {
                RepositoryCache.clear();
                WindowCache.reconfigure(new WindowCacheConfig());
                // NOTE: No longer delete the repository directory!
            }
        }
    }


    public static void makeAnalysisByDataset(Path dataset, Path repoPath, Path Output, boolean isParallel, boolean isCache) throws Exception {
        // Read dataset
        DatasetReader reader = new DatasetReader();
        List<DatasetReader.MergeInfo> mergeInfos = reader.readMergeDataset(dataset);

        System.out.printf("\n→ Testing %d merges from %s\n", mergeInfos.size(), dataset.getFileName().toString());

        // Create processors
        MergeProcessor processor = new MergeProcessor(repoPath, isParallel, isCache);
        VariantResultCollector collector = new VariantResultCollector();

        // Process each merge
        List<MergeOutputJSON> results = new ArrayList<>();
        int index = 1;
        int skippedCount = 0;
        long totalTime = 0;

        for (DatasetReader.MergeInfo info : mergeInfos) {
            int progress = (index * 100) / mergeInfos.size();
            System.out.printf("  [%d/%d|%3d%%] %s... ", index++, mergeInfos.size(), progress, info.getShortCommit());
            System.out.flush();

            MergeProcessor.ProcessedMerge processed = processor.processMerge(info);

            if (processed.wasSkipped()) {
                System.out.println(processed.getSkipReason());
                skippedCount++;
                continue;
            }

            MergeOutputJSON output = collector.collectResults(processed);
            results.add(output);
            totalTime += processed.getAnalysisResult().getExecutionTimeSeconds();

            System.out.println(collector.getSuccessSummary(processed));
        }

        // Print summary
        System.out.println(formatSummary(mergeInfos.size(), results.size(), skippedCount, totalTime));

        // Write results
        String projectName = com.google.common.io.Files.getNameWithoutExtension(dataset.getFileName().toString());
        JsonResultWriter writer = new JsonResultWriter();
        writer.writeResults(projectName, results, Output);
    }

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
