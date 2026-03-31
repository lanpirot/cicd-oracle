package ch.unibe.cs.mergeci.present;

import ch.unibe.cs.mergeci.conflict.MergeStatistics;
import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;

import ch.unibe.cs.mergeci.util.LatexVariableWriter;

import java.util.*;

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
        printJavaRatioImpactChart(allMerges, impactMerges);
        printResolutionAnalysis(impactMerges);
        printRankings(impactMerges);
        printExecutionTimeAnalysis(impactMerges);
        printConflictChunksRankings(impactMerges);
        printFooter();
        exportLatexVariables(allMerges, impactMerges);
    }

    private void printHeader() {
        System.out.println("\n" + "═".repeat(80));
        System.out.println("  EXPERIMENT RESULTS: " + formatExperimentName(experimentName));
        System.out.println("═".repeat(80));
    }

    private String formatExperimentName(String name) {
        return switch (name) {
            case "human_baseline" -> "Human Baseline";
            case "no_optimization" -> "Sequential";
            case "cache_sequential" -> "Sequential + Cache";
            case "parallel" -> "Parallel";
            case "cache_parallel" -> "Parallel + Cache";
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
        System.out.printf("  ├─ Impact:          %4d (%3d%%) ← Any variant has a different score\n",
                impactMerges.size(),
                countPercent(allMerges.size(), impactMerges.size()));
        System.out.printf("  └─ No Impact:       %4d (%3d%%) ← All variants have the same score\n",
                noImpactMerges.size(),
                countPercent(allMerges.size(), noImpactMerges.size()));
    }

    private void printModuleBreakdown(
            List<MergeOutputJSON> allMerges,
            List<MergeOutputJSON> impactMerges,
            List<MergeOutputJSON> noImpactMerges) {

        printSectionHeader("Module Type Breakdown");

        List<MergeOutputJSON> noImpactSingleModule = statistics.getSingleModule().noImpact();
        List<MergeOutputJSON> noImpactMultiModule = statistics.getMultiModule().noImpact();
        List<MergeOutputJSON> impactSingleModule = statistics.getSingleModule().impact();
        List<MergeOutputJSON> impactMultiModule = statistics.getMultiModule().impact();

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

    private void printResolutionAnalysis(List<MergeOutputJSON> impactMerges) {
        if (impactMerges.isEmpty()) {
            return;
        }

        printSectionHeader("Resolution Analysis (Impact Merges Only)");

        List<MergeOutputJSON> impactSingleModule = statistics.getSingleModule().impact();
        List<MergeOutputJSON> impactMultiModule = statistics.getMultiModule().impact();

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

    private void printJavaRatioImpactChart(
            List<MergeOutputJSON> allMerges,
            List<MergeOutputJSON> impactMerges) {

        printSectionHeader("Java Conflict File Ratio vs Impact Rate");
        System.out.println("  Fraction of conflict files that are Java → fraction of merges that are impact\n");

        String[] labels = {"[0.0, 0.2)", "[0.2, 0.4)", "[0.4, 0.6)", "[0.6, 0.8)", "[0.8, 1.0]"};
        int[] totalCounts = new int[5];
        int[] impactCounts = new int[5];

        Set<MergeOutputJSON> impactSet = new HashSet<>(impactMerges);

        for (MergeOutputJSON merge : allMerges) {
            int totalFiles = merge.getNumConflictFiles();
            if (totalFiles == 0) continue;
            double ratio = (double) merge.getNumJavaConflictFiles() / totalFiles;
            int bucket = Math.min(4, (int) (ratio / 0.2));
            totalCounts[bucket]++;
            if (impactSet.contains(merge)) {
                impactCounts[bucket]++;
            }
        }

        int barWidth = 28;
        for (int i = 0; i < 5; i++) {
            int total = totalCounts[i];
            int impact = impactCounts[i];
            double impactRate = total > 0 ? (double) impact / total : 0.0;
            int filled = total > 0 ? (int) Math.round(impactRate * barWidth) : 0;
            String bar = "█".repeat(filled) + "░".repeat(barWidth - filled);
            System.out.printf("  %s  %s  %5.1f%%  (%d/%d)\n",
                    labels[i], bar, impactRate * 100, impact, total);
        }
    }

    /**
     * Export key statistics as LaTeX variables to the shared CSV.
     * Variable names are prefixed with the experiment mode (e.g., {@code seqMergeCount}).
     */
    private void exportLatexVariables(List<MergeOutputJSON> allMerges,
                                      List<MergeOutputJSON> impactMerges) {
        String prefix = toLatexPrefix(experimentName);
        Map<String, String[]> vars = new LinkedHashMap<>();

        vars.put(prefix + "MergeCount",
                new String[]{String.valueOf(allMerges.size()), "Total merges (" + experimentName + ")"});
        vars.put(prefix + "MultiModuleCount",
                new String[]{String.valueOf(statistics.getMultiModuleCount()), "Multi-module merges"});
        vars.put(prefix + "SingleModuleCount",
                new String[]{String.valueOf(allMerges.size() - statistics.getMultiModuleCount()), "Single-module merges"});
        vars.put(prefix + "ImpactCount",
                new String[]{String.valueOf(impactMerges.size()), "Impact merges"});
        if (!allMerges.isEmpty()) {
            vars.put(prefix + "ImpactRate",
                    new String[]{String.valueOf(countPercent(allMerges.size(), impactMerges.size())), "Impact rate (%)"});
        }

        if (!impactMerges.isEmpty()) {
            List<MergeOutputJSON> withResolution = resolutionAnalyzer.findMergesWithAtLeastOneResolution(impactMerges);
            vars.put(prefix + "ResolvedCount",
                    new String[]{String.valueOf(withResolution.size()), "Impact merges with >= 1 successful variant"});
            vars.put(prefix + "ResolutionRate",
                    new String[]{String.valueOf(countPercent(impactMerges.size(), withResolution.size())),
                            "Resolution rate (% of impact)"});

            List<MergeOutputJSON> better = resolutionAnalyzer.findMergesThatPerformBetter(impactMerges);
            vars.put(prefix + "BetterCount",
                    new String[]{String.valueOf(better.size()), "Merges performing better than original"});
            vars.put(prefix + "BetterRate",
                    new String[]{String.valueOf(countPercent(impactMerges.size(), better.size())),
                            "Better-than-original rate (% of impact)"});
        }

        LatexVariableWriter.putAll(vars);
    }

    /**
     * Export RQ3-specific fine-grained quality metrics as LaTeX variables.
     * For each merge, finds the best variant (by {@link VariantScore}) and compares
     * its build and test outcomes against the human baseline.
     *
     * @param allMerges all merges from the best-mode experiment directory
     */
    public void exportRQ3LatexVariables(List<MergeOutputJSON> allMerges) {
        String prefix = "rqThree";
        Map<String, String[]> vars = new LinkedHashMap<>();

        int betterBuild = 0, betterOrEqualBuild = 0;
        int betterTests = 0, betterOrEqualTests = 0;
        int better = 0, betterOrEqual = 0;

        int brokenBaselines = 0;
        int buildFromBroken = 0;

        int comparable = 0; // merges where both baseline and best variant are scoreable

        for (MergeOutputJSON merge : allMerges) {
            if (merge.isBaselineBroken()) brokenBaselines++;

            Optional<VariantScore> baseOpt = VariantResolutionAnalyzer.baselineScore(merge);
            Optional<VariantScore> bestOpt = VariantResolutionAnalyzer.bestVariantScore(merge);
            if (baseOpt.isEmpty() || bestOpt.isEmpty()) continue;

            comparable++;
            VariantScore base = baseOpt.get();
            VariantScore best = bestOpt.get();

            if (best.successfulModules() > base.successfulModules()) betterBuild++;
            if (best.successfulModules() >= base.successfulModules()) betterOrEqualBuild++;
            if (best.passedTests() > base.passedTests()) betterTests++;
            if (best.passedTests() >= base.passedTests()) betterOrEqualTests++;
            if (best.isBetterThan(base)) better++;
            if (best.isAtLeastAsGoodAs(base)) betterOrEqual++;

            if (merge.isBaselineBroken() && best.successfulModules() > 0) buildFromBroken++;
        }

        vars.put(prefix + "MergeCount",
                new String[]{String.valueOf(allMerges.size()), "Total RQ3 merges"});
        vars.put(prefix + "ComparableCount",
                new String[]{String.valueOf(comparable), "Merges with scoreable baseline and best variant"});
        vars.put(prefix + "BrokenBaselineCount",
                new String[]{String.valueOf(brokenBaselines), "Merges with broken human baseline"});

        vars.put(prefix + "BetterBuildCount",
                new String[]{String.valueOf(betterBuild), "Best variant has more successful modules than baseline"});
        vars.put(prefix + "BetterBuildRate",
                new String[]{String.valueOf(countPercent(comparable, betterBuild)),
                        "Better-build rate (% of comparable)"});

        vars.put(prefix + "BetterOrEqualBuildCount",
                new String[]{String.valueOf(betterOrEqualBuild), "Best variant has >= successful modules"});
        vars.put(prefix + "BetterOrEqualBuildRate",
                new String[]{String.valueOf(countPercent(comparable, betterOrEqualBuild)),
                        "Better-or-equal-build rate (% of comparable)"});

        vars.put(prefix + "BuildFromBrokenCount",
                new String[]{String.valueOf(buildFromBroken), "Broken-baseline merges where best variant builds"});
        vars.put(prefix + "BuildFromBrokenRate",
                new String[]{String.valueOf(countPercent(brokenBaselines, buildFromBroken)),
                        "Build-from-broken rate (% of broken baselines)"});

        vars.put(prefix + "BetterTestsCount",
                new String[]{String.valueOf(betterTests), "Best variant passes more tests than baseline"});
        vars.put(prefix + "BetterTestsRate",
                new String[]{String.valueOf(countPercent(comparable, betterTests)),
                        "Better-tests rate (% of comparable)"});

        vars.put(prefix + "BetterOrEqualTestsCount",
                new String[]{String.valueOf(betterOrEqualTests), "Best variant passes >= tests"});
        vars.put(prefix + "BetterOrEqualTestsRate",
                new String[]{String.valueOf(countPercent(comparable, betterOrEqualTests)),
                        "Better-or-equal-tests rate (% of comparable)"});

        vars.put(prefix + "BetterCount",
                new String[]{String.valueOf(better), "Best variant strictly better overall (VariantScore)"});
        vars.put(prefix + "BetterRate",
                new String[]{String.valueOf(countPercent(comparable, better)),
                        "Better rate (% of comparable)"});

        vars.put(prefix + "BetterOrEqualCount",
                new String[]{String.valueOf(betterOrEqual), "Best variant at least as good overall"});
        vars.put(prefix + "BetterOrEqualRate",
                new String[]{String.valueOf(countPercent(comparable, betterOrEqual)),
                        "Better-or-equal rate (% of comparable)"});

        LatexVariableWriter.putAll(vars);
    }

    /**
     * Convert an experiment mode name to a camelCase LaTeX variable prefix.
     * E.g. "no_optimization" → "seq", "cache_parallel" → "cachePar".
     */
    static String toLatexPrefix(String experimentName) {
        return switch (experimentName) {
            case "human_baseline"   -> "hb";
            case "no_optimization"  -> "seq";
            case "cache_sequential" -> "cacheSeq";
            case "parallel"         -> "par";
            case "cache_parallel"   -> "cachePar";
            default -> experimentName.replaceAll("[^a-zA-Z0-9]", "");
        };
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
