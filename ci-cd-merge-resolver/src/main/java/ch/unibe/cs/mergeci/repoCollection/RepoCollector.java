package ch.unibe.cs.mergeci.repoCollection;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.conflict.MergeConflictCollector;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.RepositoryManager;
import ch.unibe.cs.mergeci.util.RepositoryStatus;
import ch.unibe.cs.mergeci.util.Utility;
import ch.unibe.cs.mergeci.util.Utility.PROJECTCOLUMN;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RepoCollector {
    private final Path cloneDir;
    private final Path tempDir;
    private final Path datasetDir;
    private final RepositoryManager repoManager;

    public RepoCollector() {
        this(AppConfig.REPO_DIR, AppConfig.TMP_DIR, AppConfig.CONFLICT_DATASET_DIR);
    }

    public RepoCollector(Path cloneDir, Path tempDir, Path datasetDir) {
        this.cloneDir = cloneDir;
        this.tempDir = tempDir;
        this.datasetDir = datasetDir;
        this.repoManager = new RepositoryManager(cloneDir);
    }

    public void processCsv() {
        try {
            processCsv(AppConfig.INPUT_PROJECT_CSV);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void processCsv(Path csvFile) throws Exception {
        cleanForFreshRun();
        resetForReanalysis();
        FileUtils.deleteDirectory(tempDir.toFile());

        BuildFailureLog failureLog = BuildFailureLog.createOrNull(
                AppConfig.DATA_BASE_DIR.resolve("build_failures.log"));

        List<String[]> rows = readCsv(csvFile);
        ensureHeaders(rows);
        flushCsv(csvFile, rows);

        int totalRepos = countUniqueRepos(rows);
        printHeader(totalRepos);

        Set<String> seen = new HashSet<>();
        int currentRepo = 0;

        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row.length < 2 || row[0].isEmpty()) continue;

            String repoName = Utility.extractRepoName(row[0].trim());
            String repoUrl = row[1].trim();

            if (repoName.isEmpty() || repoUrl.isEmpty()) continue;
            if (!seen.add(repoUrl)) continue;

            currentRepo++;
            processRepo(rows, i, repoName, repoUrl, currentRepo, totalRepos, csvFile, failureLog);
        }

        if (failureLog != null) failureLog.close();
    }

    private void cleanForFreshRun() {
        if (!AppConfig.isFreshRun()) return;
        System.out.println("FRESH_RUN enabled: Cleaning collection directories...");
        if (Files.exists(cloneDir)) FileUtils.deleteDirectory(cloneDir.toFile());
        if (Files.exists(datasetDir)) FileUtils.deleteDirectory(datasetDir.toFile());
        repoManager.resetCache();
    }

    private void resetForReanalysis() {
        if (!AppConfig.isReanalyzeSuccess()) return;
        System.out.println("REANALYZE_SUCCESS enabled: Resetting successful repositories for re-analysis...");
        repoManager.resetSuccessfulRepos();
    }

    private int countUniqueRepos(List<String[]> rows) {
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row.length < 2 || row[1].isEmpty()) continue;
            if (seen.add(row[1].trim())) total++;
        }
        return total;
    }

    private void printHeader(int totalRepos) {
        System.out.println("================================================================================");
        System.out.println("CI/CD Merge Resolver - Collection Phase");
        System.out.printf("Total repositories: %d | Fresh run: %s%n", totalRepos, AppConfig.isFreshRun());
        System.out.println("================================================================================\n");
    }

    private void processRepo(List<String[]> rows, int rowIndex, String repoName, String repoUrl,
                              int currentRepo, int totalRepos, Path csvFile,
                              BuildFailureLog failureLog) throws Exception {
        RepositoryStatus existingStatus = repoManager.getRepositoryStatus(repoName);
        if (!AppConfig.isFreshRun() && existingStatus != RepositoryStatus.NOT_PROCESSED
                && existingStatus != RepositoryStatus.NOT_PROCESSED_BUT_CLONED) {
            Path csvOutFile = datasetDir.resolve(repoName + AppConfig.CSV);
            if (existingStatus == RepositoryStatus.SUCCESS && (!Files.exists(datasetDir) || !Files.exists(csvOutFile))) {
                System.out.printf("[%d/%d] %s - ⚠ Marked SUCCESS but csv missing, reprocessing...\n", currentRepo, totalRepos, repoName);
            } else {
                System.out.printf("[%d/%d] %s - ⏩ Already processed (%s)\n", currentRepo, totalRepos, repoName, existingStatus);
                return;
            }
        }

        System.out.printf("[%d/%d] %s\n", currentRepo, totalRepos, repoName);

        Path repoFolder;
        try {
            repoFolder = repoManager.getRepositoryPath(repoName, repoUrl);
        } catch (IOException e) {
            System.out.printf("  ✗ Clone failed: %s\n", e.getMessage());
            if (failureLog != null) failureLog.logRepoFailure(repoName, "REJECTED_CLONE_FAILED", e.getMessage());
            writeRepoRow(rows, rowIndex, "unknown", RepositoryStatus.REJECTED_CLONE_FAILED, null);
            flushCsv(csvFile, rows);
            return;
        }

        String buildTool = detectBuildTool(repoFolder);
        if (!buildTool.contains("maven")) {
            System.out.printf("  ✗ Not a Maven project (build tool: %s) → REJECTED_NO_POM\n", buildTool);
            if (failureLog != null) failureLog.logRepoFailure(repoName, "REJECTED_NO_POM", "build tool: " + buildTool);
            repoManager.markRepositoryRejected(repoName, RepositoryStatus.REJECTED_NO_POM);
            writeRepoRow(rows, rowIndex, buildTool, RepositoryStatus.REJECTED_NO_POM, null);
            flushCsv(csvFile, rows);
            return;
        }

        System.out.println("  ✓ Maven project detected");
        MergeConflictCollector conflictCollector = new MergeConflictCollector(
                repoFolder, tempDir.resolve(repoName), AppConfig.getMaxConflictMerges(), failureLog);

        CollectionResult result = conflictCollector.collectDataset(
                datasetDir.resolve(repoName + AppConfig.CSV), repoName, repoUrl);

        System.out.println(formatResultSummary(result));

        if (result.getStatus() == RepositoryStatus.SUCCESS) {
            repoManager.markRepositorySuccess(repoName);
        } else {
            if (failureLog != null) failureLog.logRepoFailure(repoName, result.getStatus().name(), result.getMessage());
            repoManager.markRepositoryRejected(repoName, result.getStatus());
        }

        writeRepoRow(rows, rowIndex, buildTool, result.getStatus(), result);
        flushCsv(csvFile, rows);

        RepositoryCache.clear();
        WindowCache.reconfigure(new WindowCacheConfig());
        FileUtils.deleteDirectory(tempDir.resolve(repoName).toFile());
    }

    private String detectBuildTool(Path repoFolder) {
        List<String> found = new ArrayList<>();
        if (Files.exists(repoFolder.resolve("pom.xml"))) found.add("maven");
        if (Files.exists(repoFolder.resolve("build.gradle"))
                || Files.exists(repoFolder.resolve("build.gradle.kts"))
                || Files.exists(repoFolder.resolve("settings.gradle"))
                || Files.exists(repoFolder.resolve("settings.gradle.kts"))) found.add("gradle");
        if (Files.exists(repoFolder.resolve("build.xml"))) found.add("ant");
        if (Files.exists(repoFolder.resolve("build.sbt"))) found.add("sbt");
        if (Files.exists(repoFolder.resolve("BUILD")) || Files.exists(repoFolder.resolve("BUILD.bazel"))) found.add("bazel");
        if (found.isEmpty()) return "other";
        return String.join(",", found);
    }

    /** Write the new-column headers into row 0 if not already present. */
    private void ensureHeaders(List<String[]> rows) {
        String[] headerRow = rows.isEmpty() ? new String[0] : rows.get(0);
        if (headerRow.length < PROJECTCOLUMN.values().length) {
            headerRow = Arrays.copyOf(headerRow, PROJECTCOLUMN.values().length);
            for (PROJECTCOLUMN col : PROJECTCOLUMN.values()) {
                if (col.getColumnNumber() < 2) continue; // leave existing name/url headers as-is
                if (headerRow[col.getColumnNumber()] == null || headerRow[col.getColumnNumber()].isEmpty()) {
                    headerRow[col.getColumnNumber()] = col.getColumnName();
                }
            }
            if (rows.isEmpty()) rows.add(headerRow);
            else rows.set(0, headerRow);
        }
    }

    /** Populate the stats columns for a processed (or rejected) repository row. */
    private void writeRepoRow(List<String[]> rows, int rowIndex, String buildTool,
                               RepositoryStatus status, CollectionResult result) {
        String[] row = rows.get(rowIndex);
        if (row.length < PROJECTCOLUMN.values().length) {
            row = Arrays.copyOf(row, PROJECTCOLUMN.values().length);
            rows.set(rowIndex, row);
        }
        row[PROJECTCOLUMN.buildTool.getColumnNumber()] = Utility.escapeCsvField(buildTool);
        row[PROJECTCOLUMN.status.getColumnNumber()] = status.name();
        if (result != null) {
            row[PROJECTCOLUMN.totalCommits.getColumnNumber()]      = String.valueOf(result.getTotalCommits());
            row[PROJECTCOLUMN.totalMerges.getColumnNumber()]       = String.valueOf(result.getTotalMerges());
            row[PROJECTCOLUMN.conflictMerges.getColumnNumber()]    = String.valueOf(result.getMergesWithConflicts());
            row[PROJECTCOLUMN.javaConflictMerges.getColumnNumber()] = String.valueOf(result.getMergesWithJavaConflicts());
            row[PROJECTCOLUMN.analyzableMerges.getColumnNumber()]  = String.valueOf(result.getSuccessfulMerges());
            row[PROJECTCOLUMN.maxModules.getColumnNumber()]        = String.valueOf(result.getMaxModules());
            row[PROJECTCOLUMN.timedOut.getColumnNumber()]          = String.valueOf(result.getMergesTimedOut());
            row[PROJECTCOLUMN.noTests.getColumnNumber()]           = String.valueOf(result.getMergesWithNoTests());
        }
    }

    private void flushCsv(Path csvFile, List<String[]> rows) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFile.toFile()))) {
            for (String[] row : rows) {
                writer.write(rowToCsvLine(row));
                writer.newLine();
            }
        }
    }

    private static String rowToCsvLine(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            String value = fields[i] != null ? fields[i] : "";
            sb.append(Utility.escapeCsvField(value));
        }
        return sb.toString();
    }

    static List<String[]> readCsv(Path csvFile) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rows.add(Utility.parseCsvLine(line));
            }
        }
        return rows;
    }

    private String formatResultSummary(CollectionResult result) {
        StringBuilder sb = new StringBuilder();

        if (result.getStatus() == RepositoryStatus.SUCCESS) {
            sb.append(String.format("  ✓ SUCCESS: %d/%d conflict merges → dataset created\n",
                    result.getSuccessfulMerges(), result.getMergesWithConflicts()));
        } else {
            sb.append(String.format("  ✗ %s: %s\n", result.getStatus(), result.getMessage()));
        }

        sb.append(String.format("     Total: %d merges | Conflicts: %d | Java conflicts: %d\n",
                result.getTotalMerges(), result.getMergesWithConflicts(), result.getMergesWithJavaConflicts()));

        if (result.getMergesWithNoTests() > 0 || result.getMergesTimedOut() > 0) {
            sb.append(String.format("     No tests: %d | Timeouts: %d\n",
                    result.getMergesWithNoTests(), result.getMergesTimedOut()));
        }

        sb.append("\n");
        return sb.toString();
    }
}
