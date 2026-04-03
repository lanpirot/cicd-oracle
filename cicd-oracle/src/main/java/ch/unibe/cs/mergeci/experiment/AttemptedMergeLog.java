package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Append-only CSV log of every merge attempted during an RQ pipeline run.
 *
 * <p>Each row records the merge ID, project, mode, verdict, and — for
 * successfully processed merges — key quality metrics of the best variant.
 * The file is created (with header) on first use and appended to on
 * subsequent pipeline restarts.
 */
public class AttemptedMergeLog implements Closeable {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String HEADER =
            "timestamp,project,mergeCommit,mode,verdict,reason,"
            + "numChunks,baselineBroken,numVariants,bestModules,bestPassedTests,executionSeconds";

    private final PrintWriter writer;

    public AttemptedMergeLog(Path csvFile) throws IOException {
        Files.createDirectories(csvFile.getParent());
        boolean exists = Files.exists(csvFile) && Files.size(csvFile) > 0;
        this.writer = new PrintWriter(Files.newBufferedWriter(csvFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        if (!exists) {
            writer.println(HEADER);
            writer.flush();
        }
    }

    /** Log a fully processed merge with result data. */
    public synchronized void logProcessed(MergeOutputJSON result) {
        int numVariants = 0;
        int bestModules = 0;
        int bestPassedTests = 0;

        if (result.getVariants() != null) {
            for (MergeOutputJSON.Variant v : result.getVariants()) {
                if (v.getVariantIndex() == 0 || v.isCacheDonor()) continue;
                numVariants++;

                CompilationResult cr = v.getCompilationResult();
                if (cr == null || cr.getBuildStatus() == null
                        || cr.getBuildStatus() == CompilationResult.Status.TIMEOUT) continue;

                int modules = (cr.getBuildStatus() == CompilationResult.Status.SUCCESS)
                        ? Math.max(1, cr.getNumberOfSuccessfulModules())
                        : cr.getNumberOfSuccessfulModules();
                TestTotal tt = v.getTestResults();
                int tests = (tt != null && tt.isHasData())
                        ? tt.getRunNum() - tt.getErrorsNum() - tt.getFailuresNum()
                        : 0;

                if (modules > bestModules || (modules == bestModules && tests > bestPassedTests)) {
                    bestModules = modules;
                    bestPassedTests = tests;
                }
            }
        }

        writeLine(result.getProjectName(), result.getMergeCommit(), result.getMode(),
                "PROCESSED", "",
                result.getNumConflictChunks(), result.isBaselineBroken(),
                numVariants, bestModules, bestPassedTests, result.getTotalExecutionTime());
    }

    /** Log a merge that was skipped (already processed, baseline unusable, chunk mismatch, etc.). */
    public synchronized void logSkipped(String project, String mergeCommit, String mode, String reason) {
        writeLine(project, mergeCommit, mode, "SKIPPED", reason,
                -1, false, 0, 0, 0, 0);
    }

    /** Log a project-level failure (e.g. clone failed). */
    public synchronized void logProjectFailure(String project, String reason) {
        writeLine(project, "", "", "PROJECT_FAILURE", reason,
                -1, false, 0, 0, 0, 0);
    }

    @Override
    public synchronized void close() {
        writer.flush();
        writer.close();
    }

    private void writeLine(String project, String mergeCommit, String mode,
                           String verdict, String reason,
                           int numChunks, boolean baselineBroken,
                           int numVariants, int bestModules, int bestPassedTests,
                           long executionSeconds) {
        writer.printf("%s,%s,%s,%s,%s,%s,%d,%s,%d,%d,%d,%d%n",
                now(),
                csvField(project), csvField(mergeCommit), csvField(mode),
                verdict, csvField(reason),
                numChunks, baselineBroken,
                numVariants, bestModules, bestPassedTests, executionSeconds);
        writer.flush();
    }

    private static String csvField(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }
}
