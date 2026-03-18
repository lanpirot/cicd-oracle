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

        // Detect required Java version from pom.xml and switch JAVA_HOME if needed
        int requiredJava = JavaVersionResolver.detectRequiredVersion(newProjectPath);
        int usedJava = 0;
        if (requiredJava > 0) {
            int selected = JavaVersionResolver.selectClosestVersion(requiredJava);
            Optional<String> javaHome = JavaVersionResolver.resolveJavaHome(newProjectPath);
            if (javaHome.isPresent()) {
                usedJava = selected;
                mavenRunner.run_no_optimization(javaHome.get(), newProjectPath);
            } else {
                mavenRunner.run_no_optimization(newProjectPath);
            }
        } else {
            mavenRunner.run_no_optimization(newProjectPath);
        }

        return extractResults(newProjectPath, newProjectName, merge, requiredJava, usedJava);
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

        TestTotal testTotal = new TestTotal(projectPath.toFile());
        int runTests = testTotal.getRunNum();

        if (runTests == 0) {
            return MergeProcessResult.builder()
                    .hadTests(false)
                    .timedOut(false)
                    .requiredJavaVersion(requiredJava)
                    .usedJavaVersion(usedJava)
                    .javaVersionError(javaVersionError)
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
