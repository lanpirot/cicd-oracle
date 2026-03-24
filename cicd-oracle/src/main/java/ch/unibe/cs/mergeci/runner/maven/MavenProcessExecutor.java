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
    private final int timeoutSeconds;

    public MavenProcessExecutor(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
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
            process = startWithRetryOnTextFileBusy(pb, command);
            if (timeoutSeconds <= 0) {
                process.waitFor(); // no timeout — run to completion
            } else {
                boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!completed) {
                    handleTimeout(process, command);
                }
            }
        } catch (IOException | InterruptedException e) {
            handleExecutionError(process, e);
        }
    }

    /**
     * Start the process, with two recovery strategies:
     * <ul>
     *   <li>ETXTBSY (error=26): retry up to 3 times with exponential back-off — the kernel
     *       briefly sees the executable as write-busy between close() and execve().</li>
     *   <li>EACCES (error=13) on {@code ./mvnw}: fall back to system {@code mvn} once —
     *       setExecutable() can succeed yet the OS still denies exec (e.g. NFS, race).</li>
     * </ul>
     */
    private Process startWithRetryOnTextFileBusy(ProcessBuilder pb, String... command)
            throws IOException, InterruptedException {
        int maxAttempts = 3;
        long delayMs = 50;
        IOException lastException = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return pb.start();
            } catch (IOException e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("error=26") && attempt < maxAttempts - 1) {
                    lastException = e;
                    System.err.printf("ETXTBSY on attempt %d for %s — retrying in %dms%n",
                            attempt + 1, Arrays.toString(command), delayMs);
                    Thread.sleep(delayMs);
                    delayMs *= 2;
                } else if (msg != null && msg.contains("error=13")
                        && !pb.command().isEmpty() && pb.command().get(0).equals("./mvnw")
                        && attempt == 0) {
                    System.err.printf("EACCES on ./mvnw for %s — falling back to system mvn%n",
                            Arrays.toString(command));
                    pb.command().set(0, "mvn");
                    lastException = e;
                } else {
                    throw e;
                }
            }
        }
        throw lastException; // unreachable, but satisfies the compiler
    }

    private void handleTimeout(Process process, String... command) {
        System.err.println("TIMEOUT: Build exceeded " + timeoutSeconds +
                " seconds. Killing process: " + Arrays.toString(command));

        killProcessTree(process);

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleExecutionError(Process process, Exception e) {
        e.printStackTrace();

        if (process != null && process.isAlive()) {
            killProcessTree(process);
        }
    }

    /** Kill the process and all its descendants (e.g. surefire-forked JVMs, JaCoCo agents). */
    private void killProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
