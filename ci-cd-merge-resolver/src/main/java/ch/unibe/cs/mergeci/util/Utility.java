package ch.unibe.cs.mergeci.util;

import lombok.Getter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class Utility {

    @Getter
    public enum MERGECOLUMN {
        mergeCommit(0, "Merge Commit"),
        parent1(1, "Parent1"),
        parent2(2, "Parent2"),
        numTests(3, "Number of Tests"),
        numConflictingFiles(4, "Number of Conflicting Files"),
        numJavaFiles(5, "Number of Java Files"),
        compilationSuccess(6, "Compilation Success"),
        testSuccess(7, "Test Success"),
        elapsedTestTime(8, "Elapsed Time"),
        isMultiModule(9, "Multi Module"),
        numPassedTests(10, "Number of Passed Tests"),
        compilationTime(11, "Compilation Time"),
        testTime(12, "Test Time"),
        normalizedElapsedTime(13, "Normalized Elapsed Time"),
        numberOfModules(14, "Number of Modules"),
        modulesPassed(15, "Modules Passed");

        private final int columnNumber;
        private final String columnName;

        MERGECOLUMN(int columnNumber, String columnName){
            this.columnNumber = columnNumber;
            this.columnName = columnName;
        }
    }

    @Getter
    public enum PROJECTCOLUMN {
        repoName(0, "Repository Name"),
        repoURL(1, "Repository URL"),
        buildTool(2, "Build Tool"),
        totalCommits(3, "#Commits"),
        totalMerges(4, "#Merges"),
        conflictMerges(5, "#Conflict Merges"),
        javaConflictMerges(6, "#Java Conflict Merges"),
        analyzableMerges(7, "#Analyzable Merges"),
        maxModules(8, "#Max Modules"),
        timedOut(9, "#Timed Out"),
        noTests(10, "#No Tests"),
        status(11, "Status");

        private final int columnNumber;
        private final String columnName;

        PROJECTCOLUMN(int columnNumber, String columnName){
            this.columnNumber = columnNumber;
            this.columnName = columnName;
        }
    }

    @Getter
    public enum Experiments {
        human_baseline(false, false, "human_baseline"),
        no_cache_no_parallel(false, false, "no_optimization"),
        //cache_no_parallel(true, false),       //doesn't really make sense
        no_cache_parallel(false, true, "parallel"),
        cache_parallel(true, true, "cache_parallel");

        private final boolean cache;
        private final boolean parallel;
        private final String name;

        Experiments(boolean cache, boolean parallel, String name){
            this.cache = cache;
            this.parallel = parallel;
            this.name = name;
        }
    }

    public static void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!pool.awaitTermination(1, TimeUnit.HOURS)) {
                pool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!pool.awaitTermination(60, TimeUnit.SECONDS))
                    System.err.println("Pool did not terminate");
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Extract repository name from a repository URL.
     * Returns the last segment after the final slash.
     *
     * @param repoUrl The repository URL (e.g., "owner/repo" or "https://github.com/owner/repo")
     * @return The repository name (e.g., "repo")
     */
    public static String extractRepoName(String repoUrl) {
        return repoUrl.substring(repoUrl.lastIndexOf("/") + 1);
    }

    /**
     * Look up repository URL from an Excel file containing project information.
     * Searches for a project by name and returns its URL.
     *
     * @param excelFile Path to the Excel file containing repository information
     * @param projectName Name of the project to find (without extension)
     * @return Optional containing the repository URL, or empty if not found
     * @throws IOException if the Excel file cannot be read
     */
    public static Optional<String> getRepoUrlFromExcel(Path excelFile, String projectName) throws IOException {
        try (FileInputStream file = new FileInputStream(excelFile.toFile());
             Workbook workbook = new XSSFWorkbook(file)) {
            Sheet sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String repoName = row.getCell(PROJECTCOLUMN.repoName.getColumnNumber())
                        .getStringCellValue().split("/")[1].trim();
                if (repoName.equals(projectName)) {
                    String url = row.getCell(PROJECTCOLUMN.repoURL.getColumnNumber())
                            .getStringCellValue().trim();
                    return Optional.of(url);
                }
            }
        }
        return Optional.empty();
    }
}
