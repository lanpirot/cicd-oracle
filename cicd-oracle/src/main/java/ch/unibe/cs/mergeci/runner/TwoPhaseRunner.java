package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.runner.maven.MavenProcessExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntSupplier;

/**
 * Single-phase variant build runner with the maven-hook early-abort gate.
 *
 * <p>Runs one {@code mvn test} invocation with {@code -Dcicd.threshold-file=<path>}.
 * The maven-hook ({@link ch.unibe.cs.MavenHook}) monitors module outcomes during the
 * build and, after every module, atomically posts its running successful-module count
 * to the shared threshold file and reads back the latest high-water mark. If the
 * remaining modules cannot push the successful count to that threshold, the hook
 * writes a sidecar JSON and throws to abort. The cross-JVM file synchronisation lets
 * a faster variant lift the gate mid-flight on every variant currently running in
 * parallel for the same merge run, not just on variants that start later.
 *
 * <p>Shared by both the experiment pipeline ({@link MavenExecutionFactory}) and the
 * IntelliJ plugin orchestrator.
 */
public class TwoPhaseRunner {
    private final MavenCommandResolver commandResolver;
    private final IntSupplier timeoutSupplier;
    private final Path logDir;
    private final String javaHome;
    /** When non-null, mvnd daemons are spawned with this stable cwd instead of the
     *  variant directory.  Prevents crashes when overlayFS mounts are unmounted while
     *  daemons are still alive (daemon cwd would point at a deleted FUSE mount). */
    private final Path stableCwd;

    /**
     * @param commandResolver resolves Maven executable and goal
     * @param timeoutSupplier supplies remaining seconds for each Maven invocation
     * @param logDir          directory for build log files
     * @param javaHome        JAVA_HOME override, or null for system default
     * @param stableCwd       stable working directory for mvnd daemon processes (e.g. the plugin
     *                        temp dir).  When non-null, the Maven process is started with this cwd
     *                        and {@code -f <variantPath>} is passed explicitly.  Null uses the
     *                        variant directory as cwd (default, safe for non-overlay builds).
     */
    public TwoPhaseRunner(MavenCommandResolver commandResolver, IntSupplier timeoutSupplier,
                           Path logDir, String javaHome, Path stableCwd) {
        this.commandResolver = commandResolver;
        this.timeoutSupplier = timeoutSupplier;
        this.logDir = logDir;
        this.javaHome = javaHome;
        this.stableCwd = stableCwd;
    }

    /**
     * Run the build with the early-abort gate.
     *
     * @param variantPath    working directory for the Maven build
     * @param key            variant identifier (used for log file naming)
     * @param thresholdFile  shared cross-JVM threshold file; non-null enables the gate,
     *                       null falls back to a no-gate {@code mvn test}
     * @param mavenRepoLocal per-variant local repo path for isolation; null uses default
     * @return result containing the compilation result and whether tests were run
     */
    public TwoPhaseResult run(Path variantPath, String key, Path thresholdFile,
                               Path mavenRepoLocal) throws IOException {
        return run(variantPath, key, thresholdFile, mavenRepoLocal, null, false);
    }

