package ch.unibe.cs.mergeci.runner.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Reads {@code .github/workflows/*.yml} to detect the Maven lifecycle goal the project
 * uses for testing, so the pipeline mirrors the CI configuration as closely as possible.
 *
 * <p>Returns {@code "verify"} when a non-release workflow contains a Maven invocation
 * that includes {@code verify} without {@code -DskipTests}.  Falls back to {@code "test"}.
 *
 * <p>Rationale: {@code mvn test} only runs Surefire (unit tests); {@code mvn verify}
 * additionally runs the Failsafe integration-test phase.  Projects that gate on
 * {@code verify} in CI expose more test signal than projects that use {@code test}.
 */
public class CiConfigReader {

    /**
     * Detects the Maven goal to use for the given project directory.
     *
     * @param projectDir root of the checked-out project (must contain {@code .github/workflows/})
     * @return {@code "verify"} or {@code "test"}
     */
    public static String detectMavenGoal(Path projectDir) {
        Path workflowDir = projectDir.resolve(".github/workflows");
        if (!Files.isDirectory(workflowDir)) return "test";
        try (Stream<Path> files = Files.list(workflowDir)) {
            boolean usesVerify = files
                    .filter(CiConfigReader::isTestWorkflow)
                    .anyMatch(CiConfigReader::workflowUsesVerifyForTesting);
            return usesVerify ? "verify" : "test";
        } catch (IOException e) {
            return "test";
        }
    }

    /** Skip obviously release/deploy/publish workflows — they run mvn verify -DskipTests. */
    private static boolean isTestWorkflow(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return (name.endsWith(".yml") || name.endsWith(".yaml"))
                && !name.contains("release")
                && !name.contains("publish")
                && !name.contains("deploy");
    }

    /**
     * Returns true if any line in the workflow file contains a Maven invocation that
     * includes {@code verify} as a goal and does NOT skip tests on the same line.
     */
    private static boolean workflowUsesVerifyForTesting(Path workflowFile) {
        try {
            for (String line : Files.readAllLines(workflowFile)) {
                String trimmed = line.trim();
                if (containsMavenCall(trimmed)
                        && containsWord(trimmed, "verify")
                        && !trimmed.contains("-DskipTests")) {
                    return true;
                }
            }
        } catch (IOException ignored) {}
        return false;
    }

    private static boolean containsMavenCall(String line) {
        // matches "mvn ", "mvnw ", "./mvnw ", "mvnw.cmd" etc.
        return line.contains("mvnw") || line.contains("mvn ");
    }

    /** Word-boundary check without regex overhead. */
    private static boolean containsWord(String line, String word) {
        int idx = line.indexOf(word);
        while (idx >= 0) {
            boolean before = idx == 0 || !Character.isLetterOrDigit(line.charAt(idx - 1));
            boolean after  = idx + word.length() >= line.length()
                    || !Character.isLetterOrDigit(line.charAt(idx + word.length()));
            if (before && after) return true;
            idx = line.indexOf(word, idx + 1);
        }
        return false;
    }
}
