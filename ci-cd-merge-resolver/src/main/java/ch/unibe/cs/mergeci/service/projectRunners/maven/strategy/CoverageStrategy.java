package ch.unibe.cs.mergeci.service.projectRunners.maven.strategy;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenProcessExecutor;
import ch.unibe.cs.mergeci.util.Utility;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Coverage-enabled Maven execution strategy.
 * Executes Maven builds with Jacoco code coverage in parallel.
 */
public class CoverageStrategy implements MavenExecutionStrategy {
    private final MavenCommandResolver commandResolver;
    private final MavenProcessExecutor processExecutor;
    private final Path logDir;

    public CoverageStrategy(
            MavenCommandResolver commandResolver,
            MavenProcessExecutor processExecutor,
            Path logDir) {
        this.commandResolver = commandResolver;
        this.processExecutor = processExecutor;
        this.logDir = logDir;
    }

    @Override
    public void execute(Path... projects) {
        ExecutorService executorService = Executors.newFixedThreadPool(AppConfig.MAX_THREADS);

        for (Path project : projects) {
            executorService.submit(() -> {
                try {
                    executeProjectWithCoverage(project);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        Utility.shutdownAndAwaitTermination(executorService);
    }

    private void executeProjectWithCoverage(Path project) {
        String mavenCommand = commandResolver.resolveMavenCommand(project);
        String projectName = project.getFileName().toString();
        Path logFile = logDir.resolve(projectName + "_compilation");

        String jacoco = AppConfig.JACOCO_FULL;
        String jacocoGoalPrepareAgent = ":prepare-agent";
        String jacocoGoalReport = ":report";

        processExecutor.executeCommand(
                project,
                logFile,
                mavenCommand,
                AppConfig.MAVEN_FAIL_MODE,
                AppConfig.MAVEN_TEST_FAILURE_IGNORE,
                jacoco + jacocoGoalPrepareAgent,
                "test",
                jacoco + jacocoGoalReport);
    }

    @Override
    public String getName() {
        return "Coverage";
    }
}
