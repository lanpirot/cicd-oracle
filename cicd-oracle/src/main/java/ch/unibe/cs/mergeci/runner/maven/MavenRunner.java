package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.maven.strategy.CacheParallelStrategy;
import ch.unibe.cs.mergeci.runner.maven.strategy.MavenExecutionStrategy;
import ch.unibe.cs.mergeci.runner.maven.strategy.ParallelStrategy;
import ch.unibe.cs.mergeci.runner.maven.strategy.SequentialStrategy;
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

    public MavenRunner(Path logDir, boolean isUseMavenDaemon, int timeoutSeconds) {
        this.logDir = logDir;
        this.logDir.toFile().mkdirs();
        this.commandResolver = new MavenCommandResolver(isUseMavenDaemon);
        this.processExecutor = new MavenProcessExecutor(timeoutSeconds);
        this.cacheManager = new MavenCacheManager();
    }

    /**
     * Returns a new runner that shares this runner's log directory and command resolver
     * (preserving daemon settings) but uses a different per-process timeout.
     * Used to honour a per-merge deadline across multiple sequential Maven invocations.
     */
    public MavenRunner withTimeout(int timeoutSeconds) {
        return new MavenRunner(this.logDir, this.commandResolver, timeoutSeconds);
    }

    /** Internal constructor that accepts a pre-built command resolver (used by withTimeout). */
    MavenRunner(Path logDir, MavenCommandResolver commandResolver, int timeoutSeconds) {
        this.logDir = logDir;
        this.logDir.toFile().mkdirs();
        this.commandResolver = commandResolver;
        this.processExecutor = new MavenProcessExecutor(timeoutSeconds);
        this.cacheManager = new MavenCacheManager();
    }

    public MavenRunner(Path tempDir, int timeoutSeconds) {
        this(tempDir, false, timeoutSeconds);
    }

    public MavenRunner(Path tempDir) {
        this(tempDir, false, AppConfig.MAVEN_BUILD_TIMEOUT);
    }

    public MavenRunner() {
        this(AppConfig.TMP_DIR, false, AppConfig.MAVEN_BUILD_TIMEOUT);
    }

    /** Execute Maven builds using cache-enabled parallel strategy. */
    public void run_cache_parallel(Path... projects) {
        execute(new CacheParallelStrategy(commandResolver, processExecutor, cacheManager, logDir), projects);
    }

    /** Execute Maven builds sequentially without optimizations. */
    public void run_no_optimization(Path... projects) {
        execute(new SequentialStrategy(commandResolver, processExecutor, logDir), projects);
    }

    /** Execute Maven builds sequentially, overriding JAVA_HOME for all builds. */
    public void run_no_optimization(String javaHome, Path... projects) {
        execute(new SequentialStrategy(commandResolver, processExecutor, logDir, javaHome), projects);
    }

    /** Execute Maven builds in parallel. */
    public void run_parallel(Path... projects) {
        execute(new ParallelStrategy(commandResolver, processExecutor, logDir), projects);
    }

    /** Execute Maven builds using a custom strategy. */
    public void execute(MavenExecutionStrategy strategy, Path... projects) {
        strategy.execute(projects);
    }
}
