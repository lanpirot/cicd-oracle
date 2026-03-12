package ch.unibe.cs.mergeci.experimentSetup.mergeCollection;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import ch.unibe.cs.mergeci.service.projectRunners.maven.MavenRunner;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.model.MergeInfo;
import lombok.Getter;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Path;

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

        // Run Maven build
        mavenRunner.run_no_optimization(newProjectPath);

        // Extract results
        return extractResults(newProjectPath, newProjectName, merge);
    }

    private void checkoutMerge(Path sourcePath, Path targetPath, String commitHash)
            throws IOException, GitAPIException {

        // Copy project to temporary location
        try {
            FileUtils.copyDirectoryCompatibilityMode(sourcePath.toFile(), targetPath.toFile());
        } catch (IOException e) {
            System.err.println("Error copying folder: " + e.getMessage());
            throw e;
        }

        // Checkout the merge commit
        CheckoutCommand checkout = GitUtils.getGit(targetPath).checkout();
        checkout.setName(commitHash).setForced(true).call();
    }

    private MergeProcessResult extractResults(Path projectPath, String projectName, MergeInfo merge)
            throws IOException {

        Path compilationLogPath = mavenRunner.getLogDir().resolve(projectName + "_compilation");

        // Check if log file exists - if not, Maven failed very early
        if (!compilationLogPath.toFile().exists()) {
            return MergeProcessResult.builder()
                    .hadTests(true)
                    .timedOut(false)
                    .build();
        }

        CompilationResult compilationResult = new CompilationResult(compilationLogPath);

        // Check for timeout
        if (compilationResult.getBuildStatus() == CompilationResult.Status.TIMEOUT) {
            return MergeProcessResult.builder()
                    .hadTests(true)
                    .timedOut(true)
                    .build();
        }

        boolean compilationSuccess = compilationResult.getBuildStatus() == CompilationResult.Status.SUCCESS;

        // Extract test results
        TestTotal testTotal = new TestTotal(projectPath.toFile());
        int runTests = testTotal.getRunNum();

        // Check if tests were found
        if (runTests == 0) {
            return MergeProcessResult.builder()
                    .hadTests(false)
                    .timedOut(false)
                    .build();
        }

        boolean testSuccess = runTests > 0;
        float time = testTotal.getElapsedTime();

        return MergeProcessResult.builder()
                .hadTests(true)
                .timedOut(false)
                .compilationSuccess(compilationSuccess)
                .testSuccess(testSuccess)
                .compilationResult(compilationResult)
                .testTotal(testTotal)
                .merge(merge)
                .numTests(runTests)
                .elapsedTime(time)
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

        /**
         * Check if this result represents a successful processing (has tests and didn't timeout).
         */
        public boolean isSuccessful() {
            return hadTests && !timedOut;
        }
    }
}
