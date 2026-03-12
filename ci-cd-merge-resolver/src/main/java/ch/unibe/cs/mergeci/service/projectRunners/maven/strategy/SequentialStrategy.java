package ch.unibe.cs.mergeci.service.projectRunners.maven.strategy;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenProcessExecutor;

import java.nio.file.Path;

/**
 * Sequential Maven execution strategy.
 * Executes Maven builds one project at a time, in order.
 */
public class SequentialStrategy implements MavenExecutionStrategy {
    private final MavenCommandResolver commandResolver;
    private final MavenProcessExecutor processExecutor;
    private final Path logDir;

    public SequentialStrategy(
            MavenCommandResolver commandResolver,
            MavenProcessExecutor processExecutor,
            Path logDir) {
        this.commandResolver = commandResolver;
        this.processExecutor = processExecutor;
        this.logDir = logDir;
    }

    @Override
    public void execute(Path... projects) {
        for (Path project : projects) {
            String mavenCommand = commandResolver.resolveMavenCommand(project);
            String projectName = project.getFileName().toString();
            Path logFile = logDir.resolve(projectName + "_compilation");

            processExecutor.executeCommand(
                    project,
                    logFile,
                    mavenCommand,
                    AppConfig.MAVEN_FAIL_MODE,
                    AppConfig.MAVEN_TEST_FAILURE_IGNORE,
                    "test");
        }
    }

    @Override
    public String getName() {
        return "Sequential";
    }
}
