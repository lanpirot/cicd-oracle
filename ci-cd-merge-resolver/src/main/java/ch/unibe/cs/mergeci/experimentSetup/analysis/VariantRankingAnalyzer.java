package ch.unibe.cs.mergeci.experimentSetup.analysis;

import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.MergeOutputJSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Analyzes and ranks merge variants by resolution strategy performance.
 * Generates rankings showing which strategies (Ours, Theirs, Mixed, Human) perform best.
 */
public class VariantRankingAnalyzer {
    private final VariantResolutionAnalyzer resolutionAnalyzer;

    public VariantRankingAnalyzer(VariantResolutionAnalyzer resolutionAnalyzer) {
        this.resolutionAnalyzer = resolutionAnalyzer;
    }

    /**
     * Generate overall ranking of resolution strategies.
     *
     * @return Map of strategy name to count of merges where it was best
     */
    public Map<String, Integer> generateRanking(List<MergeOutputJSON> merges) {
        int numberOfOursBest = 0;
        int numberOfTheirsBest = 0;
        int numberOfMixedBest = 0;
        int numberOfHumanBest = 0;
        int numberAtLeastOneNonHuman = 0;

        for (MergeOutputJSON merge : merges) {
            int baselineBest = resolutionAnalyzer.countSuccessfulTests(merge.getTestResults());
            List<String> bestResolutions = new ArrayList<>(List.of("Human"));

            // Find best variant(s)
            for (MergeOutputJSON.Variant variant : merge.getVariantsExecution().getVariants()) {
                int variantBest = resolutionAnalyzer.countSuccessfulTests(variant.getTestResults());

                if (variantBest > baselineBest) {
                    // New best found
                    baselineBest = variantBest;
                    String pattern = resolutionAnalyzer.identifyUniformPattern(variant.getConflictPatterns());
                    bestResolutions = new ArrayList<>(List.of(pattern));
                } else if (variantBest == baselineBest) {
                    // Tied for best
                    String pattern = resolutionAnalyzer.identifyUniformPattern(variant.getConflictPatterns());
                    if (!bestResolutions.contains(pattern)) {
                        bestResolutions.add(pattern);
                    }
                }
            }

            // Count each best resolution
            for (String resolution : bestResolutions) {
                switch (resolution) {
                    case "Human":
                        numberOfHumanBest++;
                        break;
                    case "OursPattern":
                        numberOfOursBest++;
                        break;
                    case "TheirsPattern":
                        numberOfTheirsBest++;
                        break;
                    case "Mixed":
                        numberOfMixedBest++;
                        break;
                    default:
                        // Unknown pattern type
                }
            }

            // Check if any non-human resolution was among the best
            if (bestResolutions.stream().anyMatch(x -> !x.equals("Human"))) {
                numberAtLeastOneNonHuman++;
            }
        }

        Map<String, Integer> ranking = new TreeMap<>();
        ranking.put("Human", numberOfHumanBest);
        ranking.put("Ours", numberOfOursBest);
        ranking.put("Theirs", numberOfTheirsBest);
        ranking.put("Mixed", numberOfMixedBest);
        ranking.put("AtLeastOneNonHuman", numberAtLeastOneNonHuman);

        return ranking;
    }

    /**
     * Generate ranking sorted by value (most successful first).
     */
    public Map<String, Integer> generateSortedRanking(List<MergeOutputJSON> merges) {
        return generateRanking(merges).entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }

    /**
     * Rank merges by coverage threshold ranges.
     *
     * @return Map of coverage ranges to rankings
     */
    public Map<String, Map<String, Integer>> rankByCoverageThresholds(List<MergeOutputJSON> merges) {
        List<Double> coverageThresholds = List.of(0.0, 0.15, 0.4, 0.6, 1.0);
        Map<String, Map<String, Integer>> coverageRankings = new LinkedHashMap<>();

        for (int i = 0; i < coverageThresholds.size() - 1; i++) {
            double min = coverageThresholds.get(i);
            double max = coverageThresholds.get(i + 1);

            List<MergeOutputJSON> filtered = merges.stream()
                    .filter(x -> x.getCoverage().lineCoverage() >= min
                            && x.getCoverage().lineCoverage() < max)
                    .toList();

            String rangeKey = String.format("[%.2f, %.2f)", min, max);
            coverageRankings.put(rangeKey, generateSortedRanking(filtered));
        }

        return coverageRankings;
    }

    /**
     * Rank merges grouped by number of conflict chunks.
     *
     * @return Map of conflict chunk count to rankings
     */
    public Map<Integer, Map<String, Integer>> rankByConflictChunks(List<MergeOutputJSON> merges) {
        Map<Integer, List<MergeOutputJSON>> grouped = merges.stream()
                .collect(Collectors.groupingBy(MergeOutputJSON::getNumConflictChunks));

        Map<Integer, Map<String, Integer>> rankings = new TreeMap<>();

        for (Map.Entry<Integer, List<MergeOutputJSON>> entry : grouped.entrySet()) {
            rankings.put(entry.getKey(), generateSortedRanking(entry.getValue()));
        }

        return rankings;
    }

    /**
     * Result container for coverage-based ranking.
     */
    public static class CoverageRanking {
        private final String range;
        private final Map<String, Integer> ranking;
        private final int totalMerges;

        public CoverageRanking(String range, Map<String, Integer> ranking, int totalMerges) {
            this.range = range;
            this.ranking = ranking;
            this.totalMerges = totalMerges;
        }

        public String getRange() {
            return range;
        }

        public Map<String, Integer> getRanking() {
            return ranking;
        }

        public int getTotalMerges() {
            return totalMerges;
        }
    }

    /**
     * Result container for conflict-chunks-based ranking.
     */
    public static class ConflictChunksRanking {
        private final int numConflictChunks;
        private final Map<String, Integer> ranking;
        private final int totalMerges;

        public ConflictChunksRanking(int numConflictChunks, Map<String, Integer> ranking, int totalMerges) {
            this.numConflictChunks = numConflictChunks;
            this.ranking = ranking;
            this.totalMerges = totalMerges;
        }

        public int getNumConflictChunks() {
            return numConflictChunks;
        }

        public Map<String, Integer> getRanking() {
            return ranking;
        }

        public int getTotalMerges() {
            return totalMerges;
        }
    }
}
