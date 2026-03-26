package ch.unibe.cs.mergeci.runner.maven.strategy;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.runner.maven.MavenProcessExecutor;
import ch.unibe.cs.mergeci.util.Utility;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Parallel Maven execution strategy.
 * Executes Maven builds for multiple projects concurrently.
 */
public class ParallelStrategy implements MavenExecutionStrategy {
    private final MavenCommandResolver commandResolver;
    private final MavenProcessExecutor processExecutor;
    private final Path logDir;

    public ParallelStrategy(
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
                    executeProject(project);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        Utility.shutdownAndAwaitTermination(executorService);
    }

    private void executeProject(Path project) {
        String mavenCommand = commandResolver.resolveMavenCommand(project);
        String mavenGoal   = commandResolver.resolveMavenGoal(project);
        String projectName = project.getFileName().toString();
        Path logFile = logDir.resolve(projectName + "_compilation");

        processExecutor.executeCommand(project, logFile, null, AppConfig.buildCommand(mavenCommand, mavenGoal, project));
    }

    @Override
    public String getName() {
        return "Parallel";
    }
}
