package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.MavenRunner;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.JavaVersionResolver;
import ch.unibe.cs.mergeci.util.model.MergeInfo;
import lombok.Getter;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Processes individual merge commits by checking them out and running builds.
 * Handles the Git checkout, Maven build, and result extraction workflow.
 */
public class MergeCheckoutProcessor {
    private final MavenRunner mavenRunner;

    public MergeCheckoutProcessor(MavenRunner mavenRunner) {
        this.mavenRunner = mavenRunner;
    }

    /**
     * Process a single merge commit: checkout, build, test, and collect results.
     *
     * @param merge Merge information
     * @param projectPath Original project path
     * @param tempPath Temporary directory for checkout
     * @param projectName Base project name
     * @return Processing result with compilation and test information
     */
    public MergeProcessResult processMerge(
            MergeInfo merge,
            Path projectPath,
            Path tempPath,
            String projectName) throws GitAPIException, IOException {

        String mergeCommit = merge.getResultedMergeCommit().getName();
        String shortCommit = mergeCommit.substring(0, AppConfig.HASH_PREFIX_LENGTH);
        String newProjectName = projectName + "_" + shortCommit;
        Path newProjectPath = tempPath.resolve(newProjectName);

        // Checkout merge commit to temporary directory
        checkoutMerge(projectPath, newProjectPath, mergeCommit);

        // Patch any hardcoded <skipTests>true</skipTests> in pom.xml files so tests always run
        FileUtils.enableTestsInAllPoms(newProjectPath);

        // Resolve JAVA_HOME:
        // - .mvn/jvm.config may contain JVM flags (e.g. --sun-misc-unsafe-memory-access=allow)
        //   that require a newer JVM to run Maven itself, regardless of compilation target.
        // - Otherwise use the closest JDK that satisfies the pom's declared source version.
        int requiredJava = JavaVersionResolver.detectRequiredVersion(newProjectPath);
        int usedJava = 0;
        String javaHome = null;
        if (JavaVersionResolver.hasMvnJvmConfig(newProjectPath)) {
            javaHome = JavaVersionResolver.resolveHighestJavaHome().orElse(null);
            usedJava = JavaVersionResolver.selectHighestVersion();
        } else if (requiredJava > 0) {
            int selected = JavaVersionResolver.selectClosestVersion(requiredJava);
            javaHome = JavaVersionResolver.resolveJavaHome(newProjectPath).orElse(null);
            if (javaHome != null) usedJava = selected;
        }

        // Pre-install SNAPSHOT inter-module dependencies so a cold build can resolve them.
        // Multi-module SNAPSHOT projects publish sibling artifacts only to the local cache;
        // the first merge checkout would fail dependency resolution without this step.
        if (isSnapshotMultiModule(newProjectPath)) {
            System.out.printf("  ⚙ SNAPSHOT multi-module — pre-installing to local cache...%n");
            preInstall(newProjectPath, javaHome);
        }

        runBuild(javaHome, newProjectPath);

        MergeProcessResult result = extractResults(newProjectPath, newProjectName, merge, requiredJava, usedJava);

        // Retry 1: pom-based version detection missed the requirement (e.g. bare <source>1.6</source>
        // inside plugin config rather than a <maven.compiler.source> property).
        if (!result.isHadTests() && result.isJavaVersionError() && javaHome == null) {
            Path logFile = mavenRunner.getLogDir().resolve(newProjectName + "_compilation");
            int errorVersion = JavaVersionResolver.detectVersionErrorInLog(logFile);
            if (errorVersion > 0) {
                int retrySelected = JavaVersionResolver.selectClosestVersion(errorVersion);
                Optional<String> retryHome = JavaVersionResolver.resolveJavaHomeForVersion(errorVersion);
                if (retryHome.isPresent()) {
                    System.out.printf("  ↻ Java version error (required: %d) — retrying with Java %d%n",
                            errorVersion, retrySelected);
                    runBuild(retryHome.get(), newProjectPath);
                    result = extractResults(newProjectPath, newProjectName, merge, errorVersion, retrySelected);
                }
            }
        }

        // Retry 2: plugin compatibility error (e.g. gmaven-plugin:1.5 ExceptionInInitializerError on Java 9+).
        // Not caught by the source-version pattern — retry with Java 8 which still runs old Groovy runtimes.
        if (!result.isHadTests() && !result.isJavaVersionError() && !result.isTimedOut() && javaHome == null) {
            Path logFile = mavenRunner.getLogDir().resolve(newProjectName + "_compilation");
            if (JavaVersionResolver.detectPluginCompatibilityError(logFile)) {
                Optional<String> retryHome = JavaVersionResolver.resolveJavaHomeForVersion(8);
                if (retryHome.isPresent()) {
                    System.out.printf("  ↻ Plugin compatibility error — retrying with Java 8%n");
                    runBuild(retryHome.get(), newProjectPath);
                    result = extractResults(newProjectPath, newProjectName, merge, 8, 8);
                }
            }
        }

        return result;
    }

