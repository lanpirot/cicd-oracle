package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.runner.ExperimentTiming;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
        // Derive isMultiModule from the actual baseline build rather than the
        // (potentially stale) CSV value: the baseline CompilationResult reflects
        // the build that actually ran, while the CSV flag was set during the
        // original collection build which may have failed before the reactor.
        String projectName = processed.getAnalysisResult().getProjectName();
        CompilationResult baselineCr = processed.getAnalysisResult().compilationResults().get(projectName);
        output.setIsMultiModule(baselineCr != null && baselineCr.getTotalModules() > 1);
        output.setTotalExecutionTime(processed.getAnalysisResult().executionTimeSeconds());
        ExperimentTiming timing = processed.getAnalysisResult().runExecutionTime();
        long baselineSeconds = (timing != null && timing.getNormalizedBaselineSeconds() > 0)
                ? timing.getNormalizedBaselineSeconds()
                : (timing != null && timing.getHumanBaselineExecutionTime() != null)
                        ? timing.getHumanBaselineExecutionTime().getSeconds() : 0L;
        output.setBudgetBasisSeconds(baselineSeconds);
        output.setVariantBudgetSeconds(baselineSeconds * ch.unibe.cs.mergeci.config.AppConfig.TIMEOUT_MULTIPLIER);
        output.setThreads(processed.getAnalysisResult().maxThreads());
        output.setPeakBaselineRamBytes(processed.getAnalysisResult().peakBaselineRamBytes());
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

        return new VariantSummary(variants, variantsExecutionTimeSeconds);
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
            variant.setConflictPatterns(builder.getConflictPatterns().get(variantIndex - 1));
            variant.setOwnExecutionSeconds(
                    variantFinishSeconds != null ? variantFinishSeconds.get(key) : null);
            variant.setTotalTimeSinceMergeStartSeconds(
                    variantSinceMergeStartSeconds != null ? variantSinceMergeStartSeconds.get(key) : null);
            variant.setTimedOut(entry.getValue() != null && entry.getValue().getBuildStatus() == CompilationResult.Status.TIMEOUT);

            variants.add(variant);
        }

        variants.sort(Comparator.comparingInt(MergeOutputJSON.Variant::getVariantIndex));
        return variants;
    }

    /**
     * Collect baseline result for the human_baseline output file.
     */
    public MergeOutputJSON collectBaselineResult(MergeExperimentRunner.ProcessedMerge processed) {
        return collectBaselineResult(processed, null);
    }

    /**
     * Collect baseline result for the human_baseline output file,
     * populating conflictPatterns from the provided ground-truth map (nullable).
     */
    public MergeOutputJSON collectBaselineResult(MergeExperimentRunner.ProcessedMerge processed,
                                                  Map<String, List<String>> groundTruthPatterns) {
        MergeOutputJSON output = new MergeOutputJSON();
        populateBasicInfo(output, processed);

        MergeExperimentRunner.MergeAnalysisResult result = processed.getAnalysisResult();
        String projectName = result.getProjectName();

        MergeOutputJSON.Variant variant = new MergeOutputJSON.Variant();
        variant.setVariantIndex(0);
        variant.setCompilationResult(result.compilationResults().get(projectName));
        variant.setTestResults(result.testResults().get(projectName));
        variant.setOwnExecutionSeconds((double) output.getBudgetBasisSeconds());
        variant.setConflictPatterns(groundTruthPatterns);

        output.setVariants(List.of(variant));
        output.setVariantsExecutionTimeSeconds(output.getBudgetBasisSeconds());

        return output;
    }

    /**
     * Summary of variant execution results.
     */
    private record VariantSummary(List<MergeOutputJSON.Variant> variants, long variantsExecutionTimeSeconds) {
    }

    /**
     * Get a formatted summary string for logging.
     * Shows the best variant's module and test stats rather than a simple success count.
     * For the human baseline (no variants), shows the baseline result directly.
     */
    public String getSuccessSummary(MergeExperimentRunner.ProcessedMerge processed) {
        if (processed.wasSkipped()) {
            return processed.getSkipReason();
        }

        MergeExperimentRunner.MergeAnalysisResult result = processed.getAnalysisResult();
        String projectName = result.getProjectName();
        long executionTime = result.executionTimeSeconds();
        Map<String, CompilationResult> compilationResults = result.compilationResults();
        Map<String, TestTotal> testResults = result.testResults();

        boolean hasVariants = compilationResults.keySet().stream().anyMatch(k -> !k.equals(projectName));

        if (!hasVariants) {
            // Human baseline: single result
            CompilationResult cr = compilationResults.get(projectName);
            TestTotal tt = testResults.get(projectName);
            return formatBuildStats(cr, tt) + " | " + formatTime(executionTime);
        }

        // Find best variant: most successful modules → most passed tests → first to finish
        String bestKey = findBestVariantKey(compilationResults, testResults, projectName,
                result.variantSinceMergeStartSeconds());
        CompilationResult bestCr = bestKey != null ? compilationResults.get(bestKey) : null;
        TestTotal bestTt = bestKey != null ? testResults.get(bestKey) : null;
        return "best: " + formatBuildStats(bestCr, bestTt) + " | " + formatTime(executionTime);
    }

    /**
     * Find the key of the best variant using the ranking:
     * 1. Most successful modules (primary)
     * 2. Most passed tests (tiebreaker)
     * 3. First to finish, measured by time since merge start (final tiebreaker)
     */
    private String findBestVariantKey(Map<String, CompilationResult> compilationResults,
                                      Map<String, TestTotal> testResults,
                                      String projectName,
                                      Map<String, Double> variantSinceMergeStartSeconds) {
        String bestKey = null;
        int bestModules = Integer.MIN_VALUE;
        int bestTests = Integer.MIN_VALUE;
        double bestFinish = Double.MAX_VALUE;

        for (String key : compilationResults.keySet()) {
            if (key.equals(projectName)) continue;
            CompilationResult cr = compilationResults.get(key);
            TestTotal tt = testResults.get(key);
            int modules = effectiveSuccessfulModules(cr);
            int tests = tt != null ? tt.getPassedTests() : 0;
            double finish = (variantSinceMergeStartSeconds != null)
                    ? variantSinceMergeStartSeconds.getOrDefault(key, Double.MAX_VALUE)
                    : Double.MAX_VALUE;

            if (bestKey == null
                    || modules > bestModules
                    || (modules == bestModules && tests > bestTests)
                    || (modules == bestModules && tests == bestTests && finish < bestFinish)) {
                bestKey = key;
                bestModules = modules;
                bestTests = tests;
                bestFinish = finish;
            }
        }
        return bestKey;
    }

    /**
     * Effective successful module count for ranking purposes.
     * Single-module projects produce no Reactor Summary, so we infer from build status.
     */
    private int effectiveSuccessfulModules(CompilationResult cr) {
        if (cr == null) return -1;
        if (cr.getTotalModules() > 0) return cr.getSuccessfulModules();
        return (cr.getBuildStatus() == CompilationResult.Status.SUCCESS) ? 1 : 0;
    }

    /**
     * Format module and test stats from a single build result.
     */
    private String formatBuildStats(CompilationResult cr, TestTotal tt) {
        StringBuilder sb = new StringBuilder();

        if (cr == null) {
            sb.append("no result");
        } else if (cr.getTotalModules() > 0) {
            int s = cr.getSuccessfulModules();
            int t = cr.getTotalModules();
            sb.append(String.format("%d%% modules (%d/%d)", Math.round(s * 100f / t), s, t));
        } else if (cr.getBuildStatus() == CompilationResult.Status.SUCCESS) {
            sb.append("100% modules (1/1)");
        } else if (cr.getBuildStatus() == CompilationResult.Status.TIMEOUT) {
            sb.append("timeout");
        } else {
            sb.append("0% modules (0/1)");
        }

        if (tt != null && tt.getRunNum() > 0) {
            int passed = tt.getPassedTests();
            int total = tt.getRunNum();
            sb.append(String.format(", %d%% tests (%d/%d)", Math.round(passed * 100f / total), passed, total));
        }

        return sb.toString();
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
            return String.format("%dm %ds", mins, secs);
        } else {
            long hours = seconds / 3600;
            long mins = (seconds % 3600) / 60;
            return String.format("%dh %dm", hours, mins);
        }
    }
}
