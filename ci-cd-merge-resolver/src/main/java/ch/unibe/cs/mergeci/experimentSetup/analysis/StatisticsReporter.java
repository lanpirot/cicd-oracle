package ch.unibe.cs.mergeci.experimentSetup.analysis;

import ch.unibe.cs.mergeci.experimentSetup.MergeStatistics;
import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.MergeOutputJSON;

import java.util.List;
import java.util.Map;

/**
 * Presents comprehensive statistics and analysis results for merge experiments.
 * Orchestrates multiple analyzers to generate and display insights.
 */
public class StatisticsReporter {
    private final MergeStatistics statistics;
    private final String experimentName;
    private final VariantResolutionAnalyzer resolutionAnalyzer;
    private final VariantRankingAnalyzer rankingAnalyzer;
    private final ExecutionTimeAnalyzer timeAnalyzer;

    public StatisticsReporter(MergeStatistics statistics, String experimentName) {
        this.statistics = statistics;
        this.experimentName = experimentName;
        this.resolutionAnalyzer = new VariantResolutionAnalyzer();
        this.rankingAnalyzer = new VariantRankingAnalyzer(resolutionAnalyzer);
        this.timeAnalyzer = new ExecutionTimeAnalyzer();
    }

    /**
     * Constructor for backward compatibility (tests).
     */
    public StatisticsReporter(MergeStatistics statistics) {
        this(statistics, "Unknown Experiment");
    }

    /**
     * Present complete analysis results to console.
     */
    public void presentFullResults() {
        List<MergeOutputJSON> allMerges = statistics.getAllMerges();
        List<MergeOutputJSON> impactMerges = statistics.getImpactMerges();
        List<MergeOutputJSON> noImpactMerges = statistics.getNoImpactMerges();

        printHeader();
        printOverviewStatistics(allMerges, impactMerges, noImpactMerges);
        printModuleBreakdown(allMerges, impactMerges, noImpactMerges);
        printCoverageStatistics();
        printResolutionAnalysis(impactMerges);
        printRankings(impactMerges);
        printExecutionTimeAnalysis(impactMerges);
        printCoverageRankings(impactMerges);
        printConflictChunksRankings(impactMerges);
        printFooter();
    }

    private void printHeader() {
        System.out.println("\n" + "═".repeat(80));
        System.out.println("  EXPERIMENT RESULTS: " + formatExperimentName(experimentName));
        System.out.println("═".repeat(80));
    }

    private String formatExperimentName(String name) {
        // Convert experiment names to readable format
        return switch (name) {
            case "no_optimization" -> "No Optimization (Sequential, No Cache)";
            case "parallel" -> "Parallel Execution (No Cache)";
            case "cache_parallel" -> "Cache + Parallel Execution";
            default -> name.replace("_", " ").toUpperCase();
        };
    }

    private void printSectionHeader(String title) {
        System.out.println("\n" + "─".repeat(80));
        System.out.println("  " + title);
        System.out.println("─".repeat(80));
    }

    private void printFooter() {
        System.out.println("═".repeat(80) + "\n");
    }

    private void printOverviewStatistics(
            List<MergeOutputJSON> allMerges,
            List<MergeOutputJSON> impactMerges,
            List<MergeOutputJSON> noImpactMerges) {

        printSectionHeader("Overview");

        System.out.printf("  Total Merges:       %4d\n", allMerges.size());
        System.out.printf("  ├─ Multi-module:    %4d (%3d%%)\n",
                statistics.getMultiModuleCount(),
                countPercent(allMerges.size(), statistics.getMultiModuleCount()));
        System.out.printf("  └─ Single-module:   %4d (%3d%%)\n\n",
                allMerges.size() - statistics.getMultiModuleCount(),
                countPercent(allMerges.size(), allMerges.size() - statistics.getMultiModuleCount()));

        System.out.printf("  Impact Analysis:\n");
        System.out.printf("  ├─ Impact:          %4d (%3d%%) ← Variants change test results\n",
                impactMerges.size(),
                countPercent(allMerges.size(), impactMerges.size()));
        System.out.printf("  └─ No Impact:       %4d (%3d%%) ← All variants same results\n",
                noImpactMerges.size(),
                countPercent(allMerges.size(), noImpactMerges.size()));
    }

