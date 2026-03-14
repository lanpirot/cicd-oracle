package ch.unibe.cs.mergeci.runner.maven;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Executes Maven commands with timeout management.
 * Handles process lifecycle, timeouts, and forced termination.
 */
public class MavenProcessExecutor {
    private final int timeoutMinutes;

    public MavenProcessExecutor(int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }

    /**
     * Execute a command with timeout.
     * If the command exceeds the timeout, the process will be forcibly terminated.
     *
     * @param workingDirectory Working directory for the command
     * @param outputFile Output file for command output (null for inherited IO)
     * @param command Command and arguments to execute
     */
    public void executeCommand(Path workingDirectory, Path outputFile, String... command) {
        ProcessBuilder pb = createProcessBuilder(workingDirectory, outputFile, command);

        Process process = null;
        try {
            process = pb.start();
            boolean completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);

            if (!completed) {
                handleTimeout(process, command);
            }
        } catch (IOException | InterruptedException e) {
            handleExecutionError(process, e);
        }
    }

    private ProcessBuilder createProcessBuilder(Path workingDirectory, Path outputFile, String... command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());
        pb.redirectErrorStream(true);

        if (outputFile != null) {
            pb.redirectOutput(outputFile.toFile());
        } else {
            pb.inheritIO();
        }

        return pb;
    }

    private void handleTimeout(Process process, String... command) {
        System.err.println("TIMEOUT: Build exceeded " + timeoutMinutes +
                " minutes. Killing process: " + Arrays.toString(command));

        process.destroyForcibly();

        try {
            process.waitFor(); // Wait for forced termination to complete
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleExecutionError(Process process, Exception e) {
        e.printStackTrace();

        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
