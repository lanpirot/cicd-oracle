package ch.unibe.cs.mergeci.present;

import ch.unibe.cs.mergeci.conflict.MergeStatistics;
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

    /** Cross-mode baseline records (mergeCommit → human-baseline merge), used as a fallback
     *  when the merge's own variant-0 data is unavailable (non-baseline mode JSONs). */
    private final Map<String, MergeOutputJSON> baselineLookup;

    public VariantResolutionAnalyzer() {
        this(Map.of());
    }

    public VariantResolutionAnalyzer(Map<String, MergeOutputJSON> baselineLookup) {
        this.baselineLookup = baselineLookup;
    }

    /**
     * Find merges where at least one variant has as many or more successful tests than the original.
     */
    public List<MergeOutputJSON> findMergesWithAtLeastOneResolution(List<MergeOutputJSON> merges) {
        List<MergeOutputJSON> result = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            Optional<VariantScore> baselineScore = MergeStatistics.scoreFor(merge, baselineLookup);
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
            Optional<VariantScore> baselineScore = MergeStatistics.scoreFor(merge, baselineLookup);
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
     * Per-variant uniform-pattern list: one entry per non-baseline variant that
     * (a) is at-least-as-good-as the baseline AND (b) uses the same single
     * pattern across every conflict chunk. List length = total such variants
     * across all merges (one merge can contribute many entries).
     *
     * <p>Use this when the question is "what fraction of the successful search
     * space is uniform?" — the variant-level view of the search.
     */
    public List<String> extractUniformPatterns(List<MergeOutputJSON> merges) {
        List<String> patternList = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            Optional<VariantScore> baselineScore = MergeStatistics.scoreFor(merge, baselineLookup);
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
     * Per-merge uniform-pattern list: at most one entry per merge. A merge contributes
     * its lexicographically-smallest uniform pattern from any variant that
     * (a) is at-least-as-good-as the baseline AND (b) uses a single pattern across
     * every conflict chunk. List length = number of merges where a uniform-pattern
     * resolution exists at all.
     *
     * <p>Use this when the question is "for how many merges does a uniform-pattern
     * fix exist, and which pattern wins?" — the merge-level claim a paper makes.
     */
    public List<String> extractUniformPatternsPerMerge(List<MergeOutputJSON> merges) {
        List<String> patternList = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            Optional<VariantScore> baselineScore = MergeStatistics.scoreFor(merge, baselineLookup);
            if (baselineScore.isEmpty()) continue;

            String chosen = null;
            for (MergeOutputJSON.Variant variant : merge.getVariants()) {
                if (variant.getVariantIndex() == 0) continue;
                Optional<VariantScore> vs = variantScore(variant);
                if (vs.isEmpty() || !vs.get().isAtLeastAsGoodAs(baselineScore.get())) continue;

                Set<String> uniquePatterns = variant.getConflictPatterns().values()
                        .stream()
                        .flatMap(Collection::stream)
                        .collect(HashSet::new, HashSet::add, HashSet::addAll);
                if (uniquePatterns.size() != 1) continue;

                String pat = uniquePatterns.iterator().next();
                if (chosen == null || pat.compareTo(chosen) < 0) {
                    chosen = pat;
                }
            }
            if (chosen != null) patternList.add(chosen);
        }

        return patternList;
    }

    /**
     * Count, across all given merges, the number of non-baseline variants whose score
     * is at-least-as-good-as the baseline. Used as the denominator for the variant-level
     * uniform-pattern view.
     */
    public int countSuccessfulVariants(List<MergeOutputJSON> merges) {
        int count = 0;
        for (MergeOutputJSON merge : merges) {
            Optional<VariantScore> baselineScore = MergeStatistics.scoreFor(merge, baselineLookup);
            if (baselineScore.isEmpty()) continue;
            for (MergeOutputJSON.Variant variant : merge.getVariants()) {
                if (variant.getVariantIndex() == 0) continue;
                Optional<VariantScore> vs = variantScore(variant);
                if (vs.isPresent() && vs.get().isAtLeastAsGoodAs(baselineScore.get())) {
                    count++;
                }
            }
        }
        return count;
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

    private static Optional<VariantScore> variantScore(MergeOutputJSON.Variant variant) {
        return VariantScore.of(variant.getCompilationResult(), variant.getTestResults());
    }
}
