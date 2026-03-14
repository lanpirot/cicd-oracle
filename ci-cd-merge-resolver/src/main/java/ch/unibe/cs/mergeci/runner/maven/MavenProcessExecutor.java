package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.config.AppConfig;

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

    /** Execute a command with timeout. */
    public void executeCommand(Path workingDirectory, Path outputFile, String... command) {
        run(createProcessBuilder(workingDirectory, outputFile, null, command), command);
    }

    /** Execute a command with timeout, overriding JAVA_HOME in the process environment. */
    public void executeCommandWithJavaHome(Path workingDirectory, Path outputFile,
                                           String javaHome, String... command) {
        run(createProcessBuilder(workingDirectory, outputFile, javaHome, command), command);
    }

    private ProcessBuilder createProcessBuilder(Path workingDirectory, Path outputFile,
                                                String javaHome, String... command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());
        pb.redirectErrorStream(true);

        if (javaHome != null) {
            pb.environment().put("JAVA_HOME", javaHome);
            pb.environment().put("PATH", javaHome + "/bin:" + pb.environment().getOrDefault("PATH", ""));
        }

        // Ensure spawned Maven processes have enough heap for large projects.
        // Prepend our setting so any user-defined MAVEN_OPTS still takes effect afterwards.
        String existingOpts = pb.environment().getOrDefault("MAVEN_OPTS", "");
        pb.environment().put("MAVEN_OPTS", AppConfig.MAVEN_SUBPROCESS_HEAP + " " + existingOpts);

        if (outputFile != null) {
            pb.redirectOutput(outputFile.toFile());
        } else {
            pb.inheritIO();
        }

        return pb;
    }

    private void run(ProcessBuilder pb, String... command) {
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

    private void handleTimeout(Process process, String... command) {
        System.err.println("TIMEOUT: Build exceeded " + timeoutMinutes +
                " minutes. Killing process: " + Arrays.toString(command));

        process.destroyForcibly();

        try {
            process.waitFor();
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
