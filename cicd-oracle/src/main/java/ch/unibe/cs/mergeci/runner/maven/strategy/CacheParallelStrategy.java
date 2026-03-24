package ch.unibe.cs.mergeci.runner.maven.strategy;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.maven.MavenCacheManager;
import ch.unibe.cs.mergeci.runner.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.runner.maven.MavenProcessExecutor;
import ch.unibe.cs.mergeci.util.Utility;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cache-enabled parallel Maven execution strategy.
 * Builds the first project normally, then builds remaining projects in parallel
 * using the cached artifacts from the first build.
 */
public class CacheParallelStrategy implements MavenExecutionStrategy {
    private final MavenCommandResolver commandResolver;
    private final MavenProcessExecutor processExecutor;
    private final MavenCacheManager cacheManager;
    private final Path logDir;

    public CacheParallelStrategy(
            MavenCommandResolver commandResolver,
            MavenProcessExecutor processExecutor,
            MavenCacheManager cacheManager,
            Path logDir) {
        this.commandResolver = commandResolver;
        this.processExecutor = processExecutor;
        this.cacheManager = cacheManager;
        this.logDir = logDir;
    }

    @Override
    public void execute(Path... projects) {
        if (projects.length == 0) {
            return;
        }

        // Build first project normally to populate cache
        buildFirstProject(projects[0]);

        // Build remaining projects in parallel using cache
        if (projects.length > 1) {
            buildRemainingProjectsInParallel(projects);
        }
    }

    private void buildFirstProject(Path project) {
        System.out.println(project.toAbsolutePath());

        cacheManager.injectCacheArtifacts(project);

        String mavenCommand = commandResolver.resolveMavenCommand(project);
        String mavenGoal   = commandResolver.resolveMavenGoal(project);
        String projectName = project.getFileName().toString();
        Path logFile = logDir.resolve(projectName + "_compilation");

        processExecutor.executeCommand(project, logFile, AppConfig.buildCommand(mavenCommand, mavenGoal, project));
    }

    private void buildRemainingProjectsInParallel(Path[] projects) {
        ExecutorService executorService = Executors.newFixedThreadPool(AppConfig.MAX_THREADS);

        Path firstProject = projects[0];

        for (int i = 1; i < projects.length; i++) {
            final Path project = projects[i];

            executorService.submit(() -> {
                try {
                    buildProjectWithCache(project, firstProject);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        Utility.shutdownAndAwaitTermination(executorService);
    }

    private void buildProjectWithCache(Path project, Path sourceProject) {
        String projectName = project.getFileName().toString();

        // Inject cache configuration
        cacheManager.injectCacheArtifacts(project);

        // Copy target directories and cache from first project
        cacheManager.copyTargetDirectories(sourceProject.toFile(), project.toFile());
        cacheManager.copyCacheDirectory(sourceProject, project);

        // Build with offline mode to use cache
        String mavenCommand = commandResolver.resolveMavenCommand(project);
        String mavenGoal   = commandResolver.resolveMavenGoal(project);
        Path logFile = logDir.resolve(projectName + "_compilation");

        processExecutor.executeCommand(project, logFile, AppConfig.buildCommandOffline(mavenCommand, mavenGoal, project));
    }

    @Override
    public String getName() {
        return "Cache-Parallel";
    }
}
