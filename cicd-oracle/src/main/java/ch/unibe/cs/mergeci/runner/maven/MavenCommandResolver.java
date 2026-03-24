package ch.unibe.cs.mergeci.runner.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Resolves the appropriate Maven command based on OS and project configuration.
 * Handles Maven wrapper detection and platform-specific command selection.
 */
public class MavenCommandResolver {
    private final boolean useMavenDaemon;

    public MavenCommandResolver(boolean useMavenDaemon) {
        this.useMavenDaemon = useMavenDaemon;
    }

    public MavenCommandResolver() {
        this(false);
    }

    /**
     * Resolve the Maven command to use for the given project.
     *
     * @param projectPath Path to the Maven project
     * @return Maven command string (e.g., "mvn", "./mvnw", "mvn.cmd")
     */
    /**
     * Detects whether this project's CI uses {@code mvn verify} (integration tests included)
     * or plain {@code mvn test} by inspecting {@code .github/workflows/*.yml}.
     */
    public String resolveMavenGoal(Path projectPath) {
        return CiConfigReader.detectMavenGoal(projectPath);
    }

    public String resolveMavenCommand(Path projectPath) {
        if (useMavenDaemon) {
            return "mvnd";
        }

        boolean isWindows = isWindowsOS();

        if (isWindows) {
            return resolveWindowsCommand(projectPath);
        } else {
            return resolveUnixCommand(projectPath);
        }
    }

    private boolean isWindowsOS() {
        return System.getProperty("os.name")
                .toLowerCase(Locale.ENGLISH)
                .contains("windows");
    }

    private String resolveWindowsCommand(Path projectPath) {
        File mvnwCmd = projectPath.resolve("mvnw.cmd").toFile();

        if (mvnwCmd.exists()) {
            return "mvnw.cmd";
        }
        return "mvn.cmd";
    }

    private String resolveUnixCommand(Path projectPath) {
        File mvnw = projectPath.resolve("mvnw").toFile();

        if (mvnw.exists()) {
            // Fix line endings first — the atomic move may replace the inode, wiping the exec bit.
            fixUnixLineEndings(mvnw);
            // Also fix the wrapper properties file — CRLF there corrupts the wrapperUrl, causing
            // the JAR download to 404 with a %0D-appended URL.
            File wrapperProps = projectPath.resolve(".mvn/wrapper/maven-wrapper.properties").toFile();
            if (wrapperProps.exists()) fixUnixLineEndings(wrapperProps);
            // Set executable bit after any file replacement so it is always present.
            if (!mvnw.setExecutable(true)) {
                System.err.println("Warning: Could not set executable bit on " + mvnw.getAbsolutePath() + " — falling back to system mvn");
                return "mvn";
            }
            return "./mvnw";
        }
        return "mvn";
    }

    /**
     * Fix Windows line endings in Maven wrapper script.
     * Converts CRLF and CR to LF for Unix compatibility.
     *
     * <p>Writes to a sibling temp file and then atomically renames it over {@code mvnw}.
     * This guarantees that the inode exec'd by the OS never had an open write file descriptor,
     * which prevents {@code ETXTBSY} (error=26) under parallel load.
     */
    private void fixUnixLineEndings(File mvnw) {
        try {
            byte[] original = Files.readAllBytes(mvnw.toPath());
            String content = new String(original);
            if (!content.contains("\r")) return; // already Unix line endings, nothing to do
            content = content.replace("\r\n", "\n").replace("\r", "\n");
            Path tmp = mvnw.toPath().resolveSibling(mvnw.getName() + ".tmp");
            Files.write(tmp, content.getBytes());
            Files.move(tmp, mvnw.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Warning: Failed to fix Unix line endings in mvnw: " + e.getMessage());
            System.err.println("  Wrapper may fail on Unix systems. Consider fixing manually.");
            // Non-fatal: wrapper might still work, or fallback to system Maven will be used
        }
    }
}
