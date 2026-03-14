package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.model.MergeInfo;

/**
 * Builds Excel dataset rows from merge processing results.
 * Transforms merge information and test results into structured Excel rows.
 */
public class DatasetRowBuilder {

    /**
     * Build an Excel dataset row from merge processing result.
     *
     * @param result Merge processing result
     * @return Excel row with merge data, or null if result is not successful
     */
    public ExcelWriter.DatasetRow buildRow(MergeCheckoutProcessor.MergeProcessResult result) {
        if (!result.isSuccessful()) {
            return null;
        }

        MergeInfo merge = result.getMerge();
        String mergeCommit = merge.getResultedMergeCommit().getName();
        String parent1 = merge.getCommit1().getName();
        String parent2 = merge.getCommit2().getName();

        int numJavaFiles = countJavaFiles(merge);
        boolean isMultiModule = isMultiModule(result);

        return ExcelWriter.DatasetRow.builder()
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
                .build();
    }

    /**
     * Count the number of Java files in conflict.
     */
    private int countJavaFiles(MergeInfo merge) {
        return (int) merge.getConflictingFiles()
                .keySet()
                .stream()
                .filter(file -> file.endsWith(AppConfig.JAVA))
                .count();
    }

    /**
     * Determine if the project is multi-module based on compilation result.
     */
    private boolean isMultiModule(MergeCheckoutProcessor.MergeProcessResult result) {
        if (result.getCompilationResult() == null) {
            return false;
        }
        return !result.getCompilationResult().getModuleResults().isEmpty();
    }
}