    private void printModuleBreakdown(
            List<MergeOutputJSON> allMerges,
            List<MergeOutputJSON> impactMerges,
            List<MergeOutputJSON> noImpactMerges) {

        printSectionHeader("Module Type Breakdown");

        // No impact breakdown
        List<MergeOutputJSON> noImpactSingleModule = statistics.getSingleModule().getNoImpact();
        List<MergeOutputJSON> noImpactMultiModule = statistics.getMultiModule().getNoImpact();

        // Impact breakdown
        List<MergeOutputJSON> impactSingleModule = statistics.getSingleModule().getImpact();
        List<MergeOutputJSON> impactMultiModule = statistics.getMultiModule().getImpact();

        System.out.println("  No Impact Merges:");
        System.out.printf("  ├─ Single-module:   %4d (%3d%% of all)\n",
                noImpactSingleModule.size(),
                countPercent(allMerges.size(), noImpactSingleModule.size()));
        System.out.printf("  └─ Multi-module:    %4d (%3d%% of all)\n\n",
                noImpactMultiModule.size(),
                countPercent(allMerges.size(), noImpactMultiModule.size()));

        if (!impactMerges.isEmpty()) {
            System.out.println("  Impact Merges:");
            System.out.printf("  ├─ Single-module:   %4d (%3d%% of impact)\n",
                    impactSingleModule.size(),
                    countPercent(impactMerges.size(), impactSingleModule.size()));
            System.out.printf("  └─ Multi-module:    %4d (%3d%% of impact)\n",
                    impactMultiModule.size(),
                    countPercent(impactMerges.size(), impactMultiModule.size()));
        }
    }

    private void printCoverageStatistics() {
        printSectionHeader("Code Coverage (Average Line Coverage %)");

        // No impact coverage
        List<MergeOutputJSON> noImpactWithCoverage = statistics.getNoImpactMerges();
        List<MergeOutputJSON> noImpactSingleModuleCoverage = statistics.getSingleModule().getNoImpactWithCoverage();
        List<MergeOutputJSON> noImpactMultiModuleCoverage = statistics.getMultiModule().getNoImpactWithCoverage();

        System.out.println("  No Impact Merges:");
        System.out.printf("  ├─ All:             %6.2f%%\n",
                noImpactWithCoverage.stream()
                        .mapToDouble(x -> x.getCoverage().lineCoverage())
                        .average()
                        .orElse(0.0));
        System.out.printf("  ├─ Single-module:   %6.2f%%\n",
                noImpactSingleModuleCoverage.stream()
                        .mapToDouble(x -> x.getCoverage().lineCoverage())
                        .average()
                        .orElse(0.0));
        System.out.printf("  └─ Multi-module:    %6.2f%%\n\n",
                noImpactMultiModuleCoverage.stream()
                        .mapToDouble(x -> x.getCoverage().lineCoverage())
                        .average()
                        .orElse(0.0));

        // Impact coverage
        List<MergeOutputJSON> impactSingleModuleCoverage = statistics.getSingleModule().getImpactWithCoverage();
        List<MergeOutputJSON> impactMultiModuleCoverage = statistics.getMultiModule().getImpactWithCoverage();

        System.out.println("  Impact Merges:");
        System.out.printf("  ├─ Single-module:   %6.2f%%\n",
                impactSingleModuleCoverage.stream()
                        .mapToDouble(x -> x.getCoverage().lineCoverage())
                        .average()
                        .orElse(0.0));
        System.out.printf("  └─ Multi-module:    %6.2f%%\n",
                impactMultiModuleCoverage.stream()
                        .mapToDouble(x -> x.getCoverage().lineCoverage())
                        .average()
                        .orElse(0.0));
    }

