package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.service.projectRunners.maven.strategy.CacheParallelStrategy;
import ch.unibe.cs.mergeci.service.projectRunners.maven.strategy.CoverageStrategy;
import ch.unibe.cs.mergeci.service.projectRunners.maven.strategy.MavenExecutionStrategy;
import ch.unibe.cs.mergeci.service.projectRunners.maven.strategy.ParallelStrategy;
import ch.unibe.cs.mergeci.service.projectRunners.maven.strategy.SequentialStrategy;
import lombok.Getter;

import java.nio.file.Path;

/**
 * Orchestrates Maven build and test execution using different strategies.
 * Delegates to specialized components for command resolution, process execution, and cache management.
 */
@Getter
public class MavenRunner {
    private final Path logDir;
    private final MavenCommandResolver commandResolver;
    private final MavenProcessExecutor processExecutor;
    private final MavenCacheManager cacheManager;

    public MavenRunner(Path logDir, boolean isUseMavenDaemon, int timeoutMinutes) {
        this.logDir = logDir;
        this.logDir.toFile().mkdirs();
        this.commandResolver = new MavenCommandResolver(isUseMavenDaemon);
        this.processExecutor = new MavenProcessExecutor(timeoutMinutes);
        this.cacheManager = new MavenCacheManager();
    }

    public MavenRunner(Path tempDir, int timeoutMinutes) {
        this(tempDir, false, timeoutMinutes);
    }

    public MavenRunner(Path tempDir) {
        this(tempDir, false, AppConfig.MAVEN_BUILD_TIMEOUT);
    }

    public MavenRunner() {
        this(AppConfig.TMP_DIR, false, AppConfig.MAVEN_BUILD_TIMEOUT);
    }

    /**
     * Execute Maven builds using cache-enabled parallel strategy.
     * Builds first project normally, then remaining projects in parallel using cached artifacts.
     */
    public void run_cache_parallel(Path... projects) {
        MavenExecutionStrategy strategy = new CacheParallelStrategy(
                commandResolver,
                processExecutor,
                cacheManager,
                logDir);

        strategy.execute(projects);
    }

    /**
     * Execute Maven builds sequentially without optimizations.
     */
    public void run_no_optimization(Path... projects) {
        MavenExecutionStrategy strategy = new SequentialStrategy(
                commandResolver,
                processExecutor,
                logDir);

        strategy.execute(projects);
    }

    /**
     * Execute Maven builds in parallel.
     */
    public void run_parallel(Path... projects) {
        MavenExecutionStrategy strategy = new ParallelStrategy(
                commandResolver,
                processExecutor,
                logDir);

        strategy.execute(projects);
    }

    /**
     * Execute Maven builds with Jacoco code coverage in parallel.
     */
    public void runWithCoverage(Path... projects) {
        MavenExecutionStrategy strategy = new CoverageStrategy(
                commandResolver,
                processExecutor,
                logDir);

        strategy.execute(projects);
    }

    /**
     * Execute Maven builds using a custom strategy.
     *
     * @param strategy The execution strategy to use
     * @param projects Projects to build
     */
    public void execute(MavenExecutionStrategy strategy, Path... projects) {
        strategy.execute(projects);
    }
}
