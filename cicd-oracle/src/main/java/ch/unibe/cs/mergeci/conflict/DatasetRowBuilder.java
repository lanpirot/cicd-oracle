package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.model.MergeInfo;

/**
 * Builds dataset rows from merge processing results.
 * Transforms merge information and test results into structured CSV rows.
 */
public class DatasetRowBuilder {

    /**
     * Build a dataset row from a successfully compiled+tested merge.
     *
     * @param result Merge processing result
     * @return CSV row, or null if result is not successful
     */
    public CsvWriter.DatasetRow buildRow(MergeCheckoutProcessor.MergeProcessResult result) {
        if (!result.isSuccessful()) {
            return null;
        }

        MergeInfo merge = result.getMerge();
        return buildRowInternal(merge, result, false);
    }

    /**
     * Build a dataset row for a merge whose human baseline fails to compile but where a
     * generated variant may succeed.  All test-related fields are zero; {@code baselineBroken=true}.
     */
    public CsvWriter.DatasetRow buildBrokenMergeRow(MergeCheckoutProcessor.MergeProcessResult result) {
        MergeInfo merge = result.getMerge();
        String mergeCommit = merge.getResultedMergeCommit().getName();
        String parent1 = merge.getCommit1().getName();
        String parent2 = merge.getCommit2().getName();

        int numJavaFiles = countJavaFiles(merge);
        boolean isMultiModule = isMultiModule(result);
        boolean hasTestConflict = hasTestConflict(merge);
        int numberOfModules = result.getCompilationResult() != null
                ? result.getCompilationResult().getNumberOfModules() : 0;

        return CsvWriter.DatasetRow.builder()
                .mergeCommit(mergeCommit)
                .parent1(parent1)
                .parent2(parent2)
                .numTests(0)
                .numConflictingFiles(merge.getConflictingFiles().size())
                .numJavaFiles(numJavaFiles)
                .compilationSuccess(false)
                .testSuccess(false)
                .elapsedTestTime(0)
                .isMultiModule(isMultiModule)
                .numPassedTests(0)
                .compilationTime(0)
                .testTime(0)
                .normalizedElapsedTime(0)
                .numberOfModules(numberOfModules)
                .modulesPassed(0)
                .hasTestConflict(hasTestConflict)
                .baselineBroken(true)
                .mergeId("")
                .build();
    }

    private CsvWriter.DatasetRow buildRowInternal(MergeInfo merge,
                                                  MergeCheckoutProcessor.MergeProcessResult result,
                                                  boolean baselineBroken) {
        String mergeCommit = merge.getResultedMergeCommit().getName();
        String parent1 = merge.getCommit1().getName();
        String parent2 = merge.getCommit2().getName();

        int numJavaFiles = countJavaFiles(merge);
        boolean isMultiModule = isMultiModule(result);
        boolean hasTestConflict = hasTestConflict(merge);

        return CsvWriter.DatasetRow.builder()
                .mergeCommit(mergeCommit)
                .parent1(parent1)
                .parent2(parent2)
                .numTests(result.getNumTests())
                .numConflictingFiles(merge.getConflictingFiles().size())
                .numJavaFiles(numJavaFiles)
                .compilationSuccess(result.isCompilationSuccess())
                .testSuccess(result.isTestSuccess())
                .elapsedTestTime(result.getElapsedTime())
                .isMultiModule(isMultiModule)
                .numPassedTests(result.getNumPassedTests())
                .compilationTime(result.getCompilationTime())
                .testTime(result.getElapsedTime())
                .normalizedElapsedTime(result.getNormalizedElapsedTime())
                .numberOfModules(result.getNumberOfModules())
                .modulesPassed(result.getModulesPassed())
                .hasTestConflict(hasTestConflict)
                .baselineBroken(baselineBroken)
                .mergeId("")
                .build();
    }

    private int countJavaFiles(MergeInfo merge) {
        return (int) merge.getConflictingFiles()
                .keySet()
                .stream()
                .filter(file -> file.endsWith(AppConfig.JAVA))
                .count();
    }

    private boolean hasTestConflict(MergeInfo merge) {
        return merge.getConflictingFiles()
                .keySet()
                .stream()
                .anyMatch(file -> file.contains("src/test/"));
    }

    private boolean isMultiModule(MergeCheckoutProcessor.MergeProcessResult result) {
        if (result.getCompilationResult() == null) {
            return false;
        }
        return !result.getCompilationResult().getModuleResults().isEmpty();
    }
}
