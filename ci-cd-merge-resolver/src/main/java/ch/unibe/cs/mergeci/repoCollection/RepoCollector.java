package ch.unibe.cs.mergeci.repoCollection;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.conflict.MergeConflictCollector;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.RepositoryManager;
import ch.unibe.cs.mergeci.util.RepositoryStatus;
import ch.unibe.cs.mergeci.util.Utility;
import ch.unibe.cs.mergeci.util.Utility.PROJECTCOLUMN;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    int headerLine = 1;

    public void processExcel() {
        try {
            processExcel(AppConfig.INPUT_PROJECT_XLSX);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void processExcel(Path excelFile) throws Exception {
        cleanForFreshRun();
        resetForReanalysis();
        FileUtils.deleteDirectory(tempDir.toFile());

        BuildFailureLog failureLog = BuildFailureLog.createOrNull(
                AppConfig.DATA_BASE_DIR.resolve("build_failures.log"));

        // Load workbook into memory (close stream immediately so we can write back later)
        Workbook workbook;
        try (FileInputStream fis = new FileInputStream(excelFile.toFile())) {
            workbook = WorkbookFactory.create(fis);
        }

        try {
            Sheet sheet = workbook.getSheetAt(0);
            ensureHeaders(sheet);
            flushWorkbook(workbook, excelFile);

            int totalRepos = countUniqueRepos(sheet);
            printHeader(totalRepos);

            Set<String> seen = new HashSet<>();
            int currentRepo = 0;

            for (Row row : sheet) {
                if (row.getRowNum() < headerLine || row.getCell(0) == null) continue;

                String repoName = Utility.extractRepoName(row.getCell(0).getStringCellValue().trim());
                String repoUrl = row.getCell(1).getStringCellValue().trim();

                if (repoName.isEmpty() || repoUrl.isEmpty()) continue;
                if (!seen.add(repoUrl)) continue;

                currentRepo++;
                processRepo(row, repoName, repoUrl, currentRepo, totalRepos, workbook, excelFile, failureLog);
            }
        } finally {
            workbook.close();
            if (failureLog != null) failureLog.close();
        }
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

    private int countUniqueRepos(Sheet sheet) {
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (Row row : sheet) {
            if (row.getRowNum() < headerLine || row.getCell(0) == null) continue;
            String repoUrl = row.getCell(1) != null ? row.getCell(1).getStringCellValue().trim() : "";
            if (!repoUrl.isEmpty() && seen.add(repoUrl)) total++;
        }
        return total;
    }

    private void printHeader(int totalRepos) {
        System.out.println("================================================================================");
        System.out.println("CI/CD Merge Resolver - Collection Phase");
        System.out.printf("Total repositories: %d | Fresh run: %s%n", totalRepos, AppConfig.isFreshRun());
        System.out.println("================================================================================\n");
    }

    private void processRepo(Row row, String repoName, String repoUrl, int currentRepo, int totalRepos,
                              Workbook workbook, Path excelFile, BuildFailureLog failureLog) throws Exception {
        RepositoryStatus existingStatus = repoManager.getRepositoryStatus(repoName);
        if (!AppConfig.isFreshRun() && existingStatus != RepositoryStatus.NOT_PROCESSED
                && existingStatus != RepositoryStatus.NOT_PROCESSED_BUT_CLONED) {
            Path excelOutFile = datasetDir.resolve(repoName + AppConfig.XLSX);
            if (existingStatus == RepositoryStatus.SUCCESS && (!Files.exists(datasetDir) || !Files.exists(excelOutFile))) {
                System.out.printf("[%d/%d] %s - ⚠ Marked SUCCESS but excel missing, reprocessing...\n", currentRepo, totalRepos, repoName);
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
            writeRepoRow(row, "unknown", RepositoryStatus.REJECTED_CLONE_FAILED, null);
            flushWorkbook(workbook, excelFile);
            return;
        }

        String buildTool = detectBuildTool(repoFolder);
        if (!buildTool.contains("maven")) {
            System.out.printf("  ✗ Not a Maven project (build tool: %s) → REJECTED_NO_POM\n", buildTool);
            if (failureLog != null) failureLog.logRepoFailure(repoName, "REJECTED_NO_POM", "build tool: " + buildTool);
            repoManager.markRepositoryRejected(repoName, RepositoryStatus.REJECTED_NO_POM);
            writeRepoRow(row, buildTool, RepositoryStatus.REJECTED_NO_POM, null);
            flushWorkbook(workbook, excelFile);
            return;
        }

        System.out.println("  ✓ Maven project detected");
        MergeConflictCollector conflictCollector = new MergeConflictCollector(
                repoFolder, tempDir.resolve(repoName), AppConfig.getMaxConflictMerges(), failureLog);

        CollectionResult result = conflictCollector.collectDataset(
                datasetDir.resolve(repoName + AppConfig.XLSX), repoName, repoUrl);

        System.out.println(formatResultSummary(result));

        if (result.getStatus() == RepositoryStatus.SUCCESS) {
            repoManager.markRepositorySuccess(repoName);
        } else {
            if (failureLog != null) failureLog.logRepoFailure(repoName, result.getStatus().name(), result.getMessage());
            repoManager.markRepositoryRejected(repoName, result.getStatus());
        }

        writeRepoRow(row, buildTool, result.getStatus(), result);
        flushWorkbook(workbook, excelFile);

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
    private void ensureHeaders(Sheet sheet) {
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) headerRow = sheet.createRow(0);
        for (PROJECTCOLUMN col : PROJECTCOLUMN.values()) {
            if (col.getColumnNumber() < 2) continue; // leave existing name/url headers as-is
            Cell cell = headerRow.getCell(col.getColumnNumber());
            if (cell == null) cell = headerRow.createCell(col.getColumnNumber());
            cell.setCellValue(col.getColumnName());
        }
    }

    /** Populate the stats columns for a processed (or rejected) repository row. */
    private void writeRepoRow(Row row, String buildTool, RepositoryStatus status, CollectionResult result) {
        setCell(row, PROJECTCOLUMN.buildTool, buildTool);
        setCell(row, PROJECTCOLUMN.status, status.name());
        if (result != null) {
            setCell(row, PROJECTCOLUMN.totalCommits,      result.getTotalCommits());
            setCell(row, PROJECTCOLUMN.totalMerges,       result.getTotalMerges());
            setCell(row, PROJECTCOLUMN.conflictMerges,    result.getMergesWithConflicts());
            setCell(row, PROJECTCOLUMN.javaConflictMerges,result.getMergesWithJavaConflicts());
            setCell(row, PROJECTCOLUMN.analyzableMerges,  result.getSuccessfulMerges());
            setCell(row, PROJECTCOLUMN.maxModules,        result.getMaxModules());
            setCell(row, PROJECTCOLUMN.timedOut,          result.getMergesTimedOut());
            setCell(row, PROJECTCOLUMN.noTests,           result.getMergesWithNoTests());
        }
    }

    private void setCell(Row row, PROJECTCOLUMN col, String value) {
        Cell cell = row.getCell(col.getColumnNumber());
        if (cell == null) cell = row.createCell(col.getColumnNumber());
        cell.setCellValue(value);
    }

    private void setCell(Row row, PROJECTCOLUMN col, int value) {
        Cell cell = row.getCell(col.getColumnNumber());
        if (cell == null) cell = row.createCell(col.getColumnNumber());
        cell.setCellValue(value);
    }

    private void flushWorkbook(Workbook workbook, Path excelFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(excelFile.toFile())) {
            workbook.write(fos);
        }
    }

    private String formatResultSummary(CollectionResult result) {
        StringBuilder sb = new StringBuilder();

        if (result.getStatus() == RepositoryStatus.SUCCESS) {
            sb.append(String.format("  ✓ SUCCESS: %d/%d merges with Java conflicts → dataset created\n",
                    result.getSuccessfulMerges(), result.getMergesWithJavaConflicts()));
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
