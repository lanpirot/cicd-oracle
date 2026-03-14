package ch.unibe.cs.mergeci.repoCollection;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Thread-safe log of build and test failures encountered during conflict collection.
 * Written fresh each run (overwrites previous log).
 */
public class BuildFailureLog {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PrintWriter writer;

    public static BuildFailureLog createOrNull(Path logFile) {
        try {
            return new BuildFailureLog(logFile);
        } catch (IOException e) {
            System.err.println("Warning: could not create build failure log at " + logFile + ": " + e.getMessage());
            return null;
        }
    }

    public BuildFailureLog(Path logFile) throws IOException {
        logFile.getParent().toFile().mkdirs();
        this.writer = new PrintWriter(new FileWriter(logFile.toFile(), false));
        writeHeader();
    }

    private synchronized void writeHeader() {
        writer.printf("=================================================================================%n");
        writer.printf("CI/CD Merge Resolver - Build Failure Log - %s%n", now());
        writer.printf("=================================================================================%n%n");
        writer.flush();
    }

    /** Log a repo-level rejection (clone failure, no POM, etc.). */
    public synchronized void logRepoFailure(String repoName, String status, String detail) {
        String detailStr = (detail != null && !detail.isEmpty()) ? "  " + detail : "";
        writer.printf("[%s]  %-45s  %-30s%s%n", now(), repoName, status, detailStr);
        writer.flush();
    }

    /** Log a per-merge failure (timeout, no tests, compile error, Java version mismatch). */
    public synchronized void logMergeFailure(String repoName, String shortCommit,
                                             MergeFailureType type, String detail) {
        String detailStr = (detail != null && !detail.isEmpty()) ? "  " + detail : "";
        writer.printf("[%s]  %-45s  %s  %-18s%s%n", now(), repoName, shortCommit, type, detailStr);
        writer.flush();
    }

    public synchronized void close() {
        writer.flush();
        writer.close();
    }

    private String now() {
        return LocalDateTime.now().format(TIMESTAMP);
    }

    public enum MergeFailureType {
        TIMEOUT,
        NO_TESTS,
        JAVA_VERSION,
        COMPILE_FAILURE
    }
}
