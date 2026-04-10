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
 * Two-phase Maven execution: compile first, skip test phase if the variant
 * can't beat the current best on successful modules (primary VariantScore tiebreaker).
 *
 * <p>Shared by both the experiment pipeline ({@link MavenExecutionFactory}) and the
 * IntelliJ plugin orchestrator.
 */
public class TwoPhaseRunner {
    private final MavenCommandResolver commandResolver;
    private final IntSupplier timeoutSupplier;
    private final Path logDir;
    private final String javaHome;

    /**
     * @param commandResolver resolves Maven executable and goal
     * @param timeoutSupplier supplies remaining seconds for each Maven invocation
     * @param logDir          directory for build log files
     * @param javaHome        JAVA_HOME override, or null for system default
     */
    public TwoPhaseRunner(MavenCommandResolver commandResolver, IntSupplier timeoutSupplier,
                           Path logDir, String javaHome) {
        this.commandResolver = commandResolver;
        this.timeoutSupplier = timeoutSupplier;
        this.logDir = logDir;
        this.javaHome = javaHome;
    }

    /**
     * Run the two-phase build: compile only, then full test if competitive.
     *
     * @param variantPath    working directory for the Maven build
     * @param key            variant identifier (used for log file naming)
     * @param tracker        donor tracker for the two-phase gate (bestSuccessfulModules)
     * @param mavenRepoLocal per-variant local repo path (overlayFS mountpoint) for
     *                       isolation; {@code null} uses the default {@code ~/.m2/repository}
     * @return result containing the compilation result and whether tests were run
     */
    public TwoPhaseResult run(Path variantPath, String key, DonorTracker tracker,
                               Path mavenRepoLocal) throws IOException {
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

    /** Result of a two-phase build. */
    public record TwoPhaseResult(CompilationResult compilationResult, boolean testsRan) {}
}
