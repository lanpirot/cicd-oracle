package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.runner.maven.strategy.SequentialStrategy;

import java.nio.file.Path;

/**
 * Orchestrates Maven build and test execution.
 * Used by MavenExecutionFactory for the human baseline and sequential non-cache variant builds.
 */
public class MavenRunner {
    private final Path logDir;
    private final MavenCommandResolver commandResolver;
    private final MavenProcessExecutor processExecutor;

    public MavenRunner(Path logDir, boolean isUseMavenDaemon, int timeoutSeconds) {
        this.logDir = logDir;
        this.logDir.toFile().mkdirs();
        this.commandResolver = new MavenCommandResolver(isUseMavenDaemon);
        this.processExecutor = new MavenProcessExecutor(timeoutSeconds);
    }

    /** Execute Maven builds sequentially, overriding JAVA_HOME for all builds. */
    public void run_no_optimization(String javaHome, Path... projects) {
        new SequentialStrategy(commandResolver, processExecutor, logDir, javaHome)
                .execute(projects);
    }
}
