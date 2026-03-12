package ch.unibe.cs.mergeci.experimentSetup.analysis;

import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.MergeOutputJSON;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Analyzes execution time metrics for merge variants.
 * Computes ratios between variant execution time and baseline merge time.
 */
public class ExecutionTimeAnalyzer {

    /**
     * Calculate average ratio of variant execution time to baseline merge time.
     *
     * @param merges List of merge results
     * @return Average ratio (variant time / baseline time)
     */
    public double calculateAverageExecutionTimeRatio(List<MergeOutputJSON> merges) {
        List<Double> ratios = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            double baselineTime = merge.getCompilationResult().getTotalTime();
            double variantTime = merge.getVariantsExecution().getExecutionTimeSeconds();

            if (baselineTime > 0) {
                double ratio = variantTime / baselineTime;
                ratios.add(ratio);
            }
        }

        return ratios.stream()
                .mapToDouble(x -> x)
                .average()
                .orElse(0.0);
    }

    /**
     * Calculate execution time ratio distribution grouped by number of conflict chunks.
     *
     * @param merges List of merge results
     * @return Map of conflict chunk count to average execution time ratio
     */
    public Map<Integer, Double> calculateExecutionTimeDistribution(List<MergeOutputJSON> merges) {
        // Group merges by conflict chunk count
        Map<Integer, List<MergeOutputJSON>> grouped = new HashMap<>();

        for (MergeOutputJSON merge : merges) {
            int numChunks = merge.getNumConflictChunks();
            grouped.putIfAbsent(numChunks, new ArrayList<>());
            grouped.get(numChunks).add(merge);
        }

        // Calculate average ratio for each group
        Map<Integer, Double> ratios = new HashMap<>();

        for (Map.Entry<Integer, List<MergeOutputJSON>> entry : grouped.entrySet()) {
            double averageRatio = calculateAverageExecutionTimeRatio(entry.getValue());
            ratios.put(entry.getKey(), averageRatio);
        }

        return ratios;
    }

    /**
     * Result container for execution time analysis.
     */
    public static class ExecutionTimeMetrics {
        private final double averageRatio;
        private final Map<Integer, Double> distributionByConflictChunks;

        public ExecutionTimeMetrics(double averageRatio, Map<Integer, Double> distributionByConflictChunks) {
            this.averageRatio = averageRatio;
            this.distributionByConflictChunks = distributionByConflictChunks;
        }

        public double getAverageRatio() {
            return averageRatio;
        }

        public Map<Integer, Double> getDistributionByConflictChunks() {
            return distributionByConflictChunks;
        }
    }

    /**
     * Compute all execution time metrics at once.
     */
    public ExecutionTimeMetrics analyzeExecutionTimes(List<MergeOutputJSON> merges) {
        double averageRatio = calculateAverageExecutionTimeRatio(merges);
        Map<Integer, Double> distribution = calculateExecutionTimeDistribution(merges);

        return new ExecutionTimeMetrics(averageRatio, distribution);
    }
}