    private void runBuild(String javaHome, Path projectPath) {
        if (javaHome != null) {
            mavenRunner.run_no_optimization(javaHome, projectPath);
        } else {
            mavenRunner.run_no_optimization(projectPath);
        }
    }

    private void preInstall(Path projectPath, String javaHome) {
        String mvnCmd = mavenRunner.getCommandResolver().resolveMavenCommand(projectPath);
        String[] cmd = {mvnCmd, "-B", "-fae", "-DskipTests=true", "-Dmaven.test.skip=true", "install"};
        Path logFile = mavenRunner.getLogDir().resolve(projectPath.getFileName() + "_preinstall");
        if (javaHome != null) {
            mavenRunner.getProcessExecutor().executeCommandWithJavaHome(projectPath, logFile, javaHome, cmd);
        } else {
            mavenRunner.getProcessExecutor().executeCommand(projectPath, logFile, cmd);
        }
    }

    private static boolean isSnapshotMultiModule(Path projectPath) {
        Path pomFile = projectPath.resolve("pom.xml");
        if (!pomFile.toFile().exists()) return false;
        try {
            String pom = Files.readString(pomFile);
            return pom.contains("-SNAPSHOT") && pom.contains("<modules>");
        } catch (IOException e) {
            return false;
        }
    }

    private void checkoutMerge(Path sourcePath, Path targetPath, String commitHash)
            throws IOException, GitAPIException {

        try {
            FileUtils.copyDirectoryCompatibilityMode(sourcePath.toFile(), targetPath.toFile());
        } catch (IOException e) {
            System.err.println("Error copying folder: " + e.getMessage());
            throw e;
        }

        try (org.eclipse.jgit.api.Git git = GitUtils.getGit(targetPath)) {
            git.checkout().setName(commitHash).setForced(true).call();
        }
    }

