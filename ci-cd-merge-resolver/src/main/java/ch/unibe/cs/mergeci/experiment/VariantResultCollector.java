package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.runner.ExperimentTiming;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Collects and aggregates variant testing results into a structured output format.
 * Handles counting successful variants and building variant objects with their metrics.
 */
public class VariantResultCollector {

    /**
     * Collect all results from a processed merge into a MergeOutputJSON object.
     *
     * @param processed The processed merge containing analysis results
     * @return Complete merge output with all variant results
     */
    public MergeOutputJSON collectResults(MergeExperimentRunner.ProcessedMerge processed) {
        MergeOutputJSON output = new MergeOutputJSON();

        // Set basic merge information
        populateBasicInfo(output, processed);

        MergeExperimentRunner.MergeAnalysisResult result = processed.getAnalysisResult();

        // Build and set variant results
        VariantSummary variantSummary = buildVariantSummary(result, result.cacheWarmerKey());
        output.setVariants(variantSummary.variants());
        output.setVariantsExecutionTimeSeconds(variantSummary.variantsExecutionTimeSeconds());
        output.setBudgetExhausted(result.budgetExhausted());
        output.setNumInFlightVariantsKilled(result.numInFlightVariantsKilled());

        return output;
    }

    /**
     * Populate basic merge information from dataset.
     */
    private void populateBasicInfo(MergeOutputJSON output, MergeExperimentRunner.ProcessedMerge processed) {
        DatasetReader.MergeInfo info = processed.getInfo();

        output.setMergeCommit(info.getMergeCommit());
        output.setParent1(info.getParent1());
        output.setParent2(info.getParent2());
        output.setNumConflictChunks(processed.getNumConflictChunks());
        output.setNumConflictFiles(info.getNumConflictFiles());
        output.setNumJavaConflictFiles(info.getNumJavaFiles());
        output.setIsMultiModule(info.isMultiModule());
        output.setTotalExecutionTime(processed.getAnalysisResult().executionTimeSeconds());
        ExperimentTiming timing = processed.getAnalysisResult().runExecutionTime();
        long baselineSeconds = (timing != null && timing.getHumanBaselineExecutionTime() != null)
                ? timing.getHumanBaselineExecutionTime().getSeconds() : 0L;
        output.setBudgetBasisSeconds(baselineSeconds);
        output.setVariantBudgetSeconds(baselineSeconds * ch.unibe.cs.mergeci.config.AppConfig.TIMEOUT_MULTIPLIER);
        output.setCoverage(processed.getAnalysisResult().coverageResult());
    }

    /**
     * Build complete variant summary including all variants and success metrics.
     */
    private VariantSummary buildVariantSummary(MergeExperimentRunner.MergeAnalysisResult result,
                                               String cacheWarmerKey) {
        String projectName = result.getProjectName();
        Map<String, CompilationResult> compilationResults = result.compilationResults();
        Map<String, TestTotal> testResults = result.testResults();
        Map<String, Double> variantFinishSeconds = result.variantFinishSeconds();
        Map<String, Double> variantSinceMergeStartSeconds = result.variantSinceMergeStartSeconds();

        // Count successful variants
        int totalVariants = compilationResults.size() - 1; // Exclude baseline
        int successfulVariants = countSuccessfulVariants(compilationResults, testResults, projectName);

        // Build variant objects
        List<MergeOutputJSON.Variant> variants = buildVariants(
                compilationResults,
                testResults,
                result.analyzer(),
                projectName,
                variantFinishSeconds,
                variantSinceMergeStartSeconds,
                cacheWarmerKey
        );

        // Calculate variants execution time
        Duration variantsTime = result.runExecutionTime().getVariantsExecutionTime();
        long variantsExecutionTimeSeconds = variantsTime != null ? variantsTime.getSeconds() : 0;

        return new VariantSummary(variants, variantsExecutionTimeSeconds, successfulVariants, totalVariants);
    }

    /**
     * Count how many variants succeeded (compiled successfully and all tests passed).
     */
    private int countSuccessfulVariants(
            Map<String, CompilationResult> compilationResults,
            Map<String, TestTotal> testResults,
            String projectName) {

        int successCount = 0;

        for (Map.Entry<String, CompilationResult> entry : compilationResults.entrySet()) {
            if (entry.getKey().equals(projectName)) {
                continue; // Skip baseline
            }

            if (isVariantSuccessful(entry.getValue(), testResults.get(entry.getKey()))) {
                successCount++;
            }
        }

        return successCount;
    }

    /**
     * Check if a single variant was successful.
     */
    private boolean isVariantSuccessful(CompilationResult compilationResult, TestTotal testTotal) {
        return compilationResult != null
                && compilationResult.getBuildStatus() == CompilationResult.Status.SUCCESS
                && testTotal != null
                && testTotal.getRunNum() > 0
                && testTotal.getFailuresNum() == 0
                && testTotal.getErrorsNum() == 0;
    }