    private void printResolutionAnalysis(List<MergeOutputJSON> impactMerges) {
        if (impactMerges.isEmpty()) {
            return;
        }

        printSectionHeader("Resolution Analysis (Impact Merges Only)");

        List<MergeOutputJSON> impactSingleModule = statistics.getSingleModule().getImpact();
        List<MergeOutputJSON> impactMultiModule = statistics.getMultiModule().getImpact();

        // At least one resolution
        List<MergeOutputJSON> withResolution = resolutionAnalyzer.findMergesWithAtLeastOneResolution(impactMerges);
        List<MergeOutputJSON> withResolutionSingle = resolutionAnalyzer.findMergesWithAtLeastOneResolution(impactSingleModule);
        List<MergeOutputJSON> withResolutionMulti = resolutionAnalyzer.findMergesWithAtLeastOneResolution(impactMultiModule);

        System.out.println("  Successful Resolution (≥1 variant passes):");
        System.out.printf("  ├─ All:             %4d/%4d (%3d%%)\n",
                withResolution.size(),
                impactMerges.size(),
                countPercent(impactMerges.size(), withResolution.size()));

        if (!impactSingleModule.isEmpty()) {
            System.out.printf("  ├─ Single-module:   %4d/%4d (%3d%% of single, %3d%% of all impact)\n",
                    withResolutionSingle.size(),
                    impactSingleModule.size(),
                    countPercent(impactSingleModule.size(), withResolutionSingle.size()),
                    countPercent(impactMerges.size(), withResolutionSingle.size()));
        }

        if (!impactMultiModule.isEmpty()) {
            System.out.printf("  └─ Multi-module:    %4d/%4d (%3d%% of multi, %3d%% of all impact)\n\n",
                    withResolutionMulti.size(),
                    impactMultiModule.size(),
                    countPercent(impactMultiModule.size(), withResolutionMulti.size()),
                    countPercent(impactMerges.size(), withResolutionMulti.size()));
        }

        // Better resolutions
        List<MergeOutputJSON> performBetter = resolutionAnalyzer.findMergesThatPerformBetter(impactMerges);
        if (!performBetter.isEmpty()) {
            System.out.printf("  Better Than Original: %4d/%4d (%3d%%) ← Variant passes more tests\n\n",
                    performBetter.size(),
                    impactMerges.size(),
                    countPercent(impactMerges.size(), performBetter.size()));
        }

        // Uniform patterns
        printUniformPatternAnalysis(impactMerges, withResolution, impactSingleModule, impactMultiModule,
                withResolutionSingle, withResolutionMulti);
    }

    private void printUniformPatternAnalysis(
            List<MergeOutputJSON> impactMerges,
            List<MergeOutputJSON> withResolution,
            List<MergeOutputJSON> impactSingleModule,
            List<MergeOutputJSON> impactMultiModule,
            List<MergeOutputJSON> withResolutionSingle,
            List<MergeOutputJSON> withResolutionMulti) {

        List<String> patterns = resolutionAnalyzer.extractUniformPatterns(impactMerges);
        List<String> patternsFromResolved = resolutionAnalyzer.extractUniformPatterns(withResolution);

        System.out.println("  Uniform Patterns (Same resolution for all chunks):");

        if (!withResolution.isEmpty()) {
            System.out.printf("  ├─ All resolved:    %4d/%4d (%3d%%)\n",
                    patternsFromResolved.size(),
                    withResolution.size(),
                    countPercent(withResolution.size(), patternsFromResolved.size()));
        }

        if (!withResolutionSingle.isEmpty()) {
            List<String> singleModulePatterns = resolutionAnalyzer.extractUniformPatterns(withResolutionSingle);
            System.out.printf("  ├─ Single-module:   %4d/%4d (%3d%%)\n",
                    singleModulePatterns.size(),
                    withResolutionSingle.size(),
                    countPercent(withResolutionSingle.size(), singleModulePatterns.size()));
        }

        if (!impactMultiModule.isEmpty()) {
            List<String> multiModulePatterns = resolutionAnalyzer.extractUniformPatterns(impactMultiModule);
            System.out.printf("  └─ Multi-module:    %4d/%4d (%3d%%)\n",
                    multiModulePatterns.size(),
                    impactMultiModule.size(),
                    countPercent(impactMultiModule.size(), multiModulePatterns.size()));
        }

        // Pattern distribution
        if (!patterns.isEmpty()) {
            System.out.println("\n  Pattern Distribution:");
            Map<String, Integer> grouped = resolutionAnalyzer.groupPatternsByCount(patterns);
            grouped.forEach((pattern, count) ->
                    System.out.printf("  ├─ %-15s %4d (%5.2f%%)\n",
                            pattern + ":",
                            count,
                            (float) count * 100 / patterns.size()));
        }
    }

