package ch.unibe.cs.mergeci.util;

import lombok.Getter;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class Utility {

    @Getter
    public enum MERGECOLUMN {
        // Identifiers
        mergeCommit(0, "Merge Commit"),
        parent1(1, "Parent1"),
        parent2(2, "Parent2"),
        // Conflict structure
        numConflictingFiles(3, "Number of Conflicting Files"),
        numJavaFiles(4, "Number of Java Files"),
        // Project structure
        isMultiModule(5, "Multi Module"),
        numberOfModules(6, "Number of Modules"),
        // Compilation results
        compilationSuccess(7, "Compilation Success"),
        compilationTime(8, "Compilation Time"),
        modulesPassed(9, "Modules Passed"),
        // Test results
        numTests(10, "Number of Tests"),
        numPassedTests(11, "Number of Passed Tests"),
        testSuccess(12, "Test Success"),
        testTime(13, "Test Time"),
        elapsedTestTime(14, "Elapsed Time"),
        normalizedElapsedTime(15, "Normalized Elapsed Time"),
        hasTestConflict(16, "Has Test Conflict"),
        /** True when the human merge commit itself fails to compile; a variant may fix it. */
        baselineBroken(17, "Baseline Broken");

        private final int columnNumber;
        private final String columnName;

        MERGECOLUMN(int columnNumber, String columnName){
            this.columnNumber = columnNumber;
            this.columnName = columnName;
        }
    }

    @Getter
    public enum PROJECTCOLUMN {
        // Identity
        repoName(0, "Repository Name"),
        repoURL(1, "Repository URL"),
        buildTool(2, "Build Tool"),
        // Repository scale
        totalCommits(3, "#Commits"),
        totalMerges(4, "#Merges"),
        // Conflict funnel
        conflictMerges(5, "#Conflict Merges"),
        javaConflictMerges(6, "#Java Conflict Merges"),
        analyzableMerges(7, "#Analyzable Merges"),
        // Build structure
        maxModules(8, "#Max Modules"),
        // Issues
        timedOut(9, "#Timed Out"),
        noTests(10, "#No Tests"),
        // Outcome
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
        human_baseline(false, false, true,  "human_baseline"),
        cache_parallel(true,  true,  false, "cache_parallel"),
        no_cache_parallel(false, true,  false, "parallel"),
        cache_no_parallel(true,  false, false, "cache_sequential"),
        no_cache_no_parallel(false, false, false, "no_optimization");

        private final boolean cache;
        private final boolean parallel;
        private final boolean skipVariants;
        private final String name;

        Experiments(boolean cache, boolean parallel, boolean skipVariants, String name){
            this.cache = cache;
            this.parallel = parallel;
            this.skipVariants = skipVariants;
            this.name = name;
        }
    }

    public static void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!pool.awaitTermination(1, TimeUnit.HOURS)) {
                pool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being canceled
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
     * Look up repository URL from a CSV file containing project information.
     * Searches for a project by name and returns its URL.
     *
     * @param csvFile Path to the CSV file containing repository information
     * @param projectName Name of the project to find (without extension)
     * @return Optional containing the repository URL, or empty if not found
     * @throws IOException if the CSV file cannot be read
     */
    public static Optional<String> getRepoUrlFromCsv(Path csvFile, String projectName) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile.toFile()))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; } // skip header
                if (line.isBlank()) continue;

                String[] fields = parseCsvLine(line);
                if (fields.length <= PROJECTCOLUMN.repoURL.getColumnNumber()) continue;

                String repoName = fields[PROJECTCOLUMN.repoName.getColumnNumber()].split("/")[1].trim();
                if (repoName.equals(projectName)) {
                    return Optional.of(fields[PROJECTCOLUMN.repoURL.getColumnNumber()].trim());
                }
            }
        }
        return Optional.empty();
    }

    public static String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                result.add(field.toString());
                field = new StringBuilder();
            } else {
                field.append(c);
            }
        }
        result.add(field.toString());
        return result.toArray(new String[0]);
    }

    public static String escapeCsvField(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