    private MergeProcessResult extractResults(Path projectPath, String projectName,
                                              MergeInfo merge, int requiredJava, int usedJava)
            throws IOException {

        Path compilationLogPath = mavenRunner.getLogDir().resolve(projectName + "_compilation");

        if (!compilationLogPath.toFile().exists()) {
            return MergeProcessResult.builder()
                    .hadTests(true)
                    .timedOut(false)
                    .requiredJavaVersion(requiredJava)
                    .usedJavaVersion(usedJava)
                    .build();
        }

        CompilationResult compilationResult = new CompilationResult(compilationLogPath);

        if (compilationResult.getBuildStatus() == CompilationResult.Status.TIMEOUT) {
            return MergeProcessResult.builder()
                    .hadTests(true)
                    .timedOut(true)
                    .requiredJavaVersion(requiredJava)
                    .usedJavaVersion(usedJava)
                    .build();
        }

        boolean compilationSuccess = compilationResult.getBuildStatus() == CompilationResult.Status.SUCCESS;

        // Check for Java version errors in log (covers cases where pom.xml detection missed something)
        int versionErrorInLog = compilationSuccess ? 0
                : JavaVersionResolver.detectVersionErrorInLog(compilationLogPath);
        boolean javaVersionError = versionErrorInLog > 0;

        // Classify non-compilation failures: infra (dead repo/toolchain) vs genuine broken merge
        boolean infraFailure = !compilationSuccess && BuildFailureClassifier.isInfraFailure(compilationLogPath);
        boolean brokenMerge  = !compilationSuccess && !infraFailure
                && BuildFailureClassifier.isGenuineCompilationError(compilationLogPath);

        TestTotal testTotal = new TestTotal(projectPath.toFile());
        int runTests = testTotal.getRunNum();

        if (runTests == 0) {

            String mvnExe201 = mavenRunner.getCommandResolver().resolveMavenCommand(projectPath);
            String mvnGoal201 = mavenRunner.getCommandResolver().resolveMavenGoal(projectPath);
            String mvnCmd201 = String.join(" ", AppConfig.buildCommand(mvnExe201, mvnGoal201));
            String javaPrefix201 = usedJava > 0 ? "JAVA_HOME=<java" + usedJava + "> " : "";
            System.err.printf("  [BREAKPOINT] No tests ran — compilationSuccess=%b javaVersionError=%b infraFailure=%b brokenMerge=%b%n  → %s%s%n  → path=%s%n", // BREAKPOINT
                    compilationSuccess, javaVersionError, infraFailure, brokenMerge, javaPrefix201, mvnCmd201, projectPath);
            return MergeProcessResult.builder()
                    .hadTests(false)
                    .timedOut(false)
                    .compilationSuccess(compilationSuccess)
                    .compilationResult(compilationResult)
                    .merge(merge)
                    .requiredJavaVersion(requiredJava)
                    .usedJavaVersion(usedJava)
                    .javaVersionError(javaVersionError)
                    .infraFailure(infraFailure)
                    .brokenMerge(brokenMerge)
                    .build();
        }

        int passedTests = runTests - testTotal.getFailuresNum() - testTotal.getErrorsNum();
        boolean testSuccess = passedTests > 0;
        float time = testTotal.getElapsedTime();

        int numberOfModules = compilationResult.getNumberOfModules();
        int modulesPassed = compilationResult.getNumberOfSuccessfulModules();
        if (numberOfModules == 0) {
            numberOfModules = 1;
            modulesPassed = compilationSuccess ? 1 : 0;
        }

        float compilationTime;
        if (compilationSuccess) {
            compilationTime = compilationResult.getTotalTime() - time;
        } else {
            compilationTime = 0;
        }

        if (modulesPassed == 0) {
            String mvnExe231 = mavenRunner.getCommandResolver().resolveMavenCommand(projectPath);
            String mvnGoal231 = mavenRunner.getCommandResolver().resolveMavenGoal(projectPath);
            String mvnCmd231 = String.join(" ", AppConfig.buildCommand(mvnExe231, mvnGoal231));
            String javaPrefix231 = usedJava > 0 ? "JAVA_HOME=<java" + usedJava + "> " : "";
            System.err.printf("  [BREAKPOINT] Build failed — modulesPassed=%d/%d passedTests=%d%n  → %s%s%n  → path=%s%n", // BREAKPOINT
                    modulesPassed, numberOfModules, passedTests, javaPrefix231, mvnCmd231, projectPath);
            testSuccess = false;
            passedTests = 0;
            runTests = 0;
            time = 0;
            compilationTime = 0;
        }

        float normalizedElapsedTime = TestTotal.normalizeElapsedTime(compilationTime, time, runTests, passedTests);

        return MergeProcessResult.builder()
                .hadTests(true)
                .timedOut(false)
                .compilationSuccess(compilationSuccess)
                .testSuccess(testSuccess)
                .compilationResult(compilationResult)
                .testTotal(testTotal)
                .merge(merge)
                .numTests(runTests)
                .numPassedTests(passedTests)
                .elapsedTime(time)
                .compilationTime(compilationTime)
                .normalizedElapsedTime(normalizedElapsedTime)
                .numberOfModules(numberOfModules)
                .modulesPassed(modulesPassed)
                .requiredJavaVersion(requiredJava)
                .usedJavaVersion(usedJava)
                .javaVersionError(javaVersionError)
                .build();
    }

    /**
     * Result of processing a single merge.
     */
    @Getter
    @lombok.Builder
    public static class MergeProcessResult {
        private final boolean hadTests;
        private final boolean timedOut;
        private final boolean compilationSuccess;
        private final boolean testSuccess;
        private final CompilationResult compilationResult;
        private final TestTotal testTotal;
        private final MergeInfo merge;
        private final int numTests;
        private final float elapsedTime;
        private final int numPassedTests;
        private final float compilationTime;
        private final float normalizedElapsedTime;
        private final int numberOfModules;
        private final int modulesPassed;
        // Java version diagnostics
        private final int requiredJavaVersion;   // from pom.xml; 0 = not detected
        private final int usedJavaVersion;       // version we switched to; 0 = system default
        private final boolean javaVersionError;  // Maven log contained a source/release version error
        // Build failure classification
        private final boolean infraFailure;  // dead repo / frontend toolchain — permanently unfixable
        private final boolean brokenMerge;  // genuine javac error in merged source — a variant may fix it

        /**
         * Check if this result represents a successful processing.
         * With -fae flag, we accept partial compilation if:
         * - At least some modules compiled (modulesPassed > 0)
         * - At least some tests passed (numPassedTests > 0)
         * - Build didn't time out
         */
        public boolean isSuccessful() {
            return hadTests && !timedOut && modulesPassed > 0 && numPassedTests > 0;
        }
    }
}
