package ch.unibe.cs.mergeci.runner.maven.strategy;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.runner.maven.MavenProcessExecutor;

import java.nio.file.Path;

/**
 * Sequential Maven execution strategy.
 * Executes Maven builds one project at a time, in order.
 */
public class SequentialStrategy implements MavenExecutionStrategy {
    private final MavenCommandResolver commandResolver;
    private final MavenProcessExecutor processExecutor;
    private final Path logDir;
    private final String javaHome;  // null = use system default

    public SequentialStrategy(MavenCommandResolver commandResolver,
                               MavenProcessExecutor processExecutor,
                               Path logDir) {
        this(commandResolver, processExecutor, logDir, null);
    }

    public SequentialStrategy(MavenCommandResolver commandResolver,
                               MavenProcessExecutor processExecutor,
                               Path logDir,
                               String javaHome) {
        this.commandResolver = commandResolver;
        this.processExecutor = processExecutor;
        this.logDir = logDir;
        this.javaHome = javaHome;
    }

    @Override
    public void execute(Path... projects) {
        for (Path project : projects) {
            String[] execArgs = commandResolver.resolveExecutableArgs(project);
            String mavenGoal  = commandResolver.resolveMavenGoal(project);
            Path logFile = logDir.resolve(project.getFileName().toString() + "_compilation");

            processExecutor.executeCommand(project, logFile, javaHome,
                    AppConfig.buildCommand(execArgs, mavenGoal));
        }
    }

}
