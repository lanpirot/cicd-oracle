package ch.unibe.cs.mergeci.repoCollection;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Thread-safe log of build and test failures.
 * Phase 1 (collection) creates it fresh; Phase 2 (human baseline) appends a new section.
 */
public class BuildFailureLog {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PrintWriter writer;

    public static BuildFailureLog createOrNull(Path logFile) {
        try {
            return new BuildFailureLog(logFile, false);
        } catch (IOException e) {
            System.err.println("Warning: could not create build failure log at " + logFile + ": " + e.getMessage());
            return null;
        }
    }

    public static BuildFailureLog createOrNullAppend(Path logFile) {
        try {
            return new BuildFailureLog(logFile, true);
        } catch (IOException e) {
            System.err.println("Warning: could not open build failure log at " + logFile + ": " + e.getMessage());
            return null;
        }
    }

    public BuildFailureLog(Path logFile) throws IOException {
        this(logFile, false);
    }

    private BuildFailureLog(Path logFile, boolean append) throws IOException {
        logFile.getParent().toFile().mkdirs();
        this.writer = new PrintWriter(new FileWriter(logFile.toFile(), append));
        if (append) {
            writer.printf("%n── Human Baseline Run - %s ──%n", now());
        } else {
            writer.printf("=================================================================================%n");
            writer.printf("CI/CD Merge Resolver - Build Failure Log - %s%n", now());
            writer.printf("=================================================================================%n%n");
        }
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
        COMPILE_FAILURE,
        /** Dead remote repository or incompatible frontend toolchain — permanently unfixable. */
        INFRA_FAILURE,
        /** Merge commit has genuine source-level errors; a variant may fix it. */
        BROKEN_MERGE
    }
}