    /**
     * Build variant objects with their compilation results, test results, and conflict patterns.
     */
    private List<MergeOutputJSON.Variant> buildVariants(
            Map<String, CompilationResult> compilationResults,
            Map<String, TestTotal> testResults,
            ch.unibe.cs.mergeci.runner.VariantProjectBuilder builder,
            String projectName,
            Map<String, Double> variantFinishSeconds,
            Map<String, Double> variantSinceMergeStartSeconds,
            String cacheWarmerKey) {

        List<MergeOutputJSON.Variant> variants = new ArrayList<>(compilationResults.size());

        for (Map.Entry<String, CompilationResult> entry : compilationResults.entrySet()) {
            if (entry.getKey().equals(projectName)) {
                continue; // Skip baseline
            }

            MergeOutputJSON.Variant variant = new MergeOutputJSON.Variant();
            String key = entry.getKey();
            int variantIndex = Integer.parseInt(key.substring(key.lastIndexOf('_') + 1));
            variant.setVariantIndex(variantIndex);
            variant.setCacheWarmer(key.equals(cacheWarmerKey));
            variant.setCompilationResult(entry.getValue());
            variant.setTestResults(testResults.get(key));
            variant.setConflictPatterns(builder.getConflictPatterns().get(variants.size()));
            variant.setOwnExecutionSeconds(
                    variantFinishSeconds != null ? variantFinishSeconds.get(key) : null);
            variant.setTotalTimeSinceMergeStartSeconds(
                    variantSinceMergeStartSeconds != null ? variantSinceMergeStartSeconds.get(key) : null);
            variant.setTimedOut(entry.getValue() != null && entry.getValue().getBuildStatus() == CompilationResult.Status.TIMEOUT);

            variants.add(variant);
        }

        return variants;
    }

    /**
     * Collect baseline result for the human_baseline output file.
     */
    public MergeOutputJSON collectBaselineResult(MergeExperimentRunner.ProcessedMerge processed) {
        MergeOutputJSON output = new MergeOutputJSON();
        populateBasicInfo(output, processed);

        MergeExperimentRunner.MergeAnalysisResult result = processed.getAnalysisResult();
        String projectName = result.getProjectName();

        MergeOutputJSON.Variant variant = new MergeOutputJSON.Variant();
        variant.setVariantIndex(0);
        variant.setCompilationResult(result.compilationResults().get(projectName));
        variant.setTestResults(result.testResults().get(projectName));
        variant.setOwnExecutionSeconds((double) output.getBudgetBasisSeconds());

        output.setVariants(List.of(variant));
        output.setVariantsExecutionTimeSeconds(output.getBudgetBasisSeconds());

        return output;
    }

    /**
     * Summary of variant execution results.
     */
    private record VariantSummary(List<MergeOutputJSON.Variant> variants, long variantsExecutionTimeSeconds,
                                  int successfulVariants, int totalVariants) {
    }

    /**
     * Get a formatted summary string for logging.
     */
    public String getSuccessSummary(MergeExperimentRunner.ProcessedMerge processed) {
        if (processed.wasSkipped()) {
            return processed.getSkipReason();
        }

        VariantSummary summary = buildVariantSummary(processed.getAnalysisResult(), null);
        int successful = summary.successfulVariants();
        int total = summary.totalVariants();
        long executionTime = processed.getAnalysisResult().executionTimeSeconds();

        if (total == 0) {
            return formatBaselineSummary(processed.getAnalysisResult(), executionTime);
        }

        // Calculate success rate
        double successRate = successful * 100.0 / total;

        // Format with success indicator
        String indicator = (successful == total) ? "✓" : (successful > 0 ? "◐" : "✗");

        return String.format("%s %d/%d (%.0f%%) | %s",
                indicator,
                successful,
                total,
                successRate,
                formatTime(executionTime));
    }

    private String formatBaselineSummary(MergeExperimentRunner.MergeAnalysisResult result, long executionTime) {
        String projectName = result.getProjectName();
        CompilationResult compilation = result.compilationResults().get(projectName);
        TestTotal tests = result.testResults().get(projectName);

        boolean compiled = compilation != null
                && compilation.getBuildStatus() == CompilationResult.Status.SUCCESS;
        String indicator = compiled ? "✓" : "✗";

        if (tests != null && tests.getRunNum() > 0) {
            int passed = tests.getRunNum() - tests.getFailuresNum() - tests.getErrorsNum();
            return String.format("%s baseline %d/%d tests | %s",
                    indicator, passed, tests.getRunNum(), formatTime(executionTime));
        }
        return String.format("%s baseline | %s", indicator, formatTime(executionTime));
    }

    /**
     * Format time in a human-readable way.
     */
    private String formatTime(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            long mins = seconds / 60;
            long secs = seconds % 60;
            return String.format("%dm%ds", mins, secs);
        } else {
            long hours = seconds / 3600;
            long mins = (seconds % 3600) / 60;
            return String.format("%dh%dm", hours, mins);
        }
    }
}
