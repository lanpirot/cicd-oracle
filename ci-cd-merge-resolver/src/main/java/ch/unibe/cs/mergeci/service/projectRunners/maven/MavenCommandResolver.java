package ch.unibe.cs.mergeci.service.projectRunners.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            // Fix for Unix: set executable and fix line endings
            mvnw.setExecutable(true);
            fixUnixLineEndings(mvnw);
            return "./mvnw";
        }
        return "mvn";
    }

    /**
     * Fix Windows line endings in Maven wrapper script.
     * Converts CRLF and CR to LF for Unix compatibility.
     */
    private void fixUnixLineEndings(File mvnw) {
        try {
            String content = new String(Files.readAllBytes(mvnw.toPath()));
            content = content.replace("\r\n", "\n").replace("\r", "\n");
            Files.write(mvnw.toPath(), content.getBytes());
        } catch (IOException e) {
            System.err.println("Warning: Failed to fix Unix line endings in mvnw: " + e.getMessage());
            System.err.println("  Wrapper may fail on Unix systems. Consider fixing manually.");
            // Non-fatal: wrapper might still work, or fallback to system Maven will be used
        }
    }
}