    private void printRankings(List<MergeOutputJSON> impactMerges) {
        if (impactMerges.isEmpty()) {
            return;
        }

        printSectionHeader("Variant Resolution Ranking");

        Map<String, Integer> ranking = rankingAnalyzer.generateSortedRanking(impactMerges);

        System.out.println("  Best performing resolution strategies:");
        int index = 1;
        for (Map.Entry<String, Integer> entry : ranking.entrySet()) {
            String indicator = index == 1 ? "🥇" : (index == 2 ? "🥈" : (index == 3 ? "🥉" : "  "));
            System.out.printf("  %s %-15s %4d/%4d (%5.2f%%)\n",
                    indicator,
                    entry.getKey() + ":",
                    entry.getValue(),
                    impactMerges.size(),
                    (float) entry.getValue() * 100 / impactMerges.size());
            index++;
        }
    }

    private void printExecutionTimeAnalysis(List<MergeOutputJSON> impactMerges) {
        if (impactMerges.isEmpty()) {
            return;
        }

        printSectionHeader("Execution Time Analysis");

        ExecutionTimeAnalyzer.ExecutionTimeMetrics metrics = timeAnalyzer.analyzeExecutionTimes(impactMerges);

        System.out.printf("  Avg Time Ratio (Variants/Baseline): %.2fx\n",
                metrics.getAverageRatio());
        System.out.println("\n  Distribution by conflict chunks:");
        System.out.println("  " + metrics.getDistributionByConflictChunks());

        long mergesWithCoverage = impactMerges.stream()
                .filter(m -> m.getCoverage() != null && !Float.isNaN(m.getCoverage().lineCoverage()))
                .count();

        System.out.printf("\n  Merges with coverage data: %4d/%4d (%3d%%)\n",
                mergesWithCoverage,
                impactMerges.size(),
                countPercent(impactMerges.size(), (int) mergesWithCoverage));
    }

    private void printCoverageRankings(List<MergeOutputJSON> impactMerges) {
        if (impactMerges.isEmpty()) {
            return;
        }

        printSectionHeader("Rankings by Coverage Threshold");

        Map<String, Map<String, Integer>> coverageRankings =
                rankingAnalyzer.rankByCoverageThresholds(impactMerges);

        for (Map.Entry<String, Map<String, Integer>> rangeEntry : coverageRankings.entrySet()) {
            String range = rangeEntry.getKey();
            Map<String, Integer> ranking = rangeEntry.getValue();

            // Count total merges in this range
            int totalInRange = ranking.values().stream()
                    .max(Integer::compareTo)
                    .orElse(0);

            if (totalInRange == 0) {
                continue;
            }

            System.out.printf("\n  Coverage %s:\n", range);

            for (Map.Entry<String, Integer> entry : ranking.entrySet()) {
                System.out.printf("    ├─ %-15s %4d/%4d (%5.2f%%)\n",
                        entry.getKey() + ":",
                        entry.getValue(),
                        totalInRange,
                        (float) entry.getValue() * 100 / totalInRange);
            }
        }
    }

    private void printConflictChunksRankings(List<MergeOutputJSON> impactMerges) {
        if (impactMerges.isEmpty()) {
            return;
        }

        printSectionHeader("Rankings by Conflict Chunks");

        Map<Integer, Map<String, Integer>> conflictRankings =
                rankingAnalyzer.rankByConflictChunks(impactMerges);

        for (Map.Entry<Integer, Map<String, Integer>> chunkEntry : conflictRankings.entrySet()) {
            int numChunks = chunkEntry.getKey();
            Map<String, Integer> ranking = chunkEntry.getValue();

            int totalInGroup = ranking.values().stream()
                    .max(Integer::compareTo)
                    .orElse(0);

            if (totalInGroup == 0) {
                continue;
            }

            System.out.printf("\n  %d conflict chunk%s (%d merge%s):\n",
                    numChunks,
                    numChunks == 1 ? "" : "s",
                    totalInGroup,
                    totalInGroup == 1 ? "" : "s");

            for (Map.Entry<String, Integer> entry : ranking.entrySet()) {
                System.out.printf("    ├─ %-15s %4d/%4d (%5.2f%%)\n",
                        entry.getKey() + ":",
                        entry.getValue(),
                        totalInGroup,
                        (float) entry.getValue() * 100 / totalInGroup);
            }
        }
    }

    /**
     * Calculate percentage (part / whole * 100).
     */
    private static int countPercent(int whole, int part) {
        if (whole == 0) {
            return 0;
        }
        return part * 100 / whole;
    }

    /**
     * Print formatted output only if denominator is non-zero.
     */
    private static void printfIfNonZero(String format, int denominator, Object... args) {
        if (denominator != 0) {
            System.out.printf(format, args);
        }
    }
}