    /**
     * Run the build with the early-abort gate, optionally restricted to a pruned reactor.
     *
     * @param pruning when non-null, the reactor is limited to {@link PruningSpec#affectedModulesCsv()}
     *                via {@code -pl}, the build-cache extension is disabled, and (if
     *                {@link PruningSpec#goalOverride()} is non-null) the resolved goal is replaced.
     *                Donor builds typically pass {@code goalOverride="install"} with no
     *                {@code affectedModulesCsv} (full reactor); pruned variants pass an
     *                affected list with no goal override.
     * @param disableBuildCache when true, append {@code -Dmaven.build.cache.enabled=false}
     *                so the (still-loaded) maven-build-cache extension short-circuits
     *                without hashing inputs. Caller should set this in cache modes when
     *                no donor has been warmed yet — paying the hash tax is pure waste
     *                until something can actually be cache-hit.
     */
    public TwoPhaseResult run(Path variantPath, String key, Path thresholdFile,
                               Path mavenRepoLocal, PruningSpec pruning,
                               boolean disableBuildCache) throws IOException {
        String[] execArgs = commandResolver.resolveExecutableArgs(variantPath);
        String mavenGoal = commandResolver.resolveMavenGoal(variantPath);
        if (pruning != null && pruning.goalOverride() != null) {
            mavenGoal = pruning.goalOverride();
        }
        Path fullLog = logDir.resolve(key + "_compilation");
        Path sidecar = variantPath.resolve(".cicd-hook-result.json");

        int timeout = timeoutSupplier.getAsInt();
        if (timeout <= 0) return new TwoPhaseResult(null, false);

        String[] cmd;
        if (pruning != null && pruning.affectedModulesCsv() != null) {
            // Pruned variants don't get the early-abort gate: the gate's threshold is
            // module-count, but a pruned variant builds only a subset of modules so its
            // count is structurally lower than the donor's — the gate would always fire.
            cmd = AppConfig.buildPrunedCommand(execArgs, mavenGoal, pruning.affectedModulesCsv());
        } else if (thresholdFile != null) {
            cmd = AppConfig.buildCommandWithGate(execArgs, mavenGoal, thresholdFile);
        } else {
            cmd = AppConfig.buildCommand(execArgs, mavenGoal);
        }
        if (disableBuildCache) cmd = appendArg(cmd, "-Dmaven.build.cache.enabled=false");

        executeMaven(variantPath, fullLog, timeout, withRepoLocal(cmd, mavenRepoLocal));

        // Hook wrote a sidecar = build was early-aborted, tests did not run.
        // Parse the actual log for CompilationResult (it has the Reactor Summary
        // up to the abort point).
        if (Files.exists(sidecar)) {
            CompilationResult cr = new CompilationResult(fullLog);
            Files.deleteIfExists(sidecar);
            return new TwoPhaseResult(cr, false);
        }

        // Normal completion — full test lifecycle ran
        CompilationResult cr = new CompilationResult(fullLog);
        return new TwoPhaseResult(cr, true);
    }

    /** Execute Maven, using stable cwd when configured to avoid overlay mount issues. */
    private void executeMaven(Path variantPath, Path logFile, int timeout, String... cmd) {
        MavenProcessExecutor executor = new MavenProcessExecutor(timeout);
        if (stableCwd != null) {
            executor.executeCommandStableCwd(stableCwd, variantPath, logFile, javaHome, cmd);
        } else {
            executor.executeCommand(variantPath, logFile, javaHome, cmd);
        }
    }

    /** Append {@code -Dmaven.repo.local=<path>} to a Maven command array. */
    public static String[] withRepoLocal(String[] cmd, Path repoLocal) {
        if (repoLocal == null) return cmd;
        return appendArg(cmd, "-Dmaven.repo.local=" + repoLocal);
    }

    private static String[] appendArg(String[] cmd, String arg) {
        String[] result = java.util.Arrays.copyOf(cmd, cmd.length + 1);
        result[cmd.length] = arg;
        return result;
    }

    /** Result of a build with optional early-abort gate. */
    public record TwoPhaseResult(CompilationResult compilationResult, boolean testsRan) {}

    /**
     * Selective-reactor-pruning instructions for a single Maven invocation.
     *
     * @param affectedModulesCsv comma-separated relative module paths for {@code -pl},
     *                            or {@code null} to keep the full reactor
     * @param goalOverride       Maven goal to use instead of the resolver's default,
     *                            or {@code null} to keep the resolved goal. Use {@code "install"}
     *                            for the donor variant so unaffected modules' jars land in
     *                            the per-thread {@code ~/.m2/}.
     */
    public record PruningSpec(String affectedModulesCsv, String goalOverride) {
        public static PruningSpec donor()                       { return new PruningSpec(null, "install"); }
        public static PruningSpec prune(String csv)             { return new PruningSpec(csv, null); }
    }
}
