package ch.unibe.cs.mergeci.present;

import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Analyzes variant resolution patterns and success metrics.
 * Determines which merge variants succeeded, their patterns, and performance.
 */
public class VariantResolutionAnalyzer {

    /**
     * Find merges where at least one variant has as many or more successful tests than the original.
     */
    public List<MergeOutputJSON> findMergesWithAtLeastOneResolution(List<MergeOutputJSON> merges) {
        List<MergeOutputJSON> result = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            Optional<VariantScore> baselineScore = baselineScore(merge);
            if (baselineScore.isEmpty()) continue;

            for (MergeOutputJSON.Variant variant : merge.getVariants()) {
                if (variant.getVariantIndex() == 0) continue;
                Optional<VariantScore> vs = variantScore(variant);
                if (vs.isPresent() && vs.get().isAtLeastAsGoodAs(baselineScore.get())) {
                    result.add(merge);
                    break;
                }
            }
        }

        return result;
    }

    /**
     * Find merges where at least one variant performs strictly better than the original.
     */
    public List<MergeOutputJSON> findMergesThatPerformBetter(List<MergeOutputJSON> merges) {
        List<MergeOutputJSON> result = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            Optional<VariantScore> baselineScore = baselineScore(merge);
            if (baselineScore.isEmpty()) continue;

            for (MergeOutputJSON.Variant variant : merge.getVariants()) {
                if (variant.getVariantIndex() == 0) continue;
                Optional<VariantScore> vs = variantScore(variant);
                if (vs.isPresent() && vs.get().isBetterThan(baselineScore.get())) {
                    result.add(merge);
                    break;
                }
            }
        }

        return result;
    }

    /**
     * Extract uniform resolution patterns from successful variants.
     * A pattern is "uniform" if all conflicts in a variant use the same resolution pattern.
     *
     * @return List of uniform pattern names (e.g., "OursPattern", "TheirsPattern")
     */
    public List<String> extractUniformPatterns(List<MergeOutputJSON> merges) {
        List<String> patternList = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            Optional<VariantScore> baselineScore = baselineScore(merge);
            if (baselineScore.isEmpty()) continue;

            for (MergeOutputJSON.Variant variant : merge.getVariants()) {
                if (variant.getVariantIndex() == 0) continue;
                Optional<VariantScore> vs = variantScore(variant);
                if (vs.isEmpty() || !vs.get().isAtLeastAsGoodAs(baselineScore.get())) continue;

                List<String> allPatterns = variant.getConflictPatterns().values()
                        .stream()
                        .flatMap(Collection::stream)
                        .toList();
                Set<String> uniquePatterns = new HashSet<>(allPatterns);

                // Only add if uniform (single pattern used for all conflicts)
                if (uniquePatterns.size() == 1) {
                    patternList.addAll(uniquePatterns);
                }
            }
        }

        return patternList;
    }

    /**
     * Group patterns by name and count occurrences.
     *
     * @param patterns List of pattern names
     * @return Map of pattern name to occurrence count
     */
    public Map<String, Integer> groupPatternsByCount(List<String> patterns) {
        Map<String, Integer> grouped = new HashMap<>();

        for (String pattern : patterns) {
            grouped.put(pattern, grouped.getOrDefault(pattern, 0) + 1);
        }

        return grouped;
    }

    /**
     * Determine the uniform pattern name for a variant, or "Mixed" if multiple patterns.
     *
     * @param conflictPatterns Map of file paths to pattern lists
     * @return Pattern name or "Mixed"
     */
    public String identifyUniformPattern(Map<String, List<String>> conflictPatterns) {
        Set<String> uniquePatterns = conflictPatterns.values()
                .stream()
                .flatMap(Collection::stream)
                .collect(HashSet::new, HashSet::add, HashSet::addAll);

        if (uniquePatterns.size() == 1) {
            return uniquePatterns.iterator().next();
        } else {
            return "Mixed";
        }
    }

    /**
     * Return the score of the best non-baseline variant in a merge,
     * or empty if no variant produced a scoreable result.
     */
    static Optional<VariantScore> bestVariantScore(MergeOutputJSON merge) {
        if (merge.getVariants() == null) return Optional.empty();
        return merge.getVariants().stream()
                .filter(v -> v.getVariantIndex() != 0)
                .map(VariantResolutionAnalyzer::variantScore)
                .flatMap(Optional::stream)
                .max(Comparator.naturalOrder());
    }

    static Optional<VariantScore> baselineScore(MergeOutputJSON merge) {
        return VariantScore.of(merge.getCompilationResult(), merge.getTestResults());
    }

    private static Optional<VariantScore> variantScore(MergeOutputJSON.Variant variant) {
        return VariantScore.of(variant.getCompilationResult(), variant.getTestResults());
    }
}
