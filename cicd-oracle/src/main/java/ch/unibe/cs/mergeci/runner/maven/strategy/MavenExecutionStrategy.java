package ch.unibe.cs.mergeci.runner.maven.strategy;

import java.nio.file.Path;

/**
 * Strategy interface for different Maven execution modes.
 * Implementations define how multiple Maven projects should be built and tested.
 */
public interface MavenExecutionStrategy {

    /**
     * Execute Maven build and test for multiple projects.
     *
     * @param projects Array of project paths to build and test
     */
    void execute(Path... projects);
}
