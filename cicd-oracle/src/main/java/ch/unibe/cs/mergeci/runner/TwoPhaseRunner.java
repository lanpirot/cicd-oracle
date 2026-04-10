package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.runner.maven.MavenProcessExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.IntSupplier;

/**
 * Maven build runner with an early-abort gate: skip the test phase if the variant
 * can't beat the current best on successful modules (primary VariantScore tiebreaker).
 *
 * <p>Supports two strategies:
 * <ul>
 *   <li><b>Single-phase (preferred):</b> Runs one {@code mvn test} invocation with
 *       {@code -Dcicd.bestModules=N}.  The maven-hook ({@link ch.unibe.cs.MavenHook})
 *       monitors module outcomes during the build and aborts after compile if the
 *       variant can't reach N.  This avoids the overhead of two Maven reactor
 *       resolutions — especially significant with mvnd where the JVM is already warm.</li>
 *   <li><b>Two-phase (fallback):</b> Runs {@code mvn compile}, parses the log, then
 *       conditionally runs {@code mvn test}.  Used when the maven-hook is not installed
 *       (e.g. projects without the hook extension).</li>
 * </ul>
 *
 * <p>Shared by both the experiment pipeline ({@link MavenExecutionFactory}) and the
 * IntelliJ plugin orchestrator.
 */
public class TwoPhaseRunner {
    private final MavenCommandResolver commandResolver;
    private final IntSupplier timeoutSupplier;
    private final Path logDir;
    private final String javaHome;
    private final boolean singlePhase;

    /**
     * @param commandResolver resolves Maven executable and goal
     * @param timeoutSupplier supplies remaining seconds for each Maven invocation
     * @param logDir          directory for build log files
     * @param javaHome        JAVA_HOME override, or null for system default
     * @param singlePhase     true to use the maven-hook early-abort gate (one invocation)
     */
    public TwoPhaseRunner(MavenCommandResolver commandResolver, IntSupplier timeoutSupplier,
                           Path logDir, String javaHome, boolean singlePhase) {
        this.commandResolver = commandResolver;
        this.timeoutSupplier = timeoutSupplier;
        this.logDir = logDir;
        this.javaHome = javaHome;
        this.singlePhase = singlePhase;
    }

    /** Legacy constructor — defaults to two-phase. */
    public TwoPhaseRunner(MavenCommandResolver commandResolver, IntSupplier timeoutSupplier,
                           Path logDir, String javaHome) {
        this(commandResolver, timeoutSupplier, logDir, javaHome, false);
    }

    /**
     * Run the build with the early-abort gate.
     *
     * @param variantPath    working directory for the Maven build
     * @param key            variant identifier (used for log file naming)
     * @param tracker        donor tracker for the gate threshold (bestSuccessfulModules)
     * @param mavenRepoLocal per-variant local repo path for isolation; null uses default
     * @return result containing the compilation result and whether tests were run
     */
    public TwoPhaseResult run(Path variantPath, String key, DonorTracker tracker,
                               Path mavenRepoLocal) throws IOException {
        if (singlePhase) {
            return runSinglePhase(variantPath, key, tracker, mavenRepoLocal);
        } else {
            return runTwoPhase(variantPath, key, tracker, mavenRepoLocal);
        }
    }

    /**
     * Single Maven invocation with -Dcicd.bestModules=N.  The maven-hook aborts
     * after compile if the variant can't reach the threshold; otherwise the full
     * test lifecycle runs.  One reactor resolution, one dependency graph.
     */
    private TwoPhaseResult runSinglePhase(Path variantPath, String key,
                                           DonorTracker tracker, Path mavenRepoLocal)
            throws IOException {
        String[] execArgs = commandResolver.resolveExecutableArgs(variantPath);
        String mavenGoal = commandResolver.resolveMavenGoal(variantPath);
        Path fullLog = logDir.resolve(key + "_compilation");
        Path sidecar = variantPath.resolve(".cicd-hook-result.json");

        int timeout = timeoutSupplier.getAsInt();
        if (timeout <= 0) return new TwoPhaseResult(null, false);

        int threshold = tracker.getBestSuccessfulModules();
        String[] cmd = (threshold > 0)
                ? AppConfig.buildCommandWithGate(execArgs, mavenGoal, threshold)
                : AppConfig.buildCommand(execArgs, mavenGoal);

        new MavenProcessExecutor(timeout).executeCommand(
                variantPath, fullLog, javaHome, withRepoLocal(cmd, mavenRepoLocal));

        // Check if the hook wrote a sidecar (early abort)
        if (Files.exists(sidecar)) {
            // Hook aborted — tests did not run.  Parse the actual log for
            // CompilationResult (it has the Reactor Summary up to the abort point).
            CompilationResult cr = new CompilationResult(fullLog);
            Files.deleteIfExists(sidecar);
            return new TwoPhaseResult(cr, false);
        }

        // Normal completion — full test lifecycle ran
        CompilationResult cr = new CompilationResult(fullLog);
        return new TwoPhaseResult(cr, true);
    }

    /**
     * Original two-phase approach: compile-only first, then full test if competitive.
     * Kept as fallback for projects without the maven-hook extension.
     */
    private TwoPhaseResult runTwoPhase(Path variantPath, String key,
                                        DonorTracker tracker, Path mavenRepoLocal)
            throws IOException {
        String[] execArgs = commandResolver.resolveExecutableArgs(variantPath);
        String mavenGoal = commandResolver.resolveMavenGoal(variantPath);
        Path compileLog = logDir.resolve(key + "_compile_phase");
        Path fullLog = logDir.resolve(key + "_compilation");

        // Phase 1: compile only
        int compileTimeout = timeoutSupplier.getAsInt();
        if (compileTimeout <= 0) return new TwoPhaseResult(null, false);
        new MavenProcessExecutor(compileTimeout).executeCommand(
                variantPath, compileLog, javaHome,
                withRepoLocal(AppConfig.buildCompileOnlyCommand(execArgs), mavenRepoLocal));

        CompilationResult cr = new CompilationResult(compileLog);
        int modules = cr.getSuccessfulModules();

        if (modules < tracker.getBestSuccessfulModules()) {
            Files.move(compileLog, fullLog, StandardCopyOption.REPLACE_EXISTING);
            return new TwoPhaseResult(cr, false);
        }

        // Phase 2: full test lifecycle (re-compiles incrementally, then tests)
        int testTimeout = timeoutSupplier.getAsInt();
        if (testTimeout <= 0) {
            Files.move(compileLog, fullLog, StandardCopyOption.REPLACE_EXISTING);
            return new TwoPhaseResult(cr, false);
        }
        Files.deleteIfExists(compileLog);
        new MavenProcessExecutor(testTimeout).executeCommand(
                variantPath, fullLog, javaHome,
                withRepoLocal(AppConfig.buildCommand(execArgs, mavenGoal), mavenRepoLocal));
        CompilationResult fullCr = new CompilationResult(fullLog);
        return new TwoPhaseResult(fullCr, true);
    }

    /** Append {@code -Dmaven.repo.local=<path>} to a Maven command array. */
    public static String[] withRepoLocal(String[] cmd, Path repoLocal) {
        if (repoLocal == null) return cmd;
        String[] result = java.util.Arrays.copyOf(cmd, cmd.length + 1);
        result[cmd.length] = "-Dmaven.repo.local=" + repoLocal;
        return result;
    }

    /** Result of a build with optional early-abort gate. */
    public record TwoPhaseResult(CompilationResult compilationResult, boolean testsRan) {}
}
